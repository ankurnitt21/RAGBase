# Enterprise Knowledge Assistant

A **Spring Boot RAG (Retrieval-Augmented Generation)** application that ingests documents into domain-specific Pinecone namespaces, auto-routes questions to the right domain via LLM, and answers using only your documents — with a built-in chat UI.

Built with **LangChain4j 0.36.2** for AI orchestration.

### Key Features

- **Domain-based namespaces** — documents tagged with HR / Product / AI are stored in separate Pinecone namespaces for precise retrieval
- **LLM-powered domain routing** — questions are auto-classified to the correct domain before vector search
- **Relevance score filtering** — only chunks with cosine similarity ≥ 0.7 are used (eliminates hallucination on unrelated queries)
- **Proper message roles** — `SystemMessage` + `UserMessage` separation via LangChain4j (not concatenated strings)
- **Resilient startup** — if Pinecone is unreachable, falls back to in-memory stores so the app always starts
- **Async bulk ingestion** — upload multiple PDFs at once; processing runs in background via `@Async`
- **Scanned PDF detection** — ingestion rejects image-only PDFs with a clear error message
- **Built-in chat UI** — conversation management, domain badges, confidence scores, drag-and-drop upload

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│         Client (Chat UI / Swagger UI / curl)                │
│         http://localhost:8080                                │
└───────────────┬─────────────────────────┬───────────────────┘
                │ POST /api/chat           │ POST /api/documents{/bulk}
                ▼                          ▼
┌───────────────────────────┐  ┌──────────────────────────────┐
│       ChatService         │  │      IngestionService         │
│  (RAG orchestrator)       │  │  (document pipeline)          │
│                           │  │                               │
│  1. DomainRouter → LLM   │  │  1. Receive file + domain     │
│     classifies question   │  │  2. Detect scanned PDF        │
│  2. Read CSV memory       │  │  3. Split into chunks         │
│  3. Embed question (ONNX) │  │  4. Embed each chunk (ONNX)  │
│  4. Search domain's       │  │  5. Store in Pinecone         │
│     Pinecone namespace    │  │     namespace (hr/product/ai) │
│  5. Filter score ≥ 0.7    │  │  6. @Async for bulk uploads   │
│  6. SystemMessage + User  │  └──────────────┬───────────────┘
│     Message → LLM         │                 │
│  7. Write CSV memory      │          ONNX (local)
│  8. Return response       │       all-MiniLM-L6-v2
└─────────┬────────┬────────┘         384 dimensions
          │        │
   ┌──────┘        └──────┐
   ▼                      ▼
┌──────────┐  ┌─────────────────────────┐
│ CSV file │  │       Pinecone          │
│ (memory) │  │  ┌─────┬───────┬────┐   │
└──────────┘  │  │ hr  │product│ ai │   │
              │  └─────┴───────┴────┘   │
              │   (separate namespaces)  │
              └────────────┬────────────┘
                           │
                    ┌──────┘
                    ▼
             ┌────────────┐
             │ OpenRouter │
             │    LLM     │
             └────────────┘
```

---

## Tech Stack

| Layer               | Technology                                                    |
| ------------------- | ------------------------------------------------------------- |
| Backend             | Java 21, Spring Boot 3.4.1                                    |
| AI Framework        | **LangChain4j 0.36.2**                                        |
| LLM                 | OpenRouter (free tier — `google/gemma-4-26b-a4b-it:free`)     |
| Embeddings          | Local ONNX — `all-MiniLM-L6-v2` (384-dim, free, no API key)   |
| Vector DB           | Pinecone (index dimension = 384, cosine metric, 3 namespaces) |
| Domain Routing      | LLM-based question classification → HR / PRODUCT / AI         |
| Conversation Memory | CSV file (`data/chat_messages.csv`)                           |
| Chat UI             | Built-in HTML/CSS/JS at `http://localhost:8080`               |
| API Docs            | Springdoc OpenAPI 2.8 (Swagger UI)                            |
| Build               | Maven Wrapper (mvnw)                                          |

