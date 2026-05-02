package com.enterprise.aiassistant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Parent record for an ingested file. Each document produces many {@link DocumentChunk}s.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_domain", columnList = "domain"),
        @Index(name = "idx_documents_source", columnList = "source")
})
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 20)
    private String domain;

    @Column(nullable = false, length = 512)
    private String source;

    @Column(length = 50)
    private String version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public DocumentEntity() {}

    public DocumentEntity(String title, String domain, String source, String version) {
        this.title = title;
        this.domain = domain;
        this.source = source;
        this.version = version;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId()                 { return id; }
    public String getTitle()            { return title; }
    public String getDomain()           { return domain; }
    public String getSource()           { return source; }
    public String getVersion()          { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
