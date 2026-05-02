# RAGBase

RAGBase is a Spring Boot enterprise knowledge assistant that ingests documents and answers questions with citations.
It uses Groq (llama-3.3-70b-versatile) for chat, a fast model (llama-3.1-8b-instant) for ambiguity detection, local ONNX all-MiniLM-L6-v2 for embeddings, Pinecone for redundant vector storage, PostgreSQL + pgvector for hybrid retrieval (HNSW + BM25 + RRF), Redis Stack for domain-scoped semantic answer caching, RAGAS for automated answer evaluation, and LangSmith for end-to-end trace observability.

## Highlights

- **Domain-aware Q&A** (`HR`, `PRODUCT`, `AI`) with two-phase routing: keyword match → embedding similarity (local ONNX, no LLM call)
- **Parallel pipeline**: domain routing + embedding generation + history load all run concurrently on virtual threads
- **Single embedding, zero duplication**: embedding computed once and reused for cache lookup, vector retrieval, and cache storage
- **Hybrid retrieval**: PostgreSQL HNSW vector search + BM25 full-text search, merged via Reciprocal Rank Fusion (RRF, k=60)
- **Split retrieval tracing**: `retrieval_vector` → `retrieval_bm25` → `fusion_rrf` → `reranking` — each step individually traced
- **Ambiguity detection**: heuristic fast path + LLM confirmation using a small fast model (llama-3.1-8b-instant, ~200ms). Cache skipped if heuristic flagged
- **DB-backed prompt management**: prompts stored in PostgreSQL with versioning, active flag, and dynamic hot-reload (no restart)
- **Prompt version tracking**: version metadata flows through Postgres (query_logs), Redis (cache entries), and LangSmith traces
- **LLM streaming**: `POST /api/v1/chat/stream` returns Server-Sent Events (SSE) with token-by-token streaming
- **Grounding confidence** (`HIGH`, `MEDIUM`, `LOW`) from a post-answer verification step
- **RAGAS evaluation**: async, non-blocking faithfulness scoring via a Python FastAPI microservice backed by Groq
- **Token-based conversation memory** with incremental LLM summarization for overflow
- **Domain-scoped semantic cache** in Redis (HNSW KNN=3 + TAG filter) — skips the full pipeline on similar questions
- **LangSmith tracing**: every span has `input.value`, `output.value`, `langsmith.span.kind`, `gen_ai.*`, and `prompt.*.version` attributes
- **Rich metadata**: `documents` table (parent records), `document_chunks` with `document_id` + `chunk_index`, `query_logs` for observability
- Sync and async document ingestion APIs
- Resilience4j rate limiting, retry, circuit breaker

## Tech Stack

| Layer        | Technology                                                      |
| ------------ | --------------------------------------------------------------- |
| Runtime      | Java 21+, Spring Boot 3.4.1                                     |
| AI Framework | LangChain4j 0.36.2                                              |
| Chat LLM     | Groq `llama-3.3-70b-versatile` via OpenAI-compatible API        |
| Fast LLM     | Groq `llama-3.1-8b-instant` (ambiguity detection)               |
| Streaming    | `OpenAiStreamingChatModel` → SSE via `SseEmitter`               |
| Embeddings   | Local ONNX `all-MiniLM-L6-v2` (384-dim, no API call)            |
| Vector DB    | Pinecone (cosine, namespaces per domain) — redundant store      |
| Primary DB   | PostgreSQL 16 + pgvector (HNSW + BM25 FTS + metadata)           |
| Cache        | Redis Stack (RediSearch HNSW KNN=3, domain-scoped, TTL 24h)     |
| Evaluation   | RAGAS 0.2.10 (Python FastAPI on port 8100, faithfulness metric) |
| Tracing      | OpenTelemetry SDK → LangSmith OTLP (`OtlpHttpSpanExporter`)     |
| Resilience   | Resilience4j (rate limiter 30rpm, retry 3x, circuit breaker)    |
| API Docs     | Swagger UI (`/swagger-ui.html`)                                 |

## Database Schema

### documents (parent records)

| Column     | Type         | Description                      |
| ---------- | ------------ | -------------------------------- |
| id         | BIGSERIAL PK | Auto-increment ID                |
| title      | VARCHAR(512) | Document title / filename        |
| domain     | VARCHAR(20)  | HR / PRODUCT / AI                |
| source     | VARCHAR(512) | Original filename                |
| version    | VARCHAR(50)  | Document version (default "1.0") |
| created_at | TIMESTAMP    | Ingestion time                   |
| updated_at | TIMESTAMP    | Last update time                 |

