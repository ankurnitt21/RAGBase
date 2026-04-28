package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;
import redis.clients.jedis.search.schemafields.VectorField.VectorAlgorithm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic cache backed by Redis Stack (RediSearch module).
 *
 * Architecture:
 *   - Each cached entry is a Redis HASH at key "sc:{uuid}" containing:
 *       embedding  → float32 bytes (binary field, used by the HNSW index)
 *       answer_json → Jackson-serialized ChatResponse
 *       question   → original question text (for observability / debugging)
 *   - A RediSearch HNSW index over the "embedding" field enables approximate
 *     nearest-neighbour (ANN) search in O(log n) time, far faster than the
 *     previous O(n) linear scan over JVM heap entries.
 *
 * On a cache hit the full LLM + Pinecone + grounding pipeline is bypassed.
 *
 * COSINE distance in RediSearch: returned score = 1 − cosine_similarity.
 *   → similarity = 1 − score; threshold check: score ≤ (1 − similarityThreshold)
 *
 * TTL: configurable via app.semantic-cache.ttl-hours (default 24 h, 0 = no expiry).
 *
 * Configuration:
 *   app.semantic-cache.enabled             toggle (default true)
 *   app.semantic-cache.similarity-threshold cosine threshold 0..1 (default 0.92)
 *   app.semantic-cache.ttl-hours           entry lifetime in hours (default 24, 0=forever)
 *   app.redis.*                            connection settings (see RedisConfig)
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /** text-embedding-3-small output dimension. */
    private static final int EMBEDDING_DIM = 1536;

    private static final String INDEX_NAME  = "sc_idx";
    private static final String KEY_PREFIX  = "sc:";

    private static final String FIELD_EMBEDDING   = "embedding";
    private static final String FIELD_ANSWER_JSON = "answer_json";
    private static final String FIELD_QUESTION    = "question";
    private static final String SCORE_ALIAS       = "dist";

    @Value("${app.semantic-cache.similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${app.semantic-cache.ttl-hours:24}")
    private long ttlHours;

    private final UnifiedJedis jedis;
    private final ObjectMapper objectMapper;

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
                    new TextField(FIELD_QUESTION),
                    new TextField(FIELD_ANSWER_JSON),
                    new VectorField(FIELD_EMBEDDING, VectorAlgorithm.HNSW, vectorAttrs)
            );
            log.info("Redis semantic cache index '{}' created (dim={}, HNSW COSINE)", INDEX_NAME, EMBEDDING_DIM);
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("Index already exists")) {
                log.info("Redis semantic cache index '{}' already exists — reusing", INDEX_NAME);
            } else {
                throw e;
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Searches Redis for an answer whose stored question embedding is
     * semantically equivalent to the given embedding.
     *
     * Uses KNN=1 so only the single nearest neighbour is returned and scored.
     * The COSINE distance is converted to similarity before comparing with the
     * configured threshold.
     *
     * @param questionEmbedding embedding of the incoming user question
     * @return the cached ChatResponse if similarity >= threshold, else empty
     */
    public Optional<ChatResponse> lookup(Embedding questionEmbedding) {
        byte[] queryBytes = toBytes(questionEmbedding.vector());

        // KNN query: find the 1 nearest neighbour in embedding space
        Query q = new Query("*=>[KNN 1 @" + FIELD_EMBEDDING + " $vec AS " + SCORE_ALIAS + "]")
                .addParam("vec", queryBytes)
                .returnFields(FIELD_ANSWER_JSON, SCORE_ALIAS)
                .setSortBy(SCORE_ALIAS, true)   // ascending: smallest distance first
                .dialect(2)
                .limit(0, 1);

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
            // Index not yet ready or RediSearch module not available
            log.warn("Redis semantic cache lookup failed — falling back to full pipeline: {}", e.getMessage());
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached answer: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores an answer in Redis under a new UUID key.
     * The embedding is serialized as little-endian FLOAT32 bytes so RediSearch
     * can index it. An optional TTL is applied to limit memory growth.
     *
     * @param questionEmbedding embedding of the user question
     * @param question          original question text (for observability)
     * @param response          the answer to cache
     */
    public void put(Embedding questionEmbedding, String question, ChatResponse response) {
        String key = KEY_PREFIX + UUID.randomUUID();

        try {
            String answerJson = objectMapper.writeValueAsString(response);

            // String fields stored via the string-protocol hset
            jedis.hset(key, Map.of(
                    FIELD_QUESTION,    question,
                    FIELD_ANSWER_JSON, answerJson
            ));
            // Binary embedding field stored via the binary-protocol hset
            jedis.hset(key.getBytes(),
                    Map.of(FIELD_EMBEDDING.getBytes(), toBytes(questionEmbedding.vector())));

            if (ttlHours > 0) {
                jedis.expire(key, ttlHours * 3600);
            }

            log.debug("Semantic cache stored entry key={}", key);

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
