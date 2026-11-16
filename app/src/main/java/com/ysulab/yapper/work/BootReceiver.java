package com.ysulab.yapper.work;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or update: resume interrupted captures, set alarms and daily nudges again. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    CaptureScheduler.reconcile(context.getApplicationContext());
                    com.ysulab.yapper.today.Nudges.scheduleAll(context.getApplicationContext());
                } finally {
                    pr.finish();
                }
            }).start();
        }
    }
}
