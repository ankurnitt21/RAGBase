package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.entity.DocumentChunk;
import com.enterprise.aiassistant.repository.DocumentChunkRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain4j @Tool methods exposed to the ChatAssistant AiServices proxy.
 *
 * searchKnowledgeBase runs a two-stage retrieval pipeline:
 *   Stage 1 — Hybrid retrieval: Pinecone vector search + PostgreSQL FTS in parallel
 *   Stage 2 — Reciprocal Rank Fusion: merges both result lists by rank score,
 *              then returns the top-K by RRF score (no LLM reranking call).
 *
 * Tool outputs are captured in a ThreadLocal so ChatService can drain them
 * after the LLM finishes and run a post-generation grounding check.
 */
@Component
public class KnowledgeBaseTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTools.class);

    private static final int VECTOR_CANDIDATES  = 10;
    private static final int KEYWORD_CANDIDATES = 10;
    private static final int FINAL_TOP_K        = 5;
    private static final int RRF_K              = 60;

    /** Carries text and source filename together through the pipeline. */
    private record RankedChunk(String text, String source) {}

    /**
     * Accumulates retrieved context per request thread for post-generation grounding.
     * Cleared and drained by ChatService around each chat call.
     */
    private static final ThreadLocal<StringBuilder> RETRIEVED_CONTEXT =
            ThreadLocal.withInitial(StringBuilder::new);

    public static void clearRetrievedContext() {
        RETRIEVED_CONTEXT.get().setLength(0);
    }

    public static String drainRetrievedContext() {
        String ctx = RETRIEVED_CONTEXT.get().toString();
        RETRIEVED_CONTEXT.get().setLength(0);
        return ctx;
    }

    private final EmbeddingModel embeddingModel;
    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;
    private final DocumentChunkRepository documentChunkRepository;

    public KnowledgeBaseTools(EmbeddingModel embeddingModel,
                               Map<Domain, EmbeddingStore<TextSegment>> domainStores,
                               DocumentChunkRepository documentChunkRepository) {
        this.embeddingModel = embeddingModel;
        this.domainStores = domainStores;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Tool("Search the enterprise knowledge base for documents relevant to a query. " +
          "Call this before answering any factual question. You may call it multiple times with refined queries.")
    @Retry(name = "pinecone", fallbackMethod = "searchFallback")
    public String searchKnowledgeBase(
            @P("The search query to find relevant documents") String query,
            @P("The domain to search in. Must be one of: HR, PRODUCT, AI") String domain) {

        if (query == null || query.isBlank()) {
            return "Invalid tool call: query must not be blank. Please provide a specific search query.";
        }

        Domain d;
        try {
            d = Domain.valueOf(domain.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Unknown domain '" + domain + "'. Available domains: " +
                   Arrays.stream(Domain.values()).map(Domain::name).collect(Collectors.joining(", "));
        }

        // Stage 1 — Hybrid retrieval: vector (Pinecone) + keyword (PostgreSQL FTS)
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> vectorMatches = domainStores.get(d)
                .search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(VECTOR_CANDIDATES)
                        .build())
                .matches();

        List<DocumentChunk> keywordMatches = fetchKeywordResults(query, d);

        // Stage 2 — Reciprocal Rank Fusion: merge both lists
        List<RankedChunk> merged = reciprocalRankFusion(vectorMatches, keywordMatches);

        if (merged.isEmpty()) {
            return "No relevant documents found in domain [" + d + "] for query: " + query;
        }

        // Stage 2 output already sorted by RRF score — take top-K directly (no LLM call)
        List<RankedChunk> reranked = merged.subList(0, Math.min(FINAL_TOP_K, merged.size()));

        String result = formatResults(reranked, d);
        RETRIEVED_CONTEXT.get().append(result).append("\n\n---\n\n");

        log.debug("searchKnowledgeBase [{}]: vector={} keyword={} merged={} reranked={}",
                  d, vectorMatches.size(), keywordMatches.size(), merged.size(), reranked.size());
        return result;
    }

    @SuppressWarnings("unused")
    private String searchFallback(String query, String domain, Exception ex) {
        log.error("Knowledge base search failed for domain [{}] after retries: {}", domain, ex.getMessage());
        return "Knowledge base is temporarily unavailable. Unable to retrieve relevant documents for: " + query;
    }

    @Tool("List all knowledge domains available in the knowledge base.")
    public String listAvailableDomains() {
        return Arrays.stream(Domain.values()).map(Domain::name).collect(Collectors.joining(", "));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<DocumentChunk> fetchKeywordResults(String query, Domain domain) {
        try {
            return documentChunkRepository.fullTextSearch(query, domain.name(), KEYWORD_CANDIDATES);
        } catch (Exception e) {
            log.warn("PostgreSQL FTS failed for domain [{}], using vector-only: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /** score(doc) = Σ 1/(RRF_K + rank) — docs in both lists get scores summed. */
    private List<RankedChunk> reciprocalRankFusion(List<EmbeddingMatch<TextSegment>> vectorResults,
                                                    List<DocumentChunk> keywordResults) {
        Map<String, String> sourceMap = new LinkedHashMap<>();
        Map<String, Double> scores    = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            EmbeddingMatch<TextSegment> m = vectorResults.get(i);
            String text = m.embedded().text();
            Object src  = m.embedded().metadata().toMap().get("source");
            sourceMap.putIfAbsent(text, src != null ? src.toString() : "unknown");
            scores.merge(text, 1.0 / (RRF_K + i + 1), (a, b) -> a + b);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            DocumentChunk c = keywordResults.get(i);
            sourceMap.putIfAbsent(c.getContent(), c.getSource());
            scores.merge(c.getContent(), 1.0 / (RRF_K + i + 1), (a, b) -> a + b);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new RankedChunk(e.getKey(), sourceMap.getOrDefault(e.getKey(), "unknown")))
                .collect(Collectors.toList());
    }

    private String formatResults(List<RankedChunk> chunks, Domain domain) {
        StringBuilder sb = new StringBuilder("Results from domain [" + domain + "]:\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("[%d] source: %s\n%s\n\n".formatted(i + 1, chunks.get(i).source(), chunks.get(i).text()));
        }
        return sb.toString();
    }
}
