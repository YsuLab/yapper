package com.ysulab.yapper.work;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.ysulab.yapper.MainActivity;
import com.ysulab.yapper.R;

/** Status notifications, and sorter results with Undo / View (M2). */
public final class Notifications {

    public static final String CHANNEL_STATUS = "capture_status";
    public static final String CHANNEL_WORK = "capture_work";
    public static final String CHANNEL_RESULT = "sort_result";
    public static final int ID_STATUS = 1;
    public static final int ID_NEEDS_SETUP = 2;
    public static final int ID_WORK = 3;

    private Notifications() {}

    public static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS,
                context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_WORK,
                context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_MIN));
        // Visible in the status bar, but silent: the owner already got "Got it" at capture time.
        NotificationChannel result = new NotificationChannel(CHANNEL_RESULT,
                context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT);
        result.setSound(null, null);
        result.enableVibration(false);
        nm.createNotificationChannel(result);
    }

    /** One notification per capture, so several results can sit side by side. */
    static int resultId(String captureId) {
        return 1000 + (captureId.hashCode() & 0x3fffffff) % 100_000;
    }

    /**
     * "Sorted ✓ · 1 note → Tabby/Learning.md" with View (opens the note in Obsidian) and Undo.
     * {@code viewPath} is vault-relative; {@code vaultName} is the vault folder's name.
     */
    public static void sortResult(Context context, String captureId, String title, String text,
            String viewPath, String vaultName, boolean undo) {
        ensureChannels(context);
        Notification.Builder b = new Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(context.getColor(R.color.yapper_primary))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        int req = resultId(captureId);
        if (viewPath != null && !viewPath.isEmpty()) {
            android.net.Uri uri = android.net.Uri.parse("obsidian://open?vault=" + android.net.Uri.encode(vaultName)
                    + "&file=" + android.net.Uri.encode(viewPath));
            PendingIntent view = PendingIntent.getActivity(context, req,
                    new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentIntent(view);
            b.addAction(new Notification.Action.Builder(null, context.getString(R.string.action_view), view).build());
        }
        if (undo) {
            Intent i = new Intent(context, UndoReceiver.class)
                    .setAction(UndoReceiver.ACTION_UNDO)
                    .putExtra(CaptureScheduler.KEY_ID, captureId);
            PendingIntent p = PendingIntent.getBroadcast(context, req, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, context.getString(R.string.action_undo), p).build());
        }
        try {
            context.getSystemService(NotificationManager.class).notify(req, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted: the vault is still updated.
        }
    }

    public static void cancelResult(Context context, String captureId) {
        context.getSystemService(NotificationManager.class).cancel(resultId(captureId));
    }

    public static void status(Context context, String title, String text) {
        post(context, ID_STATUS, title, text);
    }

    public static void needsSetup(Context context, String text) {
        post(context, ID_NEEDS_SETUP, context.getString(R.string.needs_setup_title), text);
    }

    public static void clearNeedsSetup(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ID_NEEDS_SETUP);
    }

    /** Used only when WorkManager runs expedited work as a foreground service (Android 10–11). */
    public static Notification working(Context context) {
        ensureChannels(context);
        return new Notification.Builder(context, CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(context.getColor(R.color.yapper_primary))
                .setContentTitle(context.getString(R.string.working_title))
                .setOngoing(true)
                .build();
    }

    private static void post(Context context, int id, String title, String text) {
        ensureChannels(context);
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(context.getColor(R.color.yapper_primary))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            context.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted: the capture is still safe, just silent.
        }
    }
}
