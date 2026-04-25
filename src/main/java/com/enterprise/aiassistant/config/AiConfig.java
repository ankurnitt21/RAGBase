package com.enterprise.aiassistant.config;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import com.enterprise.aiassistant.dto.Domain;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;

@Configuration
@EnableAsync
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${pinecone.api-key}")
    private String pineconeApiKey;

    @Value("${pinecone.index-name}")
    private String pineconeIndexName;

    @Bean
    ChatLanguageModel chatLanguageModel(@Value("${openai.api-key}") String apiKey) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.3)
                .maxTokens(1024)
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(@Value("${openai.api-key}") String openAiApiKey) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiApiKey)
                .modelName("text-embedding-3-small")
                .build();
    }

    /**
     * Creates one EmbeddingStore per Domain. Uses Pinecone if reachable,
     * falls back to InMemoryEmbeddingStore if Pinecone times out or is misconfigured.
     * This ensures the app always starts even without network access to Pinecone.
     */
    @Bean
    Map<Domain, EmbeddingStore<TextSegment>> domainEmbeddingStores() {
        Map<Domain, EmbeddingStore<TextSegment>> stores = new EnumMap<>(Domain.class);
        for (Domain domain : Domain.values()) {
            String namespace = domain.name().toLowerCase();
            try {
                EmbeddingStore<TextSegment> store = PineconeEmbeddingStore.builder()
                        .apiKey(pineconeApiKey)
                        .index(pineconeIndexName)
                        .nameSpace(namespace)
                        .build();
                stores.put(domain, store);
                log.info("Pinecone store initialized for domain [{}] namespace '{}'", domain, namespace);
            } catch (Exception e) {
                log.warn("Pinecone unavailable for domain [{}] ({}). Using in-memory fallback. " +
                         "Documents uploaded to this domain will NOT be persisted across restarts.",
                         domain, e.getMessage());
                stores.put(domain, new InMemoryEmbeddingStore<>());
            }
        }
        return stores;
    }
}
