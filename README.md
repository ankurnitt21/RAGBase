# RAGBase

A RAG (Retrieval-Augmented Generation) knowledge assistant. Upload documents, ask questions, get cited answers. Uses LangChain4j to orchestrate the pipeline, OpenAI for embeddings and chat, and Pinecone for vector storage.

Questions are automatically routed to the correct domain (HR / PRODUCT / AI) before searching, so only relevant documents are queried.

---

## What It Solves

Enterprises have knowledge spread across PDFs and documents that nobody reads. RAGBase lets you upload those documents and ask natural language questions — getting precise, cited answers instead of digging through files manually.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4.1 |
| AI Framework | LangChain4j 0.36.2 (AiServices, @Tool) |
| LLM | OpenAI `gpt-4o-mini` |
| Embeddings | OpenAI `text-embedding-3-small` (1536-dim) |
| Vector DB | Pinecone (cosine, namespaces: hr / product / ai) |
| Conversation Memory | PostgreSQL |
| API Docs | Swagger UI (`/swagger-ui.html`) |
| Build | Maven Wrapper (`mvnw`) |

---

## Flow

### Document Ingestion (`POST /api/v1/documents`)

```
User uploads file (PDF / TXT) + selects domain
        │
        ▼
Parse file
  ├── PDF  → ApachePdfBox extracts text (rejects scanned/image PDFs)
  └── TXT  → read as plain text
        │
        ▼
Split into chunks (300 tokens, 100 token overlap)
  Overlap preserves context at chunk boundaries
        │
        ▼
Embed each chunk
  OpenAI text-embedding-3-small → 1536-dim vector per chunk
        │
        ▼
Store in Pinecone
  namespace = domain.toLowerCase()  (hr / product / ai)
  metadata  = { source, domain, ingested_at }
```

---

### Chat / Query (`POST /api/v1/chat`)

```
User sends { conversationId, question }
        │
        ▼
1. DOMAIN ROUTING
   gpt-4o-mini classifies the question → HR / PRODUCT / AI
   Falls back to PRODUCT if classification fails
        │
        ▼
2. LOAD CONVERSATION HISTORY
   PostgreSQL: last 10 messages for the conversation
        │
        ▼
3. CALL LLM (agentic)
   ChatAssistant receives the question + history
   System prompt instructs it to call searchKnowledgeBase before answering
        │
        ▼
4. LLM CALLS TOOL (zero or more times)
   searchKnowledgeBase(query, domain)
     ├── Embed query → OpenAI text-embedding-3-small
     ├── Search Pinecone namespace (cosine similarity, top 5)
     ├── Filter: keep chunks with score ≥ 0.7
     └── Return formatted excerpts + source filenames to LLM
   LLM may call this multiple times with refined queries
        │
        ▼
5. LLM GENERATES ANSWER
   Grounded in tool results, cites source documents in the answer text
        │
        ▼
6. SAVE TO MEMORY
   PostgreSQL INSERT: user message + assistant answer
        │
        ▼
7. RETURN RESPONSE
   { answer, confidence: "TOOL_BASED", sources: [], routedDomain }
```

---

## Setup

### 1. Pinecone Index

Create an index at [app.pinecone.io](https://app.pinecone.io):
- **Dimension:** `1536`
- **Metric:** `cosine`
- **Name:** `knowledge-base`

### 2. PostgreSQL

Create a database named `ai-assisstant`. Hibernate auto-creates the `chat_messages` table on first run.

### 3. Environment Variables

Create a `.env` file in the project root (loaded automatically by spring-dotenv):

```env
OPENAI_KEY=sk-...
PINECONE_API_KEY=pcsk_...
PINECONE_INDEX_NAME=knowledge-base
DB_USER=postgres
DB_PASSWORD=your_password
```

`API_KEY` is optional. Set it to enable API key authentication on all endpoints. Leave it unset to disable auth (useful in dev).

### 4. Run

```bash
./mvnw spring-boot:run
```

Server starts at **http://localhost:8080**

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/chat` | Ask a question (domain auto-detected) |
| `POST` | `/api/v1/documents` | Upload a single document (sync) |
| `POST` | `/api/v1/documents/bulk` | Upload multiple documents (async) |
| `GET` | `/api/v1/domains` | List available domains |

Supported file formats: `.pdf`, `.txt` — max 10 MB per file.

**Chat request:**
```json
{
  "conversationId": "conv-123",
  "question": "What is our PTO policy?"
}
```

**Chat response:**
```json
{
  "answer": "Employees are entitled to 20 days of PTO per year... (source: hr-policy.pdf)",
  "confidence": "TOOL_BASED",
  "sources": [],
  "routedDomain": "HR"
}
```

---

## Deploy

### Docker

```bash
./mvnw package -DskipTests

docker build -t ragbase .
docker run -p 8080:8080 --env-file .env ragbase
```

`Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jre
COPY target/ai-assistant-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Manual (any server with Java 21)

```bash
./mvnw package -DskipTests
java -jar target/ai-assistant-0.0.1-SNAPSHOT.jar
```