> **No database required.** Conversation memory is stored in a local CSV file that is auto-created on startup.

---

## Project Structure

```
ai-assistant/
├── .env                                         ← API keys (not committed to git)
├── pom.xml
├── mvnw / mvnw.cmd                              ← Maven wrapper (no global Maven needed)
├── data/
│   └── chat_messages.csv                        ← Auto-created; conversation memory
└── src/
    ├── main/
    │   ├── java/com/enterprise/aiassistant/
    │   │   ├── AiAssistantApplication.java      ← Entry point
    │   │   ├── config/AiConfig.java             ← LangChain4j beans + per-domain EmbeddingStores + @EnableAsync
    │   │   ├── controller/AssistantController.java ← REST endpoints (chat, ingest, bulk, domains)
    │   │   ├── dto/
    │   │   │   ├── ChatRequest.java             ← { conversationId?, question }
    │   │   │   ├── ChatResponse.java            ← { answer, confidence, sources[], routedDomain }
    │   │   │   ├── Domain.java                  ← Enum: HR, PRODUCT, AI
    │   │   │   └── IngestionResponse.java       ← { filename, domain, status, message }
    │   │   ├── entity/ChatMessage.java          ← Plain POJO (no JPA)
    │   │   └── service/
    │   │       ├── ChatService.java             ← RAG pipeline + domain-aware search
    │   │       ├── DomainRouterService.java     ← LLM-based question → domain classifier
    │   │       ├── IngestionService.java        ← Sync + @Async ingestion into domain namespaces
    │   │       └── MemoryService.java           ← CSV read/write, sliding window
    │   └── resources/
    │       ├── application.yml                  ← All configuration
    │       └── static/
    │           └── index.html                   ← Built-in chat UI with domain picker
    └── test/
        └── java/com/enterprise/aiassistant/
            └── AiAssistantApplicationTests.java
```

---

## Prerequisites

- **Java 21+** (tested with Java 26)
- **Pinecone** account with an index created:
  - Dimension: **384**
  - Metric: **cosine**
  - Index name: `knowledge-base` (or set via env var)
