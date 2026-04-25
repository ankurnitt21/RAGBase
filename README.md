# RAGBase

A RAG (Retrieval-Augmented Generation) knowledge assistant that lets you upload documents and ask questions about them. Uses LangChain4j to orchestrate the pipeline, OpenAI for embeddings and chat, and Pinecone for vector storage.

Questions are automatically routed to the correct domain (HR / Product / AI) before searching — so you only search relevant documents.

---

## What Problem It Solves

Enterprises have knowledge spread across PDFs and documents that nobody reads. RAGBase lets you upload those documents and ask natural language questions — getting precise, cited answers instead of digging through files manually.

---

## Flow

### Document Ingestion (`POST /api/documents`)

```
User uploads file (PDF / TXT) + selects domain (HR / PRODUCT / AI)
        │
        ▼
AssistantController → IngestionService
        │
        ▼
Parse file
  ├── PDF  → ApachePdfBox extracts text (rejects scanned/image PDFs)
  └── TXT  → read as plain text
        │
        ▼
Split into chunks (300 tokens, 100 token overlap)
  e.g. "...sentence A. sentence B. sentence C..."
       Chunk 1: sentence A, B, C
       Chunk 2: sentence B, C, D   ← overlap preserves context at boundaries
        │
        ▼
Embed each chunk
  OpenAI text-embedding-3-small → 1536-dim vector per chunk
        │
        ▼
Store in Pinecone
  namespace = domain.toLowerCase()  (hr / product / ai)
  each record = { id, vector[1536], metadata: { source, domain, ingested_at } }
```

---

### Chat / Query (`POST /api/chat`)

```
User sends { conversationId, question }
        │
        ▼
1. DOMAIN ROUTING
   DomainRouterService → gpt-4o-mini classifies question
   "What is our PTO policy?" → HR
   Falls back to PRODUCT if classification fails
        │
        ▼
2. LOAD CONVERSATION HISTORY
   PostgreSQL: SELECT last 10 messages WHERE conversation_id = ?
   ORDER BY created_at DESC → reversed to chronological order
        │
        ▼
3. EMBED THE QUESTION
   OpenAI text-embedding-3-small → 1536-dim vector
        │
        ▼
4. VECTOR SEARCH
   Pinecone: search domain namespace (e.g. "hr")
   Top 5 closest chunks by cosine similarity
   Filter: keep only chunks with score ≥ 0.7
        │
        ▼
5. BUILD PROMPT
   SystemMessage : "You are a helpful enterprise knowledge assistant..."
   UserMessage   :
     - Retrieved document excerpts (from step 4)
     - Last 10 conversation messages (from step 2)
     - User question
        │
        ▼
6. CALL LLM
   gpt-4o-mini → generates grounded answer citing source documents
        │
        ▼
7. SAVE TO MEMORY
   PostgreSQL INSERT: user message
   PostgreSQL INSERT: assistant answer
        │
        ▼
8. RETURN RESPONSE
   {
     answer       : "..."
     confidence   : HIGH (3+ chunks) | MEDIUM (1-2) | LOW (0)
     sources      : ["policy.pdf"]
     routedDomain : "HR"
   }
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4.1 |
| AI Framework | LangChain4j 0.36.2 |
| LLM | OpenAI `gpt-4o-mini` |
| Embeddings | OpenAI `text-embedding-3-small` (1536-dim) |
| Vector DB | Pinecone (cosine, 3 namespaces: hr / product / ai) |
| Domain Routing | LLM-based question classifier |
| Conversation Memory | PostgreSQL |
| API Docs | Swagger UI (`/swagger-ui.html`) |
| Build | Maven Wrapper (`mvnw`) |

---

## Setup

### 1. Pinecone Index

Create an index at [app.pinecone.io](https://app.pinecone.io):
- **Dimension:** `1536`
- **Metric:** `cosine`
- **Name:** `knowledge-base`

### 2. PostgreSQL

Create a database named `ai-assisstant` on your local PostgreSQL instance. Hibernate will auto-create the `chat_messages` table on first run.

### 3. Environment Variables

Create a `.env` file in the project root:

```env
OPENAI_KEY=sk-...
PINECONE_API_KEY=pcsk_...
PINECONE_INDEX_NAME=knowledge-base
DB_USER=postgres
DB_PASSWORD=your_password
```

### 4. Run

**Linux / macOS:**
```bash
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run
```

**Windows (PowerShell):**
```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.1\jbr"
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
```

Server starts at **http://localhost:8080**

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/chat` | Ask a question (domain auto-detected) |
| `POST` | `/api/documents` | Upload a single document (sync) |
| `POST` | `/api/documents/bulk` | Upload multiple documents (async) |
| `GET` | `/api/domains` | List available domains |

**Chat request:**
```json
{
  "conversationId": "conv-123",
  "question": "What is our return policy?"
}
```

**Chat response:**
```json
{
  "answer": "Customers can return items within 30 days...",
  "confidence": "HIGH",
  "sources": ["policy.pdf"],
  "routedDomain": "PRODUCT"
}
```

Supported file formats: `.pdf`, `.txt` — max 10 MB.

---

## How to Deploy

### Docker (recommended)

1. Build the JAR:
```bash
./mvnw package -DskipTests
```

2. Create a `Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jre
COPY target/ai-assistant-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

3. Build and run:
```bash
docker build -t ragbase .
docker run -p 8080:8080 --env-file .env ragbase
```

### Manual (any server with Java 21)

```bash
./mvnw package -DskipTests
java -jar target/ai-assistant-0.0.1-SNAPSHOT.jar
```

Pass environment variables via `--env-file`, system environment, or `-Dspring.datasource.password=...` flags.
