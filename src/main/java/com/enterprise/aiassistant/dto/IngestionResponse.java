package com.enterprise.aiassistant.dto;

public record IngestionResponse(String filename, Domain domain, String status, String message) {
}
