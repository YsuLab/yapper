package com.ysulab.yapper.assist;

import android.Manifest;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.voice.VoiceInteractionSession;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import com.ysulab.yapper.R;
import com.ysulab.yapper.YapId;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.capture.ScreenText;
import com.ysulab.yapper.capture.ScreenshotCheck;
import com.ysulab.yapper.capture.SilenceDetector;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.ui.DitherView;
import com.ysulab.yapper.ui.SmokeScene;
import com.ysulab.yapper.work.CaptureScheduler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One side-key press. Starts recording immediately, collects screen text and screenshot as the
 * system delivers them, ends on ~2 s of silence or a tap, saves the bundle, says "Got it" and closes.
 *
 * <p>Order of safety: the bundle folder and meta exist before recording starts; screen data is
 * written as soon as it arrives; the final meta + WorkManager job are queued on the same
 * single-thread executor, after every pending write. Side-key release is not used: Android does not
 * deliver it to the assistant (see docs/decisions.md).
 */
public class CaptureSession extends VoiceInteractionSession {

    /** Shared, so writes finish even after this session object is destroyed. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yap-capture-io");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private static final long POLL_MS = 100;
    /** Time to show "saved" while the smoke settles. The bundle is already queued by then. */
    private static final long GOT_IT_MS = 700;
    private static final int MAX_SCREEN_CHARS = 20_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> screenTexts = new ArrayList<>();

    private CaptureStore store;
    private Settings settings;
    private String id;
    private AudioCapture audio;
    private SilenceDetector detector;
    private boolean finished;

    private TextView title;
    private TextView hint;
    private TextView timer;
    private View dot;
    private DitherView field;
    private long startedAt;
    private PowerManager.WakeLock screenLock;

