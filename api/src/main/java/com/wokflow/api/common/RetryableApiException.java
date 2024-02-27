package com.wokflow.api.common;

import org.springframework.http.HttpStatus;

public class RetryableApiException extends ApiException {
    private final long retryAfterSeconds;

    public RetryableApiException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
