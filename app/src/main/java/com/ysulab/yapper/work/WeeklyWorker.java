package com.ysulab.yapper.work;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.R;
import com.ysulab.yapper.assist.RambleActivity;
import com.ysulab.yapper.review.WeekDigest;
import com.ysulab.yapper.review.WeekRange;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Weekly review: builds the week's note in {@code Weekly/} from the vault (offline, no LLM), then
 * posts "Your week is ready" with View (opens it in Obsidian) and Reflect (starts a ramble in review
 * mode). The reflection is sorted into the same note by the sorter's {@code weekly_review} tool.
 */
public final class WeeklyWorker extends Worker {

    static final String UNIQUE = "weekly-review";
    static final int NOTIFY_ID = 8;
    static final int REQ_REFLECT = 7101;
    static final int REQ_VIEW = 7102;

    public WeeklyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(WeeklyWorker.class)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req);
    }

    /** App-private memory of what the digest last wrote, so an untouched note can be refreshed. */
    static File stateDir(Context ctx) {
        return new File(ctx.getFilesDir(), "weekly");
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Settings settings = new Settings(ctx);
        ZoneId zone = ZoneId.of(Settings.ZONE);
        WeekRange week = WeekRange.forReview(ZonedDateTime.now(zone).toLocalDate());
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (vault == null) {
            notify(ctx, week, null, null); // still ask: the reflection is saved and sorted later
            return Result.success();
        }
        try {
            WeekDigest.Result r = WeekDigest.prepare(new SafVaultFs(vault), week, zone, stateDir(ctx));
            notify(ctx, week, r, vault.name());
            return Result.success();
        } catch (Exception e) {
            if (getRunAttemptCount() < 3) {
                return Result.retry();
            }
            notify(ctx, week, null, null);
            return Result.success();
        }
    }

    private static void notify(Context ctx, WeekRange week, WeekDigest.Result r, String vaultName) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("weekly_review",
                ctx.getString(R.string.channel_review), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent reflect = PendingIntent.getActivity(ctx, REQ_REFLECT,
                new Intent(ctx, RambleActivity.class)
                        .putExtra(RambleActivity.EXTRA_MODE, "review")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = r == null ? ctx.getString(R.string.review_text_no_vault)
                : ctx.getString(R.string.review_text, r.stats());
        Notification.Builder b = new Notification.Builder(ctx, "weekly_review")
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(ctx.getColor(R.color.yapper_primary))
                .setContentTitle(ctx.getString(R.string.review_title, "W" + week.number()))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(24 * 3600_000L);
        if (vaultName != null) {
            android.net.Uri uri = android.net.Uri.parse("obsidian://open?vault=" + android.net.Uri.encode(vaultName)
                    + "&file=" + android.net.Uri.encode(week.notePath()));
            PendingIntent view = PendingIntent.getActivity(ctx, REQ_VIEW,
                    new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentIntent(view);
            b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.action_view), view).build());
        } else {
            b.setContentIntent(reflect);
        }
        b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.review_reflect), reflect).build());
        try {
            nm.notify(NOTIFY_ID, b.build());
        } catch (SecurityException ignored) {
            // notifications off
        }
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
