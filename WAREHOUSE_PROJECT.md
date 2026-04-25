# Warehouse Intelligence Platform — Project Proposal

> A real-world, interview-ready project: an AI-powered assistant that lets warehouse stakeholders query inventory, shipping, orders, costs, and BI reports using natural language — no SQL knowledge needed.

---

## 1. The Real Problem in Warehouses

In any warehouse or distribution center (DC), stakeholders constantly need data:

| Person                | What They Need                               | How They Get It Today                          |
| --------------------- | -------------------------------------------- | ---------------------------------------------- |
| **DC Manager**        | "How many SKUs are below reorder point?"     | Asks BI team, waits 2 hours for a report       |
| **Operations Lead**   | "What's our pick rate this week vs last?"    | Opens 3 different dashboards, does mental math |
| **Inventory Analyst** | "Show me dead stock over 180 days in Zone A" | Writes SQL query against prod DB (risky)       |
| **Finance/VP**        | "What's our cost-per-order trend for Q1?"    | Waits for weekly Excel from BI team            |
| **Shipping Manager**  | "Which carriers had the most damage claims?" | Asks someone to pull a report from TMS         |
| **Stakeholder**       | "Explain what this BI report means"          | Uploads PDF, reads 30 pages, still confused    |

**Pain points:**

1. BI team is a bottleneck — every question is a ticket
2. Dashboards exist but people don't know which one to look at
3. Direct database access is dangerous (SELECT * FROM orders... locks table)
4. BI reports (PDF/Excel) are dense and hard to parse
5. No single place to ask "how is the warehouse doing?"

---

## 2. The Solution: Warehouse Intelligence Platform

One AI assistant that:

1. **Answers questions from BI reports** (PDF/Excel upload → RAG pipeline)
2. **Queries live database tables** (natural language → SQL → safe read-only execution)
3. **Combines both** — "Compare this month's shipping cost to the Q1 report"

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway / Nginx                       │
│                    (JWT auth, rate limiting, SSL)                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
   ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼──────┐
   │  Chat API  │  │ Upload API│  │ Admin API  │
   │  Service   │  │  Service  │  │  Service   │
   └─────┬──┬──┘  └─────┬─────┘  └────────────┘
         │  │            │
    ┌────┘  └──┐    ┌────┘
    ▼          ▼    ▼
┌────────┐  ┌──────────┐  ┌─────────────────────────┐
│ Intent │  │ RAG      │  │   RabbitMQ              │
│ Router │  │ Pipeline │  │   (ingestion queue)     │
│ (LLM)  │  │          │  └───────────┬─────────────┘
└───┬────┘  └────┬─────┘              │
    │            │              ┌─────▼──────┐
    │  ┌─────────┘              │  Worker    │
    │  │                        │  (parse →  │
    ▼  ▼                        │  chunk →   │
┌──────────────┐                │  embed →   │
│ Query Router │                │  store)    │
│              │                └────────────┘
│ ├── "report" │
│ │   → RAG    │─────────▶ Pinecone (vector search)
│ │            │
│ ├── "data"   │
│ │   → SQL    │─────────▶ Read-Only DB Replica
│ │   Generator│
│ │            │
│ └── "both"   │
│     → RAG +  │─────────▶ Both sources, merged answer
│       SQL    │
└──────────────┘
        │
        ▼
   ┌──────────┐
   │   LLM    │  (answer generation)
   └──────────┘
