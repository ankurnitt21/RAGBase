package com.enterprise.aiassistant.dto;

import java.util.List;

public record ChatResponse(String answer, String confidence, List<String> sources, String routedDomain, boolean domainFallback) {
}
