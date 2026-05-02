package com.enterprise.aiassistant.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.ClarifyOption;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * LLM-based ambiguity checker. Uses a small fast model (llama-3.1-8b-instant)
 * for low-latency ambiguity detection.
 *
 * Prompts are loaded dynamically from the DB via PromptService (versioned, hot-reloadable).
 */
@Service
public class AmbiguityLlmChecker {

    private static final Logger log = LoggerFactory.getLogger(AmbiguityLlmChecker.class);

    public record AmbiguityCheckResult(boolean ambiguous, List<ClarifyOption> options) {}

    private final ChatLanguageModel chatModel;
    private final PromptService promptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmbiguityLlmChecker(@org.springframework.beans.factory.annotation.Qualifier("fastChatModel") ChatLanguageModel chatModel,
                                PromptService promptService) {
        this.chatModel = chatModel;
        this.promptService = promptService;
    }

    public AmbiguityCheckResult check(String question, String history) {
        String promptTemplate = promptService.getPrompt("ambiguity-check");
        String historySection = (history == null || history.isBlank()) ? "None" : history;
        String prompt = promptTemplate.formatted(historySection, question);

        try {
            String raw = chatModel.generate(prompt).strip();
            return parseResponse(raw);
        } catch (Exception e) {
            log.warn("Ambiguity LLM call failed — treating as unambiguous: {}", e.getMessage());
            return new AmbiguityCheckResult(false, List.of());
        }
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    private AmbiguityCheckResult parseResponse(String raw) {
        JsonNode node = extractJson(raw);
        if (node == null) {
            log.debug("Could not extract JSON from ambiguity response — treating as unambiguous");
            return new AmbiguityCheckResult(false, List.of());
        }

        try {
            boolean ambiguous = node.has("ambiguous") && node.get("ambiguous").asBoolean(false);
            List<ClarifyOption> options = new ArrayList<>();
            JsonNode opts = node.get("options");
            if (opts != null && opts.isArray()) {
                int idx = 1;
                for (JsonNode opt : opts) {
                    String query  = textOrNull(opt, "query");
                    String reason = textOrNull(opt, "reason");
                    if (query != null) {
                        options.add(new ClarifyOption(idx++, query, reason != null ? reason : ""));
                    }
                }
            }
            return new AmbiguityCheckResult(ambiguous, options);
        } catch (Exception e) {
            log.warn("Failed to parse ambiguity JSON — treating as unambiguous: {}", e.getMessage());
            return new AmbiguityCheckResult(false, List.of());
        }
    }

    private JsonNode extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Try direct parse
        JsonNode node = tryParse(raw);
        if (node != null) return node;

        // Strip markdown code fences
        String stripped = raw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        node = tryParse(stripped);
        if (node != null) return node;

        // Find first { ... }
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return tryParse(raw.substring(start, end + 1));
        }
        return null;
    }

    private JsonNode tryParse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
