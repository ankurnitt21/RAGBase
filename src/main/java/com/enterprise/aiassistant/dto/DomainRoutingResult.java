package com.enterprise.aiassistant.dto;

/**
 * Result of DomainRouterService.route(). fallback=true means the LLM-based
 * classifier failed after retries and Domain.PRODUCT was used as a default.
 */
public record DomainRoutingResult(Domain domain, boolean fallback) {}
