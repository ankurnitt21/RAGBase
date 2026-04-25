package com.enterprise.aiassistant.entity;

import java.time.LocalDateTime;

public class ChatMessage {

    private String conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public ChatMessage() {
    }

    public ChatMessage(String conversationId, String role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public ChatMessage(String conversationId, String role, String content, LocalDateTime createdAt) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
