package com.enterprise.aiassistant.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Centralised exception-to-HTTP-response mapping for all controllers.
 * Every known exception type is caught here and converted to a structured
 * ApiError response body with an appropriate HTTP status code.
 * Unhandled exceptions fall through to the catch-all handler returning 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ChatProcessingException.class)
    public ResponseEntity<ApiError> handleChat(ChatProcessingException ex, HttpServletRequest req) {
        log.error("Chat processing failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(503, "Chat Unavailable", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(DocumentIngestionException.class)
    public ResponseEntity<ApiError> handleIngestion(DocumentIngestionException ex, HttpServletRequest req) {
        log.error("Document ingestion failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(422, "Ingestion Failed", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Validation Failed", message, req.getRequestURI()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", "Missing required parameter: " + ex.getParameterName(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", message, req.getRequestURI()));
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ApiError> handleUnsupportedFileType(UnsupportedFileTypeException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of(415, "Unsupported Media Type", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        int status = ex.getStatusCode().value();
        return ResponseEntity.status(status)
                .body(ApiError.of(status, ex.getReason() != null ? ex.getReason() : "Error", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ApiError> handleRateLimit(RequestNotPermitted ex, HttpServletRequest req) {
        log.warn("Rate limit exceeded for {}", req.getRequestURI());
        return ResponseEntity.status(429)
                .body(ApiError.of(429, "Too Many Requests",
                        "Rate limit exceeded. Please slow down and retry after a moment.",
                        req.getRequestURI()));
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiError> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest req) {
        log.error("Circuit breaker open for {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(503, "Service Unavailable",
                        "Service is temporarily unavailable due to repeated failures. Please try again shortly.",
                        req.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(413, "File Too Large", "Maximum upload size is 10MB", req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
    }
}
