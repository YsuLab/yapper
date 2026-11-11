package com.ysulab.yapper.today;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.ysulab.yapper.R;
import com.ysulab.yapper.assist.RambleActivity;
import com.ysulab.yapper.settings.Settings;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * M5: a morning "Plan your day?" and an evening "Debrief?" notification. One tap starts a ramble in
 * plan or debrief mode, so the owner never has to remember to open anything. Inexact alarms: a few
 * minutes late is fine and saves battery.
 */
public final class Nudges extends BroadcastReceiver {

    static final String ACTION = "com.ysulab.yapper.NUDGE";
    static final String EXTRA_KIND = "kind";
    static final String CHANNEL = "daily_nudge";
    static final int REQ_MORNING = 7001;
    static final int REQ_EVENING = 7002;
    static final int NOTIFY_ID = 7;

    public static void scheduleAll(Context context) {
        Settings s = new Settings(context);
        schedule(context, "plan", s.morningNudgeMin(), REQ_MORNING);
        schedule(context, "debrief", s.eveningNudgeMin(), REQ_EVENING);
    }

    /** Next time {@code minuteOfDay} happens after now (in the owner's zone). Pure, for tests. */
    public static ZonedDateTime next(ZonedDateTime now, int minuteOfDay) {
        ZonedDateTime t = now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(minuteOfDay);
        return t.isAfter(now.plusSeconds(30)) ? t : t.plusDays(1);
    }

    private static void schedule(Context context, String kind, int minuteOfDay, int req) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, req,
                new Intent(context, Nudges.class).setAction(ACTION).putExtra(EXTRA_KIND, kind),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (minuteOfDay < 0) {
            return; // off
        }
        long at = next(ZonedDateTime.now(ZoneId.of(Settings.ZONE)), minuteOfDay).toInstant().toEpochMilli();
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        String kind = intent.getStringExtra(EXTRA_KIND);
        boolean plan = !"debrief".equals(kind);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_nudge), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent talk = PendingIntent.getActivity(context, plan ? REQ_MORNING : REQ_EVENING,
                new Intent(context, RambleActivity.class)
                        .putExtra(RambleActivity.EXTRA_MODE, plan ? "plan" : "debrief")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_yapper)
                .setColor(context.getColor(R.color.yapper_primary))
                .setContentTitle(context.getString(plan ? R.string.nudge_plan_title : R.string.nudge_debrief_title))
                .setContentText(context.getString(plan ? R.string.nudge_plan_text : R.string.nudge_debrief_text))
                .setContentIntent(talk)
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.nudge_talk), talk).build())
                .setAutoCancel(true)
                .setTimeoutAfter(3 * 3600_000L)
                .build();
        try {
            nm.notify(NOTIFY_ID, n);
        } catch (SecurityException ignored) {
            // notifications off
        }
        scheduleAll(context); // tomorrow
    }
}
