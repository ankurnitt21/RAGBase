package com.enterprise.aiassistant.exception;

/**
 * Thrown by AssistantController when an uploaded file has a MIME type other
 * than application/pdf or text/plain. Caught by GlobalExceptionHandler and
 * returned as HTTP 415 Unsupported Media Type.
 */
public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
