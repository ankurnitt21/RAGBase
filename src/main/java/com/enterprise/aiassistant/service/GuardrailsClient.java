package com.enterprise.aiassistant.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for the Guardrails AI Python sidecar service.
 * 
 * Provides four security guards:
 * 1. Prompt Injection Detection — blocks adversarial input at entry
 * 2. Cache Poisoning Prevention — validates before semantic cache writes
 * 3. Data Exfiltration Protection — blocks PII/secrets in responses
 * 4. Indirect Injection Detection — detects injections in retrieved context
 */
@Service
public class GuardrailsClient {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsClient.class);

    @Value("${guardrails.service-url:http://localhost:8200}")
    private String guardrailsServiceUrl;

    @Value("${guardrails.enabled:true}")
    private boolean guardrailsEnabled;

    @Value("${guardrails.timeout-ms:3000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GuardrailsClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // ── Response Records ─────────────────────────────────────────────────────

    public record InjectionResult(
            boolean isInjection,
            double confidence,
            String attackType,
            String reason,
            double guardLatencyMs
    ) {}

    public record CachePoisonResult(
            boolean safeToCache,
            List<String> issues,
            String riskLevel,
            double guardLatencyMs
    ) {}

    public record DataExfiltrationResult(
            boolean safe,
            boolean containsPii,
            boolean containsSecrets,
            List<String> issues,
            double guardLatencyMs
    ) {}

    public record IndirectInjectionResult(
            boolean containsInjection,
            double confidence,
            String attackType,
            List<String> flaggedSegments,
            String reason,
            double guardLatencyMs
    ) {}

    // ── Guard 1: Prompt Injection Detection ──────────────────────────────────

    public InjectionResult detectPromptInjection(String userInput) {
        if (!guardrailsEnabled) {
            return new InjectionResult(false, 0.0, null, null, 0.0);
        }

        try {
            Map<String, Object> body = Map.of("user_input", userInput);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(guardrailsServiceUrl + "/guards/prompt-injection/detect"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Guardrails injection check returned status {}: {}", response.statusCode(), response.body());
                return new InjectionResult(false, 0.0, null, "Guard service error", 0.0);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            return new InjectionResult(
                    Boolean.TRUE.equals(result.get("is_injection")),
                    toDouble(result.get("confidence")),
                    (String) result.get("attack_type"),
                    (String) result.get("reason"),
                    toDouble(result.get("guard_latency_ms"))
            );

        } catch (Exception e) {
            log.warn("Guardrails injection check failed (fail-open): {}", e.getMessage());
            return new InjectionResult(false, 0.0, null, "Guard unavailable", 0.0);
        }
    }

    // ── Guard 2: Cache Poisoning Prevention ──────────────────────────────────

    public CachePoisonResult checkCachePoison(String question, String answer, String confidence, String domain) {
        if (!guardrailsEnabled) {
            return new CachePoisonResult(true, List.of(), "low", 0.0);
        }

        try {
            Map<String, Object> body = Map.of(
                    "question", question,
                    "answer", answer,
                    "confidence", confidence,
                    "domain", domain
            );
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(guardrailsServiceUrl + "/guards/cache-poison/check"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Guardrails cache-poison check returned status {}", response.statusCode());
                return new CachePoisonResult(true, List.of(), "unknown", 0.0);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<String> issues = (List<String>) result.getOrDefault("issues", List.of());

            return new CachePoisonResult(
                    Boolean.TRUE.equals(result.get("safe_to_cache")),
                    issues,
                    (String) result.getOrDefault("risk_level", "low"),
                    toDouble(result.get("guard_latency_ms"))
            );

        } catch (Exception e) {
            log.warn("Guardrails cache-poison check failed (fail-open): {}", e.getMessage());
            return new CachePoisonResult(true, List.of(), "unknown", 0.0);
        }
    }

    // ── Guard 3: Data Exfiltration Protection ────────────────────────────────

    public DataExfiltrationResult checkDataExfiltration(String responseText, String originalQuery) {
        if (!guardrailsEnabled) {
            return new DataExfiltrationResult(true, false, false, List.of(), 0.0);
        }

        try {
            Map<String, Object> body = Map.of(
                    "response_text", responseText,
                    "original_query", originalQuery
            );
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(guardrailsServiceUrl + "/guards/data-exfiltration/check"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Guardrails exfiltration check returned status {}", response.statusCode());
                return new DataExfiltrationResult(true, false, false, List.of(), 0.0);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<String> issues = (List<String>) result.getOrDefault("issues", List.of());

            return new DataExfiltrationResult(
                    Boolean.TRUE.equals(result.get("safe")),
                    Boolean.TRUE.equals(result.get("contains_pii")),
                    Boolean.TRUE.equals(result.get("contains_secrets")),
                    issues,
                    toDouble(result.get("guard_latency_ms"))
            );

        } catch (Exception e) {
            log.warn("Guardrails exfiltration check failed (fail-open): {}", e.getMessage());
            return new DataExfiltrationResult(true, false, false, List.of(), 0.0);
        }
    }

    // ── Guard 4: Indirect Injection Detection ────────────────────────────────

    public IndirectInjectionResult detectIndirectInjection(String retrievedContext, String originalQuery) {
        if (!guardrailsEnabled) {
            return new IndirectInjectionResult(false, 0.0, null, List.of(), null, 0.0);
        }

        try {
            Map<String, Object> body = Map.of(
                    "retrieved_context", retrievedContext,
                    "original_query", originalQuery
            );
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(guardrailsServiceUrl + "/guards/indirect-injection/detect"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Guardrails indirect-injection check returned status {}", response.statusCode());
                return new IndirectInjectionResult(false, 0.0, null, List.of(), "Guard service error", 0.0);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<String> flagged = (List<String>) result.getOrDefault("flagged_segments", List.of());

            return new IndirectInjectionResult(
                    Boolean.TRUE.equals(result.get("contains_injection")),
                    toDouble(result.get("confidence")),
                    (String) result.get("attack_type"),
                    flagged,
                    (String) result.get("reason"),
                    toDouble(result.get("guard_latency_ms"))
            );

        } catch (Exception e) {
            log.warn("Guardrails indirect-injection check failed (fail-open): {}", e.getMessage());
            return new IndirectInjectionResult(false, 0.0, null, List.of(), "Guard unavailable", 0.0);
        }
    }

    // ── Health Check ─────────────────────────────────────────────────────────

    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(guardrailsServiceUrl + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
    }
}
