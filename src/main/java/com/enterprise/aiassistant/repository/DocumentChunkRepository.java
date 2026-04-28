package com.enterprise.aiassistant.repository;

import com.enterprise.aiassistant.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for document_chunks table.
 * Provides a native PostgreSQL full-text search query used as the keyword
 * search leg of the hybrid retrieval pipeline in KnowledgeBaseTools.
 * Results are ordered by ts_rank (BM25-style relevance score) descending.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * PostgreSQL full-text search using BM25-style ts_rank.
     * plainto_tsquery handles multi-word queries without needing tsquery syntax.
     * Results ordered by relevance rank descending.
     */
    @Query(value = """
            SELECT * FROM document_chunks
            WHERE domain = :domain
              AND to_tsvector('english', content) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(to_tsvector('english', content), plainto_tsquery('english', :query)) DESC
            LIMIT :limit
            """,
            nativeQuery = true)
    List<DocumentChunk> fullTextSearch(@Param("query") String query,
                                        @Param("domain") String domain,
                                        @Param("limit") int limit);
}
