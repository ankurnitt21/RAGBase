package com.enterprise.aiassistant.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.enterprise.aiassistant.dto.Domain;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;

@Component
public class KnowledgeBaseTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTools.class);

    private final EmbeddingModel embeddingModel;
    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;

    public KnowledgeBaseTools(EmbeddingModel embeddingModel,
                               Map<Domain, EmbeddingStore<TextSegment>> domainStores) {
        this.embeddingModel = embeddingModel;
        this.domainStores = domainStores;
    }

    @Tool("Search the enterprise knowledge base for documents relevant to a query. " +
          "Call this before answering any factual question. You may call it multiple times with refined queries.")
    @Retry(name = "pinecone", fallbackMethod = "searchFallback")
    public String searchKnowledgeBase(
            @P("The search query to find relevant documents") String query,
            @P("The domain to search in. Must be one of: HR, PRODUCT, AI") String domain) {

        Domain d;
        try {
            d = Domain.valueOf(domain.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Unknown domain '" + domain + "'. Available domains: " +
                   Arrays.stream(Domain.values()).map(Domain::name).collect(Collectors.joining(", "));
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = domainStores.get(d)
                .search(request)
                .matches()
                .stream()
                .filter(m -> m.score() >= 0.7)
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return "No relevant documents found in domain [" + d + "] for query: " + query;
        }

        StringBuilder sb = new StringBuilder("Results from domain [" + d + "]:\n\n");
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            Object source = match.embedded().metadata().toMap().get("source");
            sb.append("[%d] source: %s (score: %.2f)\n%s\n\n"
                    .formatted(i + 1, source != null ? source : "unknown", match.score(), match.embedded().text()));
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private String searchFallback(String query, String domain, Exception ex) {
        log.error("Knowledge base search failed for domain [{}] after retries: {}", domain, ex.getMessage());
        return "Knowledge base is temporarily unavailable. Unable to retrieve relevant documents for: " + query;
    }

    @Tool("List all knowledge domains available in the knowledge base.")
    public String listAvailableDomains() {
        return Arrays.stream(Domain.values())
                .map(Domain::name)
                .collect(Collectors.joining(", "));
    }
}
