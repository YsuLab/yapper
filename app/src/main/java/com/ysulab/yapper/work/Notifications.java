package com.ysulab.yapper.work;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.ysulab.yapper.MainActivity;
import com.ysulab.yapper.R;

/** Quiet status notifications for M1. Result notifications with Undo/View come with the sorter (M2). */
public final class Notifications {

    public static final String CHANNEL_STATUS = "capture_status";
    public static final String CHANNEL_WORK = "capture_work";
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
                .setSmallIcon(android.R.drawable.ic_popup_sync)
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
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
