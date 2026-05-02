package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.entity.DocumentChunk;
import com.enterprise.aiassistant.repository.DocumentChunkRepository;
import com.enterprise.aiassistant.repository.VectorSearchRepository;
import com.enterprise.aiassistant.repository.VectorSearchRepository.VectorSearchResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
 * searchKnowledgeBase runs a two-stage hybrid retrieval pipeline:
 *   Stage 1 — PostgreSQL HNSW vector search (cosine) + PostgreSQL FTS (BM25) in parallel
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
    public record RankedChunk(String text, String source) {}

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
    private final VectorSearchRepository vectorSearchRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public KnowledgeBaseTools(EmbeddingModel embeddingModel,
                               VectorSearchRepository vectorSearchRepository,
                               DocumentChunkRepository documentChunkRepository) {
        this.embeddingModel = embeddingModel;
        this.vectorSearchRepository = vectorSearchRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Tool("Search the enterprise knowledge base for documents relevant to a query. " +
          "Call this before answering any factual question. You may call it multiple times with refined queries.")
    @Retry(name = "llm", fallbackMethod = "searchFallback")
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

        // Stage 1 — Hybrid retrieval: HNSW vector search + BM25 keyword search (both PostgreSQL)
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<VectorSearchResult> vectorMatches = vectorSearchRepository.vectorSearch(
                queryEmbedding.vector(), d.name(), VECTOR_CANDIDATES);

        List<DocumentChunk> keywordMatches = fetchKeywordResults(query, d);

        // Stage 2 — Reciprocal Rank Fusion: merge both lists
        List<RankedChunk> merged = reciprocalRankFusion(vectorMatches, keywordMatches);

        if (merged.isEmpty()) {
            return "No relevant documents found in domain [" + d + "] for query: " + query;
        }

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

    // ── Individual retrieval steps (called by ChatService for per-step tracing) ──

    /**
     * Vector search using a pre-computed embedding. Avoids duplicate embedding generation.
     */
    public List<VectorSearchResult> vectorSearch(float[] queryEmbedding, String domain) {
        return vectorSearchRepository.vectorSearch(queryEmbedding, domain, VECTOR_CANDIDATES);
    }

    /**
     * BM25 keyword search via PostgreSQL full-text search.
     */
    public List<DocumentChunk> bm25Search(String query, String domain) {
        return fetchKeywordResults(query, Domain.valueOf(domain.toUpperCase()));
    }

    /**
     * Reciprocal Rank Fusion: merges vector and BM25 result lists.
     */
    public List<RankedChunk> rrfMerge(List<VectorSearchResult> vectorResults,
                                       List<DocumentChunk> keywordResults) {
        return reciprocalRankFusion(vectorResults, keywordResults);
    }

    /**
     * Takes top-K from merged results, formats them, and captures in ThreadLocal
     * for grounding check.
     */
    public String formatAndCapture(List<RankedChunk> merged, String domain, int topK) {
        if (merged.isEmpty()) {
            return "No relevant documents found in domain [" + domain + "]";
        }
        List<RankedChunk> reranked = merged.subList(0, Math.min(topK, merged.size()));
        String result = formatResults(reranked, Domain.valueOf(domain.toUpperCase()));
        RETRIEVED_CONTEXT.get().append(result).append("\n\n---\n\n");
        return result;
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

    /** RRF score(doc) = Σ 1/(RRF_K + rank) — docs in both lists get scores summed. */
    private List<RankedChunk> reciprocalRankFusion(List<VectorSearchResult> vectorResults,
                                                    List<DocumentChunk> keywordResults) {
        Map<String, String> sourceMap = new LinkedHashMap<>();
        Map<String, Double> scores    = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            VectorSearchResult m = vectorResults.get(i);
            sourceMap.putIfAbsent(m.content(), m.source());
            scores.merge(m.content(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            DocumentChunk c = keywordResults.get(i);
            sourceMap.putIfAbsent(c.getContent(), c.getSource());
            scores.merge(c.getContent(), 1.0 / (RRF_K + i + 1), Double::sum);
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
