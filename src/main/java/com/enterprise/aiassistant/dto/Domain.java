package com.enterprise.aiassistant.dto;

/**
 * Supported knowledge domains. Each domain maps to a separate Pinecone namespace
 * (hr / product / ai) and a separate PostgreSQL partition in document_chunks.
 * DomainRouterService classifies every incoming question into one of these values.
 */
public enum Domain {
    HR,
    PRODUCT,
    AI
}
