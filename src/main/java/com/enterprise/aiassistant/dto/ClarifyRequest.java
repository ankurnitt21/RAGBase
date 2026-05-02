package com.enterprise.aiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/chat/clarify.
 *
 * After the system returns AWAITING_CLARIFICATION with clarificationOptions,
 * the client displays those options to the user. The user either picks one of
 * the suggested queries (chosenQuery = options[n].query) or types their own.
 *
 * conversationId — must match the conversation that produced the clarification.
 * chosenQuery    — the resolved, self-contained question to re-enter the pipeline with.
 */
public record ClarifyRequest(
        @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "conversationId must be a valid UUID"
        )
        String conversationId,

        @NotBlank(message = "chosenQuery must not be blank")
        @Size(max = 2000, message = "chosenQuery must not exceed 2000 characters")
        String chosenQuery
) {}
