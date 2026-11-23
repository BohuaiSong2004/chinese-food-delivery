package com.wokflow.mockpay;

import org.springframework.http.HttpStatus;

class ApiError extends RuntimeException {
    final HttpStatus status;

    ApiError(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
