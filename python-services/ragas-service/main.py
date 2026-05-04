"""
RAGAS Evaluation Service — uses the real ragas library (v0.2.x).

Metrics (LLM-only, no external embedding download required):
  - faithfulness       : are answer claims supported by the retrieved context?
  - context_precision  : are top chunks more relevant? (requires ground_truth)
  - context_recall     : are all ground-truth facts covered? (requires ground_truth)
  - answer_relevancy   : added opportunistically if a local embedding model loads

LangSmith visibility: the Java ChatService creates a `ragas_evaluation` OTel span
and sends scores to LangSmith via OTLP (TracingConfig.java).  This Python service
does NOT need its own LangSmith/LANGCHAIN_TRACING_V2 setup — it just computes and
returns the metric scores that the Java layer then traces.
"""

import os
import logging
from typing import Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ragas-service")

app = FastAPI(title="RAGAS Evaluation Service", version="2.0.0")

# ─── Configuration ─────────────────────────────────────────────────────────────
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_BASE_URL = os.getenv("GROQ_BASE_URL", "https://api.groq.com/openai/v1")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")

# ─── Lazy singletons ──────────────────────────────────────────────────────────
_llm_wrapper = None
_embeddings_wrapper = None        # None means "not yet attempted"
_embeddings_failed = False        # True means we already tried and it failed


def _get_llm():
    global _llm_wrapper
    if _llm_wrapper is None:
        from langchain_openai import ChatOpenAI
        from ragas.llms import LangchainLLMWrapper
        _llm_wrapper = LangchainLLMWrapper(
            ChatOpenAI(
                model=GROQ_MODEL,
                api_key=GROQ_API_KEY,
                base_url=GROQ_BASE_URL,
                temperature=0,
            )
        )
        logger.info("RAGAS LLM ready: %s via %s", GROQ_MODEL, GROQ_BASE_URL)
    return _llm_wrapper


def _try_get_embeddings():
    """Return an embeddings wrapper, or None if the local model cannot be loaded."""
    global _embeddings_wrapper, _embeddings_failed
    if _embeddings_failed:
        return None
    if _embeddings_wrapper is not None:
        return _embeddings_wrapper
    try:
        from langchain_huggingface import HuggingFaceEmbeddings
        from ragas.embeddings import LangchainEmbeddingsWrapper
        _embeddings_wrapper = LangchainEmbeddingsWrapper(
            HuggingFaceEmbeddings(model_name="sentence-transformers/all-MiniLM-L6-v2")
        )
        logger.info("RAGAS embeddings ready: all-MiniLM-L6-v2")
        return _embeddings_wrapper
    except Exception as e:
        _embeddings_failed = True
        logger.warning("Embeddings unavailable (AnswerRelevancy skipped): %s", str(e)[:120])
        return None


# ─── Request / Response models ─────────────────────────────────────────────────

class EvaluationRequest(BaseModel):
    question: str
    answer: str
    contexts: list[str]
    ground_truth: Optional[str] = None


class EvaluationResponse(BaseModel):
    answer_relevancy: Optional[float] = None
    faithfulness: Optional[float] = None
    context_precision: Optional[float] = None
    context_recall: Optional[float] = None


# ─── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/evaluate", response_model=EvaluationResponse)
def evaluate_rag(req: EvaluationRequest):
    """
    Evaluate a RAG response using the real ragas library.

    Always runs: faithfulness (LLM-only).
    Runs if embeddings available: answer_relevancy (LLM + embeddings).
    Runs if ground_truth provided: context_precision, context_recall (LLM-only).

    Scores are returned to the Java ChatService, which records them as an OTel span
    sent to LangSmith via OTLP — that is the project's tracing integration.
    """
    try:
        from ragas import evaluate
        from ragas.dataset_schema import EvaluationDataset, SingleTurnSample
        from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision, ContextRecall

        llm = _get_llm()
        embeddings = _try_get_embeddings()

        sample = SingleTurnSample(
            user_input=req.question,
            response=req.answer,
            retrieved_contexts=req.contexts,
            reference=req.ground_truth,
        )
        dataset = EvaluationDataset(samples=[sample])

        # Always include faithfulness (LLM-only, no embedding dependency)
        metrics = [Faithfulness(llm=llm)]

        # AnswerRelevancy needs embeddings — only add if they loaded successfully
        if embeddings is not None:
            metrics.append(AnswerRelevancy(llm=llm, embeddings=embeddings))

        # ground_truth metrics (LLM-only)
        if req.ground_truth:
            metrics.extend([ContextPrecision(llm=llm), ContextRecall(llm=llm)])

        logger.info("RAGAS | metrics=%s | q=%s", [m.name for m in metrics], req.question[:80])

        result = evaluate(
            dataset=dataset,
            metrics=metrics,
            raise_exceptions=False,
            show_progress=False,
        )
        scores = result.to_pandas().to_dict(orient="records")[0]

        response = EvaluationResponse(
            faithfulness=_safe_float(scores.get("faithfulness")),
            answer_relevancy=_safe_float(scores.get("answer_relevancy")),
            context_precision=_safe_float(scores.get("context_precision")),
            context_recall=_safe_float(scores.get("context_recall")),
        )
        logger.info("RAGAS scores: %s", response.model_dump())
        return response

    except Exception as e:
        logger.error("RAGAS evaluation failed: %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail=f"Evaluation failed: {str(e)}")


def _safe_float(val) -> Optional[float]:
    if val is None:
        return None
    try:
        import math
        f = float(val)
        return None if math.isnan(f) else round(f, 4)
    except (ValueError, TypeError):
        return None
