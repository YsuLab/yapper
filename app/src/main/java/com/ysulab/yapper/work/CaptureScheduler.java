package com.ysulab.yapper.work;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.transcribe.TranscriberConfig;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Queues background work for captures and recovers anything left behind.
 *
 * <p>Two unique jobs per capture: {@code inbox-<id>} (no constraints, works offline) and
 * {@code transcribe-<id>} (needs network, retries with backoff for days). KEEP policy means calling
 * {@link #reconcile} often is cheap and never duplicates work.
 */
public final class CaptureScheduler {

    public static final String KEY_ID = "capture_id";
    /** A RECORDING bundle older than this has no live session: the app died mid-capture. */
    static final long STALE_RECORDING_MS = 10 * 60_000L;

    private CaptureScheduler() {}

    public static CaptureStore store(Context context) {
        return new CaptureStore(new File(context.getFilesDir(), "captures"));
    }

    public static void enqueueInbox(Context context, String id) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(InboxWorker.class)
                .setInputData(new Data.Builder().putString(KEY_ID, id).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("capture")
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("inbox-" + id, ExistingWorkPolicy.KEEP, req);
    }

    public static void enqueueTranscribe(Context context, String id) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(TranscribeWorker.class)
                .setInputData(new Data.Builder().putString(KEY_ID, id).build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("capture")
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork("transcribe-" + id, ExistingWorkPolicy.KEEP, req);
    }

    /**
     * Walks every bundle and queues whatever is unfinished. Called on app open, after boot, when a
     * new capture starts, and after settings change (e.g. a transcriber key was added).
     */
    public static void reconcile(Context context) {
        CaptureStore store = store(context);
        List<String> active = new ArrayList<>();
        for (TranscriberConfig c : new Settings(context).activeTranscribers()) {
            active.add(c.id);
        }
        long now = System.currentTimeMillis();
        for (String id : store.ids()) {
            try {
                CaptureMeta m = store.load(id);
                if (m.state == CaptureMeta.State.RECORDING && now - m.createdAtMs > STALE_RECORDING_MS) {
                    m = store.update(id, x -> {
                        x.advanceTo(CaptureMeta.State.RECORDED);
                        x.stopReason = "recovered";
                    });
                }
                if (m.state == CaptureMeta.State.RECORDED) {
                    enqueueInbox(context, id);
                } else if (m.state == CaptureMeta.State.INBOX && !m.pendingTranscribers(active).isEmpty()) {
                    enqueueTranscribe(context, id);
                } else if (m.state == CaptureMeta.State.DONE && !m.pendingTranscribers(active).isEmpty()) {
                    // A new transcriber was added later: run it on old captures too (benchmark).
                    store.update(id, x -> x.state = CaptureMeta.State.INBOX);
                    enqueueTranscribe(context, id);
                }
            } catch (IOException | RuntimeException e) {
                // One broken bundle must not block the others.
            }
        }
    }

    /** Counts per state, for the status screen. */
    public static int[] counts(Context context) {
        CaptureStore store = store(context);
        int[] c = new int[CaptureMeta.State.values().length];
        for (String id : store.ids()) {
            try {
                c[store.load(id).state.ordinal()]++;
            } catch (IOException | RuntimeException ignored) {
                // counted as nothing
            }
        }
        return c;
    }
}