    public CaptureSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.capture_overlay, null);
        title = v.findViewById(R.id.capture_title);
        hint = v.findViewById(R.id.capture_hint);
        timer = v.findViewById(R.id.capture_timer);
        dot = v.findViewById(R.id.capture_dot);
        field = v.findViewById(R.id.capture_field);
        SmokeScene scene = field.scene();
        scene.moonY = 0.42f;
        scene.moonR = 0.09f;
        scene.quietTop = 0.11f;
        scene.quietBottom = 0.11f;
        // Only a tap on the sheet itself ends the capture; a stray touch on the dimmed top part does nothing.
        v.findViewById(R.id.capture_sheet).setOnClickListener(x -> stop("tap"));
        return v;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        if (id != null && !finished) {
            // Side key pressed again while listening: treat it like a tap ("I'm done").
            stop("tap");
            return;
        }
        main.removeCallbacksAndMessages(null);
        Context ctx = getContext();
        finished = false;
        audio = null;
        detector = null;
        screenTexts.clear();
        settings = new Settings(ctx);
        store = CaptureScheduler.store(ctx);
        id = YapId.generate(Instant.now(), new SecureRandom());
        setText(R.string.capture_listening, R.string.capture_hint);
        startedAt = SystemClock.elapsedRealtime();
        keepScreenOn(true);
        if (timer != null) {
            timer.setText(clock(0));
        }
        if (dot != null) {
            dot.setVisibility(View.VISIBLE);
        }
        if (field != null) {
            // Smoke starts to rise the moment the side key is pressed; recording does not wait for it.
            field.press();
        }

        try {
            store.create(id, System.currentTimeMillis(), Settings.ZONE);
        } catch (Exception e) {
            // Can't even write to private storage: nothing we can save. Say so and leave.
            setText(R.string.capture_failed, R.string.capture_failed_hint);
            main.postDelayed(this::finish, 1500);
            return;
        }

        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Keep the screen data anyway; the capture is still useful.
            setText(R.string.capture_no_mic, R.string.capture_no_mic_readout);
            main.postDelayed(() -> stop("no_mic"), 1500);
            return;
        }
        audio = new AudioCapture();
        File dir = store.dir(id);
        try {
            String name = audio.start(ctx, dir);
            final String captureId = id;
            IO.execute(() -> update(captureId, m -> m.audioFile = name));
        } catch (Exception e) {
            audio = null;
            setText(R.string.capture_no_mic, R.string.capture_mic_busy_readout);
            main.postDelayed(() -> stop("mic_error"), 1500);
            return;
        }
        detector = new SilenceDetector(settings.silenceMs(), settings.noSpeechMs(), settings.maxCaptureMs());
        main.post(poll);
        IO.execute(() -> CaptureScheduler.reconcile(ctx.getApplicationContext()));
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (finished || audio == null) {
                return;
            }
            int amp = audio.amplitude();
            if (field != null) {
                // The moon is the meter: loud syllables send rings out from it.
                field.setLevel(SilenceDetector.level(amp));
            }
            if (timer != null) {
                timer.setText(clock(SystemClock.elapsedRealtime() - startedAt));
            }
            SilenceDetector.Decision d = detector.onSample(amp, SystemClock.elapsedRealtime());
            switch (d) {
                case STOP_SILENCE: stop("silence"); return;
                case STOP_NO_SPEECH: stop("no_speech"); return;
                case STOP_MAX: stop("max"); return;
                default: main.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    public void onHandleAssist(AssistState state) {
        if (finished || id == null) {
            return;
        }
        AssistStructure structure = state.getAssistStructure();
        AssistContent content = state.getAssistContent();
        String text = ScreenText.collect(AssistNodes.roots(structure), MAX_SCREEN_CHARS);
        String pkg = structure != null && structure.getActivityComponent() != null
                ? structure.getActivityComponent().getPackageName() : "";
        String web = content != null && content.getWebUri() != null ? content.getWebUri().toString() : "";
        boolean focused = state.isFocused();
        if (!text.isEmpty()) {
            if (focused) {
                screenTexts.add(0, text);
            } else {
                screenTexts.add(text);
            }
        }
        final String all = String.join("\n\n---\n\n", screenTexts);
        final String captureId = id;
        IO.execute(() -> {
            try {
                store.writeText(captureId, CaptureStore.SCREEN_TEXT, all);
            } catch (Exception ignored) {
                // screen text is a bonus; audio is the source of truth
            }
            update(captureId, m -> {
                m.screenTextChars = all.length();
                if (focused || m.sourceApp.isEmpty()) {
                    if (!pkg.isEmpty()) m.sourceApp = pkg;
                    if (!web.isEmpty()) m.webUri = web;
                }
            });
        });
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        if (finished || id == null) {
            return;
        }
        final String captureId = id;
        if (screenshot == null) {
            IO.execute(() -> update(captureId, m -> m.screenshot = "none"));
            return;
        }
        // Copy now: hardware bitmaps can't be read pixel by pixel and the original may be recycled.
        final Bitmap copy;
        try {
            copy = screenshot.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException | OutOfMemoryError e) {
            IO.execute(() -> update(captureId, m -> m.screenshot = "error"));
            return;
        }
        IO.execute(() -> {
            String result;
            try {
                if (ScreenshotCheck.isBlank(sample(copy))) {
                    result = "blank";
                } else {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    copy.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                    store.writeBytes(captureId, CaptureStore.SCREENSHOT, bos.toByteArray());
                    result = "saved";
                }
            } catch (Exception | OutOfMemoryError e) {
                result = "error";
            } finally {
                copy.recycle();
            }
            final String r = result;
            update(captureId, m -> m.screenshot = r);
        });
    }

    private static int[] sample(Bitmap b) {
        int cols = 48;
        int rows = 96;
        int[] out = new int[cols * rows];
        int i = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                out[i++] = b.getPixel((int) ((x + 0.5) * b.getWidth() / cols),
                        (int) ((y + 0.5) * b.getHeight() / rows));
            }
        }
        return out;
    }

    /**
     * Keeps the screen on while the owner talks, unlocked or on the lock screen. If the screen went off,
     * Android would hide the session and end the capture early. Two layers: the window flag, and a screen
     * wake lock (with a time limit) for the lock screen, whose own timeout is short.
     */
    @SuppressWarnings("deprecation")
    private void keepScreenOn(boolean on) {
        Window w = getWindow() != null ? getWindow().getWindow() : null;
        if (w != null) {
            if (on) {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
        try {
            if (on) {
                if (screenLock == null) {
                    PowerManager pm = getContext().getSystemService(PowerManager.class);
                    screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE, "yapper:capture");
                    screenLock.setReferenceCounted(false);
                }
                long limit = (settings != null ? settings.maxCaptureMs() : 5 * 60_000L) + 10_000L;
                screenLock.acquire(limit);
            } else if (screenLock != null && screenLock.isHeld()) {
                screenLock.release();
            }
        } catch (RuntimeException ignored) {
            // without the lock the window flag still helps while unlocked
        }
    }

    /** Ends the capture. Safe to call more than once. */
    private void stop(String reason) {
        if (finished || id == null) {
            return;
        }
        finished = true;
        keepScreenOn(false);
        main.removeCallbacks(poll);
        long duration = audio != null ? audio.stop() : 0;
        long speech = detector != null ? detector.speechMs() : 0;
        final String captureId = id;
        final Context app = getContext().getApplicationContext();
        IO.execute(() -> {
            update(captureId, m -> {
                m.durationMs = duration;
                m.speechMs = speech;
                m.stopReason = reason;
                m.advanceTo(CaptureMeta.State.RECORDED);
            });
            CaptureScheduler.enqueueInbox(app, captureId);
        });
        if (field != null) {
            field.release();
        }
        if (dot != null) {
            dot.setVisibility(View.INVISIBLE);
        }
        if (!"hidden".equals(reason)) {
            setText(R.string.capture_got_it, R.string.capture_got_it_hint);
            buzz();
            main.postDelayed(this::finish, GOT_IT_MS);
        }
    }

    @Override
    public void onHide() {
        // Home, back or screen off while listening: keep what we have.
        stop("hidden");
        super.onHide();
    }

    @Override
    public void onDestroy() {
        stop("hidden");
        keepScreenOn(false);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buzz() {
        try {
            Vibrator v = getContext().getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            }
        } catch (RuntimeException ignored) {
            // no haptics is fine
        }
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void setText(int titleRes, int hintRes) {
        if (title != null) {
            title.setText(titleRes);
        }
        if (hint != null) {
            if (hintRes == 0) {
                hint.setVisibility(View.GONE);
            } else {
                hint.setVisibility(View.VISIBLE);
                hint.setText(hintRes);
            }
        }
    }

    private void update(String captureId, CaptureStore.Mutator mutator) {
        try {
            store.update(captureId, mutator);
        } catch (Exception ignored) {
            // meta write failed; reconcile() still finds the bundle by its folder
        }
    }
}
