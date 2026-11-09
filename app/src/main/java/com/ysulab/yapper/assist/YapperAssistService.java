package com.ysulab.yapper.assist;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import com.ysulab.yapper.work.CaptureScheduler;

/**
 * Makes Yapper selectable as the default digital assistant. The system keeps this service bound
 * while Yapper is the assistant, which also keeps our process alive after a capture is saved.
 */
public class YapperAssistService extends VoiceInteractionService {

    @Override
    public void onReady() {
        super.onReady();
        new Thread(() -> CaptureScheduler.reconcile(getApplicationContext()), "yap-reconcile").start();
    }

    /** Side key held on the lock screen (needs supportsLaunchVoiceAssistFromKeyguard). */
    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        showSession(new Bundle(), 0);
    }
}
