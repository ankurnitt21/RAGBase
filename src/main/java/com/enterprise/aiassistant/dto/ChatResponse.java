package com.enterprise.aiassistant.dto;

import java.util.List;

/**
 * Response body for POST /api/v1/chat.
 * confidence is computed by GroundingService: HIGH (SUPPORTED), MEDIUM (PARTIAL), LOW (UNSUPPORTED).
 * sources lists the filenames cited by the searchKnowledgeBase tool calls.
 * domainFallback=true means domain routing failed and defaulted to PRODUCT.
 */
public record ChatResponse(String answer, String confidence, List<String> sources, String routedDomain, boolean domainFallback) {
}
