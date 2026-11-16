package com.ysulab.yapper.assist;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.ysulab.yapper.MainActivity;
import com.ysulab.yapper.R;
import com.ysulab.yapper.YapId;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.capture.RambleSplitter;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.work.CaptureScheduler;
import java.io.File;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * M4 ramble mode: records for up to an hour with the screen off, as a foreground service of type
 * microphone. Audio is cut into ~5-minute parts at pauses; each finished part is recorded in the
 * bundle at once, so a crash loses at most the part in progress. Stop from the notification.
 *
 * <p>Started from an activity (the app, the Quick Settings tile, the launcher shortcut or a daily
 * nudge), because Android 14+ only lets a visible app start microphone recording in the background.
 */
public final class RambleService extends Service {

    public static final String ACTION_START = "com.ysulab.yapper.RAMBLE_START";
    public static final String ACTION_STOP = "com.ysulab.yapper.RAMBLE_STOP";
    public static final String EXTRA_MODE = "mode";
    static final String CHANNEL = "ramble";
    static final int NOTIFICATION_ID = 4;
    private static final long POLL_MS = 200;

    /** Bundle id of the ramble being recorded, or null. Read by reconcile() so it is not "recovered". */
    private static volatile String activeId;

    private HandlerThread thread;
    private Handler handler;
    private CaptureStore store;
    private AudioCapture audio;
    private RambleSplitter splitter;
    private PowerManager.WakeLock wakeLock;
    private String id;
    private String mode = "ramble";
    private int part;
    private long startedAt;
    private long maxMs;
    private boolean stopping;

    public static String activeId() {
        return activeId;
    }

    public static boolean isRunning() {
        return activeId != null;
    }

    public static Intent startIntent(Context context, String mode) {
        return new Intent(context, RambleService.class).setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode == null ? "ramble" : mode);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("yap-ramble");
        thread.start();
        handler = new Handler(thread.getLooper());
        store = CaptureScheduler.store(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            handler.post(() -> finishRamble("tap"));
            return START_NOT_STICKY;
        }
        if (id != null) {
            return START_NOT_STICKY; // already recording
        }
        if (intent != null && intent.getStringExtra(EXTRA_MODE) != null) {
            mode = intent.getStringExtra(EXTRA_MODE);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        id = YapId.generate(Instant.now(), new SecureRandom());
        startedAt = SystemClock.elapsedRealtime();
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) {
            // Not allowed to start mic recording from here (app not visible). Nothing recorded yet.
            id = null;
            stopSelf();
            return START_NOT_STICKY;
        }
        activeId = id;
        maxMs = new Settings(this).maxRambleMs();
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yapper:ramble");
        wakeLock.acquire(maxMs + 5 * 60_000L);
        final String rambleMode = mode;
        handler.post(() -> begin(rambleMode));
        return START_NOT_STICKY;
    }

    private void begin(String rambleMode) {
        try {
            store.create(id, System.currentTimeMillis(), Settings.ZONE);
            store.update(id, m -> m.mode = rambleMode);
        } catch (Exception e) {
            cleanup();
            return;
        }
        if (!nextPart()) {
            finishRamble("mic_error");
            return;
        }
        buzz();
        handler.post(poll);
        CaptureScheduler.reconcile(getApplicationContext());
    }

    /** Starts recording part {@code part + 1}. */
    private boolean nextPart() {
        audio = new AudioCapture();
        part++;
        try {
            String name = audio.start(this, store.dir(id), RambleSplitter.partBase(part));
            store.update(id, m -> {
                if (m.audioFile.isEmpty()) {
                    m.audioFile = name;
                }
            });
            if (splitter == null) {
                splitter = new RambleSplitter();
            }
            splitter.partStarted(SystemClock.elapsedRealtime());
            pendingPart = name;
            return true;
        } catch (Exception e) {
            audio = null;
            return false;
        }
    }

    /** File name of the part being recorded; added to the bundle when it is finalized. */
    private String pendingPart;

    private void closePart() {
        if (audio == null) {
            return;
        }
        long d = audio.stop();
        audio = null;
        final String name = pendingPart;
        pendingPart = null;
        File f = name == null ? null : store.file(id, name);
        if (f != null && f.isFile() && f.length() > 0) {
            try {
                store.update(id, m -> {
                    if (!m.audioParts.contains(name)) {
                        m.audioParts.add(name);
                    }
                    m.durationMs += d;
                });
            } catch (Exception ignored) {
                // reconcile() still finds the bundle; the part file is on disk
            }
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (stopping || audio == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - startedAt >= maxMs) {
                finishRamble("max");
                return;
            }
            if (splitter.onSample(audio.amplitude(), now)) {
                closePart();
                if (!nextPart()) {
                    finishRamble("mic_error");
                    return;
                }
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void finishRamble(String reason) {
        if (stopping || id == null) {
            if (!stopping) {
                cleanup();
            }
            return;
        }
        stopping = true;
        handler.removeCallbacks(poll);
        closePart();
        final String captureId = id;
        try {
            store.update(captureId, m -> {
                m.stopReason = reason;
                m.advanceTo(CaptureMeta.State.RECORDED);
            });
        } catch (Exception ignored) {
            // reconcile() promotes it later
        }
        CaptureScheduler.enqueueInbox(getApplicationContext(), captureId);
        buzz();
        cleanup();
    }

    private void cleanup() {
        activeId = null;
        id = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Killed by the system or the owner: keep what we have.
        if (id != null && !stopping) {
            handler.post(() -> finishRamble("killed"));
        }
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_ramble),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RambleService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        int title;
        switch (mode) {
            case "plan": title = R.string.ramble_title_plan; break;
            case "debrief": title = R.string.ramble_title_debrief; break;
            case "review": title = R.string.ramble_title_review; break;
            default: title = R.string.ramble_title; break;
        }
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(getColor(R.color.yapper_primary))
                .setContentTitle(getString(title))
                .setContentText(getString(R.string.ramble_text))
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.ramble_stop), stop).build());
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void buzz() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
            }
        } catch (RuntimeException ignored) {
            // no haptics is fine
        }
    }
}
