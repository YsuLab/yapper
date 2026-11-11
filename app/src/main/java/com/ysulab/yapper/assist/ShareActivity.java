package com.ysulab.yapper.assist;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.ysulab.yapper.R;
import com.ysulab.yapper.YapId;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.capture.SharedItems;
import com.ysulab.yapper.capture.SilenceDetector;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.ui.DitherView;
import com.ysulab.yapper.ui.SmokeScene;
import com.ysulab.yapper.work.CaptureScheduler;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Share target: "Share → Yapper" from WhatsApp or any app. Text, images, PDFs and other files.
 *
 * <p>Same sheet as the side key. The share is copied into a new capture bundle first (mode
 * "share"), while the mic already listens for an optional voice note ("this is my new timetable,
 * remind me before each quiz"). Silence, no speech, or a tap ends it. Then the normal pipeline runs:
 * Inbox note → transcript → sorter. The activity stays open until every file is copied, because the
 * read permission on a shared {@code content://} URI ends with this activity.
 */
public final class ShareActivity extends Activity {

    /** One thread: copies, then the final meta write, then the job, always in that order. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "yap-share-io"));
    private static final long POLL_MS = 100;
    private static final long GOT_IT_MS = 1300;

    private final Handler main = new Handler(Looper.getMainLooper());

    private CaptureStore store;
    private Settings settings;
    private String id;
    private AudioCapture audio;
    private SilenceDetector detector;
    private boolean finished;
    private long startedAt;

    private TextView title;
    private TextView hint;
    private TextView timer;
    private View dot;
    private DitherView field;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // Recreated (rotation, process restart): the first instance already saved this share.
            finish();
            return;
        }
        setContentView(R.layout.capture_overlay);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        title = findViewById(R.id.capture_title);
        hint = findViewById(R.id.capture_hint);
        timer = findViewById(R.id.capture_timer);
        dot = findViewById(R.id.capture_dot);
        field = findViewById(R.id.capture_field);
        SmokeScene scene = field.scene();
        scene.transparent = true;
        scene.moonY = 0.36f;
        scene.moonR = 0.13f;
        scene.quietTop = 0f;
        scene.quietBottom = 0.26f;
        findViewById(R.id.capture_sheet).setOnClickListener(x -> stop("tap"));

        settings = new Settings(this);
        store = CaptureScheduler.store(this);
        id = YapId.generate(Instant.now(), new SecureRandom());
        startedAt = SystemClock.elapsedRealtime();
        field.press();

        Intent in = getIntent();
        String text = SharedItems.text(in.getStringExtra(Intent.EXTRA_SUBJECT), textOf(in));
        List<Uri> uris = streams(in);
        Uri ref = getReferrer();
        String source = SharedItems.sourceApp(ref == null ? null : ref.toString());
        if (text.isEmpty() && uris.isEmpty()) {
            show(getString(R.string.share_nothing), "");
            finished = true;
            main.postDelayed(this::finish, GOT_IT_MS);
            return;
        }
        try {
            // Bundle folder + meta first: from here on reconcile() can find this share.
            store.create(id, System.currentTimeMillis(), Settings.ZONE);
            store.update(id, m -> {
                m.mode = "share";
                m.sourceApp = source;
            });
        } catch (IOException | RuntimeException e) {
            show(getString(R.string.capture_failed), getString(R.string.capture_failed_hint));
            finished = true;
            main.postDelayed(this::finish, 2000);
            return;
        }
        show(getString(R.string.share_saving), getString(R.string.share_hint));
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Target SDK 36 never calls onBackPressed. Back means "done", not "cancel": it's saved already.
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> stop("tap"));
        }
        IO.execute(() -> copyShare(id, text, uris));

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audio = new AudioCapture();
            try {
                String name = audio.start(this, store.dir(id));
                final String captureId = id;
                IO.execute(() -> update(captureId, m -> m.audioFile = name));
                detector = new SilenceDetector(settings.silenceMs(), settings.noSpeechMs(), settings.maxCaptureMs());
                if (dot != null) {
                    dot.setVisibility(View.VISIBLE);
                }
                main.post(poll);
            } catch (Exception e) {
                audio = null; // mic busy: the share alone is still saved
            }
        }
        if (audio == null) {
            if (dot != null) {
                dot.setVisibility(View.INVISIBLE);
            }
            main.postDelayed(() -> stop("no_mic"), 600);
        }
    }

    /** Copies the shared text and files into the bundle. Runs on {@link #IO}. */
    private void copyShare(String captureId, String text, List<Uri> uris) {
        int copied = 0;
        int skipped = 0;
        int images = 0;
        List<String> names = new ArrayList<>();
        try {
            if (!text.isEmpty()) {
                store.writeText(captureId, SharedItems.TEXT_FILE, text);
            }
        } catch (IOException e) {
            // the files may still make it
        }
        ContentResolver cr = getContentResolver();
        for (Uri uri : uris) {
            if (copied >= SharedItems.MAX_FILES) {
                skipped++;
                continue;
            }
            String name = SharedItems.fileName(copied, cr.getType(uri), displayName(cr, uri));
            File target = store.file(captureId, name);
            if (copy(cr, uri, target)) {
                names.add(name);
                copied++;
                if (SharedItems.isImage(name)) {
                    images++;
                }
            } else {
                skipped++;
            }
        }
        final int skip = skipped;
        final int textChars = text.length();
        update(captureId, m -> {
            m.sharedFiles.clear();
            m.sharedFiles.addAll(names);
            m.sharedTextChars = textChars;
            m.sharedSkipped = skip;
        });
        final String r = SharedItems.describe(images, copied - images, !text.isEmpty());
        main.post(() -> {
            if (!finished) {
                show(getString(R.string.share_saved, r), getString(R.string.share_hint));
            }
        });
    }

    /** Streams one shared URI into the bundle (temp file + rename). False if unreadable or too big. */
    private static boolean copy(ContentResolver cr, Uri uri, File target) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        long total = 0;
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                return false;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > SharedItems.MAX_FILE_BYTES) {
                    throw new IOException("too big");
                }
                out.write(buf, 0, n);
            }
            ((FileOutputStream) out).getFD().sync();
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return false;
        }
        if (total == 0 || !tmp.renameTo(target)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // some providers refuse queries; the MIME type still gives an extension
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private static String textOf(Intent in) {
        CharSequence cs = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (cs != null) {
            return cs.toString();
        }
        ArrayList<CharSequence> many = in.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
        if (many != null) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : many) {
                if (c != null) {
                    sb.append(sb.length() == 0 ? "" : "\n\n").append(c);
                }
            }
            return sb.toString();
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent in) {
        Set<Uri> out = new LinkedHashSet<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Uri u : list) {
                    if (u != null) out.add(u);
                }
            }
        } else {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        }
        // Some apps put the files only in ClipData (that is also where the read grant lives).
        ClipData clip = in.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        List<Uri> safe = new ArrayList<>();
        for (Uri u : out) {
            // Never read our own private files through a crafted share.
            if ("content".equals(u.getScheme())) {
                safe.add(u);
            }
        }
        return safe;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (finished || audio == null) {
                return;
            }
            int amp = audio.amplitude();
            if (field != null) {
                field.setLevel(SilenceDetector.level(amp));
            }
            if (timer != null) {
                timer.setText(CaptureSession.clock(SystemClock.elapsedRealtime() - startedAt));
            }
            switch (detector.onSample(amp, SystemClock.elapsedRealtime())) {
                case STOP_SILENCE: stop("silence"); return;
                case STOP_NO_SPEECH: stop("no_speech"); return;
                case STOP_MAX: stop("max"); return;
                default: main.postDelayed(this, POLL_MS);
            }
        }
    };

    /** Ends the share: stops the mic, then (after the copy) finalizes the bundle and queues it. */
    private void stop(String reason) {
        if (finished || id == null) {
            return;
        }
        finished = true;
        main.removeCallbacks(poll);
        long duration = audio != null ? audio.stop() : 0;
        long speech = detector != null ? detector.speechMs() : 0;
        final String captureId = id;
        final android.content.Context app = getApplicationContext();
        if (field != null) {
            field.release();
        }
        if (dot != null) {
            dot.setVisibility(View.INVISIBLE);
        }
        boolean visible = !"hidden".equals(reason);
        if (visible) {
            show(getString(R.string.capture_got_it), getString(R.string.capture_got_it_hint));
            buzz();
        }
        final long shownAt = SystemClock.elapsedRealtime();
        // Queued after copyShare on the same thread, so the meta is final when the job starts.
        IO.execute(() -> {
            update(captureId, m -> {
                m.durationMs = duration;
                m.speechMs = speech;
                m.stopReason = reason;
                if (speech == 0) {
                    // No words: the audio file stays in the bundle but is not sent to a transcriber,
                    // which would invent text from silence.
                    m.audioFile = "";
                }
                m.advanceTo(CaptureMeta.State.RECORDED);
            });
            CaptureScheduler.enqueueInbox(app, captureId);
            long wait = Math.max(0, GOT_IT_MS - (SystemClock.elapsedRealtime() - shownAt));
            main.postDelayed(() -> {
                if (!isFinishing()) {
                    finish();
                }
            }, visible ? wait : 0);
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home, recents or screen off: keep what we have. Copying still finishes on IO.
        if (!finished) {
            stop("hidden");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Android 10–12. Back means "done" here, not "cancel": the share is already saved.
        stop("tap");
    }

    @Override
    protected void onDestroy() {
        if (!finished) {
            stop("hidden");
        }
        main.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void show(String t, String h) {
        if (title != null) {
            title.setText(t);
        }
        if (hint != null) {
            hint.setVisibility(h.isEmpty() ? View.GONE : View.VISIBLE);
            hint.setText(h);
        }
    }

    private void buzz() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            }
        } catch (RuntimeException ignored) {
            // no haptics is fine
        }
    }

    private void update(String captureId, CaptureStore.Mutator mutator) {
        try {
            store.update(captureId, mutator);
        } catch (Exception ignored) {
            // reconcile() still finds the bundle by its folder
        }
    }
}
