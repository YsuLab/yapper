package com.ysulab.yapper.transcribe;

/** A failed transcription. {@link #retryable} decides between "try again later" and "give up". */
public final class TranscriptionException extends Exception {

    public final boolean retryable;
    public final int httpCode;

    public TranscriptionException(String message, boolean retryable, int httpCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpCode = httpCode;
    }

    /** Network errors, timeouts, 408, 429 and 5xx are worth retrying. Auth and bad input are not. */
    public static boolean isRetryableStatus(int code) {
        return code == 408 || code == 409 || code == 425 || code == 429 || code >= 500;
    }
}
