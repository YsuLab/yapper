package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.ActionLog;
import com.ysulab.yapper.sort.Executor;
import com.ysulab.yapper.sort.Summary;
import com.ysulab.yapper.vault.InboxNote;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import java.time.ZonedDateTime;

/**
 * Undo for one sorted capture: removes exactly the blocks in {@code actions.json} (newest first),
 * leaves any block the owner edited, and moves the capture note back to Inbox/.
 */
public final class UndoWorker extends Worker {

    public UndoWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
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
        VaultAccess vault = VaultAccess.open(ctx, new Settings(ctx).vaultUri());
        if (vault == null) {
            return Result.retry();
        }
        SafVaultFs fs = new SafVaultFs(vault);
        try {
            CaptureMeta m = store.load(id);
            if (!"done".equals(m.sorted)) {
                return Result.success();
            }
            ActionLog log = ActionLog.parse(store.readText(id, SortWorker.ACTIONS));
            // M3 first: cancel alarms, delete To Do tasks. A network failure retries the whole undo.
            com.ysulab.yapper.reminders.MsAccount acct = new com.ysulab.yapper.reminders.MsAccount(ctx);
            int todoLeft;
            try {
                todoLeft = new com.ysulab.yapper.reminders.ReminderSync(id,
                        new com.ysulab.yapper.reminders.LocalAlarms(ctx), 0)
                        .undo(log, acct.isSignedIn() ? acct.todo() : null);
            } catch (Exception e) {
                store.writeText(id, SortWorker.ACTIONS, log.serialize());
                if (getRunAttemptCount() < 4) {
                    return Result.retry();
                }
                todoLeft = -1;
            }
            Executor.UndoResult r;
            try {
                r = new Executor(fs, id, "", ZonedDateTime.now(SortWorker.zone(m))).undo(log);
            } finally {
                store.writeText(id, SortWorker.ACTIONS, log.serialize());
            }
            m = SortWorker.moveCapture(fs, store, m, InboxNote.INBOX_DIR);
            SortWorker.appendToCapture(fs, m, Summary.undoneBlock(id, r.removed, r.kept));
            store.update(id, x -> x.sorted = "undone");
            Notifications.cancelResult(ctx, id);
            Notifications.status(ctx, "Undone",
                    "Removed " + r.removed + (r.kept > 0 ? ", kept " + r.kept + " you edited" : "")
                            + (todoLeft != 0 ? ". Some To Do tasks could not be removed" : "")
                            + ". The capture is back in Inbox.");
            return Result.success();
        } catch (Exception e) {
            return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
        }
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
