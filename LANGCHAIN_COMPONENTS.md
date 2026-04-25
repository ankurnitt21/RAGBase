# LangChain Components — When to Use What

> A decision-focused reference for every LangChain component. For each one: what it is, when to use it, and when NOT to use it.

---

## 1. Models (Model I/O)

These are the primitives that talk to AI models. Every LangChain app uses at least one.

### LLMs (Text-in → Text-out)

| Property | Value |
|---|---|
| **What it is** | Raw text completion models — give it text, get text back |
| **Examples** | OpenAI (text-davinci), Anthropic, Mistral, local Ollama models |
| **When to use** | Legacy apps, simple text generation, models that don't support chat format |
| **When NOT to use** | Almost always prefer Chat Models instead — LLMs are the older API |

### Chat Models (Message-based)

| Property | Value |
|---|---|
| **What it is** | Message-based models with system/user/assistant roles |
| **Examples** | ChatOpenAI, ChatAnthropic, ChatGoogleGenerativeAI, OllamaChatModel |
| **When to use** | **Default choice for everything** — RAG, agents, routing, SQL generation, formatting |
| **When NOT to use** | Only skip if the model doesn't support chat format (rare today) |

**Decision:** Always start with Chat Models. LLMs are legacy.

### Embedding Models

| Property | Value |
|---|---|
| **What it is** | Converts text → vector (list of numbers representing meaning) |
| **Examples** | OpenAIEmbeddings, HuggingFaceEmbeddings, all-MiniLM-L6-v2 (local ONNX) |
| **When to use** | Any time you need semantic search — RAG, Schema RAG, document similarity, clustering |
| **When NOT to use** | If you're only doing direct LLM calls with no retrieval |
| **NOT an LLM** | Embedding models don't generate text — they map text to numbers. Fast (~1ms), deterministic. |

**Decision:**
- Need semantic search? → You need an Embedding Model
- Budget-constrained or offline? → Use local models (all-MiniLM-L6-v2 via ONNX)
- Maximum quality? → Use OpenAI ada-002 or Cohere embed-v3

---

## 2. Prompts

Everything related to structuring inputs before sending to a model.

### PromptTemplate

| Property | Value |
|---|---|
| **What it is** | String template with `{variables}` that get filled at runtime |
| **When to use** | Simple prompts with variable injection — SQL generation, formatting, classification |
| **When NOT to use** | When you need system/user/assistant role separation |

### ChatPromptTemplate

| Property | Value |
|---|---|
| **What it is** | Template that creates a list of messages (SystemMessage, HumanMessage, etc.) |
| **When to use** | **Default choice** — any time you're using a Chat Model (which is almost always) |
| **When NOT to use** | Only skip if using a raw LLM (not chat-based) |

### Few-Shot Prompt Templates

| Property | Value |
|---|---|
| **What it is** | Template that includes example input→output pairs before the actual question |
| **When to use** | When the LLM needs to learn a pattern — SQL generation, classification, formatting |
| **When NOT to use** | Simple factual questions where examples don't help |

### Example Selectors

| Property | Value |
|---|---|
| **What it is** | Dynamically picks the best few-shot examples based on the input |
| **When to use** | When you have 50+ examples but only want to inject the 3 most relevant ones |
| **When NOT to use** | If you have < 10 examples (just include them all) |

**Decision:**
- Chat Model? → ChatPromptTemplate
- Need examples? → Few-Shot Prompt Template
- Too many examples? → Example Selector picks the best ones

---

## 3. Output Parsers

Convert raw LLM text output into structured data.

| Parser | When to Use |
|---|---|
| **StrOutputParser** | Default — when you just want the text string back |
| **PydanticOutputParser** | When you need typed objects (e.g., parse LLM output into a Java/Python class) |
| **JSONOutputParser** | When you need JSON output from the LLM |
| **EnumOutputParser** | When the LLM should return one of a fixed set of values (e.g., DATA / DOCUMENT / BOTH) |
| **RegexParser** | When you need to extract specific patterns from LLM output |

