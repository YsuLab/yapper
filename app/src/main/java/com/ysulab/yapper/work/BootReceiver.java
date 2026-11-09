package com.ysulab.yapper.work;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot, pick up any capture that was interrupted mid-recording. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    CaptureScheduler.reconcile(context.getApplicationContext());
                } finally {
                    pr.finish();
                }
            }).start();
        }
    }
}