```

---

## 4. Key Concepts — Vector Embeddings, RAG, and LangChain

Before diving into the flows, here are the foundational concepts used throughout:

### What Are Vector Embeddings?

A **vector embedding** is a list of numbers (e.g., 384 floats) that represents the *meaning* of a piece of text. An **Embedding Model** (like `all-MiniLM-L6-v2`) converts text into these vectors.

- Text that has similar meaning → vectors that are close together in mathematical space
- Text with different meaning → vectors that are far apart
- This allows **semantic search** — finding content by *meaning*, not just keyword match

**Example:**
- "damage claims" → `[0.91, 0.34, 0.67, ...]`
- "damaged goods" → `[0.89, 0.37, 0.64, ...]` (very close — similar meaning)
- "employee salary" → `[0.12, 0.88, 0.05, ...]` (far away — different meaning)

**Why it matters:** The embedding model has been trained on billions of sentences. It learned that "carrier" ≈ "shipping company", "delivery" ≈ "shipment arrival", etc. This bridges business language and technical column/table names — exact word match is not needed, meaning match is enough.

### What Is RAG (Retrieval-Augmented Generation)?

**RAG** is a pattern where you:

1. **Retrieve** relevant context (document chunks, table schemas) from a vector database
2. **Augment** the LLM's prompt by injecting that retrieved context
3. **Generate** an answer grounded in the retrieved context (not hallucinated)

Without RAG, the LLM only knows what it was trained on. With RAG, it can answer questions about your private documents and live data.

### What Is LangChain / LangChain4j?

**LangChain** (Python) / **LangChain4j** (Java) is a framework that provides pre-built components for AI pipelines:

| LangChain Component | What It Does | Used In |
|---|---|---|
| **RouterChain** | Routes questions to different processing chains based on intent | Intent Router, Category Router |
| **EmbeddingModel** | Converts text → vector embeddings | Every step that does vector search |
| **VectorStore** | Stores and searches vectors (connects to Pinecone) | Table schema search, document chunk search |
| **VectorStoreRetriever** | Wraps VectorStore with top-K and min-score filtering | Retrieving relevant tables/chunks |
| **PromptTemplate** | Fills variables into a structured prompt before sending to LLM | CoT prompts for SQL generation, answer formatting |
| **LLMChain** | PromptTemplate + LLM call combined as one unit | SQL generation, answer formatting |
| **RetrievalQA** | VectorStoreRetriever + LLMChain orchestrated together | The full RAG pipeline in one call |
| **SQLChain** | Validates + executes SQL safely against a database | SQL execution with guardrails |

---

## 5. Domain Model — Warehouse Namespaces

### Document Domains (for RAG — PATH B)

| Domain         | Namespace    | What Gets Uploaded                                             | Who Uploads      |
| -------------- | ------------ | -------------------------------------------------------------- | ---------------- |
| **INVENTORY**  | `inventory`  | Stock level reports, cycle count results, ABC analysis PDFs    | Inventory team   |
| **SHIPPING**   | `shipping`   | Carrier scorecards, damage reports, delivery SLAs, rate sheets | Shipping manager |
| **OPERATIONS** | `operations` | SOPs, pick/pack procedures, safety manuals, training docs      | Ops lead         |
| **FINANCE**    | `finance`    | Cost reports, P&L, cost-per-order analysis, budget PDFs        | Finance/BI team  |
| **PRODUCT**    | `product`    | Product specs, catalog, supplier quality reports               | Product team     |
| **COMPLIANCE** | `compliance` | Audit reports, regulatory docs, OSHA guidelines                | Compliance team  |

### Database Domains (for Text-to-SQL — PATH A)

| Schema/Table                   | What It Contains                        | Example Questions                              |
| ------------------------------ | --------------------------------------- | ---------------------------------------------- |
| `inventory.stock_levels`       | Current qty per SKU per location        | "What's on hand for SKU-4892 in Zone A?"       |
| `inventory.reorder_alerts`     | Items below reorder point               | "How many items need reordering?"              |
| `orders.order_header`          | Order metadata (customer, date, status) | "How many orders shipped today?"               |
| `orders.order_lines`           | Line items per order                    | "What's the average order size this week?"     |
| `shipping.shipments`           | Tracking, carrier, cost, delivery date  | "What's our on-time delivery rate?"            |
| `shipping.carrier_performance` | Carrier metrics                         | "Which carrier is cheapest for ground?"        |
| `product.catalog`              | SKU master data                         | "Show me all products in category 'Fasteners'" |
| `costs.cost_per_order`         | Cost breakdown per order                | "What's our cost-per-order trend?"             |
| `returns.return_lines`         | Return reasons, qty, cost               | "Top return reasons this month?"               |

### Category Namespaces — 100 Tables Grouped

| Namespace | Tables Included | Size |
|-----------|----------------|------|
| `INVENTORY` | stock_levels, reorder_alerts, cycle_counts, bin_locations, sku_master, warehouse_zones, ... | ~18 tables |
| `SHIPPING` | shipments, carriers, carrier_performance, tracking_events, damage_claims, delivery_sla, ... | ~16 tables |
| `ORDERS` | order_header, order_lines, order_status_history, customers, return_header, return_lines, ... | ~20 tables |
| `FINANCE` | cost_per_order, invoices, payments, cost_centers, budget_actuals, charge_backs, ... | ~15 tables |
| `PRODUCT` | catalog, suppliers, supplier_scorecards, product_specs, category_master, ... | ~14 tables |
| `OPERATIONS` | pick_tasks, pack_stations, shift_schedules, equipment, labor_metrics, ... | ~17 tables |

**Pinecone stores both types in separate namespaces:**

```
SHIPPING_SCHEMA  ← table DDL descriptions (for Text-to-SQL)
SHIPPING_DOCS    ← document chunks (for RAG)
INVENTORY_SCHEMA ← table DDL descriptions
INVENTORY_DOCS   ← document chunks
...and so on for each domain
```

---

## 6. The Three Query Modes

### Mode 1: Report Query (RAG — PATH B)

```
User: "What did the Q1 supplier scorecard say about vendor ABC?"
  → Intent: DOCUMENT
  → Search PRODUCT_DOCS namespace in Pinecone
  → Find chunks from "Q1_Supplier_Scorecard.pdf"
  → LLM summarizes findings
```

### Mode 2: Data Query (Text-to-SQL — PATH A)

```
User: "How many orders shipped yesterday?"
  → Intent: DATA
  → Find relevant tables via vector search in ORDERS_SCHEMA namespace
  → LLM generates SQL: SELECT COUNT(*) FROM orders.order_header
                        WHERE ship_date = CURRENT_DATE - 1 AND status = 'SHIPPED'
  → Execute against READ-ONLY replica
  → LLM formats result: "847 orders shipped yesterday"
```

### Mode 3: Combined Query (PATH A + PATH B merged)

```
User: "Compare our current stock levels to the reorder analysis from last month's report"
  → Intent: BOTH
  → PATH A (SQL): SELECT sku, qty_on_hand, reorder_point FROM inventory.stock_levels
                   WHERE qty_on_hand < reorder_point
  → PATH B (RAG): Search INVENTORY_DOCS for "reorder analysis"
  → LLM merges both: "Currently 47 SKUs are below reorder point.
     Last month's inventory report (page 8) recommended increasing
     safety stock for fasteners by 15%. Of the 47 SKUs, 23 are
     fasteners — suggesting the recommendation wasn't implemented."
```

---

## 7. Top-Level Intent Routing (Shared by Both Paths)

```
User question
      ↓
┌─────────────────────────────────────────────────────────┐
│                     INTENT ROUTER                        │
│         (LangChain RouterChain / AWS Bedrock)            │
│                                                          │
│  "damage claims last month"  → DATA    → PATH A         │
│  "what does our SOP say"     → DOCUMENT → PATH B        │
│  "compare report vs DB"      → BOTH    → PATH A + B     │
└──────────────┬──────────────────────────┬───────────────┘
               │                          │
               ▼                          ▼
         PATH A                      PATH B
      Text-to-SQL               RAG (Documents)
