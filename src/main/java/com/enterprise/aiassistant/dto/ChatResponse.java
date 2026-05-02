package com.enterprise.aiassistant.dto;

import java.util.List;

/**
 * Response body for POST /api/v1/chat and POST /api/v1/chat/clarify.
 *
 * status — "COMPLETED" for a full answer, "AWAITING_CLARIFICATION" when the
 *          question was ambiguous and the user must pick a clarification option.
 * clarificationOptions — populated only when status=AWAITING_CLARIFICATION;
 *          each option carries a suggested refined question and a reason.
 * confidence — HIGH / MEDIUM / LOW (grounding check); LOW for clarification responses.
 * sources    — filenames cited by the knowledge-base tool calls.
 * domainFallback=true means domain routing fell back to PRODUCT.
 */
public record ChatResponse(
        String answer,
        String confidence,
        List<String> sources,
        String routedDomain,
        boolean domainFallback,
        String status,
        List<ClarifyOption> clarificationOptions
) {}

