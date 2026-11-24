package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.hub.HubClient;
import com.ysulab.yapper.hub.HubPayload;
import com.ysulab.yapper.settings.Settings;
import java.io.IOException;

/**
 * Sends one capture's tasks to the hub ({@code hub.json} in the bundle → {@code POST /api/captures}).
 * Runs only with a network; WorkManager retries with backoff until the hub answers, so a capture made
 * offline reaches the board after reconnecting. The send is idempotent, so a repeat never duplicates.
 * After Undo, the same worker removes the capture's tasks from the hub instead.
 */
public final class HubWorker extends Worker {

    static final String PAYLOAD = "hub.json";
    /** Written when the hub has the capture. */
    static final String SENT = "hub.sent";
    /** Written by Undo: take the tasks off the board (or never send them). */
    static final String UNDO = "hub.undo";
    static final String UNDONE = "hub.undone";

    public HubWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static boolean exists(CaptureStore store, String id, String name) {
        return store.file(id, name).isFile();
    }

    /** True when this bundle still has hub work to do (for reconcile). */
    static boolean pending(CaptureStore store, String id) {
        if (!exists(store, id, PAYLOAD)) {
            return false;
        }
        if (exists(store, id, UNDO)) {
            return exists(store, id, SENT) && !exists(store, id, UNDONE);
        }
        return !exists(store, id, SENT);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String id = getInputData().getString(CaptureScheduler.KEY_ID);
        if (id == null) {
            return Result.failure();
        }
        CaptureStore store = CaptureScheduler.store(ctx);
        HubClient hub = new Settings(ctx).hub();
        if (!hub.isConfigured() || !pending(store, id)) {
            return Result.success(); // reconcile() sends it once a token is saved
        }
        try {
            String payload = store.readText(id, PAYLOAD);
            if (exists(store, id, UNDO)) {
                for (String taskId : HubPayload.itemIds(payload)) {
                    HubClient.Response r = hub.deleteTask(taskId);
                    if (!r.ok() && r.code != 404) {
                        return HubClient.retryable(r.code) ? Result.retry() : stop(ctx, r);
                    }
                }
                store.writeText(id, UNDONE, Long.toString(System.currentTimeMillis()));
                return Result.success();
            }
            HubClient.Response r = hub.postCapture(payload);
            if (r.ok()) {
                store.writeText(id, SENT, Long.toString(System.currentTimeMillis()));
                return Result.success();
            }
            return HubClient.retryable(r.code) ? Result.retry() : stop(ctx, r);
        } catch (IOException e) {
            return Result.retry(); // offline or the hub is down: try again later
        } catch (Exception e) {
            return Result.failure(); // our own file is broken; the capture is still safe in the vault
        }
    }

    /** A refusal that will not fix itself (wrong token, rejected data): tell him, keep the payload. */
    private static Result stop(Context ctx, HubClient.Response r) {
        Notifications.status(ctx, "Today board refused a capture",
                (r.code == 401 ? "The phone token is wrong. Fix it in Yapper's settings; the tasks wait on the phone."
                        : "HTTP " + r.code + ". The tasks wait on the phone."));
        return Result.failure();
    }
}
