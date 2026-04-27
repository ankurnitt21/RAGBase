package com.enterprise.aiassistant.dto;

public record IngestionResponse(String jobId, String filename, Domain domain, String status, String message) {
}
