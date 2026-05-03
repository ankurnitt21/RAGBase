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
        from langchain_openai import ChatOpenAI

        llm = ChatOpenAI(
            model=GROQ_MODEL,
            openai_api_key=GROQ_API_KEY,
            openai_api_base=GROQ_BASE_URL,
            temperature=0,
        )

        # Manual faithfulness: check if each claim in the answer is supported by contexts
        context_str = "\n".join(req.contexts)
        faithfulness_prompt = (
            "Given the following context and answer, evaluate faithfulness.\n\n"
            f"Context:\n{context_str}\n\n"
            f"Answer:\n{req.answer}\n\n"
            "Rate how faithful the answer is to the context on a scale of 0.0 to 1.0, "
            "where 1.0 means every claim is fully supported by the context and 0.0 means "
            "no claims are supported.\n"
            "Respond with ONLY a decimal number between 0.0 and 1.0, nothing else."
        )

        logger.info("Running faithfulness evaluation via LLM")
        result = llm.invoke(faithfulness_prompt)
        raw = result.content.strip()

        faithfulness_score = _safe_float(raw)
        if faithfulness_score is not None:
            faithfulness_score = max(0.0, min(1.0, faithfulness_score))

        response = EvaluationResponse(
            faithfulness=faithfulness_score,
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
