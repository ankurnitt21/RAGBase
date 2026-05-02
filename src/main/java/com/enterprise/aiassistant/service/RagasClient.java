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

@Service
public class RagasClient {

    private static final Logger log = LoggerFactory.getLogger(RagasClient.class);

    @Value("${ragas.service-url:http://localhost:8100}")
    private String ragasServiceUrl;

    @Value("${ragas.enabled:false}")
    private boolean ragasEnabled;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RagasClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record RagasResult(
            Double answerRelevancy,
            Double faithfulness,
            Double contextPrecision,
            Double contextRecall
    ) {}

    public RagasResult evaluate(String question, String answer, List<String> contexts, String groundTruth) {
        if (!ragasEnabled) {
            log.debug("RAGAS evaluation disabled");
            return null;
        }

        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("question", question);
            body.put("answer", answer);
            body.put("contexts", contexts);
            if (groundTruth != null) {
                body.put("ground_truth", groundTruth);
            }

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ragasServiceUrl + "/evaluate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("RAGAS service returned status {}: {}", response.statusCode(), response.body());
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            RagasResult ragasResult = new RagasResult(
                    toDouble(result.get("answer_relevancy")),
                    toDouble(result.get("faithfulness")),
                    toDouble(result.get("context_precision")),
                    toDouble(result.get("context_recall"))
            );

            log.info("RAGAS evaluation: relevancy={}, faithfulness={}, precision={}, recall={}",
                    ragasResult.answerRelevancy(), ragasResult.faithfulness(),
                    ragasResult.contextPrecision(), ragasResult.contextRecall());

            return ragasResult;

        } catch (Exception e) {
            log.warn("RAGAS evaluation failed: {}", e.getMessage());
            return null;
        }
    }

    private static Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
