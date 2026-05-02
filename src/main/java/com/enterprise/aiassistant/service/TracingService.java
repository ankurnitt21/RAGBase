package com.enterprise.aiassistant.service;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.PipelineMetrics;

import java.util.Map;
import java.util.function.Supplier;

@Service
public class TracingService {

    private static final Logger log = LoggerFactory.getLogger(TracingService.class);
    private final Tracer tracer;

    public TracingService(Tracer tracer) {
        this.tracer = tracer;
    }

    public Span startRootSpan(String query) {
        Span span = tracer.spanBuilder("rag_pipeline")
                .startSpan();
        span.setAttribute("langsmith.span.kind", "chain");
        span.setAttribute("input.value", query);
        return span;
    }

    /**
     * Create a child span without executing work (caller manages lifecycle).
     */
    public Span startChildSpan(String name, Span parent, String spanKind, String input) {
        Span span = tracer.spanBuilder(name)
                .setParent(Context.current().with(parent))
                .startSpan();
        span.setAttribute("langsmith.span.kind", spanKind);
        if (input != null) span.setAttribute("input.value", truncate(input));
        return span;
    }

    /**
     * Execute a pipeline step inside a child span with input/output tracking.
     */
    public <T> T traceStep(String spanName, Span parentSpan, String input, Supplier<T> work) {
        Span childSpan = tracer.spanBuilder(spanName)
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        childSpan.setAttribute("langsmith.span.kind", "chain");
        if (input != null) childSpan.setAttribute("input.value", truncate(input));
        long start = System.currentTimeMillis();
        try (Scope ignored = childSpan.makeCurrent()) {
            T result = work.get();
            long latencyMs = System.currentTimeMillis() - start;
            childSpan.setAttribute("latency_ms", latencyMs);
            if (result != null) childSpan.setAttribute("output.value", truncate(result.toString()));
            return result;
        } catch (Exception e) {
            childSpan.setStatus(StatusCode.ERROR, e.getMessage());
            childSpan.recordException(e);
            throw e;
        } finally {
            childSpan.end();
        }
    }

    /**
     * Execute a void pipeline step inside a child span with input/output tracking.
     */
    public void traceStepVoid(String spanName, Span parentSpan, String input, Runnable work) {
        traceStep(spanName, parentSpan, input, () -> { work.run(); return "done"; });
    }

    /**
     * Execute a step, record latency, and set input/output + span kind.
     */
    public <T> T traceStepWithLatency(String spanName, Span parentSpan, String spanKind,
                                       String input, LatencySetter latencySetter, Supplier<T> work) {
        Span childSpan = tracer.spanBuilder(spanName)
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        childSpan.setAttribute("langsmith.span.kind", spanKind);
        if (input != null) childSpan.setAttribute("input.value", truncate(input));
        long start = System.currentTimeMillis();
        try (Scope ignored = childSpan.makeCurrent()) {
            T result = work.get();
            long latencyMs = System.currentTimeMillis() - start;
            childSpan.setAttribute("latency_ms", latencyMs);
            latencySetter.set(latencyMs);
            if (result != null) childSpan.setAttribute("output.value", truncate(result.toString()));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            latencySetter.set(latencyMs);
            childSpan.setStatus(StatusCode.ERROR, e.getMessage());
            childSpan.recordException(e);
            throw e;
        } finally {
            childSpan.end();
        }
    }

    /**
     * Trace an LLM call with gen_ai attributes for LangSmith.
     */
    public String traceLlmCall(String spanName, Span parentSpan, PipelineMetrics metrics,
                                String promptText, Supplier<String> llmCall) {
        Span childSpan = tracer.spanBuilder(spanName)
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        childSpan.setAttribute("langsmith.span.kind", "llm");
        childSpan.setAttribute("gen_ai.system", "Groq");
        childSpan.setAttribute("gen_ai.request.model", "llama-3.3-70b-versatile");
        if (promptText != null) {
            childSpan.setAttribute("gen_ai.prompt.0.role", "user");
            childSpan.setAttribute("gen_ai.prompt.0.content", truncate(promptText));
        }
        long start = System.currentTimeMillis();
        try (Scope ignored = childSpan.makeCurrent()) {
            String result = llmCall.get();
            long latencyMs = System.currentTimeMillis() - start;
            childSpan.setAttribute("latency_ms", latencyMs);
            metrics.llmLatencyMs += latencyMs;

            int estimatedInputTokens = promptText != null ? (int) Math.ceil(promptText.length() / 4.0) : 0;
            int estimatedOutputTokens = (int) Math.ceil(result.length() / 4.0);
            metrics.recordLlmCall(estimatedInputTokens, estimatedOutputTokens);

            childSpan.setAttribute("gen_ai.usage.input_tokens", estimatedInputTokens);
            childSpan.setAttribute("gen_ai.usage.output_tokens", estimatedOutputTokens);
            childSpan.setAttribute("gen_ai.completion.0.role", "assistant");
            childSpan.setAttribute("gen_ai.completion.0.content", truncate(result));

            return result;
        } catch (Exception e) {
            childSpan.setStatus(StatusCode.ERROR, e.getMessage());
            childSpan.recordException(e);
            throw e;
        } finally {
            childSpan.end();
        }
    }

