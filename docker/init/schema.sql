-- ============================================================
-- RAGBase — PostgreSQL schema
-- Applied automatically by Docker on first container start.
-- JPA ddl-auto=update also keeps this in sync at runtime.
-- ============================================================

-- ── pgvector extension for HNSW vector search ────────────────────────────────
CREATE EXTENSION IF NOT EXISTS vector;

-- ── Conversation history ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_id
    ON chat_messages (conversation_id);

-- ── Documents (parent record for ingested files) ─────────────────────────────
CREATE TABLE IF NOT EXISTS documents (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(512) NOT NULL,
    domain     VARCHAR(20)  NOT NULL,
    source     VARCHAR(512) NOT NULL,
    version    VARCHAR(50)  DEFAULT '1.0',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_domain ON documents (domain);
CREATE INDEX IF NOT EXISTS idx_documents_source ON documents (source);

-- ── Document chunks (hybrid retrieval: vector HNSW + keyword FTS) ────────────
CREATE TABLE IF NOT EXISTS document_chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT       REFERENCES documents(id) ON DELETE CASCADE,
    domain      VARCHAR(20)  NOT NULL,
    content     TEXT         NOT NULL,
    source      VARCHAR(512) NOT NULL,
    chunk_index INT          NOT NULL DEFAULT 0,
    embedding   vector(384),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_domain
    ON document_chunks (domain);

CREATE INDEX IF NOT EXISTS idx_document_chunks_source
    ON document_chunks (source);

CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id
    ON document_chunks (document_id);

-- GIN index on the tsvector column for fast full-text search (BM25 leg)
CREATE INDEX IF NOT EXISTS idx_document_chunks_fts
    ON document_chunks USING GIN (to_tsvector('english', content));

-- HNSW index for approximate nearest-neighbour vector search (cosine distance)
CREATE INDEX IF NOT EXISTS idx_document_chunks_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- ── Query logs (observability / analytics) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS query_logs (
    id                      BIGSERIAL PRIMARY KEY,
    user_query              TEXT         NOT NULL,
    domain                  VARCHAR(20),
    response                TEXT,
    cache_hit               BOOLEAN      NOT NULL DEFAULT FALSE,
    latency_ms              BIGINT       NOT NULL DEFAULT 0,
    llm_calls               INT          NOT NULL DEFAULT 0,
    input_tokens            INT          NOT NULL DEFAULT 0,
    output_tokens           INT          NOT NULL DEFAULT 0,
    cost_usd                DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    predicted_domain        VARCHAR(20),
    final_domain            VARCHAR(20),
    is_misclassified        BOOLEAN      NOT NULL DEFAULT FALSE,
    cache_latency_ms        BIGINT       NOT NULL DEFAULT 0,
    embedding_latency_ms    BIGINT       NOT NULL DEFAULT 0,
    retrieval_latency_ms    BIGINT       NOT NULL DEFAULT 0,
    reranking_latency_ms    BIGINT       NOT NULL DEFAULT 0,
    llm_latency_ms          BIGINT       NOT NULL DEFAULT 0,
    grounding_latency_ms    BIGINT       NOT NULL DEFAULT 0,
    ragas_answer_relevancy  DOUBLE PRECISION,
    ragas_faithfulness      DOUBLE PRECISION,
    ragas_context_precision DOUBLE PRECISION,
    ragas_context_recall    DOUBLE PRECISION,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_query_logs_domain ON query_logs (domain);
CREATE INDEX IF NOT EXISTS idx_query_logs_created_at ON query_logs (created_at);

-- ── Migration: add new columns if upgrading from older schema ────────────
DO $$ BEGIN
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS llm_calls INT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS input_tokens INT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS output_tokens INT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0.0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS predicted_domain VARCHAR(20);
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS final_domain VARCHAR(20);
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS is_misclassified BOOLEAN NOT NULL DEFAULT FALSE;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS cache_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS embedding_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS retrieval_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS reranking_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS llm_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS grounding_latency_ms BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS ragas_answer_relevancy DOUBLE PRECISION;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS ragas_faithfulness DOUBLE PRECISION;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS ragas_context_precision DOUBLE PRECISION;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS ragas_context_recall DOUBLE PRECISION;
    ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS prompt_version INT DEFAULT 1;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

-- ── Prompt templates (versioned, DB-driven, dynamically loaded) ──────────────
CREATE TABLE IF NOT EXISTS prompt_templates (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,       -- e.g. chat-system, domain-router, query-rewriter, ambiguity-check
    version     INT           NOT NULL DEFAULT 1,
    content     TEXT          NOT NULL,       -- the prompt template text
    active      BOOLEAN       NOT NULL DEFAULT FALSE,
    description VARCHAR(500),                 -- optional: what this version changes
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    UNIQUE(name, version)                    -- one version per name
);

CREATE INDEX IF NOT EXISTS idx_prompt_templates_name ON prompt_templates (name);
CREATE INDEX IF NOT EXISTS idx_prompt_templates_active ON prompt_templates (name, active) WHERE active = TRUE;