### document_chunks (hybrid retrieval)

| Column      | Type         | Description                    |
| ----------- | ------------ | ------------------------------ |
| id          | BIGSERIAL PK | Auto-increment ID              |
| document_id | BIGINT FK    | References documents(id)       |
| domain      | VARCHAR(20)  | HR / PRODUCT / AI              |
| content     | TEXT         | Chunk text                     |
| source      | VARCHAR(512) | Original filename              |
| chunk_index | INT          | Position in document (0-based) |
| embedding   | vector(384)  | pgvector HNSW indexed          |
| created_at  | TIMESTAMP    | Ingestion time                 |

### query_logs (observability)

| Column         | Type         | Description                            |
| -------------- | ------------ | -------------------------------------- |
| id             | BIGSERIAL PK | Auto-increment ID                      |
| user_query     | TEXT         | Original user question                 |
| domain         | VARCHAR(20)  | Routed domain                          |
| response       | TEXT         | Generated answer                       |
| cache_hit      | BOOLEAN      | Whether semantic cache was used        |
| latency_ms     | BIGINT       | End-to-end latency                     |
| prompt_version | INT          | Active chat-system prompt version used |
| created_at     | TIMESTAMP    | Request time                           |

### prompt_templates (versioned prompt management)

| Column      | Type         | Description                                             |
| ----------- | ------------ | ------------------------------------------------------- |
| id          | BIGSERIAL PK | Auto-increment ID                                       |
| name        | VARCHAR(100) | Prompt identifier (e.g. `chat-system`, `domain-router`) |
| version     | INT          | Version number (auto-incremented per name)              |
| content     | TEXT         | The prompt template text                                |
| active      | BOOLEAN      | Only one version per name can be active                 |
| description | VARCHAR(500) | What this version changes                               |
| created_at  | TIMESTAMP    | Creation time                                           |
| updated_at  | TIMESTAMP    | Last modification time                                  |

Constraints: `UNIQUE(name, version)` — ensures exactly one row per prompt + version.

## Architecture (Chat Pipeline)

```
rag_pipeline (~1.5s)
 ├── domain_routing              (keyword match → embedding similarity, 0 LLM calls)
 ├── embedding_generation        (ONNX all-MiniLM-L6-v2, ~10ms — computed ONCE)
 ├── [ambiguity_detection]       (fast model llama-3.1-8b-instant, ~200ms — only if heuristic triggers)
 ├── semantic_cache_lookup       (Redis HNSW KNN=3, @domain filter — skipped if ambiguous)
 ├── retrieval_vector            (pgvector HNSW cosine, top-10 — uses pre-computed embedding)
 ├── retrieval_bm25              (PostgreSQL FTS ts_rank, top-10)
 ├── fusion_rrf                  (Reciprocal Rank Fusion, k=60)
 ├── reranking                   (top-5 by RRF score)
 ├── llm_generation (~1.2s)
 │     └── llm_call              (Groq llama-3.3-70b-versatile)
 ├── grounding_check             (LLM: SUPPORTED/PARTIAL/UNSUPPORTED → HIGH/MEDIUM/LOW)
 ├── response_build              (persist, cache, build ChatResponse)
 └── ragas_evaluation            (async, non-blocking — Python RAGAS service)
```

### Key Design Decisions

1. **Parallel startup**: `domain_routing` + `embedding_generation` + `history_load` + `hasHistory_check` all launch on virtual threads simultaneously. The embedding is needed for cache AND retrieval, so it's computed once and reused everywhere.

2. **Ambiguity detection**: Uses `llama-3.1-8b-instant` (fast, ~200ms) instead of the main 70b model. Two-phase: heuristic rules (word count, pronouns, follow-up phrases) → LLM confirmation. If the heuristic flags a query, the semantic cache is skipped even if the LLM says "not ambiguous" (edge-case safety).

3. **Split retrieval**: Instead of a monolithic "retrieval" step, each sub-step (`retrieval_vector`, `retrieval_bm25`, `fusion_rrf`, `reranking`) is individually traced in LangSmith for fine-grained latency observability.

4. **LLM streaming**: `POST /api/v1/chat/stream` returns an SSE event stream. Tokens arrive in real-time via `StreamingChatLanguageModel`. After streaming completes, grounding check + persistence run, and a final `done` event carries the full `ChatResponse` with metadata.

5. **Semantic cache KNN=3**: Requests 3 nearest neighbors from the HNSW index (instead of 1) for better recall on approximate searches. The best match above the 0.92 similarity threshold is returned.

