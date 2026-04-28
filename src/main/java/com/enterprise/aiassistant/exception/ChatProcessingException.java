package com.enterprise.aiassistant.exception;

/**
 * Thrown when the LLM chat pipeline fails after all retries are exhausted.
 * Caught by GlobalExceptionHandler and returned as HTTP 503 Service Unavailable.
 * Also used as the circuit breaker fallback signal in ChatService.
 */
public class ChatProcessingException extends RuntimeException {

    public ChatProcessingException(String message) {
        super(message);
    }

    public ChatProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
