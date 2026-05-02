package com.enterprise.aiassistant.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.embedding.Embedding;
import jakarta.annotation.PostConstruct;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;
import redis.clients.jedis.search.schemafields.VectorField.VectorAlgorithm;

/**
 * Semantic cache backed by Redis Stack (RediSearch module) with domain-wise isolation.
 *
 * Architecture:
 *   - Each cached entry is a Redis HASH at key "sc:{domain}:{uuid}" containing:
 *       embedding  → float32 bytes (binary field, used by the HNSW index)
 *       answer_json → Jackson-serialized ChatResponse
 *       question   → original question text (for observability / debugging)
 *       domain     → TAG field for domain-scoped KNN search
 *   - A single RediSearch HNSW index with a TAG filter on "domain" enables
 *     domain-scoped approximate nearest-neighbour (ANN) search in O(log n) time.
 *     Different domains (HR, PRODUCT, AI) share one Redis instance and one index,
 *     but cache lookups are scoped to the requesting domain so that an HR question
 *     never returns a cached PRODUCT answer.
 *
 * On a cache hit the full LLM + retrieval + grounding pipeline is bypassed.
 *
 * COSINE distance in RediSearch: returned score = 1 − cosine_similarity.
 *   → similarity = 1 − score; threshold check: score ≤ (1 − similarityThreshold)
 *
 * TTL: configurable via app.semantic-cache.ttl-hours (default 24 h, 0 = no expiry).
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /** Embedding dimension — must match the Pinecone knowledge-base index (384-dim).
     *  text-embedding-3-small is used with dimensions=384 (OpenAI supports reduction). */
    private static final int EMBEDDING_DIM = 384;

    private static final String INDEX_NAME  = "sc_idx";
    private static final String KEY_PREFIX  = "sc:";

    private static final String FIELD_EMBEDDING   = "embedding";
    private static final String FIELD_ANSWER_JSON = "answer_json";
    private static final String FIELD_QUESTION    = "question";
    private static final String FIELD_DOMAIN      = "domain";
    private static final String FIELD_CONFIDENCE  = "confidence";
    private static final String FIELD_CREATED_AT  = "created_at";
    private static final String FIELD_MODEL_VER   = "model_version";
    private static final String FIELD_PROMPT_VER  = "prompt_version";
    private static final String SCORE_ALIAS       = "dist";

    @Value("${app.semantic-cache.similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${app.semantic-cache.ttl-hours:24}")
    private long ttlHours;

    @Value("${groq.chat-model:llama-3.3-70b-versatile}")
    private String modelVersion;

    private final UnifiedJedis jedis;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public SemanticCacheService(UnifiedJedis jedis, ObjectMapper objectMapper) {
        this.jedis = jedis;
        this.objectMapper = objectMapper;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates the HNSW vector index on startup.
     * Safe to call when the index already exists — the "Index already exists"
     * error from Redis is caught and ignored so restarts are idempotent.
     *
     * HNSW parameters:
     *   M=16            — graph connectivity, balances recall vs. memory
     *   EF_CONSTRUCTION=200 — search width during build, higher = better recall
     */
    @PostConstruct
    void initIndex() {
        createIndexIfAbsent();
    }

    /**
     * Ensures the RediSearch index exists. Safe to call multiple times —
     * after the first successful creation the flag short-circuits.
     * Called at startup and lazily before put/lookup if the index was destroyed.
     */
    private void ensureIndex() {
        if (indexReady.get()) return;
        createIndexIfAbsent();
    }

    private void createIndexIfAbsent() {
        Map<String, Object> vectorAttrs = new HashMap<>();
        vectorAttrs.put("TYPE", "FLOAT32");
        vectorAttrs.put("DIM", EMBEDDING_DIM);
        vectorAttrs.put("DISTANCE_METRIC", "COSINE");
        vectorAttrs.put("M", 16);
        vectorAttrs.put("EF_CONSTRUCTION", 200);

        try {
            jedis.ftCreate(INDEX_NAME,
                    FTCreateParams.createParams()
                            .on(IndexDataType.HASH)
                            .prefix(KEY_PREFIX),
                    new TagField(FIELD_DOMAIN),
                    new TextField(FIELD_QUESTION),
                    new TextField(FIELD_ANSWER_JSON),
                    new TagField(FIELD_CONFIDENCE),
                    new TextField(FIELD_CREATED_AT),
                    new TagField(FIELD_MODEL_VER),
                    new TagField(FIELD_PROMPT_VER),
                    new VectorField(FIELD_EMBEDDING, VectorAlgorithm.HNSW, vectorAttrs)
            );
            indexReady.set(true);
            log.info("Redis semantic cache index '{}' created (dim={}, HNSW COSINE, domain-tagged)", INDEX_NAME, EMBEDDING_DIM);
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("Index already exists")) {
                indexReady.set(true);
                log.info("Redis semantic cache index '{}' already exists — reusing", INDEX_NAME);
            } else {
                throw e;
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Searches Redis for a cached answer whose question embedding is
     * semantically equivalent to the given embedding, scoped to the given domain.
     *
     * The TAG filter on domain ensures HR questions only match HR cache entries,
     * PRODUCT only PRODUCT, etc. — all within a single Redis index.
     *
     * @param questionEmbedding embedding of the incoming user question
     * @param domain            the routed domain (HR, PRODUCT, AI)
     * @return the cached ChatResponse if similarity >= threshold, else empty
     */
    public Optional<ChatResponse> lookup(Embedding questionEmbedding, String domain) {
        ensureIndex();
        byte[] queryBytes = toBytes(questionEmbedding.vector());

        // KNN query scoped to domain via TAG filter — KNN=3 for better HNSW recall
        Query q = new Query("@" + FIELD_DOMAIN + ":{" + domain + "}=>[KNN 3 @" + FIELD_EMBEDDING + " $vec AS " + SCORE_ALIAS + "]")
                .addParam("vec", queryBytes)
                .returnFields(FIELD_ANSWER_JSON, SCORE_ALIAS)
                .setSortBy(SCORE_ALIAS, true)   // ascending: smallest distance first
                .dialect(2)
                .limit(0, 3);

        try {
            SearchResult result = jedis.ftSearch(INDEX_NAME, q);
            List<Document> docs = result.getDocuments();

            if (docs.isEmpty()) {
                log.debug("Semantic cache miss — index is empty");
                return Optional.empty();
            }

            Document doc = docs.get(0);
            // COSINE distance: score = 1 − cosine_similarity  (range [0, 2])
            double distance   = Double.parseDouble(doc.get(SCORE_ALIAS).toString());
            double similarity = 1.0 - distance;

            if (similarity < similarityThreshold) {
                log.debug("Semantic cache miss (similarity={}, threshold={})",
                        String.format("%.4f", similarity), similarityThreshold);
                return Optional.empty();
            }

            String answerJson = doc.get(FIELD_ANSWER_JSON).toString();
            ChatResponse response = objectMapper.readValue(answerJson, ChatResponse.class);
            log.info("Semantic cache hit (similarity={})", String.format("%.4f", similarity));
            return Optional.of(response);

        } catch (JedisDataException e) {
            // Index might have been destroyed externally (e.g. FLUSHALL)
            if (e.getMessage() != null && e.getMessage().contains("no such index")) {
                indexReady.set(false);
                ensureIndex();
            }
            log.warn("Redis semantic cache lookup failed — falling back to full pipeline: {}", e.getMessage());
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached answer: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores an answer in Redis under a domain-prefixed UUID key.
     * The embedding is serialized as little-endian FLOAT32 bytes so RediSearch
     * can index it. A TAG field stores the domain for scoped lookups.
     *
     * @param questionEmbedding embedding of the user question
     * @param question          original question text (for observability)
     * @param domain            the routed domain (HR, PRODUCT, AI)
     * @param response          the answer to cache
     */
    public void put(Embedding questionEmbedding, String question, String domain, ChatResponse response) {
        put(questionEmbedding, question, domain, response, 0);
    }

    /**
     * Stores an answer in Redis with prompt version metadata.
     */
    public void put(Embedding questionEmbedding, String question, String domain, ChatResponse response, int promptVersion) {
        ensureIndex();
        String key = KEY_PREFIX + domain.toLowerCase() + ":" + UUID.randomUUID();

        try {
            String answerJson = objectMapper.writeValueAsString(response);

            jedis.hset(key, Map.of(
                    FIELD_QUESTION,    question,
                    FIELD_ANSWER_JSON, answerJson,
                    FIELD_DOMAIN,      domain,
                    FIELD_CONFIDENCE,  response.confidence() != null ? response.confidence() : "UNKNOWN",
                    FIELD_CREATED_AT,  java.time.Instant.now().toString(),
                    FIELD_MODEL_VER,   modelVersion,
                    FIELD_PROMPT_VER,  String.valueOf(promptVersion)
            ));
            // Binary embedding field stored via the binary-protocol hset
            jedis.hset(key.getBytes(),
                    Map.of(FIELD_EMBEDDING.getBytes(), toBytes(questionEmbedding.vector())));

            if (ttlHours > 0) {
                jedis.expire(key, ttlHours * 3600);
            }

            log.debug("Semantic cache stored entry key={} promptVersion={}", key, promptVersion);

        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize answer for caching — entry skipped: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Redis semantic cache write failed — entry skipped: {}", e.getMessage());
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    /**
     * Converts a float array to little-endian FLOAT32 bytes.
     * Redis vector fields require IEEE 754 single-precision little-endian binary.
     */
    private static byte[] toBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) buf.putFloat(f);
        return buf.array();
    }
}