    /**
     * Trace a retriever step with retrieval.documents attributes for LangSmith.
     */
    public String traceRetrieval(String spanName, Span parentSpan, String query,
                                  LatencySetter latencySetter, Supplier<String> work) {
        Span childSpan = tracer.spanBuilder(spanName)
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        childSpan.setAttribute("langsmith.span.kind", "retriever");
        childSpan.setAttribute("input.value", truncate(query));
        long start = System.currentTimeMillis();
        try (Scope ignored = childSpan.makeCurrent()) {
            String result = work.get();
            long latencyMs = System.currentTimeMillis() - start;
            childSpan.setAttribute("latency_ms", latencyMs);
            latencySetter.set(latencyMs);
            childSpan.setAttribute("output.value", truncate(result));
            childSpan.setAttribute("retrieval.documents.0.document.content", truncate(result));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            latencySetter.set(latencyMs);
            childSpan.setStatus(StatusCode.ERROR, e.getMessage());
            childSpan.recordException(e);
            throw e;
        } finally {
            childSpan.end();
        }
    }

    public void finalizeRootSpan(Span rootSpan, PipelineMetrics metrics, String domain,
                                  String confidence, boolean fallbackFlag) {
        rootSpan.setAttribute("output.value",
                String.format("{\"domain\":\"%s\",\"confidence\":\"%s\",\"cache_hit\":%s,\"llm_calls\":%d}",
                        domain, confidence, metrics.cacheHit, metrics.llmCalls.get()));

        AttributesBuilder builder = Attributes.builder()
                .put("domain", domain != null ? domain : "UNKNOWN")
                .put("cache_hit", metrics.cacheHit)
                .put("llm_calls", metrics.llmCalls.get())
                .put("total_latency_ms", metrics.totalLatencyMs)
                .put("is_misclassified", metrics.isMisclassified)
                .put("confidence", confidence != null ? confidence : "UNKNOWN")
                .put("fallback_flag", fallbackFlag)
                .put("input_tokens", metrics.inputTokens.get())
                .put("output_tokens", metrics.outputTokens.get())
                .put("cost_usd", metrics.costUsd)
                .put("cache_latency_ms", metrics.cacheLatencyMs)
                .put("embedding_latency_ms", metrics.embeddingLatencyMs)
                .put("retrieval_latency_ms", metrics.retrievalLatencyMs)
                .put("reranking_latency_ms", metrics.rerankingLatencyMs)
                .put("llm_latency_ms", metrics.llmLatencyMs)
                .put("grounding_latency_ms", metrics.groundingLatencyMs);

        // Attach prompt version metadata for LangSmith tracking
        if (metrics.promptVersions != null) {
            metrics.promptVersions.forEach((name, version) ->
                    builder.put("prompt." + name + ".version", version));
        }

        rootSpan.setAllAttributes(builder.build());

        if (metrics.ragasAnswerRelevancy != null) {
            rootSpan.setAttribute("ragas.answer_relevancy", metrics.ragasAnswerRelevancy);
            rootSpan.setAttribute("ragas.faithfulness", metrics.ragasFaithfulness);
            rootSpan.setAttribute("ragas.context_precision", metrics.ragasContextPrecision);
            rootSpan.setAttribute("ragas.context_recall", metrics.ragasContextRecall);
        }

        rootSpan.end();
    }

    static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
    }

    @FunctionalInterface
    public interface LatencySetter {
        void set(long latencyMs);
    }
}