6. **RAGAS evaluation**: Runs asynchronously after the response is returned. A Python FastAPI microservice on port 8100 uses RAGAS 0.2.10 with `faithfulness` metric. The LLM backend for RAGAS is Groq (via `langchain-openai` with custom base URL). Scores are written back to the `query_logs` table asynchronously.

7. **LangSmith tracing**: Every span carries `input.value`, `output.value`, and `langsmith.span.kind`. LLM spans additionally carry `gen_ai.system`, `gen_ai.request.model`, `gen_ai.prompt.0.content`, `gen_ai.completion.0.content`, and token usage. The root span carries all pipeline metrics + RAGAS scores + prompt versions.

8. **DB-backed prompt management**: Prompts are stored in PostgreSQL with versioning and an active flag. On startup, if the `prompt_templates` table is empty, prompts are seeded from classpath `.st` files as version 1 (active). At runtime, new versions are created via the REST API and activated with a single PUT call. The in-memory cache ensures zero-latency prompt loading even if the DB is slow.

## Prompt Management

Prompts are no longer hardcoded or loaded from static files. They live in the database with full version control.

### How It Works

1. **First startup**: Seeds prompt_templates table from classpath `.st` files (v1, active)
2. **Runtime**: PromptService loads active prompts into `ConcurrentHashMap` — zero-latency reads
3. **New version**: `POST /api/v1/prompts/{name}` with new content → creates version N+1 (inactive)
4. **Activate**: `PUT /api/v1/prompts/{name}/activate/{version}` → deactivates old, activates new
5. **Hot-reload**: `POST /api/v1/prompts/refresh` → reloads from DB without app restart
6. **Fallback**: If DB fails at runtime, serves from in-memory cache (last-known-good)

### Version Metadata Propagation

| Store                   | Field                                                               | Purpose                                    |
| ----------------------- | ------------------------------------------------------------------- | ------------------------------------------ |
| PostgreSQL `query_logs` | `prompt_version`                                                    | Which chat-system prompt version was used  |
| Redis semantic cache    | `prompt_version` TAG                                                | Filter/invalidate cache by prompt version  |
| LangSmith trace         | `prompt.chat-system.version`, `prompt.query-rewriter.version`, etc. | Compare performance across prompt versions |

### LangSmith Integration

Every trace includes prompt version metadata as span attributes on the root `rag_pipeline` span:

```
prompt.chat-system.version = 2
prompt.query-rewriter.version = 1
prompt.ambiguity-check.version = 1
```

This enables:

- Filtering traces by prompt version in LangSmith dashboard
- Comparing latency/quality across prompt versions
- Correlating RAGAS scores with specific prompt iterations
- A/B analysis by activating different versions and comparing metrics

### Example: Create & Activate a New Prompt Version

```bash
# Create v2 with improved instructions
curl -X POST http://localhost:8080/api/v1/prompts/chat-system \
  -H "Content-Type: application/json" \
  -d '{"content":"You are a precise enterprise assistant...", "description":"More concise output"}'

# Activate v2 (deactivates v1 automatically)
curl -X PUT http://localhost:8080/api/v1/prompts/chat-system/activate/2

# Verify active versions
curl http://localhost:8080/api/v1/prompts/active
# → {"chat-system":2,"query-rewriter":1,"ambiguity-check":1,"domain-router":1}
```

## API Endpoints

| Method | Path                                  | Description                                |
| ------ | ------------------------------------- | ------------------------------------------ |
| `POST` | `/api/v1/chat`                        | Ask a question (synchronous JSON response) |
| `POST` | `/api/v1/chat/stream`                 | Ask a question (SSE streaming response)    |
| `POST` | `/api/v1/chat/clarify`                | Submit clarified question                  |
| `POST` | `/api/v1/documents`                   | Ingest one document (sync)                 |
| `POST` | `/api/v1/documents/bulk`              | Ingest multiple documents (async)          |
| `GET`  | `/api/v1/documents/status/{jobId}`    | Check async ingestion job                  |
| `GET`  | `/api/v1/domains`                     | List available domains                     |
| `GET`  | `/api/v1/prompts/active`              | Get active prompt versions                 |
| `GET`  | `/api/v1/prompts/{name}`              | List all versions of a prompt              |
| `POST` | `/api/v1/prompts/{name}`              | Create new prompt version (inactive)       |
| `PUT`  | `/api/v1/prompts/{name}/activate/{v}` | Activate a specific version                |
| `POST` | `/api/v1/prompts/refresh`             | Hot-reload prompts from DB                 |
| `GET`  | `/actuator/health`                    | Integration health summary                 |