```

> **🔷 LLM Step: YES** — The Intent Router uses an LLM (AWS Bedrock) with few-shot examples to classify the question.
>
> **🔷 LangChain component:** `RouterChain` — routes questions to different destination chains based on classification.
>
> **🔷 RAG relevance:** This is the *gateway* to both RAG (PATH B) and Schema-RAG (PATH A). It decides which retrieval strategy to use.

**How the LLM classifies:**

The router prompt gives the LLM few-shot examples:

| Question | Classification |
|---|---|
| "How many orders shipped yesterday?" | DATA |
| "What carriers do we use?" | DATA |
| "What does our return policy say?" | DOCUMENT |
| "What is the SOP for cycle counting?" | DOCUMENT |
| "Compare current stock levels to last month report" | BOTH |

The LLM returns only: `DATA` / `DOCUMENT` / `BOTH`.

---

## 8. PATH A — Text-to-SQL Flow (Live Database Data)

**When used:** User needs numbers, counts, trends, or real-time data from the live database.

> Example: *"Which carriers had the most damage claims last month, and what was their average delivery time?"*

**Key insight:** PATH A is essentially the same RAG pipeline as PATH B — except instead of searching document chunks, it searches **table DDL descriptions** in Pinecone. Instead of the LLM generating an answer from text, it generates a **SQL query** from schema context.

---

### PATH A Overview Diagram

```
User Question
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 1: RouterChain — Category Routing                         │
│  🔷 LLM Step: YES (LLM classifies domain)                      │
│  Decides namespace: SHIPPING / INVENTORY / ORDERS / etc.        │
└──────────────────────┬──────────────────────────────────────────┘
                       │ namespace = "SHIPPING"
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 2: EmbeddingModel — Question → Vector                    │
│  🔷 LLM Step: NO (math-only, no generation)                    │
│  User question → 384-dim vector via all-MiniLM-L6-v2           │
└──────────────────────┬──────────────────────────────────────────┘
                       │ question_vector = [0.67, 0.82, ...]
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 3: VectorStoreRetriever — Find Relevant Tables           │
│  🔷 LLM Step: NO (cosine similarity search, no generation)     │
│  Search Pinecone SHIPPING_SCHEMA → top-K table DDLs            │
└──────────────────────┬──────────────────────────────────────────┘
                       │ 4 table DDLs retrieved
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 4: LLMChain — SQL Generation (CoT + SQLCoder)            │
│  🔷 LLM Step: YES (SQLCoder generates SQL from schema)         │
│  PromptTemplate(CoT) + retrieved DDLs + question → SQL query   │
└──────────────────────┬──────────────────────────────────────────┘
                       │ SQL query
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 5: SQLChain — Validate + Execute                         │
│  🔷 LLM Step: NO (pure code — parsing, validation, DB query)   │
│  Safety checks → execute on read-only DB → raw rows            │
└──────────────────────┬──────────────────────────────────────────┘
                       │ rows
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP 6: LLMChain — Format Answer (AWS Bedrock)                │
│  🔷 LLM Step: YES (Claude 3 formats rows into English)         │
│  rows + question → natural language summary                     │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
               Final answer to user
```

---

### STEP 1 — Category Routing

| Property | Value |
|---|---|
| **LLM Step?** | YES — LLM classifies domain from question |
| **LangChain component** | `RouterChain` (Level 2 — category routing) |
| **RAG relevance** | Narrows the search space — instead of searching all 100 tables, only search the ~16 tables in one namespace |
| **Vector embedding?** | No — this is keyword/LLM classification, not vector search |

**What happens:**

The LLM (or keyword matcher with LLM fallback) determines which domain namespace the question belongs to:

- "carriers", "damage", "delivery" → `SHIPPING`
- "stock", "reorder", "bin location" → `INVENTORY`
- "orders", "shipped", "customer" → `ORDERS`
- "cost", "invoice", "payment" → `FINANCE`

**Output:** namespace = `"SHIPPING"` → will search `SHIPPING_SCHEMA` in Pinecone

---

### STEP 2 — Question → Vector Embedding

| Property | Value |
|---|---|
| **LLM Step?** | NO — this is a mathematical transformation, not generative AI |
| **LangChain component** | `EmbeddingModel` (wraps all-MiniLM-L6-v2) |
| **RAG relevance** | This is the "R" (Retrieval) setup — the question must become a vector to search the vector database |
| **Vector embedding?** | YES — this is where the question gets embedded |

**What happens:**

The user's question is converted into a 384-dimensional vector using the `all-MiniLM-L6-v2` embedding model (runs locally via ONNX, no API call needed):

```
Input:  "Which carriers had the most damage claims last month?"
Output: [0.67, 0.82, 0.71, 0.29, 0.55, 0.91, 0.38, ...]  (384 numbers)
```

**Why this is NOT an LLM step:** An embedding model is not generative — it doesn't produce text. It's a neural network that maps text to a point in 384-dimensional space. It's fast (~1ms) and deterministic.

---

### STEP 2b — One-Time Setup: Table DDLs → Vector Store

| Property | Value |
|---|---|
| **LLM Step?** | NO — embedding only, no generation |
| **LangChain component** | `EmbeddingModel` + `VectorStore` (Pinecone) |
| **RAG relevance** | This is the **knowledge base creation** for Schema RAG — same concept as indexing PDF chunks, but for table descriptions |
| **Vector embedding?** | YES — each table description gets embedded and stored |

**What happens (one-time, at application startup or via admin tool):**

For each database table, a **rich text description** is written and embedded:

```
✅ GOOD description (semantic match will work):
   "damage_claims table — stores all damage claims filed against carrier
    shipments. Contains carrier details, claim amount, damage reason,
    and claim date. Use for: carrier damage analysis, claim costs,
    damage rate by carrier."

❌ BAD description (semantic match will fail):
   "dc table — dc_id, s_id, c_id, dt, amt"
