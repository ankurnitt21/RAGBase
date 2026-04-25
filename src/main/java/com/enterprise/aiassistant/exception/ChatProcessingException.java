package com.enterprise.aiassistant.exception;

public class ChatProcessingException extends RuntimeException {

    public ChatProcessingException(String message) {
        super(message);
    }

    public ChatProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
