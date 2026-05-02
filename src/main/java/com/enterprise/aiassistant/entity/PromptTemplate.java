package com.enterprise.aiassistant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "prompt_templates",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}),
        indexes = {
                @Index(name = "idx_prompt_templates_name", columnList = "name"),
                @Index(name = "idx_prompt_templates_active", columnList = "name, active")
        })
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private boolean active = false;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public PromptTemplate() {}

    public PromptTemplate(String name, int version, String content, boolean active, String description) {
        this.name = name;
        this.version = version;
        this.content = content;
        this.active = active;
        this.description = description;
    }

    // Getters & setters

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
