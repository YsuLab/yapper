package com.ysulab.yapper.work;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Undo" button on a result notification. The vault work runs in {@link UndoWorker}. */
public final class UndoReceiver extends BroadcastReceiver {

    public static final String ACTION_UNDO = "com.ysulab.yapper.UNDO";

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(CaptureScheduler.KEY_ID);
        if (ACTION_UNDO.equals(intent.getAction()) && id != null) {
            Notifications.cancelResult(context, id);
            CaptureScheduler.enqueueUndo(context, id);
        }
    }
}
