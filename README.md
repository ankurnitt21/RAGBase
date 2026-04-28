# RAGBase

RAGBase is a Spring Boot knowledge assistant that ingests documents and answers questions with citations.
It uses OpenAI for chat/embeddings, Pinecone for vector retrieval, PostgreSQL for persistence + keyword search, and Redis for semantic answer caching.

## Highlights

- Domain-aware Q&A (`HR`, `PRODUCT`, `AI`) with fallback behavior
- Hybrid retrieval: Pinecone vector search + PostgreSQL full-text search
- Grounding confidence (`HIGH`, `MEDIUM`, `LOW`) from a post-answer verification step
- Conversation memory with token-budget controls
- Semantic cache in Redis to skip repeated full pipelines
- Sync and async document ingestion APIs

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.4.1 |
| AI | LangChain4j 0.36.2, OpenAI (`gpt-4o-mini`, `text-embedding-3-small`) |
| Vector DB | Pinecone (cosine, namespaces per domain) |
| Relational DB | PostgreSQL (JPA + full-text search) |
| Cache | Redis Stack (semantic cache index) |
| Resilience | Resilience4j (rate limiter, retry, circuit breaker) |
| API Docs | Swagger UI (`/swagger-ui.html`) |

## Architecture (Chat Path)

1. Validate request and apply rate limiting.
2. Optional semantic cache lookup (Redis).
3. Ambiguity detection and optional query rewrite.
4. Domain routing (`HR`, `PRODUCT`, `AI`).
5. Build conversation context from PostgreSQL memory.
6. Retrieve supporting chunks from Pinecone + PostgreSQL FTS (RRF merge).
7. Generate answer with citations.
8. Run grounding check and map confidence.
9. Persist conversation messages and update semantic cache.

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/chat` | Ask a question |
| `POST` | `/api/v1/documents` | Ingest one document (sync) |
| `POST` | `/api/v1/documents/bulk` | Ingest multiple documents (async) |
| `GET` | `/api/v1/documents/status/{jobId}` | Check async ingestion job |
| `GET` | `/api/v1/domains` | List available domains |
| `GET` | `/actuator/health` | Integration health summary |

### Chat Request

```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "question": "What are HR roles and responsibilities?"
}
```

`conversationId` must be a UUID when provided.

### Chat Response

```json
{
  "answer": "HR is responsible for recruitment, onboarding, payroll and compliance...",
  "confidence": "HIGH",
  "sources": ["HR-Roles-and-Responsibilities-PDF.pdf"],
  "routedDomain": "HR",
  "domainFallback": false
}
```

`domainFallback = true` means no strong domain match was found and default routing was used.

## Setup

### 1) Prerequisites

- Java 21
- PostgreSQL
- Pinecone index (`1536` dimensions, metric `cosine`)
- Redis Stack / Redis Cloud with RediSearch support

### 2) Environment

Create `.env` in the project root:

```env
SPRING_PROFILES_ACTIVE=dev

DB_USER=postgres
DB_PASSWORD=your_password

OPENAI_KEY=sk-...
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small

PINECONE_API_KEY=pcsk_...
PINECONE_INDEX_NAME=knowledge-base

REDIS_HOST=...
REDIS_PORT=...
REDIS_USER=default
REDIS_PASSWORD=...
REDIS_SSL=false

API_KEY=
RATE_LIMIT_RPM=30
```

### 3) Run

```bash
./mvnw spring-boot:run
```

App starts on `http://localhost:8080`.

## Prompt Management

Prompt files are loaded from resources:

- `src/main/resources/prompts/` for default/prod
- `src/main/resources/prompts-dev/` for `dev` profile

Key prompt files:

- `chat-system.st`
- `chat-user.st`
- `domain-router.st`
- `query-rewriter.st`

## Health and Observability

`/actuator/health` includes:

- `db` (PostgreSQL connectivity)
- `openai` (embedding API check)
- `pinecone` (backend/store status)

Request/response timing is logged by filters, and chat pipeline events include routing, retrieval, grounding, and cache hit/miss signals.

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
