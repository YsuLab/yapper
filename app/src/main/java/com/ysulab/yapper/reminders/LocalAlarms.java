package com.ysulab.yapper.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.ysulab.yapper.MainActivity;

/**
 * Exact alarms via {@link AlarmManager#setAlarmClock}: rings on the minute even in Doze. Needs
 * USE_EXACT_ALARM (Android 13+, granted at install) or SCHEDULE_EXACT_ALARM (12, on by default).
 * If exact alarms are denied anyway, falls back to an inexact while-idle alarm.
 */
public final class LocalAlarms implements ReminderSync.Alarms {

    private final Context context;

    public LocalAlarms(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void schedule(int id, long atMs, String title, String captureId) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent ring = ringIntent(context, id, title, captureId);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) {
            PendingIntent show = PendingIntent.getActivity(context, id,
                    new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMs, show), ring);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, ring);
        }
    }

    @Override
    public void cancel(int id) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        am.cancel(ringIntent(context, id, "", ""));
        AlarmReceiver.dismiss(context, id);
    }

    public static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static PendingIntent ringIntent(Context context, int id, String title, String captureId) {
        Intent i = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_RING)
                .putExtra(AlarmReceiver.EXTRA_ID, id)
                .putExtra(AlarmReceiver.EXTRA_TITLE, title)
                .putExtra(AlarmReceiver.EXTRA_CAPTURE, captureId);
        return PendingIntent.getBroadcast(context, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
