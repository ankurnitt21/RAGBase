package com.enterprise.aiassistant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "query_logs", indexes = {
        @Index(name = "idx_query_logs_domain", columnList = "domain"),
        @Index(name = "idx_query_logs_created_at", columnList = "created_at")
})
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_query", nullable = false, columnDefinition = "TEXT")
    private String userQuery;

    @Column(length = 20)
    private String domain;

    @Column(columnDefinition = "TEXT")
    private String response;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "llm_calls", nullable = false)
    private int llmCalls;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    @Column(name = "predicted_domain", length = 20)
    private String predictedDomain;

    @Column(name = "final_domain", length = 20)
    private String finalDomain;

    @Column(name = "is_misclassified", nullable = false)
    private boolean isMisclassified;

    @Column(name = "cache_latency_ms", nullable = false)
    private long cacheLatencyMs;

    @Column(name = "embedding_latency_ms", nullable = false)
    private long embeddingLatencyMs;

    @Column(name = "retrieval_latency_ms", nullable = false)
    private long retrievalLatencyMs;

    @Column(name = "reranking_latency_ms", nullable = false)
    private long rerankingLatencyMs;

    @Column(name = "llm_latency_ms", nullable = false)
    private long llmLatencyMs;

    @Column(name = "grounding_latency_ms", nullable = false)
    private long groundingLatencyMs;

    @Column(name = "ragas_answer_relevancy")
    private Double ragasAnswerRelevancy;

    @Column(name = "ragas_faithfulness")
    private Double ragasFaithfulness;

    @Column(name = "ragas_context_precision")
    private Double ragasContextPrecision;

    @Column(name = "ragas_context_recall")
    private Double ragasContextRecall;

    @Column(name = "prompt_version")
    private Integer promptVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public QueryLog() {}

    public QueryLog(String userQuery, String domain, String response, boolean cacheHit, long latencyMs) {
        this.userQuery = userQuery;
        this.domain = domain;
        this.response = response;
        this.cacheHit = cacheHit;
        this.latencyMs = latencyMs;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId()                       { return id; }
    public String getUserQuery()              { return userQuery; }
    public String getDomain()                 { return domain; }
    public String getResponse()               { return response; }
    public boolean isCacheHit()               { return cacheHit; }
    public long getLatencyMs()                { return latencyMs; }
    public int getLlmCalls()                  { return llmCalls; }
    public int getInputTokens()              { return inputTokens; }
    public int getOutputTokens()             { return outputTokens; }
    public double getCostUsd()               { return costUsd; }
    public String getPredictedDomain()       { return predictedDomain; }
    public String getFinalDomain()           { return finalDomain; }
    public boolean isMisclassified()         { return isMisclassified; }
    public long getCacheLatencyMs()          { return cacheLatencyMs; }
    public long getEmbeddingLatencyMs()      { return embeddingLatencyMs; }
    public long getRetrievalLatencyMs()      { return retrievalLatencyMs; }
    public long getRerankingLatencyMs()      { return rerankingLatencyMs; }
    public long getLlmLatencyMs()            { return llmLatencyMs; }
    public long getGroundingLatencyMs()      { return groundingLatencyMs; }
    public Double getRagasAnswerRelevancy()  { return ragasAnswerRelevancy; }
    public Double getRagasFaithfulness()     { return ragasFaithfulness; }
    public Double getRagasContextPrecision() { return ragasContextPrecision; }
    public Double getRagasContextRecall()    { return ragasContextRecall; }
    public LocalDateTime getCreatedAt()      { return createdAt; }

    public void setLlmCalls(int llmCalls)                          { this.llmCalls = llmCalls; }
    public void setInputTokens(int inputTokens)                    { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens)                  { this.outputTokens = outputTokens; }
    public void setCostUsd(double costUsd)                         { this.costUsd = costUsd; }
    public void setPredictedDomain(String predictedDomain)         { this.predictedDomain = predictedDomain; }
    public void setFinalDomain(String finalDomain)                 { this.finalDomain = finalDomain; }
    public void setMisclassified(boolean misclassified)            { this.isMisclassified = misclassified; }
    public void setCacheLatencyMs(long cacheLatencyMs)             { this.cacheLatencyMs = cacheLatencyMs; }
    public void setEmbeddingLatencyMs(long embeddingLatencyMs)     { this.embeddingLatencyMs = embeddingLatencyMs; }
    public void setRetrievalLatencyMs(long retrievalLatencyMs)     { this.retrievalLatencyMs = retrievalLatencyMs; }
    public void setRerankingLatencyMs(long rerankingLatencyMs)     { this.rerankingLatencyMs = rerankingLatencyMs; }
    public void setLlmLatencyMs(long llmLatencyMs)                 { this.llmLatencyMs = llmLatencyMs; }
    public void setGroundingLatencyMs(long groundingLatencyMs)     { this.groundingLatencyMs = groundingLatencyMs; }
    public void setRagasAnswerRelevancy(Double v)   { this.ragasAnswerRelevancy = v; }
    public void setRagasFaithfulness(Double v)      { this.ragasFaithfulness = v; }
    public void setRagasContextPrecision(Double v)  { this.ragasContextPrecision = v; }
    public void setRagasContextRecall(Double v)     { this.ragasContextRecall = v; }
    public Integer getPromptVersion()               { return promptVersion; }
    public void setPromptVersion(Integer v)         { this.promptVersion = v; }
}
