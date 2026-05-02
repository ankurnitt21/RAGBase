package com.enterprise.aiassistant.dto;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable metrics bag carried through the RAG pipeline.
 * Thread-safe counters for LLM calls / tokens since the pipeline
 * uses virtual threads for parallel phases.
 */
public class PipelineMetrics {

    // Latency breakdown
    public long cacheLatencyMs;
    public long embeddingLatencyMs;
    public long retrievalLatencyMs;
    public long rerankingLatencyMs;
    public long llmLatencyMs;
    public long groundingLatencyMs;
    public long totalLatencyMs;

    // Cache
    public boolean cacheHit;
    public double cacheSimilarityScore;

    // LLM
    public final AtomicInteger llmCalls = new AtomicInteger(0);
    public final AtomicLong inputTokens = new AtomicLong(0);
    public final AtomicLong outputTokens = new AtomicLong(0);
    public double costUsd;

    // Domain
    public String predictedDomain;
    public String finalDomain;
    public boolean isMisclassified;

    // Retrieval
    public int topK;
    public int retrievalSourceCount;
    public boolean domainFilterUsed;

    // RAGAS
    public Double ragasAnswerRelevancy;
    public Double ragasFaithfulness;
    public Double ragasContextPrecision;
    public Double ragasContextRecall;

    // Contexts for RAGAS evaluation
    public List<String> retrievedContexts;

    // Prompt versioning — tracks which prompt versions were used in this request
    public Map<String, Integer> promptVersions;

    public void recordLlmCall(int inTokens, int outTokens) {
        llmCalls.incrementAndGet();
        inputTokens.addAndGet(inTokens);
        outputTokens.addAndGet(outTokens);
    }
}
