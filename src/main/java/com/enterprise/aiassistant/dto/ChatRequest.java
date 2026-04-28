package com.enterprise.aiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/chat.
 * conversationId is optional — if omitted, ChatService generates a new UUID.
 * question is required, max 2000 characters.
 */
public record ChatRequest(
        @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "conversationId must be a valid UUID"
        )
        String conversationId,

        @NotBlank(message = "question must not be blank")
        @Size(max = 2000, message = "question must not exceed 2000 characters")
        String question
) {}
