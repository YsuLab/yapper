package com.ysulab.yapper.reminders;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import com.ysulab.yapper.R;

/** A local reminder rings: loud notification with Snooze 10 min and Done. */
public final class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_RING = "com.ysulab.yapper.ALARM_RING";
    static final String ACTION_SNOOZE = "com.ysulab.yapper.ALARM_SNOOZE";
    static final String ACTION_DONE = "com.ysulab.yapper.ALARM_DONE";
    static final String EXTRA_ID = "alarm_id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_CAPTURE = "capture_id";
    public static final String CHANNEL = "reminder_alarm";
    static final long SNOOZE_MS = 10 * 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(EXTRA_ID, 0);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String capture = intent.getStringExtra(EXTRA_CAPTURE);
        if (id == 0 || intent.getAction() == null) {
            return;
        }
        switch (intent.getAction()) {
            case ACTION_RING:
                ring(context, id, title == null ? "" : title, capture == null ? "" : capture);
                break;
            case ACTION_SNOOZE:
                dismiss(context, id);
                new LocalAlarms(context).schedule(id, System.currentTimeMillis() + SNOOZE_MS,
                        title == null ? "" : title, capture == null ? "" : capture);
                break;
            case ACTION_DONE:
                dismiss(context, id);
                break;
            default:
                break;
        }
    }

    static void ensureChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[] {0, 600, 400, 600, 400, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static void ring(Context context, int id, String title, String capture) {
        ensureChannel(context);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title.isEmpty() ? context.getString(R.string.alarm_default_title) : title)
                .setContentText(context.getString(R.string.alarm_text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setShowWhen(true)
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.alarm_snooze),
                        action(context, ACTION_SNOOZE, id, title, capture)).build())
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.alarm_done),
                        action(context, ACTION_DONE, id, title, capture)).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT; // repeats the sound until the owner reacts
        try {
            context.getSystemService(NotificationManager.class).notify(tag(id), n);
        } catch (SecurityException e) {
            // notifications off: nothing else we can do from a receiver
        }
    }

    private static PendingIntent action(Context context, String action, int id, String title, String capture) {
        Intent i = new Intent(context, AlarmReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CAPTURE, capture);
        return PendingIntent.getBroadcast(context, id ^ action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void dismiss(Context context, int id) {
        context.getSystemService(NotificationManager.class).cancel(tag(id));
    }

    /** Notification id space for alarms, apart from the capture notifications. */
    private static int tag(int id) {
        return 200_000_000 + (id % 100_000_000);
    }
}
