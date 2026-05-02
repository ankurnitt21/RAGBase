package com.enterprise.aiassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity for storing document chunks in PostgreSQL for keyword (FTS) search.
 * Each chunk belongs to a parent {@link DocumentEntity} via document_id.
 * DocumentChunkRepository queries this table using to_tsvector / plainto_tsquery
 * as the keyword search leg of the hybrid retrieval pipeline in KnowledgeBaseTools.
 */
@Entity
@Table(name = "document_chunks", indexes = {
        @Index(name = "idx_document_chunks_domain", columnList = "domain"),
        @Index(name = "idx_document_chunks_source", columnList = "source"),
        @Index(name = "idx_document_chunks_document_id", columnList = "document_id")
})
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false, length = 20)
    private String domain;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String source;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DocumentChunk() {}

    public DocumentChunk(String domain, String content, String source) {
        this.domain = domain;
        this.content = content;
        this.source = source;
        this.chunkIndex = 0;
        this.createdAt = LocalDateTime.now();
    }

    public DocumentChunk(Long documentId, String domain, String content, String source, int chunkIndex) {
        this.documentId = documentId;
        this.domain = domain;
        this.content = content;
        this.source = source;
        this.chunkIndex = chunkIndex;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId()                 { return id; }
    public Long getDocumentId()         { return documentId; }
    public String getDomain()           { return domain; }
    public String getContent()          { return content; }
    public String getSource()           { return source; }
    public int getChunkIndex()          { return chunkIndex; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
