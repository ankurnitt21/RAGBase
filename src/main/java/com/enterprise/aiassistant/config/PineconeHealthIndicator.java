package com.enterprise.aiassistant.config;

import com.enterprise.aiassistant.dto.Domain;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator health check for Pinecone. Inspects the type of each domain's
 * EmbeddingStore — PineconeEmbeddingStore (live) vs InMemoryEmbeddingStore
 * (fallback). No network call is made. Status: UP (all Pinecone),
 * DOWN (all in-memory), UNKNOWN (mixed). Reported at GET /actuator/health.
 */
@Component("pinecone")
public class PineconeHealthIndicator implements HealthIndicator {

    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;

    public PineconeHealthIndicator(Map<Domain, EmbeddingStore<TextSegment>> domainStores) {
        this.domainStores = domainStores;
    }

    @Override
    public Health health() {
        long pineconeCount = domainStores.values().stream()
                .filter(s -> s instanceof PineconeEmbeddingStore)
                .count();
        long fallbackCount = domainStores.values().stream()
                .filter(s -> s instanceof InMemoryEmbeddingStore)
                .count();

        if (fallbackCount == 0) {
            return Health.up()
                    .withDetail("namespaces", domainStores.size())
                    .withDetail("backend", "pinecone")
                    .build();
        } else if (pineconeCount == 0) {
            return Health.down()
                    .withDetail("namespaces", domainStores.size())
                    .withDetail("backend", "in-memory (all namespaces using fallback)")
                    .build();
        } else {
            return Health.unknown()
                    .withDetail("pinecone_namespaces", pineconeCount)
                    .withDetail("fallback_namespaces", fallbackCount)
                    .build();
        }
    }
}
