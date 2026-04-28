package com.enterprise.aiassistant.exception;

import java.time.LocalDateTime;

/**
 * Uniform error response body returned by GlobalExceptionHandler for all
 * HTTP error responses. Includes status code, short error label, human-readable
 * message, request path, and timestamp for logging and client display.
 */
public record ApiError(
        int status,
        String error,
        String message,
        String path,
        LocalDateTime timestamp
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, LocalDateTime.now());
    }
}
