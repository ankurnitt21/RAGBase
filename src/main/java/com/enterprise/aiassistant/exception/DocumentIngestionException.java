package com.enterprise.aiassistant.exception;

/**
 * Thrown when document parsing or ingestion fails (e.g. scanned PDF with no
 * extractable text, Pinecone write failure after retries). Caught by
 * GlobalExceptionHandler and returned as HTTP 422 Unprocessable Entity.
 * Not retried by Resilience4j — declared in ignore-exceptions.
 */
public class DocumentIngestionException extends RuntimeException {

    public DocumentIngestionException(String message) {
        super(message);
    }

    public DocumentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
