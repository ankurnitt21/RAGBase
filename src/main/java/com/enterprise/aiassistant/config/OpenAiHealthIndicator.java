package com.enterprise.aiassistant.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("openai")
public class OpenAiHealthIndicator implements HealthIndicator {

    private final EmbeddingModel embeddingModel;

    public OpenAiHealthIndicator(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Health health() {
        try {
            embeddingModel.embed("health").content();
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down().withDetail("error", ex.getMessage()).build();
        }
    }
}
