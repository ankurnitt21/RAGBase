package com.enterprise.aiassistant.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * JDBC-based repository for pgvector operations on document_chunks.
 * Handles embedding storage (batch UPDATE) and HNSW vector search.
 * Complements the JPA DocumentChunkRepository which handles text/FTS queries.
 */
@Repository
public class VectorSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public VectorSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Batch-updates the embedding column for a list of chunk IDs.
     * Called after JPA saveAll() during document ingestion so that each
     * chunk row has both text (for BM25 FTS) and embedding (for HNSW search).
     */
    public void batchUpdateEmbeddings(List<Long> chunkIds, List<float[]> embeddings) {
        if (chunkIds.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunkIds and embeddings must have equal size");
        }

        String sql = "UPDATE document_chunks SET embedding = cast(? as vector) WHERE id = ?";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, toVectorString(embeddings.get(i)));
                ps.setLong(2, chunkIds.get(i));
            }

            @Override
            public int getBatchSize() {
                return chunkIds.size();
            }
        });

        log.debug("Batch-updated {} embeddings in document_chunks", chunkIds.size());
    }

    /**
     * HNSW approximate nearest-neighbour search using pgvector cosine distance.
     * Returns chunks ordered by cosine similarity (highest first).
     *
     * @param queryEmbedding the query vector (384-dim float array)
     * @param domain         domain filter (HR, PRODUCT, AI)
     * @param limit          max results to return
     * @return list of matching chunks with similarity scores
     */
    public List<VectorSearchResult> vectorSearch(float[] queryEmbedding, String domain, int limit) {
        String sql = """
                SELECT id, domain, content, source,
                       1 - (embedding <=> cast(? as vector)) AS similarity
                FROM document_chunks
                WHERE domain = ? AND embedding IS NOT NULL
                ORDER BY embedding <=> cast(? as vector)
                LIMIT ?
                """;

        String vecStr = toVectorString(queryEmbedding);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
                rs.getLong("id"),
                rs.getString("domain"),
                rs.getString("content"),
                rs.getString("source"),
                rs.getDouble("similarity")
        ), vecStr, domain, vecStr, limit);
    }

    /**
     * Converts a float array to the pgvector string literal format: [0.1,0.2,0.3]
     */
    private static String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public record VectorSearchResult(Long id, String domain, String content, String source, double similarity) {}
}