- **OpenRouter** account for the LLM API key (free tier available at [openrouter.ai](https://openrouter.ai))

> Maven is **not** required globally — the included `mvnw` / `mvnw.cmd` wrapper handles it automatically.

---

## Setup

### 1. Create Pinecone index

- Go to [Pinecone Console](https://app.pinecone.io/)
- Create an index named `knowledge-base` with **dimension = 384** and **cosine** metric

### 2. Create `.env` file

Create a `.env` file in the project root with:

```env
OPENROUTER_API_KEY=sk-or-v1-your-key-here
OPENROUTER_AI_MODEL=google/gemma-4-26b-a4b-it:free
PINECONE_API_KEY=your-pinecone-api-key
PINECONE_INDEX_NAME=knowledge-base
```

#### Choosing a free OpenRouter model

Free models on OpenRouter have upstream rate limits. If you get a 429 error, switch to another free model. To list currently available free models:

```powershell
Invoke-RestMethod -Uri "https://openrouter.ai/api/v1/models" `
  -Headers @{"Authorization"="Bearer $env:OPENROUTER_API_KEY"} |
  Select-Object -ExpandProperty data |
  Where-Object { $_.id -like "*:free" } |
  Select-Object id
```

Then update `OPENROUTER_AI_MODEL` in `.env` and restart.

### 3. Run

**Windows (PowerShell):**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
```

**Linux / macOS:**

```bash
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run
```

Server starts on **http://localhost:8080**.

On first startup, the ONNX embedding model (`all-MiniLM-L6-v2`) is downloaded automatically to `~/.djl.ai/` (~80 MB, one-time).

---

## Chat UI

Once the app is running, open:

**http://localhost:8080**

The built-in chat UI provides:

- **Conversation management** — create, switch, and delete conversations (saved in browser localStorage)
- **Chat interface** — send questions and see AI responses with confidence scores, source citations, and **domain badges** showing which namespace was searched
- **Document upload with domain picker** — select HR/Product/AI domain, then drag-and-drop single or multiple files
- **Bulk upload** — multiple files are ingested asynchronously in the background
- **Responsive design** — works on desktop and mobile

No additional setup or npm install required — it's a static HTML page served by Spring Boot.

---

## Swagger UI

The Swagger API docs are still available at:

**http://localhost:8080/swagger-ui.html**

The raw OpenAPI spec is at `/v3/api-docs`.

---

## API Endpoints

### `POST /api/chat` — Ask a question

The system automatically routes your question to the correct domain (HR, PRODUCT, AI) using an LLM classifier.

**Request body:**

```json
{
  "conversationId": "conv-123",
  "question": "What is our return policy?"
}
```

- `conversationId` is optional — if omitted, a UUID is auto-generated
- Reuse the same `conversationId` across requests to maintain conversation history
- **No domain field needed** — the LLM auto-detects which domain to search

**Response:**

```json
{
  "answer": "Customers can return items within 30 days of purchase...",
  "confidence": "HIGH",
  "sources": ["company-handbook.pdf"],
  "routedDomain": "PRODUCT"
}
```

| Field          | Values                                                           |
| -------------- | ---------------------------------------------------------------- |
| `confidence`   | `HIGH` — 3+ docs matched · `MEDIUM` — 1-2 docs · `LOW` — no docs |
| `sources`      | Distinct filenames of documents that contributed to the answer   |
| `routedDomain` | The domain namespace that was searched: `HR`, `PRODUCT`, or `AI` |

---

### `POST /api/documents` — Ingest a single document (sync)

Upload a **PDF** or **plain-text** file into a specific domain namespace. Blocks until ingestion completes.

```bash
# curl
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/hr-policy.pdf" \
  -F "domain=HR"

# PowerShell
curl.exe -X POST http://localhost:8080/api/documents `
  -F "file=@C:\docs\hr-policy.pdf" -F "domain=HR"
```

**Response:**

```json
{
  "filename": "hr-policy.pdf",
  "domain": "HR",
  "status": "COMPLETED",
  "message": "Document ingested successfully into HR namespace"
}
```

---

### `POST /api/documents/bulk` — Ingest multiple documents (async)

Upload multiple files at once. Returns immediately — ingestion runs in the background via `@Async`.

```bash
curl -X POST http://localhost:8080/api/documents/bulk \
  -F "files=@doc1.pdf" \
  -F "files=@doc2.pdf" \
  -F "files=@doc3.txt" \
  -F "domain=PRODUCT"
```

**Response:**

```json
[
  { "filename": "doc1.pdf", "domain": "PRODUCT", "status": "ACCEPTED", "message": "Queued for async ingestion..." },
  { "filename": "doc2.pdf", "domain": "PRODUCT", "status": "ACCEPTED", "message": "Queued for async ingestion..." },
  { "filename": "doc3.txt", "domain": "PRODUCT", "status": "ACCEPTED", "message": "Queued for async ingestion..." }
]
```

---

### `GET /api/domains` — List available domains

Returns the list of supported domain namespaces.

**Response:** `["HR", "PRODUCT", "AI"]`

---

## Domain Namespace System

Documents are organized into **domain namespaces** in Pinecone. Each domain is a separate Pinecone namespace within the same index:

| Domain      | Namespace | Example Documents                               |
| ----------- | --------- | ----------------------------------------------- |
| **HR**      | `hr`      | Employee handbook, leave policy, benefits guide |
| **PRODUCT** | `product` | Product catalog, pricing, return policy, specs  |
| **AI**      | `ai`      | ML papers, LLM docs, RAG tutorials, model specs |

### How domain routing works

1. User asks: _"What is our PTO policy?"_
2. `DomainRouterService` sends the question to the LLM with a classification prompt
3. LLM responds: `HR`
4. `ChatService` searches **only** the `hr` Pinecone namespace
5. Response includes `"routedDomain": "HR"` so the UI shows which domain was used

This avoids searching all namespaces and gives more relevant results.

Supported formats: `.pdf`, `.txt` (and any plain-text file).
Max upload size: **10 MB** (configurable in `application.yml`).

---

## How It Works — Complete Request Flows

### Flow 1 — Document Ingestion (`POST /api/documents`)

```
═══════════════════════════════════════════════════════════════
  POST /api/documents
  Body: multipart file (PDF or .txt) + domain=HR
═══════════════════════════════════════════════════════════════

STEP 1 — Receive file + domain
  AssistantController.ingestDocument(MultipartFile file, Domain domain)
  └── delegates to IngestionService.ingest(file, domain)

STEP 2 — Detect file type
  ├── .pdf  → ApachePdfBoxDocumentParser  (reads all pages)
  └── other → plain text reader

STEP 3 — Split into chunks  [DocumentSplitters.recursive]
  "This is a long document about return policy..."
                        ↓
  Chunk 1: "This is a long document about..."   (300 tokens)
  Chunk 2: "...document about return policy..." (300 tokens, 100 overlap)
  Chunk 3: "...return policy and refunds..."    (300 tokens, 100 overlap)
  ↑ each chunk = one future Pinecone record

STEP 4 — Attach metadata to each chunk
  {
    source:      "policy.pdf",
    ingested_at: "2026-04-24T10:00:00Z",
    domain:      "HR"
  }

STEP 5 — Embed each chunk  [ONNX — all-MiniLM-L6-v2, local]
  Chunk 1 text → ONNX model → [-0.264, 0.099, 0.150, ...]  (384 numbers)
  Chunk 2 text → ONNX model → [-0.155, 0.211, 0.089, ...]  (384 numbers)
  Chunk 3 text → ONNX model → [ 0.033, 0.178, 0.321, ...]  (384 numbers)

STEP 6 — Store in Pinecone  [EmbeddingStoreIngestor]
  Stored in namespace "hr" (matching the domain):
  {
    id:     "<auto-uuid>",
    values: [-0.264, 0.099, ...],           ← 384-dim vector
    metadata: {
      document_content: "chunk text...",   ← returned at query time
      source:           "policy.pdf",
      ingested_at:      "2026-04-24...",
      domain:           "HR"
    }
  }

RESPONSE
  HTTP 200:
  {
    "filename": "policy.pdf",
    "domain": "HR",
    "status": "COMPLETED",
    "message": "Document ingested successfully into HR namespace"
  }
═══════════════════════════════════════════════════════════════
```

---

### Flow 2 — Chat Query (`POST /api/chat`)

```
═══════════════════════════════════════════════════════════════
  POST /api/chat
  Body: { "conversationId": "conv-1", "question": "What is the return policy?" }
═══════════════════════════════════════════════════════════════

STEP 1 — Resolve conversation ID
  conversationId provided? → use it
  conversationId null?     → auto-generate UUID
  (same ID = same conversation thread)

STEP 2 — Route question to domain  [DomainRouterService]
  LLM classifies: "What is the return policy?" → PRODUCT
  Now we know to search only the "product" namespace

STEP 3 — Load conversation history  [MemoryService]
  Read data/chat_messages.csv
  Filter rows where conversation_id = "conv-1"
  Take last 10 rows (sliding window)
  Result:
    user:      "What is the return policy?"
    assistant: "30 days, original packaging..."
    user:      "How many days for refund?"
    ...up to 10 messages

STEP 4 — Embed the question  [ONNX — all-MiniLM-L6-v2, local]
  "What is the return policy?"
                  ↓
  [0.11, -0.43, 0.85, ...]   (384 numbers)

STEP 5 — Semantic search in Pinecone  [EmbeddingStore.search()]
  Namespace: "product" (from domain routing)
  Query: vector [0.11, -0.43, 0.85, ...]
  TopK:  5
  ↓
  Pinecone searches ONLY the "product" namespace (cosine similarity)
  Returns top 5 closest matches with their metadata:
    Chunk A (score 0.94): "Return policy: 30 days..."
    Chunk B (score 0.81): "Items must be in original packaging..."
    Chunk C (score 0.76): "Refunds processed in 5 business days..."
    ...

STEP 5 — Build prompt  [ChatService.buildHistory() + buildContext()]

  History block (from STEP 2):
    "user: What is the return policy?
     assistant: 30 days..."

  Context block (from STEP 4):
    "[1] Return policy: 30 days...
     [2] Items must be in original packaging...
     [3] Refunds processed in 5 business days..."

  Full prompt sent to LLM:
  ┌─────────────────────────────────────────────────────────┐
  │ SYSTEM: Answer only using provided context.             │
  │         If unsure, say 'I don't have enough info.'      │
  │                                                         │
  │ CONVERSATION HISTORY:                                   │
  │ user: What is the return policy?                        │
  │ assistant: 30 days...                                   │
  │                                                         │
  │ RETRIEVED CONTEXT:                                      │
  │ [1] Return policy: 30 days...                           │
  │ [2] Items must be in original packaging...              │
  │ [3] Refunds processed in 5 business days...             │
  │                                                         │
  │ USER QUESTION:                                          │
  │ What is the return policy?                              │
  └─────────────────────────────────────────────────────────┘

STEP 7 — Call LLM  [ChatLanguageModel → OpenRouter]
  Model: google/gemma-4-26b-a4b-it:free
  LLM reads the full prompt above and generates:
  "Customers can return items within 30 days of purchase,
   provided they are in original packaging. Refunds are
   processed within 5 business days."
  (On 429 rate limit → graceful fallback message returned)

STEP 8 — Save to conversation memory  [MemoryService.saveMessage() × 2]
  Append to data/chat_messages.csv:
    conv-1, user,      "What is the return policy?", 2026-04-24T10:00:00
    conv-1, assistant, "Customers can return...",    2026-04-24T10:00:01

STEP 9 — Compute confidence score
  0 chunks returned  → LOW
  1–2 chunks         → MEDIUM
  3+ chunks          → HIGH

STEP 10 — Build sources list
  Collect distinct "source" metadata from returned chunks:
  ["policy.pdf"]

RESPONSE
  HTTP 200:
  {
    "answer":       "Customers can return items within 30 days...",
    "confidence":   "HIGH",
    "sources":      ["policy.pdf"],
    "routedDomain": "PRODUCT"
  }
═══════════════════════════════════════════════════════════════
```

---

## Conversation Memory (CSV)

Conversation history is stored in `data/chat_messages.csv`, auto-created on first run.

**Format:**

```
conversation_id,role,content,created_at
conv-123,user,"What is the return policy?",2026-04-24T10:00:00
conv-123,assistant,"Items can be returned within 30 days...",2026-04-24T10:00:01
```

- **Sliding window:** only the last **10 messages** per conversation are included in the prompt
- The file grows indefinitely; old entries are preserved but not sent to the LLM
- To reset a conversation, simply use a new `conversationId`

---

## Key Design Decisions

| Decision                           | Rationale                                                                      |
| ---------------------------------- | ------------------------------------------------------------------------------ |
| Domain namespaces in Pinecone      | Isolates HR/Product/AI docs; faster, more relevant searches                    |
| LLM-based domain routing           | Auto-classifies questions — no manual domain selection needed                  |
| Score threshold ≥ 0.7              | Filters out low-relevance chunks; prevents hallucination on unrelated queries  |
| SystemMessage + UserMessage roles  | Proper API-level role separation — LLM follows system instructions reliably    |
| Resilient Pinecone startup         | Falls back to in-memory if Pinecone is unreachable; app always starts          |
| Scanned PDF detection              | Rejects image-only PDFs early with clear error instead of ingesting empty text |
| Async bulk ingestion (`@Async`)    | Multiple PDFs processed in parallel without blocking the API                   |
| Local ONNX embeddings              | Free, no API key, no rate limits, 384-dim `all-MiniLM-L6-v2`                   |
| CSV for memory                     | Zero dependencies — no database setup required                                 |
| OpenRouter free tier               | Access to multiple LLMs without an OpenAI subscription                         |
| Sliding window (10 messages)       | Keeps prompt tokens bounded                                                    |
| Top-K = 5 for RAG                  | Balance between answer coverage and noise                                      |
| Chunk size 300 tokens, 100 overlap | Good retrieval granularity for most documents                                  |
| Confidence scoring                 | Transparent signal to the caller about answer reliability                      |

---

## Troubleshooting

| Symptom                                   | Cause                                  | Fix                                                              |
| ----------------------------------------- | -------------------------------------- | ---------------------------------------------------------------- |
| `No endpoints found for <model>:free`     | Model removed from OpenRouter          | Switch `OPENROUTER_AI_MODEL` to another free model               |
| `429 Too Many Requests`                   | Upstream rate limit on free model      | Wait a minute or switch model                                    |
| `AccessDeniedException` on DJL pytorch    | Corrupted ONNX cache                   | `Remove-Item "$env:USERPROFILE\.djl.ai\pytorch" -Recurse -Force` |
| Pinecone dimension mismatch               | Index was created with wrong dimension | Recreate Pinecone index with dimension = **384**                 |
| `400 Bad Request` on `/api/chat` via curl | Shell escaping of JSON on Windows      | Use PowerShell `Invoke-RestMethod` instead of curl               |

---

## Future Enhancements

See [PRODUCTION_ROADMAP.md](PRODUCTION_ROADMAP.md) for the full production-readiness plan.

- [ ] JWT authentication + role-based domain access (RBAC)
- [ ] PostgreSQL for conversations, documents, and audit logs
- [ ] RabbitMQ ingestion queue with retry and status tracking
- [ ] S3/MinIO file storage for original PDFs
- [ ] Redis caching for embeddings and LLM responses
- [ ] Streaming responses via SSE (`/api/chat/stream`)
- [ ] Dynamic domains (admin-managed, stored in DB instead of enum)
- [ ] Multi-tenancy (org-level namespace isolation)
- [ ] Prometheus metrics + Grafana dashboards
- [ ] Docker + Kubernetes deployment
- [x] ~~Simple React / Angular UI~~ → Built-in HTML/JS chat UI included
- [x] ~~Domain-based namespace routing~~ → Implemented
- [x] ~~Score-based relevance filtering~~ → ≥ 0.7 threshold
- [x] ~~Resilient Pinecone startup~~ → In-memory fallback
- [x] ~~Scanned PDF detection~~ → Early rejection with error message

---

## Concepts Deep Dive

### What is RAG?

Instead of asking an LLM "what do you know about X" (which can hallucinate), RAG:

1. Stores your own documents as vectors
2. Finds the most relevant pieces at query time
3. Hands those pieces to the LLM: "answer using only this"

```
Without RAG:  User → LLM → answer (from training data, may hallucinate)
With RAG:     User → search your docs → LLM + your docs → grounded answer
```

---

### Chunking + Overlap

A 50-page PDF is too large to send to the LLM. It gets split into chunks:

```
Original text:
"...sentence A. sentence B. sentence C. sentence D. sentence E..."

Chunk 1:  "sentence A. sentence B. sentence C."        (300 tokens)
Chunk 2:  "sentence B. sentence C. sentence D."        (300 tokens, 100 overlap)
Chunk 3:  "sentence C. sentence D. sentence E."        (300 tokens, 100 overlap)
```

**Why overlap?** If an answer spans two chunk boundaries, without overlap you'd miss it. The 100-token overlap ensures no meaning is lost at the seams.

Each chunk becomes one separate record in Pinecone.

---

### Embedding Model (ONNX)

The ONNX model (`all-MiniLM-L6-v2`) converts text into a fixed-size array of numbers:

```
"Return policy: 30 days, original packaging"
                    ↓  ONNX (runs locally)
     [-0.264, 0.099, 0.150, ..., -0.067]   ← 384 numbers
```

These numbers encode the **meaning** of the text mathematically. Similar meaning = similar numbers. The model was trained on millions of sentence pairs to learn this mapping — which is why it's called **semantic search** (not keyword search):

```
"How long do I have to return something?"    →  [0.11, -0.43, 0.85, ...]
"Return policy: 30 days of purchase"         →  [0.12, -0.45, 0.87, ...]
```

Different words, very similar vectors — cosine similarity catches this.

**The same model must be used at ingest time AND query time.** That's why both `IngestionService` and `ChatService` use ONNX, and why the Pinecone index dimension must be 384.

---

### Embedding Dimensions Compared

The "dimension" is just the output size (number of numbers) of an embedding model:

| Model                                  | Dimensions | Cost           | Requires               |
| -------------------------------------- | ---------- | -------------- | ---------------------- |
| `all-MiniLM-L6-v2` (ONNX, what we use) | **384**    | Free           | Nothing — runs locally |
| `text-embedding-3-small` (OpenAI)      | 1536       | Paid           | OpenAI API key         |
| `text-embedding-3-large` (OpenAI)      | 3072       | More expensive | OpenAI API key         |
| `llama-text-embed-v2` (Pinecone)       | 4096       | Paid           | Pinecone Inference API |

More dimensions = more nuance, but diminishing returns for typical document Q&A. 384 is sufficient.

**Hard rule:** ingest and query must use the same model. If you ever switch models, you must recreate the Pinecone index with the new dimension and re-ingest all documents.

---

### What Each Credential Does

Pinecone has two completely separate APIs:

```
Pinecone Inference API  →  text in, vector out  (paid, separate endpoint — we don't use this)
Pinecone Data API       →  vector in, vector out (storage + search — what we use)
```

LangChain4j's `PineconeEmbeddingStore` only uses the **Data API**. So:

| Credential           | Unlocks                                                             |
| -------------------- | ------------------------------------------------------------------- |
| `PINECONE_API_KEY`   | Read/write to your index (Data API only — store and search vectors) |
| `OPENROUTER_API_KEY` | Access to LLMs (text generation)                                    |
| _(no key needed)_    | ONNX embedding — runs locally on your machine                       |

The `llama-text-embed-v2` model shown in your Pinecone console UI is only used when you search via the **Pinecone browser UI** ("Search by text"). It is **not** used by our app at all.

---

### What a Pinecone Record Looks Like

Every stored record has three parts:

```json
{
  "id": "0453679b-956e-421c-abaf-dcc5ae153cc9",
  "values": [-0.264, 0.099, 0.150, ...],
  "metadata": {
    "document_content": "Return policy: 30 days...",
    "source": "policy.txt",
    "ingested_at": "2026-04-24T08:54:43Z",
    "type": "text"
  }
}
```

- **`values`** — the 384-number vector (the "address" in semantic space). Unreadable to humans.
- **`metadata.document_content`** — the original text chunk. Returned at query time and sent to the LLM.
- **`metadata.source`** — filename used for the `sources` field in `ChatResponse`.

---

### Key Numbers Reference

| Setting              | Value              | Why                                               |
| -------------------- | ------------------ | ------------------------------------------------- |
| Embedding dimensions | **384**            | Output size of `all-MiniLM-L6-v2`                 |
| Chunk size           | **300 tokens**     | ~225 words — good retrieval granularity           |
| Chunk overlap        | **100 tokens**     | ~75 words — prevents losing context at boundaries |
| Top-K retrieval      | **5**              | 5 most relevant chunks sent to LLM                |
| Memory window        | **10 messages**    | Keeps prompt size bounded                         |
| Max file upload      | **10 MB**          | Configurable in `application.yml`                 |
| Confidence: HIGH     | 3+ chunks matched  | Answer well-grounded in documents                 |
| Confidence: MEDIUM   | 1–2 chunks matched | Partial grounding                                 |
| Confidence: LOW      | 0 chunks matched   | LLM answering from training data only             |
