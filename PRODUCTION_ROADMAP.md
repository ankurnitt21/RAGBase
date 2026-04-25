# Production Roadmap

> What needs to be implemented to make this application production-ready for N concurrent users, dynamic domains, and role-based access.

## Current State (Demo)

| Component     | Implementation                          | Limitation                                          |
| ------------- | --------------------------------------- | --------------------------------------------------- |
| Auth          | None                                    | Anyone can access everything                        |
| Users/Roles   | None                                    | No user identity                                    |
| Domains       | Hardcoded enum (HR, PRODUCT, AI)        | Can't add new domains without code change           |
| Ingestion     | `@Async` in-memory threads              | No retry, no persistence, no status tracking        |
| File Storage  | Multipart → parse immediately → discard | Original PDF not saved anywhere                     |
| Vector DB     | Pinecone with in-memory fallback        | Fallback loses data on restart                      |
| LLM           | Single free model, no fallback chain    | Rate-limited, no cost tracking                      |
| Memory        | CSV file                                | Race conditions, no user isolation, grows forever   |
| Deployment    | `mvnw spring-boot:run`                  | Single instance, no scaling                         |
| Observability | `log.info()`                            | No metrics, no alerts, no tracing                   |
| Rate Limiting | None                                    | One user can exhaust LLM quota                      |
| Caching       | None                                    | Same question re-embeds and re-calls LLM every time |

---

## Phase 1 — Security & Persistence (Priority: Critical)

### 1.1 JWT Authentication (Spring Security + Keycloak/Auth0)

- [ ] Add `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`
- [ ] Configure JWT decoder pointing to Keycloak/Auth0 issuer
- [ ] Extract `userId`, `roles`, `orgId` from JWT claims
- [ ] Protect all `/api/*` endpoints — require valid Bearer token
- [ ] Allow `/`, `/index.html`, `/swagger-ui/**` without auth

### 1.2 Role-Based Domain Access (RBAC)

- [ ] Create `Role` enum or DB table: `EMPLOYEE`, `HR_MANAGER`, `PRODUCT_TEAM`, `ADMIN`
- [ ] Create `role_domain_access` mapping table (which role can query/upload to which domain)
- [ ] Add access check in `ChatService`: if routed domain not in user's allowed domains → 403
- [ ] Add access check in `IngestionService`: if upload domain not allowed → 403
- [ ] Return `accessDenied` field in response instead of silently filtering

### 1.3 PostgreSQL Database

- [ ] Add `spring-boot-starter-data-jpa` + PostgreSQL driver
- [ ] Replace CSV memory with `conversations` + `messages` tables
- [ ] Create `documents` table (id, filename, domain, uploadedBy, status, chunkCount, s3Key, error, timestamps)
- [ ] Create `domains` table (dynamic — admin can add new domains without code change)
- [ ] Create `role_domain_access` table
- [ ] Create `audit_log` table (who queried what, who uploaded what, access denials)
- [ ] Add Flyway/Liquibase for schema migrations

```sql
-- Core tables
CREATE TABLE domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE role_domain_access (
    role_name VARCHAR(50) NOT NULL,
    domain_id UUID REFERENCES domains(id),
    access_level VARCHAR(20) DEFAULT 'read',  -- read, read_write, admin
    PRIMARY KEY (role_name, domain_id)
);

CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID REFERENCES conversations(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    routed_domain VARCHAR(50),
    confidence VARCHAR(10),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename VARCHAR(255) NOT NULL,
    domain_id UUID REFERENCES domains(id),
    uploaded_by VARCHAR(100) NOT NULL,
    s3_key VARCHAR(500),
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, PROCESSING, COMPLETED, FAILED
    chunk_count INT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100),
    action VARCHAR(50) NOT NULL,
    domain VARCHAR(50),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);
```

---

## Phase 2 — Reliable Ingestion Pipeline (Priority: High)

### 2.1 RabbitMQ Job Queue

- [ ] Add `spring-boot-starter-amqp` (RabbitMQ)
- [ ] Create exchange `document-ingestion` with queue `pdf-ingestion`
- [ ] Upload API: save file to S3 → insert doc record (PENDING) → publish message → return 202
- [ ] Worker consumer: consume message → download from S3 → parse → chunk → embed → upsert → update doc status
- [ ] Dead letter queue for failed messages (after 3 retries)
- [ ] `GET /api/documents/{id}/status` endpoint for polling

