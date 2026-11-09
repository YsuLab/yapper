package com.ysulab.yapper.assist;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Required for Android to list Yapper as a selectable assistant. Yapper does not offer speech
 * recognition to other apps, so every request ends with an error.
 */
public class StubRecognitionService extends RecognitionService {

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {
            // caller is gone
        }
    }

    @Override
    protected void onCancel(Callback listener) {}

    @Override
    protected void onStopListening(Callback listener) {}
}