```

Each description is embedded into a 384-dim vector and stored in Pinecone under the `SHIPPING_SCHEMA` namespace:

```
Pinecone SHIPPING_SCHEMA namespace:
  carriers         → vector: [0.23, 0.87, ...]  +  text: "carriers table — stores carrier name, mode..."
  damage_claims    → vector: [0.91, 0.34, ...]  +  text: "damage_claims table — stores all damage claims..."
  shipments        → vector: [0.44, 0.78, ...]  +  text: "shipments table — tracks each shipment..."
  tracking_events  → vector: [0.11, 0.92, ...]  +  text: "tracking_events table — individual scan events..."
  delivery_sla     → vector: [0.05, 0.44, ...]  +  text: "delivery_sla table — SLA commitments per carrier..."
```

**This is Schema RAG — treating your database schema as a searchable document.**

---

### STEP 3 — Vector Search: Find Relevant Tables

| Property | Value |
|---|---|
| **LLM Step?** | NO — cosine similarity math, no generation |
| **LangChain component** | `VectorStoreRetriever` (wraps Pinecone search with top-K and min-score filtering) |
| **RAG relevance** | This IS the "R" (Retrieval) in RAG — finding the most relevant context to inject into the LLM prompt |
| **Vector embedding?** | YES — the question vector from Step 2 is compared against all stored table vectors |

**What happens:**

The question vector is compared against every table description vector in the `SHIPPING_SCHEMA` namespace using **cosine similarity**. Tables with scores above the threshold (≥ 0.70) are retrieved:

```
Question vector: [0.67, 0.82, 0.71, ...]

Cosine similarity results:
  carriers         score: 0.94  ✅  "carrier" in both → vectors very close
  damage_claims    score: 0.91  ✅  "damage claims" direct match
  shipments        score: 0.87  ✅  "delivery time" semantically related
  carrier_perf     score: 0.83  ✅  "carrier performance" related
  tracking_events  score: 0.61  ❌  below 0.70 threshold → skipped
  delivery_sla     score: 0.55  ❌  below threshold → skipped

Result: 4 table DDLs retrieved
```

**Why semantic search works here:** The embedding model learned from billions of sentences that:
- "damage" ≈ "damage_claims" ≈ "damaged goods"
- "carrier" ≈ "carrier_id" ≈ "shipping company"
- "delivery" ≈ "delivery_date" ≈ "shipment delivery"

Business language ↔ technical column names — the embedding model is the bridge.

---

### STEP 4 — SQL Generation (LLM + Chain-of-Thought)

| Property | Value |
|---|---|
| **LLM Step?** | YES — SQLCoder (fine-tuned LLM) generates SQL from schema context |
| **LangChain component** | `LLMChain` = `PromptTemplate` (CoT structure) + LLM (`SQLCoder`) |
| **RAG relevance** | This is the "A" (Augmented) in RAG — the retrieved table DDLs are *injected* into the prompt so the LLM has context it wasn't trained on |
| **Vector embedding?** | No — this step consumes the retrieved text, doesn't embed anything |

**What happens:**

A **Chain-of-Thought (CoT) prompt** is constructed with:
1. The retrieved table DDLs from Step 3 (the "augmented" context)
2. Few-shot examples (2-3 question→SQL pairs for this namespace)
3. The user's original question

The prompt instructs SQLCoder to think step-by-step:

```
### Instructions:
Think step by step before writing SQL.
  Step 1 — Identify which tables are needed
  Step 2 — Identify the JOINs required
  Step 3 — Identify filters and aggregations
  Step 4 — Write the final SELECT query

Rules: SELECT only. Never UPDATE/DELETE/INSERT/DROP. Add LIMIT 1000.

### Schema (retrieved from Pinecone):
  [4 table DDLs injected here]

### Few-shot Examples:
  Q: "Total shipments by carrier this month?"
  A: SELECT c.carrier_name, COUNT(*) ... GROUP BY c.carrier_name

### Question: "Which carriers had the most damage claims last month?"
```

**SQLCoder output:**

```sql
-- Step 1: Need damage_claims (count), carriers (name), shipments (delivery time)
-- Step 2: JOIN damage_claims → carriers, shipments → carriers
-- Step 3: Filter claim_date last month, GROUP BY carrier, ORDER BY claims DESC

SELECT c.carrier_name,
       COUNT(dc.claim_id) AS total_damage_claims,
       ROUND(AVG(s.delivery_date - s.ship_date), 1) AS avg_delivery_days
FROM shipping.damage_claims dc
JOIN shipping.carriers c ON dc.carrier_id = c.carrier_id
JOIN shipping.shipments s ON dc.shipment_id = s.shipment_id
WHERE dc.claim_date >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month')
  AND dc.claim_date < DATE_TRUNC('month', CURRENT_DATE)
GROUP BY c.carrier_name
ORDER BY total_damage_claims DESC
LIMIT 1000;
```

**Why SQLCoder instead of GPT-4?** SQLCoder (defog/sqlcoder-70b) is fine-tuned specifically for SQL generation — it outperforms GPT-4 on SQL benchmarks and is free to self-host.

---

### STEP 5 — SQL Validation + Execution

| Property | Value |
|---|---|
| **LLM Step?** | NO — pure code logic (parsing, validation, database execution) |
| **LangChain component** | `SQLChain` (validates and executes SQL safely) |
| **RAG relevance** | Not directly RAG — this is the safety/execution layer |
| **Vector embedding?** | No |

**What happens — the safety pipeline:**

```
SQL from Step 4
     │
     ▼
┌──────────────────┐    ❌ Syntax error → retry once (send error back to SQLCoder)
│ 1. SQL Parser    │
│    (JSqlParser)  │
└────────┬─────────┘
         │ ✅ Valid syntax
         ▼
┌──────────────────┐    ❌ Not SELECT → REJECT immediately
│ 2. Safety Check  │    ❌ Unauthorized table → REJECT (per-role whitelist)
│    SELECT only   │
│    + whitelist   │
└────────┬─────────┘
         │ ✅ Safe
         ▼
┌──────────────────┐    ❌ Timeout > 5s → "Query too complex"
│ 3. Execute on    │    ❌ > 1000 rows  → return first 100 + warning
│    READ-ONLY     │
│    replica DB    │
└────────┬─────────┘
         │ ✅ Results
         ▼
  Raw rows returned