### 2.2 S3/MinIO File Storage

- [ ] Add AWS S3 SDK or MinIO client
- [ ] Upload original PDF to S3 on ingestion (preserves source of truth)
- [ ] Store S3 key in `documents` table
- [ ] Worker downloads from S3 to process (decoupled from upload)

### 2.3 Document Status Tracking

- [ ] `GET /api/documents` — list user's documents with status
- [ ] `GET /api/documents/{id}` — single document status + chunk count
- [ ] `DELETE /api/documents/{id}` — remove document + its vectors from Pinecone
- [ ] WebSocket or SSE for real-time status updates in UI

---

## Phase 3 — Performance & Caching (Priority: Medium)

### 3.1 Redis Cache

- [ ] Add `spring-boot-starter-data-redis`
- [ ] Cache embedding vectors: `emb:sha256(text) → float[384]` (TTL 1h)
- [ ] Cache LLM responses: `llm:sha256(prompt) → answer` (TTL 30min)
- [ ] Cache domain routing: `route:sha256(question) → domain` (TTL 5min)

### 3.2 Rate Limiting

- [ ] Token bucket per user in Redis: `rate:userId → {tokens, lastRefill}`
- [ ] Chat: 20 requests/minute
- [ ] Upload: 10 files/hour
- [ ] Return 429 with `Retry-After` header

### 3.3 LLM Resilience

- [ ] Add Resilience4j circuit breaker on LLM calls
- [ ] Fallback chain: primary model → secondary model → graceful error
- [ ] Token counting before sending (reject prompts > 8K tokens)
- [ ] Cost tracking per user per day (log input/output tokens)

---

## Phase 4 — Deployment & Observability (Priority: Medium)

### 4.1 Docker

- [ ] Multi-stage Dockerfile (build + runtime)
- [ ] `docker-compose.yml` with app + PostgreSQL + RabbitMQ + Redis + MinIO
- [ ] Health check endpoint: `GET /actuator/health`

### 4.2 Kubernetes

- [ ] Deployment manifests: API (3 replicas) + Worker (2 replicas)
- [ ] HPA: scale API on CPU > 70%, scale Worker on queue depth > 10
- [ ] ConfigMap for non-sensitive config, Secret for API keys
- [ ] Ingress with TLS termination

### 4.3 Observability

- [ ] Add `micrometer-registry-prometheus`
- [ ] Custom metrics: `chat_requests_total`, `llm_latency_seconds`, `ingestion_duration_seconds`
- [ ] Structured JSON logs (for ELK/Loki)
- [ ] Distributed tracing with Jaeger/Zipkin
- [ ] Alerts: LLM error rate > 10%, queue depth > 100, P95 latency > 5s

---

## Phase 5 — Enterprise Features (Priority: Low)

### 5.1 Dynamic Domains

- [ ] Admin CRUD API: `POST/PUT/DELETE /api/admin/domains`
- [ ] Domain creation auto-provisions Pinecone namespace
- [ ] UI: admin panel to manage domains and role mappings

### 5.2 Multi-Tenancy

- [ ] `orgId` in JWT → Pinecone namespace prefix: `{orgId}-{domain}`
- [ ] Complete data isolation between organizations
- [ ] Tenant-level rate limits and quotas

### 5.3 Advanced RAG

- [ ] Streaming responses via SSE (`/api/chat/stream`)
- [ ] Conversation summary to compress long histories
- [ ] Hybrid search (keyword + vector)
- [ ] Re-ranking with cross-encoder model
- [ ] Prompt injection detection middleware
- [ ] Evaluation pipeline with golden question dataset

### 5.4 Audit & Compliance

- [ ] Immutable audit log (who asked what, who uploaded what, access denials)
- [ ] Data retention policies (auto-delete conversations > 90 days)
- [ ] GDPR: right to deletion — purge user's data across all stores
- [ ] Document versioning (re-ingest updated PDF, keep history)

---

## Implementation Order (Recommended)

```
Phase 1 (Security + DB)     ████████████████████  ← Do first, everything depends on this
Phase 2 (Queue + S3)        ██████████████        ← Reliability
Phase 3 (Cache + Limits)    ████████              ← Performance
Phase 4 (Docker + Metrics)  ████████              ← Operations
Phase 5 (Enterprise)        ████                  ← Nice to have
```

Each phase can be shipped independently. Phase 1 is the most critical — without auth and persistence, nothing else matters.
