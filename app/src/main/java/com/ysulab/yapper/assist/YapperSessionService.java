package com.ysulab.yapper.assist;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Hands the system a new {@link CaptureSession} each time the side key is held. */
public class YapperSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new CaptureSession(this);
    }
}