```

**Safety guardrails:**
- **SELECT only** — regex + parser blocks UPDATE/DELETE/INSERT/DROP
- **Table whitelist per role** — warehouse staff can't query finance tables
- **Read-only replica** — even if SQL injection passes, it can't write
- **5-second timeout** — blocks expensive queries
- **LIMIT 1000** — auto-appended if missing
- **Self-healing** — on syntax error, SQL is sent back to SQLCoder once for fixing

**Example result:**

```
carrier_name    | total_damage_claims | avg_delivery_days
────────────────┼─────────────────────┼──────────────────
FastShip Co     | 312                 | 5.8
QuickCarry      | 198                 | 4.2
ReliableFreight |  87                 | 3.1
NightRun LLC    |  54                 | 3.8
```

---

### STEP 6 — Answer Formatting

| Property | Value |
|---|---|
| **LLM Step?** | YES — AWS Bedrock Claude 3 converts raw rows to natural language |
| **LangChain component** | `LLMChain` = `PromptTemplate` + LLM (Bedrock Claude 3) |
| **RAG relevance** | This is the "G" (Generation) in RAG — the final answer generation step |
| **Vector embedding?** | No |

**What happens:**

The raw database rows + the original question are sent to Claude 3 with a formatting prompt:

```
Prompt: "User asked: '{question}'. Query result: {rows}. Write a concise, business-friendly summary."
```

**LLM output:**

> "Last month, FastShip Co had the highest damage claims (312) and the slowest average delivery time at 5.8 days. ReliableFreight was the top performer with only 87 claims and 3.1-day average delivery. Consider shifting volume away from FastShip Co until their damage rate improves.
>
> Source: shipping.damage_claims, shipping.shipments (live data)"

---

### PATH A — Step Summary

| Step | What Happens | LLM? | LangChain Component | RAG Role | Vector Embedding? |
|------|-------------|-------|-------------------|----------|------------------|
| 1. Category Routing | Classify domain namespace | ✅ YES | RouterChain | Narrows search space | No |
| 2. Question Embedding | Question → 384-dim vector | ❌ NO | EmbeddingModel | Retrieval setup | ✅ YES |
| 2b. DDL Indexing (one-time) | Table descriptions → vectors in Pinecone | ❌ NO | EmbeddingModel + VectorStore | Knowledge base creation | ✅ YES |
| 3. Table Retrieval | Find relevant tables via cosine similarity | ❌ NO | VectorStoreRetriever | **R** — Retrieval | ✅ YES (similarity search) |
| 4. SQL Generation | Generate SQL from schema + question | ✅ YES | LLMChain (PromptTemplate + SQLCoder) | **A** — Augmented (DDLs injected) | No |
| 5. Validation + Execution | Parse, validate, execute on read-only DB | ❌ NO | SQLChain | Safety layer | No |
| 6. Answer Formatting | Rows → natural language summary | ✅ YES | LLMChain (PromptTemplate + Bedrock) | **G** — Generation | No |

**LLM calls in PATH A: 3** (routing, SQL generation, answer formatting)
**Non-LLM steps: 4** (embedding, indexing, retrieval, execution)

---

## 9. PATH B — RAG Flow (Documents, PDFs, SOPs, Contracts)

**When used:** User asks questions about company documents — SOPs, carrier contracts, BI reports, manuals. This data is NOT in the database, it's in uploaded PDFs/Excel files.

> Example: *"What is the SOP for handling damaged goods?"*
> Example: *"What does our carrier contract say about claim limits?"*

---

### PATH B Overview Diagram

```
                  ┌─────────────────────────────────────────┐
                  │  ONE-TIME: Document Ingestion Pipeline   │
                  │  (runs when files are uploaded)          │
                  │                                          │
                  │  STEP A: Load → Chunk → Embed → Store   │
                  │  🔷 LLM: NO (all non-LLM processing)    │
                  └─────────────────────────────────────────┘

User Question (at query time)
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP B: RouterChain — Category Routing                         │
│  🔷 LLM Step: YES                                              │
│  Decides namespace: SHIPPING_DOCS / INVENTORY_DOCS / etc.      │
└──────────────────────┬──────────────────────────────────────────┘
                       │ namespace = "SHIPPING_DOCS"
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP C: EmbeddingModel + VectorStoreRetriever                 │
│  🔷 LLM Step: NO (embedding + cosine similarity search)        │
│  Question → vector → search Pinecone → top chunks retrieved    │
└──────────────────────┬──────────────────────────────────────────┘
                       │ 3 document chunks retrieved
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  STEP D: RetrievalQA Chain — Inject Chunks into Prompt         │
│  🔷 LLM Step: YES (Bedrock Claude 3 generates answer)          │
│  System prompt + retrieved chunks + question → LLM answer      │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
               Final answer to user
