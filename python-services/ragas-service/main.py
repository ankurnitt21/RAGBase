import os
import logging
from typing import Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="RAGAS Evaluation Service", version="1.0.0")
logger = logging.getLogger("ragas-service")
logging.basicConfig(level=logging.INFO)

# Configure LLM for RAGAS (uses Groq via OpenAI-compatible API)
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_BASE_URL = os.getenv("GROQ_BASE_URL", "https://api.groq.com/openai/v1")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")


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


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/evaluate", response_model=EvaluationResponse)
def evaluate(req: EvaluationRequest):
    try:
        from datasets import Dataset
        from ragas import evaluate as ragas_evaluate
        from ragas.metrics import (
            faithfulness,
            context_precision,
            context_recall,
        )
        from langchain_openai import ChatOpenAI
        from ragas.llms import LangchainLLMWrapper

        # Use Groq as LLM via OpenAI-compatible API
        llm = ChatOpenAI(
            model=GROQ_MODEL,
            openai_api_key=GROQ_API_KEY,
            openai_api_base=GROQ_BASE_URL,
            temperature=0,
        )
        ragas_llm = LangchainLLMWrapper(llm)

        data = {
            "question": [req.question],
            "answer": [req.answer],
            "contexts": [req.contexts],
        }

        # faithfulness doesn't require embeddings; answer_relevancy does so we skip it
        metrics = [faithfulness]

        if req.ground_truth:
            data["ground_truth"] = [req.ground_truth]
            metrics.extend([context_precision, context_recall])

        dataset = Dataset.from_dict(data)

        logger.info("Running RAGAS evaluation with %d metrics", len(metrics))
        result = ragas_evaluate(dataset=dataset, metrics=metrics, llm=ragas_llm)

        df = result.to_pandas()
        row = df.iloc[0]

        response = EvaluationResponse(
            answer_relevancy=_safe_float(row.get("answer_relevancy")),
            faithfulness=_safe_float(row.get("faithfulness")),
            context_precision=_safe_float(row.get("context_precision")),
            context_recall=_safe_float(row.get("context_recall")),
        )

        logger.info("RAGAS result: %s", response.model_dump())
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
