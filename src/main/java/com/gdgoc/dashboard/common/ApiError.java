package com.gdgoc.dashboard.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> validationErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, Instant.now(), null);
    }

    public static ApiError ofValidation(int status, String error, String message, String path, Map<String, String> validationErrors) {
        return new ApiError(status, error, message, path, Instant.now(), validationErrors);
    }
}