**Decision:**
- Just want text? → StrOutputParser (or nothing — it's the default)
- Need structured data? → PydanticOutputParser or JSONOutputParser
- Need classification? → EnumOutputParser

---

## 4. Chains (Classic Abstraction)

Pre-LCEL orchestration. These are **fixed workflows** — step 1 → step 2 → step 3. Still widely used and well-documented.

### LLMChain

| Property | Value |
|---|---|
| **What it is** | PromptTemplate + LLM combined as one callable unit |
| **When to use** | Any single LLM call with a template — SQL generation, answer formatting, classification |
| **When NOT to use** | When you need multi-step logic (use SequentialChain or LCEL) |

### SequentialChain

| Property | Value |
|---|---|
| **What it is** | Run multiple chains one after another, passing outputs forward |
| **When to use** | Multi-step pipelines where step 2 depends on step 1's output |
| **When NOT to use** | When steps are independent (use RunnableParallel instead) |

### RouterChain

| Property | Value |
|---|---|
| **What it is** | Routes input to different chains based on a classification |
| **When to use** | When you need to send questions down different paths (DATA → SQL chain, DOCUMENT → RAG chain) |
| **When NOT to use** | Modern code should use RunnableBranch instead (see Section 5) |
| **Note** | This is the **classic** way. Still works, but RunnableBranch is the modern replacement. |

### MultiPromptChain

| Property | Value |
|---|---|
| **What it is** | RouterChain that routes to different prompt templates based on input |
| **When to use** | When different question types need fundamentally different prompts |
| **When NOT to use** | If you only have 2-3 paths (RunnableBranch is simpler) |

### RetrievalQA

| Property | Value |
|---|---|
| **What it is** | VectorStoreRetriever + LLMChain orchestrated together — the core RAG chain |
| **When to use** | **Standard RAG** — retrieve relevant documents/chunks, inject into prompt, LLM answers |
| **When NOT to use** | When you need conversation history (use ConversationalRetrievalChain) |

### ConversationalRetrievalChain

| Property | Value |
|---|---|
| **What it is** | RetrievalQA + Memory — maintains conversation history across turns |
| **When to use** | Chat-based RAG where follow-up questions like "tell me more about that" need to work |
| **When NOT to use** | Single-turn Q&A with no conversation context needed |

### Summarization / QA / Map-Reduce Chains

| Chain | When to Use |
|---|---|
| **Summarization Chain** | Summarize long documents (stuff / map-reduce / refine strategies) |
| **QA Chain** | Answer questions from a set of documents |
| **Map-Reduce** | Process many documents in parallel, then combine results |

**Decision Tree for Chains:**

```
Need to call an LLM once with a template?
  → LLMChain

Need to run steps in sequence?
  → SequentialChain

Need to route to different paths?
  → RouterChain (classic) or RunnableBranch (modern)

Need RAG (retrieve + answer)?
  → RetrievalQA (single-turn) or ConversationalRetrievalChain (multi-turn)

Need to process many documents?
  → Map-Reduce chain
```

---

## 5. Runnables (LCEL — Modern Core Abstraction)

**LangChain Expression Language (LCEL)** — the modern way to build pipelines. Everything is a `Runnable` that can be composed with `|` (pipe).

### Why LCEL Over Classic Chains?

| | Classic Chains | LCEL Runnables |
|---|---|---|
| Composability | Fixed structure | Pipe anything together |
| Streaming | Limited | Built-in |
| Parallelism | Manual | RunnableParallel |
| Error handling | Try/catch | RunnableWithFallbacks |
| Debugging | Harder | LangSmith integration |

### Runnable Types — When to Use Each

#### RunnableLambda

| Property | Value |
|---|---|
| **What it is** | Wraps any Python/Java function as a Runnable so it fits in a pipeline |
| **When to use** | Custom logic between LLM calls — data transformation, validation, formatting |
| **When NOT to use** | If a built-in Runnable already does what you need |

#### RunnablePassthrough

| Property | Value |
|---|---|
| **What it is** | Passes input through unchanged (or adds extra fields) |
| **When to use** | When you need the original input available later in the pipeline alongside processed data |
| **When NOT to use** | If you don't need the original input downstream |

#### RunnableParallel

| Property | Value |
|---|---|
| **What it is** | Runs multiple runnables at the same time on the same input |
| **When to use** | Independent operations — e.g., embed a question AND look up metadata simultaneously |
| **When NOT to use** | When step 2 depends on step 1's output (that's sequential, not parallel) |

#### RunnableBranch (Router — Modern Way)

| Property | Value |
|---|---|
| **What it is** | Routes input to different runnables based on conditions — the modern replacement for RouterChain |
| **When to use** | **Any routing decision** — DATA → SQL chain, DOCUMENT → RAG chain, BOTH → both chains |
| **When NOT to use** | If there's only one path (no branching needed) |

```
How RunnableBranch works:

Input
 ├─ condition_1 is true? → Run chain_1
 ├─ condition_2 is true? → Run chain_2
 └─ else               → Run default_chain

Example:
 ├─ intent == "DATA"     → Text-to-SQL chain
 ├─ intent == "DOCUMENT" → RAG chain
 └─ intent == "BOTH"     → Both chains merged
```

