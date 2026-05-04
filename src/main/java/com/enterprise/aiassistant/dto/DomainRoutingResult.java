package com.enterprise.aiassistant.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of DomainRouterService.route().
 * - domain          : primary domain (first entry, used for cache keys)
 * - domainQuestions : ordered map of domain → domain-specific sub-question
 * - fallback        : true when the LLM classifier failed
 */
public record DomainRoutingResult(Domain domain, Map<Domain, String> domainQuestions, boolean fallback) {

    /** Convenience constructor for single-domain results (backward compat). */
    public DomainRoutingResult(Domain domain, boolean fallback) {
        this(domain, Map.of(domain, ""), fallback);
    }

    /** Ordered list of all detected domains. */
    public List<Domain> domains() {
        return new ArrayList<>(domainQuestions.keySet());
    }

    /** Sub-question extracted for a specific domain (empty string if not present). */
    public String questionFor(Domain d) {
        return domainQuestions.getOrDefault(d, "");
    }

    /** Human-readable domain list for logging / tracing. */
    public String domainsLabel() {
        return domainQuestions.keySet().stream().map(Domain::name)
                .collect(Collectors.joining(","));
    }
}
