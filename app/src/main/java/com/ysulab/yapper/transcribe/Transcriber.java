package com.ysulab.yapper.transcribe;

import java.io.File;

/**
 * Speech-to-text provider. Pluggable so the M1 benchmark can compare providers on the owner's own
 * Saudi Arabic + English speech, and a self-hosted GPU server can replace the cloud later.
 */
public interface Transcriber {

    /** Stable id stored in capture metadata, e.g. {@code openai}. */
    String id();

    /** Human label for notes, e.g. {@code OpenAI gpt-4o-transcribe}. */
    String label();

    /** Blocking call. Run it on a worker thread. */
    Transcript transcribe(File audio) throws TranscriptionException;

    final class Transcript {
        public final String text;
        public final long latencyMs;

        public Transcript(String text, long latencyMs) {
            this.text = text;
            this.latencyMs = latencyMs;
        }
    }
}