#### RunnableRetry

| Property | Value |
|---|---|
| **What it is** | Automatically retries a runnable on failure |
| **When to use** | Unreliable steps — LLM API calls that might timeout, SQL generation that might have syntax errors |
| **When NOT to use** | Steps that will always fail for the same input (retry won't help) |

#### RunnableWithFallbacks

| Property | Value |
|---|---|
| **What it is** | If the primary runnable fails, try a fallback runnable |
| **When to use** | Graceful degradation — primary LLM fails → try cheaper model. SQL fails → fall back to RAG. |
| **When NOT to use** | If there's no meaningful fallback |

**Decision Tree for Runnables:**

```
Need custom logic in the pipeline?
  → RunnableLambda

Need original input preserved?
  → RunnablePassthrough

Need to run things in parallel?
  → RunnableParallel

Need to route to different paths?
  → RunnableBranch  ← This is the modern Router

Need auto-retry?
  → RunnableRetry

Need fallback on failure?
  → RunnableWithFallbacks
```

---

## 6. Agents

LLM systems that **decide what to do next** — unlike chains/runnables which follow a fixed path.

### When to Use Agents vs Chains/Runnables

| Use Case | Use Chains/Runnables | Use Agents |
|---|---|---|
| Fixed workflow (always the same steps) | ✅ | ❌ |
| LLM decides which tool to call | ❌ | ✅ |
| Predictable, testable pipeline | ✅ | ❌ |
| Open-ended questions needing multiple tools | ❌ | ✅ |
| Production system with SLA | ✅ (predictable latency) | ⚠️ (unpredictable loops) |

### Agent Components

| Component | What It Is |
|---|---|
| **Agent** | The LLM-based planner that decides what to do next |
| **AgentExecutor** | The loop that runs the agent → calls tools → feeds results back → repeats until done |
| **Tools** | Functions the agent can call (search, database, API, calculator, etc.) |
| **Memory** | Optional conversation history to maintain context across turns |

### Agent Types — When to Use Each

| Agent Type | When to Use |
|---|---|
| **ReAct Agent** | General purpose — thinks step by step (Reason → Act → Observe → repeat) |
| **OpenAI Functions Agent** | When using OpenAI models with function calling — most reliable for tool use |
| **Tool-Calling Agent** | Generic version of Functions Agent — works with any model that supports tool calling |
| **Structured Chat Agent** | When tools need complex structured inputs (not just simple strings) |

**Decision:** If your workflow is predictable (PATH A or PATH B), use Chains/Runnables. If the LLM needs to dynamically choose between many tools, use Agents.

---

## 7. Tools

Functions that agents can call. **Only used with agents** — chains/runnables call functions directly.

| Tool Type | When to Use |
|---|---|
| **Search Tool** | Agent needs to search the web or a knowledge base |
| **Database Tool** | Agent needs to query a database |
| **API Tool** | Agent needs to call an external API |
| **Python/Code Tool** | Agent needs to run calculations or code |
| **Custom Tool** | Wrap any function as a tool for agent use |

**Decision:** If you're using an Agent, define Tools. If you're using Chains/Runnables, call functions directly — no Tool wrapper needed.

---

## 8. Memory

How conversation state is persisted between calls.

| Memory Type | When to Use | Trade-off |
|---|---|---|
| **ConversationBufferMemory** | Short conversations (< 20 turns) | Stores everything — grows linearly, hits token limit |
| **ConversationWindowMemory** | Medium conversations | Keeps last N turns only — loses old context |
| **ConversationSummaryMemory** | Long conversations | LLM summarizes old turns — preserves context, costs extra LLM call |
| **VectorStoreRetrieverMemory** | Very long conversations or when specific past messages matter | Stores messages as vectors, retrieves relevant ones — most scalable |
| **CombinedMemory** | Need both short-term (buffer) and long-term (summary) | Combines two memory types |

**Decision Tree:**

```
Single-turn Q&A (no conversation)?
  → No memory needed

Short conversation (< 20 messages)?
  → ConversationBufferMemory

Long conversation?
  → ConversationSummaryMemory (cheap) or VectorStoreRetrieverMemory (precise)

Need both recent + old context?
  → CombinedMemory
```

---

## 9. Document Loaders

Ingest raw data from various sources. Used in the ingestion pipeline (PATH B Step A).

| Loader | When to Use |
|---|---|
| **PDFLoader** | PDF files — BI reports, SOPs, contracts |
| **CSVLoader** | CSV/Excel data |
| **TextLoader** | Plain text files |
| **WebBaseLoader** | Scrape web pages |
| **DirectoryLoader** | Load all files from a folder |
| **Notion / Confluence / Google Docs** | Load from SaaS platforms |
| **Custom Loader** | Any unsupported format |

**Decision:** Match the loader to your file format. Use DirectoryLoader for bulk ingestion.

---

## 10. Text Splitters

Break documents into chunks for embedding. Critical for RAG quality.

| Splitter | When to Use |
|---|---|
| **RecursiveCharacterTextSplitter** | **Default choice** — tries paragraphs, then sentences, then characters. Works for most text. |
| **TokenTextSplitter** | When you need precise token-count chunks (important for LLM context limits) |
| **MarkdownTextSplitter** | Markdown documents — splits on headers, preserves structure |
| **CodeTextSplitter** | Source code — splits on functions/classes, preserves syntax |
| **CharacterTextSplitter** | Simple split on a single character (e.g., `\n\n`). Less smart than Recursive. |

**Key parameters:**
- **chunk_size:** How many tokens per chunk (typically 500)
- **chunk_overlap:** How many tokens overlap between adjacent chunks (typically 50–100)

**Why overlap?** If a sentence is split across two chunks, the overlap ensures both chunks contain the full sentence. Without overlap, you lose information at chunk boundaries.

**Decision:** Start with `RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)`. Only change if you have a specific format (Markdown, code).

---

## 11. Vector Stores

Where embedded vectors are stored and searched.

| Vector Store | When to Use |
|---|---|
| **Pinecone** | Production — managed service, per-namespace isolation, scales automatically |
| **Chroma** | Development/prototyping — runs locally, easy setup, no account needed |
| **FAISS** | In-memory — fastest for small datasets (< 1M vectors), no server needed |
| **Weaviate** | Need hybrid search (vector + keyword) or GraphQL API |
| **Milvus** | Self-hosted at scale — open source alternative to Pinecone |
| **Azure AI Search** | Already on Azure — integrates with Azure ecosystem |

**Decision Tree:**

```
Prototyping locally?
  → Chroma or FAISS

Production with managed infrastructure?
  → Pinecone (easiest) or Azure AI Search (if on Azure)

Self-hosted production?
  → Milvus or Weaviate

Need namespace isolation (multi-tenant/multi-domain)?
  → Pinecone (built-in namespaces)
```

---

## 12. Retrievers

Abstraction over how you search for relevant content. This is the "R" in RAG.

| Retriever | When to Use |
|---|---|
| **VectorStoreRetriever** | **Default choice** — wraps any VectorStore with top-K and score filtering |
| **MultiQueryRetriever** | When a single query misses relevant results — generates multiple query variations and merges results |
| **ParentDocumentRetriever** | When you need small chunks for precision but full documents for context — retrieves the parent document of matched chunks |
| **ContextualCompressionRetriever** | When retrieved chunks contain too much irrelevant text — LLM compresses/filters chunks before injecting into prompt |
| **SelfQueryRetriever** | When questions contain metadata filters — "Show me shipping docs from 2026" → filters on metadata, not just content |

**Decision Tree:**

```
Standard RAG?
  → VectorStoreRetriever (top-K, min-score)

Results missing relevant content?
  → MultiQueryRetriever (tries multiple phrasings)

Chunks too small to be useful alone?
  → ParentDocumentRetriever (returns full parent document)

Retrieved chunks contain too much noise?
  → ContextualCompressionRetriever (LLM filters irrelevant parts)

Questions have metadata conditions (date, author, type)?
  → SelfQueryRetriever (auto-generates metadata filters)
```

---

## 13. Callbacks & Tracing

Observability — see what's happening inside your pipeline.

| Component | When to Use |
|---|---|
| **Callbacks** (on_chain_start, on_llm_end, etc.) | Custom logging, metrics, cost tracking |
| **LangSmith Tracing** | **Production** — full trace of every step, latency, token usage, errors |
| **Custom Logging** | When you need logs in your own format/system |

**Decision:** Always use LangSmith in production. Use callbacks for custom metrics (cost tracking, latency alerts).

---

## 14. Caching

Avoid repeated LLM calls for the same input.

| Cache Type | When to Use |
|---|---|
| **In-Memory Cache** | Development — fast, lost on restart |
| **Redis Cache** | **Production** — persistent, shared across instances, fast |
| **SQLite Cache** | Single-server production — persistent, no Redis dependency |

**Decision:** Development → In-Memory. Production → Redis.

---

## 15. Streaming

Send tokens to the user as they're generated (instead of waiting for the full response).

| When to Use | When NOT to Use |
|---|---|
| Chat UIs where responsiveness matters | Backend APIs where you need the full response before proceeding |
| Long answers that take > 2 seconds | Short classification responses (DATA/DOCUMENT/BOTH) |

---

## 16. Evaluation & Testing

Measure the quality of your RAG/LLM pipeline.

| Tool | When to Use |
|---|---|
| **LLM-based Evaluators** | Grade answer quality (relevance, correctness, grounding) |
| **Pairwise Comparison** | Compare two LLM outputs — which is better? |
| **RAG Evaluation** | Measure retrieval quality (are the right chunks found?) and answer quality (is the answer correct given the chunks?) |
| **LangSmith Experiments** | A/B test different prompts, models, or retrieval strategies |

---

## 17. LangGraph (Sibling Project)

When chains and runnables aren't enough — you need **stateful, multi-step workflows with loops and branches**.

| | Chains/Runnables | LangGraph |
|---|---|---|
| Flow type | Linear or branching | Loops, cycles, state machines |
| State | Stateless (or simple memory) | Full state management |
| Retry logic | RunnableRetry | Built-in node retry with state rollback |
| Human-in-the-loop | Not built-in | First-class support |
| Complexity | Low–Medium | Medium–High |

**When to use LangGraph:**
- Multi-step agent workflows with loops
- Human approval steps in the middle of a pipeline
- Complex error recovery (retry step 3 without redoing steps 1-2)
- When you need a state machine, not a pipeline

**When NOT to use LangGraph:**
- Simple RAG (RetrievalQA is enough)
- Fixed linear pipelines (SequentialChain or LCEL pipes are simpler)

---

## Master Decision Tree

```
What are you building?
│
├─ Simple LLM call with a prompt?
│  → PromptTemplate + ChatModel (LLMChain)
│
├─ RAG (answer from documents)?
│  ├─ Single-turn? → RetrievalQA
│  └─ Multi-turn?  → ConversationalRetrievalChain
│
├─ Need to route to different paths?
│  ├─ Classic way → RouterChain
│  └─ Modern way  → RunnableBranch
│
├─ Multi-step pipeline (step 1 → step 2 → step 3)?
│  ├─ Classic way → SequentialChain
│  └─ Modern way  → LCEL pipe (runnable1 | runnable2 | runnable3)
│
├─ LLM needs to dynamically pick tools?
│  → Agent + AgentExecutor + Tools
│
├─ Complex workflow with loops/state?
│  → LangGraph
│
├─ Need semantic search?
│  → EmbeddingModel + VectorStore + VectorStoreRetriever
│
└─ Need to ingest documents?
   → DocumentLoader + TextSplitter + EmbeddingModel + VectorStore
```

---

## How These Map to the Warehouse Intelligence Platform

| Platform Component | LangChain Components Used |
|---|---|
| **Intent Router** | RunnableBranch (or RouterChain) + ChatModel for classification |
| **Category Router** | RunnableBranch (or RouterChain) + ChatModel or keyword matching |
| **Document Ingestion** | DocumentLoader → TextSplitter → EmbeddingModel → VectorStore |
| **Schema Indexing** | EmbeddingModel → VectorStore (same pipeline, different content) |
| **Document Search** | EmbeddingModel + VectorStoreRetriever (Pinecone, DOCS namespace) |
| **Table Search** | EmbeddingModel + VectorStoreRetriever (Pinecone, SCHEMA namespace) |
| **SQL Generation** | LLMChain (PromptTemplate with CoT + SQLCoder) |
| **SQL Execution** | SQLChain (validate + execute) — not an LLM component |
| **Answer Formatting** | LLMChain (PromptTemplate + Bedrock Claude 3) |
| **Full RAG Pipeline** | RetrievalQA (Retriever + LLMChain orchestrated together) |
| **Conversation History** | ConversationBufferMemory or ConversationSummaryMemory |
| **Caching** | Redis Cache (embeddings + LLM responses) |
| **Observability** | LangSmith Tracing + Custom Callbacks |

---

## One-Line Mental Model

> **LangChain = Models + Prompts + Runnables (Router/Branch) + Retrieval + Agents + Memory + Observability**

- **Models** generate text or embeddings
- **Prompts** structure what you send to models
- **Runnables** compose steps into pipelines (with routing, parallelism, fallbacks)
- **Retrieval** finds relevant context from documents or schemas (the "R" in RAG)
- **Agents** let the LLM decide what to do (when the workflow isn't fixed)
- **Memory** persists state across conversation turns
- **Observability** shows you what's happening inside the pipeline
