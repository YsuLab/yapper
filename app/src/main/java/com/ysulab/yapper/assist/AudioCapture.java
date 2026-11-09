package com.ysulab.yapper.assist;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.File;
import java.io.IOException;

/**
 * Records the microphone to a file. Ogg/Opus 16 kHz mono first: small, good for speech, accepted by
 * the transcription APIs, plays in Obsidian, and a truncated Ogg file is still mostly playable if the
 * app dies mid-capture. Falls back to AAC in .m4a if the device refuses Opus.
 */
final class AudioCapture {

    private MediaRecorder recorder;
    private File file;
    private long startedAtMs;

    /** Starts recording into {@code dir}. Returns the file name used. */
    String start(Context context, File dir) throws IOException {
        try {
            return startWith(context, new File(dir, "audio.ogg"), true);
        } catch (IOException | RuntimeException e) {
            release();
            return startWith(context, new File(dir, "audio.m4a"), false);
        }
    }

    private String startWith(Context context, File out, boolean opus) throws IOException {
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        recorder = r;
        file = out;
        r.setAudioSource(MediaRecorder.AudioSource.MIC);
        if (opus) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            r.setAudioSamplingRate(16000);
            r.setAudioEncodingBitRate(24000);
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(16000);
            r.setAudioEncodingBitRate(48000);
        }
        r.setAudioChannels(1);
        r.setOutputFile(out.getAbsolutePath());
        r.prepare();
        r.start();
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        return out.getName();
    }

    int amplitude() {
        try {
            return recorder == null ? 0 : recorder.getMaxAmplitude();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Stops and finalizes the file. Returns the recorded duration in ms. */
    long stop() {
        long duration = android.os.SystemClock.elapsedRealtime() - startedAtMs;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                // stop() throws if nothing was captured yet (< ~100 ms). The file may be empty.
            }
        }
        release();
        return duration;
    }

    boolean isRecording() {
        return recorder != null;
    }

    private void release() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // already released
            }
            recorder = null;
        }
    }
}
