package com.ysulab.yapper.sort;

/** A sorter call failed. {@code retryable} = try again later (network, 429, 5xx). */
public final class SortException extends Exception {

    public final boolean retryable;

    public SortException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static boolean isRetryableStatus(int code) {
        return code == 408 || code == 409 || code == 425 || code == 429 || code >= 500;
    }
}