```

---

### STEP A — One-Time Document Ingestion

| Property | Value |
|---|---|
| **LLM Step?** | NO — all sub-steps are non-LLM (parsing, splitting, embedding, storing) |
| **LangChain components** | `DocumentLoader` → `DocumentSplitter` → `EmbeddingModel` → `VectorStore` |
| **RAG relevance** | This creates the **knowledge base** that RAG retrieves from at query time |
| **Vector embedding?** | YES — every document chunk gets embedded |

**What happens when a file is uploaded:**

```
Upload: "Damaged_Goods_SOP.pdf"
  → RabbitMQ queue receives message
  → Worker service picks it up
  → Pipeline runs:

  ┌─────────────────────────────────────────────────────────┐
  │  1. LOAD (DocumentLoader)                                │
  │     PDF → extract raw text                               │
  │     NOT an LLM step — uses Apache PDFBox or similar      │
  │                                                          │
  │  2. CHUNK (DocumentSplitter)                             │
  │     Split text into 500-token chunks with 50-token       │
  │     overlap (so context isn't lost at chunk boundaries)  │
  │     NOT an LLM step — rule-based text splitting          │
  │                                                          │
  │     Chunk 1: "Step 1 — Receive damaged item,             │
  │              photograph all sides before moving..."      │
  │     Chunk 2: "Step 2 — File damage claim via             │
  │              carrier portal. Attach DC-101 scan..."      │
  │     Chunk 3: "Step 3 — Email claim number to             │
  │              carrier rep. CC: claims@warehouse.com..."   │
  │                                                          │
  │  3. EMBED (EmbeddingModel)                               │
  │     Each chunk → 384-dim vector via all-MiniLM-L6-v2    │
  │     NOT an LLM step — mathematical transformation        │
  │                                                          │
  │     Chunk 1 → [0.72, 0.45, 0.88, 0.31, ...]             │
  │     Chunk 2 → [0.68, 0.51, 0.79, 0.44, ...]             │
  │     Chunk 3 → [0.74, 0.39, 0.82, 0.28, ...]             │
  │                                                          │
  │  4. STORE (VectorStore → Pinecone)                       │
  │     Save vector + metadata in SHIPPING_DOCS namespace    │
  │     NOT an LLM step — database write                     │
  │                                                          │
  │     Metadata: {file: "Damaged_Goods_SOP.pdf",            │
  │               page: 3, chunk: 1}                         │
  └─────────────────────────────────────────────────────────┘
```

**Why chunking?** LLMs have token limits. A 30-page PDF won't fit in one prompt. Chunking breaks it into searchable pieces. The 50-token overlap ensures a sentence split across chunk boundaries isn't lost.

---

### STEP B — Category Routing

| Property | Value |
|---|---|
| **LLM Step?** | YES — same LLM-based category router as PATH A |
| **LangChain component** | `RouterChain` (Level 2 — category routing) |
| **RAG relevance** | Narrows document search to the right namespace |
| **Vector embedding?** | No |

**What happens:**

Same category router as PATH A Step 1, but the output namespace is different:

```
Question: "What is the SOP for handling damaged goods?"
  → Keywords: "damaged goods", "SOP" → SHIPPING
  → Output: namespace = "SHIPPING_DOCS"   (not SHIPPING_SCHEMA)
```

PATH A searches `_SCHEMA` namespaces (table DDLs). PATH B searches `_DOCS` namespaces (document chunks).

---

### STEP C — Question Embedding + Vector Search

| Property | Value |
|---|---|
| **LLM Step?** | NO — embedding is math, vector search is cosine similarity |
| **LangChain components** | `EmbeddingModel` (question → vector) + `VectorStoreRetriever` (Pinecone search) |
| **RAG relevance** | This IS the "R" (Retrieval) in RAG — finding the most relevant document chunks |
| **Vector embedding?** | YES — the question is embedded and compared against all stored chunk vectors |

**What happens:**

```
Question: "What is the SOP for handling damaged goods?"
    ↓
  all-MiniLM-L6-v2 embeds question
    ↓
  Question vector: [0.71, 0.48, 0.85, 0.33, ...]
    ↓
  Pinecone search (namespace = "SHIPPING_DOCS", topK=5, minScore=0.70):

  Damaged_Goods_SOP.pdf chunk 1  score: 0.93  ✅ selected
    "Step 1 — Receive damaged item, photograph all sides..."

  Damaged_Goods_SOP.pdf chunk 2  score: 0.89  ✅ selected
    "Step 2 — File damage claim via carrier portal..."

  Damaged_Goods_SOP.pdf chunk 3  score: 0.81  ✅ selected
    "Step 3 — Email claim number to carrier rep..."

  FastShip_Contract_2026.pdf p4  score: 0.67  ❌ below threshold
  Q1_Carrier_Scorecard.pdf p12   score: 0.51  ❌ below threshold

  Result: 3 chunks retrieved
```

**Why this works:** The embedding model understands that "handling damaged goods" is semantically close to "receive damaged item" and "file damage claim" — even though the exact words differ.

---

### STEP D — Answer Generation (RetrievalQA Chain)

| Property | Value |
|---|---|
| **LLM Step?** | YES — AWS Bedrock Claude 3 generates the final answer from retrieved chunks |
| **LangChain component** | `RetrievalQA` / `ConversationalRetrievalChain` — orchestrates retriever + LLM in one call |
| **RAG relevance** | This combines "A" (Augmented — chunks injected into prompt) and "G" (Generation — LLM generates answer) |
| **Vector embedding?** | No — chunks are already text, no embedding needed here |

**What happens:**

The retrieved chunks are injected into the LLM prompt as context:

```
System: You are a warehouse assistant. Answer ONLY using the
        provided context. If the answer is not in the context,
        say "I don't have that information in our documents."

Context (retrieved chunks):
  [CHUNK 1 - Damaged_Goods_SOP.pdf, page 3]
  "Step 1 — Receive damaged item, photograph all sides
   before moving. Fill form DC-101 within 2 hours..."

  [CHUNK 2 - Damaged_Goods_SOP.pdf, page 3]
  "Step 2 — File damage claim via carrier portal within
   24 hours. Attach DC-101 scan..."

  [CHUNK 3 - Damaged_Goods_SOP.pdf, page 4]
  "Step 3 — Email claim number to carrier rep.
   CC: claims@warehouse.com. Escalate if no response in 48hrs..."

User question: "What is the SOP for handling damaged goods?"
```

**LLM output:**

> "According to our Damaged Goods SOP, here are the steps:
>
> 1. Photograph all sides of the damaged item before moving it. Fill form DC-101 within 2 hours.
> 2. File a damage claim via the carrier portal within 24 hours. Attach the DC-101 scan. You'll receive a claim number.
> 3. Email the claim number to the carrier rep (CC: claims@warehouse.com). Escalate to your supervisor if no response within 48 hours.
>
> Source: Damaged_Goods_SOP.pdf, pages 3–4"

**Why "ONLY using the provided context"?** This prevents hallucination — the LLM can't make up procedures that don't exist in your actual documents.

---

### PATH B — Step Summary

| Step | What Happens | LLM? | LangChain Component | RAG Role | Vector Embedding? |
|------|-------------|-------|-------------------|----------|------------------|
| A. Document Ingestion (one-time) | Load → Chunk → Embed → Store in Pinecone | ❌ NO | DocumentLoader + DocumentSplitter + EmbeddingModel + VectorStore | Knowledge base creation | ✅ YES |
| B. Category Routing | Classify document namespace | ✅ YES | RouterChain | Narrows search space | No |
| C. Question Search | Question → vector → find matching chunks | ❌ NO | EmbeddingModel + VectorStoreRetriever | **R** — Retrieval | ✅ YES |
| D. Answer Generation | Chunks + question → LLM generates answer | ✅ YES | RetrievalQA (Retriever + LLMChain) | **A+G** — Augmented + Generation | No |

**LLM calls in PATH B: 2** (routing, answer generation)
**Non-LLM steps: 2** (document ingestion, vector search)

---

## 10. PATH A vs PATH B — Side-by-Side Comparison

| | PATH A — Text-to-SQL | PATH B — RAG (Documents) |
|---|---|---|
| **Answer comes from** | Live PostgreSQL database | PDF/Excel chunks in Pinecone |
| **What LLM generates** | SQL query (Step 4) + formatted answer (Step 6) | Natural language answer (Step D) |
| **Pinecone namespace** | `{DOMAIN}_SCHEMA` (table descriptions) | `{DOMAIN}_DOCS` (document chunks) |
| **What's stored in Pinecone** | Table DDL descriptions (embedded) | Document chunks (embedded) |
| **LLM calls** | 3 (routing, SQL gen, formatting) | 2 (routing, answer gen) |
| **Non-LLM steps** | 4 (embed, index, retrieve, execute) | 2 (ingest, search) |
| **LangChain chain type** | RetrievalQA → SQLChain → LLMChain | RetrievalQA (ConversationalRetrievalChain) |
| **Embedding model** | all-MiniLM-L6-v2 (same) | all-MiniLM-L6-v2 (same) |
| **Answer LLM** | AWS Bedrock Claude 3 (same) | AWS Bedrock Claude 3 (same) |
| **Example question** | "How many orders shipped yesterday?" | "What does our SOP say about returns?" |

**Core insight:** Both paths are the SAME RAG pattern:

```
PATH B (Document RAG):
  question → embed → search doc chunks → inject into prompt → LLM answers

PATH A (Schema RAG → SQL):
  question → embed → search table DDLs → inject into prompt → LLM writes SQL → execute → LLM formats

The only difference:
  - What's in the VectorStore: doc chunks vs table DDLs
  - What the LLM generates: answer text vs SQL query
  - PATH A has extra steps: SQL execution + answer formatting
```

---

## 11. When Things Go Wrong — Fallback Handling

### SQLCoder Can't Generate a Query (PATH A)

1. **Self-assessment** — the prompt includes: *"If you cannot write a valid SQL query, respond with CANNOT_QUERY: [reason]"*
2. **Fallback to RAG** — if SQL fails, automatically try PATH B (the answer may be in a PDF report)
3. **Ask for clarification** — return: *"I found tables for inventory and shipping. Did you mean current stock levels or shipped quantities?"*

### No Relevant Chunks Found (PATH B)

1. **Score too low** — all chunks score below 0.70 → return: *"I don't have documents related to that topic."*
2. **Wrong namespace** — router misclassified → the system can retry with the next-best namespace
3. **Document not uploaded** — the information simply isn't in the system yet

---

## 12. Schema Strategy — How the LLM Knows Your Database

The LLM doesn't magically know your tables. With 100+ tables, there are four strategies at different scales:

| # Tables | Strategy | How It Works | Tradeoff |
|----------|----------|-------------|----------|
| < 20 | **Full Schema Injection** | Include ALL table DDLs in every prompt | Simple but wastes tokens |
| 20–100 | **Domain Grouping** | Group tables by domain, inject one domain's DDLs | Easy, but cross-domain queries break |
| 100–1000+ | **Schema RAG** (our approach) | Embed table descriptions in Pinecone, vector-search per query | Most scalable, reuses existing RAG pipeline |
| Any scale | **Two-LLM-Call Pipeline** | Call 1: LLM picks tables from a list. Call 2: LLM writes SQL with full DDLs | Most robust, slightly higher latency |

**We use Schema RAG (Strategy 3)** because:
- We already have Pinecone for document RAG — reuse it for schema
- 100 tables across 6 namespaces fits perfectly
- Same EmbeddingModel, same retrieval logic, different content

---

## 13. Tool Decisions

| Concern | Tool Chosen | Why | Alternatives Skipped |
|---------|------------|-----|---------------------|
| SQL Generation | SQLCoder (defog/sqlcoder-70b) | Fine-tuned for SQL; beats GPT-4 on benchmarks; free to self-host | GPT-4, DIN-SQL |
| Prompting | Chain-of-Thought (CoT) | Step-by-step reasoning in one prompt; reduces hallucinated columns ~40% | DIN-SQL multi-query |
| Table Selection | Category Pattern + Pinecone | Category routing is predictable; Pinecone already in stack | LlamaIndex, Vanna.ai |
| SQL Execution | LangChain SQLChain | Handles retries, timeouts, schema formatting out-of-box | Raw JDBC |
| Answer Formatting | AWS Bedrock (Claude 3) | AWS-native; no external API keys; SOC2 compliant | OpenAI API |
| Embeddings | all-MiniLM-L6-v2 (local ONNX) | Free, fast (~1ms), no API dependency, 384-dim vectors | OpenAI ada-002, Cohere |
| Vector DB | Pinecone | Per-domain namespaces for both docs and schemas | Weaviate, Milvus |

---

## 14. Role-Based Access Control

| Role                | Can Query (RAG)                | Can Query (SQL)               | Can Upload | Can Admin |
| ------------------- | ------------------------------ | ----------------------------- | ---------- | --------- |
| `WAREHOUSE_STAFF`   | Operations, Product            | inventory.stock_levels only   | No         | No        |
| `INVENTORY_ANALYST` | Inventory, Operations, Product | inventory.*, product.*        | Inventory  | No        |
| `SHIPPING_MANAGER`  | Shipping, Operations           | shipping.*, orders.*          | Shipping   | No        |
| `FINANCE_ANALYST`   | Finance, Operations            | costs.*, orders.*, returns.*  | Finance    | No        |
| `DC_MANAGER`        | All domains                    | All tables                    | All        | No        |
| `ADMIN`             | All                            | All                           | All        | Yes       |

**Key principle:** A warehouse staff member should NEVER see finance/cost data. The SQL generator's table whitelist is enforced per-role at Step 5.

---

## 15. Tech Stack

| Layer             | Technology                              | Why                                                          |
| ----------------- | --------------------------------------- | ------------------------------------------------------------ |
| Backend           | Java 21, Spring Boot 3.x                | Enterprise standard                                          |
| AI Framework      | LangChain4j                             | Java-native RAG components; RetrievalQA, RouterChain, etc.   |
| SQL LLM           | SQLCoder (defog/sqlcoder-70b via vLLM)  | Fine-tuned for SQL; free to self-host                        |
| SQL Prompting     | Chain-of-Thought (CoT)                  | Reduces hallucinations with step-by-step reasoning           |
| Answer LLM        | AWS Bedrock (Claude 3 / Llama3)         | AWS-native; SOC2 compliant                                   |
| Embeddings        | Local ONNX (all-MiniLM-L6-v2)           | Free, fast, 384-dim vectors, no API dependency               |
| Vector DB         | Pinecone                                | Per-domain namespaces for docs + schemas                     |
| SQL DB            | PostgreSQL (read-only replica)          | Safe execution; even SQL injection can't write               |
| Queue             | RabbitMQ                                | Async document ingestion pipeline                            |
| Cache             | Redis                                   | Embedding cache, SQL result cache, rate limiting             |
| File Storage      | S3 / MinIO                              | Original PDF/Excel storage                                   |
| Auth              | Keycloak                                | JWT + RBAC; table whitelist enforced per role                |
| Deployment        | Docker + Kubernetes                     | Horizontal scaling                                           |
| Monitoring        | Prometheus + Grafana                    | Metrics, SQL latency, LLM cost tracking                      |

---

## 16. Interview Talking Points

### "Tell me about a project you've built"

> "I built a Warehouse Intelligence Platform — an AI assistant that lets warehouse stakeholders query both their BI reports and live database tables using natural language.
>
> The core is a RAG pipeline: BI team uploads PDF reports into domain-specific namespaces — inventory, shipping, finance, etc. When someone asks a question, an LLM classifies which domain to search, finds the relevant document chunks via vector similarity in Pinecone, and generates a grounded answer.
>
> The unique part is the Text-to-SQL mode. If the question needs live data — like 'how many orders shipped today' — the system uses Schema RAG to find the relevant table DDLs, generates a safe SQL query via SQLCoder, runs it against a read-only replica, and presents the result. For complex questions, it combines both sources."

### "What challenges did you face?"

> 1. **Prompt engineering for RAG** — the LLM kept saying 'I don't have enough information' even when the chunks had the answer. Fixed by proper SystemMessage/UserMessage separation.
> 2. **SQL safety** — built multiple guardrails: SELECT-only parsing, table whitelisting per role, read-only replica, query timeout, automatic LIMIT.
> 3. **Domain routing accuracy** — improved with few-shot examples in the routing prompt and cosine similarity threshold (≥ 0.7).

### "How would you scale this?"

> "The API is stateless — 3+ pods behind a load balancer. Document ingestion is decoupled via RabbitMQ. Redis caches embeddings and LLM responses. Pinecone and PostgreSQL handle their own scaling. For multi-tenant, prefix Pinecone namespaces with org ID."

---

## 17. Comparison: Current Project → Warehouse Project

| Feature          | Current (Knowledge Assistant) | Warehouse Intelligence Platform |
| ---------------- | ----------------------------- | ------------------------------- |
| RAG pipeline     | ✅                            | ✅ (same)                       |
| Domain routing   | ✅ 3 domains                  | ✅ 6+ warehouse domains         |
| Text-to-SQL      | ❌                            | ✅ Natural language → safe SQL  |
| Combined queries | ❌                            | ✅ RAG + SQL merged             |
| Auth             | ❌                            | ✅ JWT + Keycloak               |
| RBAC             | ❌                            | ✅ Per-domain + per-table       |
| Ingestion queue  | ❌ (@Async)                   | ✅ RabbitMQ                     |
| File storage     | ❌ (ephemeral)                | ✅ S3/MinIO                     |
| Database         | ❌ (CSV)                      | ✅ PostgreSQL                   |
| Caching          | ❌                            | ✅ Redis                        |
| Monitoring       | ❌                            | ✅ Prometheus + Grafana         |
| Deployment       | Local                         | Docker + K8s                    |

---

## 18. Implementation Plan

Build incrementally:

1. **Start with current project** (already done) — proves RAG + domain routing works
2. **Add PostgreSQL + Auth** — Phase 1 from [PRODUCTION_ROADMAP.md](PRODUCTION_ROADMAP.md)
3. **Add Text-to-SQL service** — schema description + LLM + safe executor
4. **Add intent router** — classify: report / data / combined
5. **Add warehouse domains** — inventory, shipping, finance, operations
6. **Add RabbitMQ + S3** — reliable ingestion
7. **Add role-based table whitelisting** — the SQL guardrail layer
8. **Deploy with Docker** — compose file with all services