### Chat Request

```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "question": "What are HR roles and responsibilities?"
}
```

### Chat Response

```json
{
  "answer": "HR is responsible for recruitment, onboarding, payroll and compliance...",
  "confidence": "HIGH",
  "sources": ["HR-Roles-and-Responsibilities-PDF.pdf"],
  "routedDomain": "HR",
  "domainFallback": false,
  "status": "COMPLETED",
  "clarificationOptions": []
}
```

## Setup

### 1) Prerequisites

- Java 21+
- Docker (for PostgreSQL + pgvector, Redis Stack)

### 2) Infrastructure

```bash
cd docker
docker compose up -d
```

This starts:

- PostgreSQL 16 + pgvector (port 5434, db=ragbase_db)
- Redis Stack (port 6379 + RedisInsight 8001)
- pgAdmin (port 5051)

### 3) Environment

Create `.env` in the project root:

```env
SPRING_PROFILES_ACTIVE=dev

DB_URL=jdbc:postgresql://localhost:5434/ragbase_db
DB_USER=ragbase_admin
DB_PASSWORD=ragbase_secret_2024

GROQ_API_KEY=gsk_...
GROQ_CHAT_MODEL=llama-3.3-70b-versatile

OPENAI_API_KEY=sk-placeholder

PINECONE_API_KEY=pcsk_...
PINECONE_INDEX_NAME=knowledge-base

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_USER=default
REDIS_PASSWORD=
REDIS_SSL=false

API_KEY=
RATE_LIMIT_RPM=30
```

### 4) Run

```bash
./mvnw spring-boot:run
```

App starts on `http://localhost:8080`.

### 5) RAGAS Evaluation Service (optional)

The RAGAS service evaluates answer quality asynchronously using the `faithfulness` metric.

```bash
cd python-services/ragas-service
pip install -r requirements.txt
GROQ_API_KEY=gsk_... python -m uvicorn main:app --host 0.0.0.0 --port 8100
```

Enable in the app:

```env
RAGAS_ENABLED=true
RAGAS_SERVICE_URL=http://localhost:8100
```

**How it works:**

1. After each chat response is returned, a `CompletableFuture` sends the question + answer + contexts to the Python service
2. The Python service uses RAGAS 0.2.10 with `faithfulness` metric (LLM-based, no embeddings needed)
3. The LLM backend is Groq via `langchain-openai` with `ChatOpenAI(openai_api_base="https://api.groq.com/openai/v1")`
4. Results are written back to the `query_logs.ragas_faithfulness` column asynchronously
5. Scores range from 0.0 (no faithfulness) to 1.0 (perfectly grounded in context)

### 6) LangSmith Tracing (optional)

Traces are exported via OpenTelemetry OTLP HTTP to LangSmith:

```env
LANGSMITH_API_KEY=lsv2_...
LANGSMITH_PROJECT=rag-system
```

Each trace shows the full pipeline with input/output on every span. LLM spans include `gen_ai.*` attributes (model, tokens, prompt/completion content).

## Memory Strategy

Token-based conversation memory with incremental summarization:

1. **Recent window** — Walk messages newest→oldest, include verbatim up to `MEMORY_RECENT_TOKENS` (default 1500, ~8-10 turns).
2. **Overflow summary** — If older messages exist beyond the recent window, they are always compressed via incremental LLM summarization (bounded to `MEMORY_SUMMARY_MAX` tokens, default 400).
3. **Incremental updates** — On each turn, only NEW overflow messages are sent to the LLM. The existing summary is extended, not regenerated. Zero LLM cost when no new overflow occurs.
4. **Cold start** — After restart, summary regenerated from scratch on first request that needs it (full history in PostgreSQL).

## Domain Routing

Two-phase classifier — no LLM call:

1. **Keyword match** (instant): If keywords produce a clear winner (2+ hits with 2× lead), return immediately.
2. **Embedding similarity** (local ONNX, ~5-10 ms): Question embedded and compared against pre-computed domain prototype embeddings via cosine similarity. Prototype phrases capture each domain's semantic space.

The detected domain is used consistently across Redis cache lookup, Pinecone retrieval, and PostgreSQL vector/FTS queries.

## Build and Test

```bash
./mvnw test
./mvnw package -DskipTests
```

## Deployment

### Docker

```bash
./mvnw package -DskipTests
docker build -t ragbase .
docker run -p 8080:8080 --env-file .env ragbase
```

### Jar

```bash
./mvnw package -DskipTests
java -jar target/*.jar
```
