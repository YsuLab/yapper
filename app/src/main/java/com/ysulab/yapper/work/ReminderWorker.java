package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.R;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.reminders.LocalAlarms;
import com.ysulab.yapper.reminders.MsAccount;
import com.ysulab.yapper.reminders.MsAuth;
import com.ysulab.yapper.reminders.ReminderSync;
import com.ysulab.yapper.reminders.TodoClient;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.ActionLog;

/**
 * M3: sends a sorted capture's reminders to Microsoft To Do (→ Samsung Reminder). Needs network;
 * retries with backoff. If To Do is unusable for good, every waiting reminder rings locally instead.
 */
public final class ReminderWorker extends Worker {

    static final int MAX_ATTEMPTS = 20;

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
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
        MsAccount acct = new MsAccount(ctx);
        ReminderSync sync = new ReminderSync(id, new LocalAlarms(ctx), new Settings(ctx).alarmWindowMs());
        ActionLog log;
        try {
            if (!"done".equals(store.load(id).sorted)) {
                return Result.success(); // undone meanwhile
            }
            log = ActionLog.parse(store.readText(id, SortWorker.ACTIONS));
        } catch (Exception e) {
            return Result.failure();
        }
        try {
            if (!acct.isReady()) {
                sync.fallBackToLocal(log, System.currentTimeMillis());
                return Result.success();
            }
            sync.push(log, acct.todo(), System.currentTimeMillis());
            return Result.success();
        } catch (MsAuth.AuthException e) {
            if (e.needsSignIn) {
                int n = sync.fallBackToLocal(log, System.currentTimeMillis());
                Notifications.needsSetup(ctx, ctx.getString(R.string.ms_sign_in_again, n));
                return Result.success();
            }
            return retryOrLocal(sync, log);
        } catch (TodoClient.GraphException e) {
            if (e.code == 404 || e.code == 403 || e.code == 401 && getRunAttemptCount() > 2) {
                // List deleted, or consent missing: ring locally and ask the owner to fix it.
                int n = sync.fallBackToLocal(log, System.currentTimeMillis());
                Notifications.needsSetup(ctx, ctx.getString(R.string.ms_todo_failed, n, e.getMessage()));
                return Result.success();
            }
            return retryOrLocal(sync, log);
        } catch (Exception e) {
            return retryOrLocal(sync, log);
        } finally {
            try {
                store.writeText(id, SortWorker.ACTIONS, log.serialize());
            } catch (Exception ignored) {
                // next run re-reads what was saved before; To Do creates are rare and the log
                // is written right after each run
            }
        }
    }

    private Result retryOrLocal(ReminderSync sync, ActionLog log) {
        if (getRunAttemptCount() < MAX_ATTEMPTS) {
            return Result.retry();
        }
        sync.fallBackToLocal(log, System.currentTimeMillis());
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
