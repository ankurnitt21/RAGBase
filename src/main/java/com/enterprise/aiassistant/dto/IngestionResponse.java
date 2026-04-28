package com.enterprise.aiassistant.dto;

/**
 * Response body for document ingestion endpoints.
 * Sync ingestion (POST /documents): jobId is null, status is "COMPLETED".
 * Async ingestion (POST /documents/bulk): jobId is a UUID to poll at
 * GET /documents/status/{jobId}, status is "ACCEPTED".
 */
public record IngestionResponse(String jobId, String filename, Domain domain, String status, String message) {
}
