package com.enterprise.aiassistant.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.DomainRoutingResult;
import com.enterprise.aiassistant.dto.PipelineMetrics;
import com.enterprise.aiassistant.entity.DocumentChunk;
import com.enterprise.aiassistant.entity.QueryLog;
import com.enterprise.aiassistant.exception.ChatProcessingException;
import com.enterprise.aiassistant.repository.ChatMessageRepository;
import com.enterprise.aiassistant.repository.QueryLogRepository;
import com.enterprise.aiassistant.repository.VectorSearchRepository;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Orchestrates the full chat request pipeline:
 *
 *   rag_pipeline
 *    ├── domain_routing          (parallel with embedding)
 *    ├── embedding_generation    (parallel with routing — compute once, reuse everywhere)
 *    ├── ambiguity_detection     (fast model, skip cache if flagged)
 *    ├── semantic_cache_lookup   (KNN=3, skipped if ambiguous)
 *    ├── retrieval_vector        (pgvector HNSW, uses pre-computed embedding)
 *    ├── retrieval_bm25          (PostgreSQL FTS)
 *    ├── fusion_rrf              (Reciprocal Rank Fusion)
 *    ├── reranking               (top-K selection)
 *    ├── llm_generation          (~1.2s)
 *    │     └── llm_call
 *    ├── grounding_check
 *    ├── response_build
 *    └── ragas_evaluation        (async, non-blocking)
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern SOURCE_PATTERN = Pattern.compile("source:\\s*(.+?)\\s*\\n");

    @Value("${app.self-consistency.enabled:false}")
    private boolean selfConsistencyEnabled;

    @Value("${app.self-consistency.samples:3}")
    private int selfConsistencySamples;

    @Value("${app.semantic-cache.enabled:true}")
    private boolean semanticCacheEnabled;

    private final KnowledgeBaseTools knowledgeBaseTools;
    private final MemoryService memoryService;
    private final DomainRouterService domainRouter;
    private final QueryAmbiguityDetector ambiguityDetector;
    private final AmbiguityLlmChecker ambiguityLlmChecker;
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final SemanticCacheService semanticCache;
    private final ChatMessageRepository chatMessageRepository;
    private final QueryLogRepository queryLogRepository;
    private final Executor pipelineExecutor;
    private final TracingService tracingService;
    private final RagasClient ragasClient;
    private final PromptService promptService;
    private final GuardrailsClient guardrailsClient;

    public ChatService(KnowledgeBaseTools knowledgeBaseTools,
                       MemoryService memoryService,
                       DomainRouterService domainRouter,
                       QueryAmbiguityDetector ambiguityDetector,
                       AmbiguityLlmChecker ambiguityLlmChecker,
                       ChatLanguageModel chatLanguageModel,
                       StreamingChatLanguageModel streamingChatModel,
                       EmbeddingModel embeddingModel,
                       SemanticCacheService semanticCache,
                       ChatMessageRepository chatMessageRepository,
                       QueryLogRepository queryLogRepository,
                       @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                       TracingService tracingService,
                       RagasClient ragasClient,
                       PromptService promptService,
                       GuardrailsClient guardrailsClient) {
        this.knowledgeBaseTools = knowledgeBaseTools;
        this.memoryService = memoryService;
        this.domainRouter = domainRouter;
        this.ambiguityDetector = ambiguityDetector;
        this.ambiguityLlmChecker = ambiguityLlmChecker;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.semanticCache = semanticCache;
        this.chatMessageRepository = chatMessageRepository;
        this.queryLogRepository = queryLogRepository;
        this.pipelineExecutor = pipelineExecutor;
        this.tracingService = tracingService;
        this.ragasClient = ragasClient;
        this.promptService = promptService;
        this.guardrailsClient = guardrailsClient;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "chatFallback")
    @Retry(name = "llm")
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        PipelineMetrics metrics = new PipelineMetrics();

        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        String originalQuestion = request.question();

        // ── Guard 0: Prompt Injection Detection (FIRST — before any processing) ──
        GuardrailsClient.InjectionResult injectionCheck = guardrailsClient.detectPromptInjection(originalQuestion);
        if (injectionCheck.isInjection()) {
            log.warn("[{}] GUARDRAIL BLOCKED: Prompt injection detected — type={}, confidence={}, reason={}",
                    conversationId, injectionCheck.attackType(), injectionCheck.confidence(), injectionCheck.reason());
            return new ChatResponse(
                    "I'm unable to process this request. Your input was flagged by our security system.",
                    "BLOCKED", List.of(), "SECURITY", false, "BLOCKED", List.of());
        }

        Span rootSpan = tracingService.startRootSpan(originalQuestion);
        metrics.promptVersions = promptService.getActiveVersions();

        try {
            // ── Step 1: domain_routing + embedding_generation (PARALLEL) ─────
            CompletableFuture<DomainRoutingResult> routingFuture = CompletableFuture.supplyAsync(
                    () -> tracingService.traceStep("domain_routing", rootSpan,
                            originalQuestion, () -> domainRouter.route(originalQuestion)),
                    pipelineExecutor);

            CompletableFuture<Embedding> embeddingFuture = CompletableFuture.supplyAsync(
                    () -> tracingService.traceStepWithLatency("embedding_generation", rootSpan,
                            "embedding", originalQuestion,
                            latency -> metrics.embeddingLatencyMs = latency,
                            () -> embeddingModel.embed(originalQuestion).content()),
                    pipelineExecutor);

            // Start history loads in parallel too
            CompletableFuture<String> historyFuture = CompletableFuture.supplyAsync(
                    () -> memoryService.getContext(conversationId), pipelineExecutor);
            CompletableFuture<Boolean> hasHistoryFuture = CompletableFuture.supplyAsync(
                    () -> chatMessageRepository.existsByConversationId(conversationId), pipelineExecutor);

            DomainRoutingResult routing = routingFuture.join();
            Embedding questionEmbedding = embeddingFuture.join();

            metrics.predictedDomain = routing.domain().name();
            metrics.finalDomain = routing.domain().name();

            // ── Step 2: Ambiguity detection (fast model) ─────────────────────
            boolean heuristicAmbiguous = false;
            boolean hasHistory = hasHistoryFuture.join();

            boolean vague = originalQuestion.strip().split("\\s+").length < 4
                    && !originalQuestion.matches(".*\\d+.*");
            if (!hasHistory && vague) {
                log.info("[{}] Vague question with no history — requesting clarification (fast path)", conversationId);
                metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                tracingService.finalizeRootSpan(rootSpan, metrics, "UNKNOWN", "LOW", true);
                return new ChatResponse("Your question is a bit vague. Could you add more detail?",
                        "LOW", List.of(), "UNKNOWN", true, "AWAITING_CLARIFICATION", List.of());
            }

            if (ambiguityDetector.isAmbiguous(originalQuestion, hasHistory)) {
                heuristicAmbiguous = true;
                String history = historyFuture.join();
                AmbiguityLlmChecker.AmbiguityCheckResult ambiguity =
                        tracingService.traceStep("ambiguity_detection", rootSpan,
                                originalQuestion, () -> ambiguityLlmChecker.check(originalQuestion, history));

                if (ambiguity.ambiguous()) {
                    String message = ambiguity.options().isEmpty()
                            ? "Could you please expand your question? I need a bit more context to help you."
                            : "Your question is ambiguous. Please choose one of the suggested refinements or rephrase:";
                    metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                    tracingService.finalizeRootSpan(rootSpan, metrics, "UNKNOWN", "LOW", true);
                    return new ChatResponse(message, "LOW", List.of(), "UNKNOWN", true,
                            "AWAITING_CLARIFICATION", ambiguity.options());
                }
                log.info("[{}] LLM determined question is unambiguous despite heuristic flag — continuing (cache skipped)", conversationId);
            }

            // ── Step 3: semantic_cache_lookup (skip if heuristic flagged) ────
            if (semanticCacheEnabled && !heuristicAmbiguous) {
                Optional<ChatResponse> cached = tracingService.traceStepWithLatency(
                        "semantic_cache_lookup", rootSpan, "tool", originalQuestion,
                        latency -> metrics.cacheLatencyMs = latency,
                        () -> semanticCache.lookup(questionEmbedding, routing.domain().name()));

                if (cached.isPresent()) {
                    metrics.cacheHit = true;
                    metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                    log.info("[{}] Semantic cache hit (domain={}) — skipping full pipeline",
                             conversationId, routing.domain());
                    logQueryWithMetrics(originalQuestion, routing.domain().name(), cached.get().answer(),
                            metrics, "HIGH", routing.fallback());
                    tracingService.finalizeRootSpan(rootSpan, metrics, routing.domain().name(),
                            cached.get().confidence(), routing.fallback());
                    return cached.get();
                }
            }

            // ── Step 4: retrieval_vector (uses pre-computed embedding!) ──────
            List<VectorSearchRepository.VectorSearchResult> vectorResults = tracingService.traceStep(
                    "retrieval_vector", rootSpan, originalQuestion,
                    () -> knowledgeBaseTools.vectorSearch(questionEmbedding.vector(), routing.domain().name()));

            // ── Step 5: retrieval_bm25 ──────────────────────────────────────
            List<DocumentChunk> bm25Results = tracingService.traceStep(
                    "retrieval_bm25", rootSpan, originalQuestion,
                    () -> knowledgeBaseTools.bm25Search(originalQuestion, routing.domain().name()));

            // ── Step 6: fusion_rrf ──────────────────────────────────────────
            List<KnowledgeBaseTools.RankedChunk> merged = tracingService.traceStep(
                    "fusion_rrf", rootSpan,
                    String.format("{\"vector_count\":%d,\"bm25_count\":%d}", vectorResults.size(), bm25Results.size()),
                    () -> knowledgeBaseTools.rrfMerge(vectorResults, bm25Results));

            // ── Step 7: reranking ───────────────────────────────────────────
            KnowledgeBaseTools.clearRetrievedContext();
            String retrieved = tracingService.traceStepWithLatency("reranking", rootSpan,
                    "chain", String.format("{\"candidates\":%d}", merged.size()),
                    latency -> metrics.rerankingLatencyMs = latency,
                    () -> knowledgeBaseTools.formatAndCapture(merged, routing.domain().name(), 5));
            String retrievedContext = KnowledgeBaseTools.drainRetrievedContext();

            if (retrieved.startsWith("No relevant documents found")) {
                String noInfoAnswer = "I could not find relevant information in the knowledge base for this question.";
                metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                logQueryWithMetrics(originalQuestion, routing.domain().name(), noInfoAnswer,
                        metrics, "LOW", routing.fallback());
                tracingService.finalizeRootSpan(rootSpan, metrics, routing.domain().name(), "LOW", routing.fallback());
                return new ChatResponse(noInfoAnswer, "LOW", List.of(), routing.domain().name(),
                        routing.fallback(), "COMPLETED", List.of());
            }

            String history = historyFuture.join();
            log.info("Question routed to domain: {} (fallback={})", routing.domain(), routing.fallback());

            // ── Guard: Indirect Injection Detection (retrieved context) ──────
            GuardrailsClient.IndirectInjectionResult indirectCheck =
                    guardrailsClient.detectIndirectInjection(retrievedContext, originalQuestion);
            if (indirectCheck.containsInjection()) {
                log.warn("[{}] GUARDRAIL: Indirect injection in retrieved context — type={}, confidence={}, reason={}",
                        conversationId, indirectCheck.attackType(), indirectCheck.confidence(), indirectCheck.reason());
                metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                tracingService.finalizeRootSpan(rootSpan, metrics, routing.domain().name(), "BLOCKED", routing.fallback());
                return new ChatResponse(
                        "I detected potentially manipulated content in the knowledge base results. "
                        + "For your safety, I cannot provide an answer based on this context. Please try rephrasing your question.",
                        "BLOCKED", List.of(), routing.domain().name(), routing.fallback(), "BLOCKED", List.of());
            }

            // ── Step 8: llm_generation (with child llm_call) ────────────────
            Span llmGenSpan = tracingService.startChildSpan("llm_generation", rootSpan, "chain", originalQuestion);
            String answer;
            try {
                answer = generateLlmAnswer(originalQuestion, history, retrieved, metrics, llmGenSpan);
                llmGenSpan.setAttribute("output.value", TracingService.truncate(answer));
            } catch (Exception e) {
                llmGenSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                llmGenSpan.end();
            }

            // ── Guard: Data Exfiltration Protection (response output) ────────
            GuardrailsClient.DataExfiltrationResult exfilCheck =
                    guardrailsClient.checkDataExfiltration(answer, originalQuestion);
            if (!exfilCheck.safe()) {
                log.warn("[{}] GUARDRAIL: Data exfiltration blocked — pii={}, secrets={}, issues={}",
                        conversationId, exfilCheck.containsPii(), exfilCheck.containsSecrets(), exfilCheck.issues());
                metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                tracingService.finalizeRootSpan(rootSpan, metrics, routing.domain().name(), "BLOCKED", routing.fallback());
                return new ChatResponse(
                        "I generated a response but it was blocked by our security system because it "
                        + "contained sensitive information that should not be disclosed. Please refine your question.",
                        "BLOCKED", List.of(), routing.domain().name(), routing.fallback(), "BLOCKED", List.of());
            }

            // ── Step 9+10: response_build (grounding runs async — label only, no decision) ──
            List<String> sources = extractSources(retrievedContext);
            ChatResponse response = tracingService.traceStep("response_build", rootSpan,
                    "{\"sources\":" + sources.size() + "}",
                    () -> {
                        memoryService.saveMessage(conversationId, "user", originalQuestion);
                        memoryService.saveMessage(conversationId, "assistant", answer);
                        ChatResponse resp = new ChatResponse(answer, "MEDIUM", sources,
                                routing.domain().name(), routing.fallback(), "COMPLETED", List.of());
                        if (semanticCacheEnabled && questionEmbedding != null) {
                            // ── Guard: Cache Poisoning Prevention ─────────────
                            GuardrailsClient.CachePoisonResult cacheCheck =
                                    guardrailsClient.checkCachePoison(originalQuestion, answer, "MEDIUM", routing.domain().name());
                            if (cacheCheck.safeToCache()) {
                                int pv = promptService.getActiveVersion("chat-system");
                                semanticCache.put(questionEmbedding, originalQuestion, routing.domain().name(), resp, pv);
                            } else {
                                log.warn("[{}] GUARDRAIL: Cache poisoning prevented — issues={}",
                                        conversationId, cacheCheck.issues());
                            }
                        }
                        return resp;
                    });

            metrics.totalLatencyMs = System.currentTimeMillis() - startTime;

            QueryLog savedLog = logQueryWithMetrics(originalQuestion, routing.domain().name(), answer,
                    metrics, "MEDIUM", routing.fallback());
            tracingService.finalizeRootSpan(rootSpan, metrics, routing.domain().name(),
                    "MEDIUM", routing.fallback());

            // ── Step 11: grounding_check (async — label only, updates DB after) ──
            final String evalQuestion = originalQuestion;
            final String evalAnswer = answer;
            final String evalContext = retrievedContext;
            final Long savedLogId = savedLog != null ? savedLog.getId() : null;
            final String domainName = routing.domain().name();
            final boolean fallbackFlag = routing.fallback();

            CompletableFuture.runAsync(() -> {
                try {
                    Span groundSpan = tracingService.startChildSpan("grounding_check", rootSpan, "chain",
                            "{\"question\":\"" + evalQuestion + "\",\"answer_length\":" + evalAnswer.length() + "}");
                    try {
                        long gStart = System.currentTimeMillis();
                        String confidence = groundingCheck(evalQuestion, evalAnswer, evalContext, metrics);
                        long gLatency = System.currentTimeMillis() - gStart;
                        metrics.groundingLatencyMs = gLatency;
                        groundSpan.setAttribute("output.value", "{\"confidence\":\"" + confidence + "\"}");
                        groundSpan.setAttribute("latency_ms", gLatency);
                        log.info("Grounding async done: confidence={}, latency={}ms", confidence, gLatency);
                    } catch (Exception e) {
                        groundSpan.setStatus(StatusCode.ERROR, e.getMessage());
                        log.warn("Async grounding check failed: {}", e.getMessage());
                    } finally {
                        groundSpan.end();
                    }
                } catch (Exception e) {
                    log.warn("Grounding span creation failed: {}", e.getMessage());
                }
            }, pipelineExecutor);

            // ── Step 12: ragas_evaluation (async, non-blocking) ─────────────
            CompletableFuture.runAsync(() -> {
                try {
                    String ragasInput = String.format(
                            "{\"question\":\"%s\",\"answer\":\"%s\",\"contexts_count\":%d}",
                            TracingService.truncate(evalQuestion),
                            TracingService.truncate(evalAnswer).replace("\"", "'"),
                            evalContext != null ? 1 : 0);
                    Span ragasSpan = tracingService.startChildSpan("ragas_evaluation", rootSpan, "chain", ragasInput);
                    try {
                        long rStart = System.currentTimeMillis();
                        RagasClient.RagasResult ragas = ragasClient.evaluate(
                                evalQuestion, evalAnswer,
                                evalContext != null ? List.of(evalContext) : List.of(), null);
                        long rLatency = System.currentTimeMillis() - rStart;
                        ragasSpan.setAttribute("latency_ms", rLatency);

                        if (ragas != null) {
                            String ragasOutput = String.format(
                                    "{\"faithfulness\":%.4f,\"answer_relevancy\":%.4f,\"context_precision\":%.4f,\"context_recall\":%.4f,\"latency_ms\":%d}",
                                    ragas.faithfulness() != null ? ragas.faithfulness() : 0.0,
                                    ragas.answerRelevancy() != null ? ragas.answerRelevancy() : 0.0,
                                    ragas.contextPrecision() != null ? ragas.contextPrecision() : 0.0,
                                    ragas.contextRecall() != null ? ragas.contextRecall() : 0.0,
                                    rLatency);
                            ragasSpan.setAttribute("output.value", ragasOutput);

                            if (savedLogId != null) {
                                queryLogRepository.findById(savedLogId).ifPresent(ql -> {
                                    ql.setRagasAnswerRelevancy(ragas.answerRelevancy());
                                    ql.setRagasFaithfulness(ragas.faithfulness());
                                    ql.setRagasContextPrecision(ragas.contextPrecision());
                                    ql.setRagasContextRecall(ragas.contextRecall());
                                    queryLogRepository.save(ql);
                                    log.info("RAGAS scores saved: faithfulness={}", ragas.faithfulness());
                                });
                            }
                        } else {
                            ragasSpan.setAttribute("output.value", "{\"status\":\"disabled_or_failed\"}");
                        }
                    } catch (Exception e) {
                        ragasSpan.setStatus(StatusCode.ERROR, e.getMessage());
                        ragasSpan.setAttribute("output.value", "{\"error\":\"" + e.getMessage() + "\"}");
                        log.warn("RAGAS evaluation failed: {}", e.getMessage());
                    } finally {
                        ragasSpan.end();
                    }
                } catch (Exception e) {
                    log.warn("RAGAS span creation failed: {}", e.getMessage());
                }
            }, pipelineExecutor);

            return response;

        } catch (Exception e) {
            rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            rootSpan.recordException(e);
            rootSpan.end();
            throw e;
        }
    }

    // ── Streaming chat ───────────────────────────────────────────────────────

    public SseEmitter chatStream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        pipelineExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            PipelineMetrics metrics = new PipelineMetrics();

            String conversationId = request.conversationId() != null
                    ? request.conversationId()
                    : UUID.randomUUID().toString();
            String originalQuestion = request.question();

            try {
                // ── Pre-LLM pipeline (same as chat()) ────────────────────────
                CompletableFuture<DomainRoutingResult> routingFuture = CompletableFuture.supplyAsync(
                        () -> domainRouter.route(originalQuestion), pipelineExecutor);
                CompletableFuture<Embedding> embeddingFuture = CompletableFuture.supplyAsync(
                        () -> embeddingModel.embed(originalQuestion).content(), pipelineExecutor);
                CompletableFuture<String> historyFuture = CompletableFuture.supplyAsync(
                        () -> memoryService.getContext(conversationId), pipelineExecutor);

                DomainRoutingResult routing = routingFuture.join();
                Embedding questionEmbedding = embeddingFuture.join();
                metrics.predictedDomain = routing.domain().name();
                metrics.finalDomain = routing.domain().name();

                // Cache lookup
                if (semanticCacheEnabled) {
                    Optional<ChatResponse> cached = semanticCache.lookup(questionEmbedding, routing.domain().name());
                    if (cached.isPresent()) {
                        metrics.cacheHit = true;
                        emitter.send(SseEmitter.event().data(cached.get().answer()));
                        emitter.send(SseEmitter.event().name("done").data(cached.get()));
                        emitter.complete();
                        return;
                    }
                }

                // Retrieval
                List<VectorSearchRepository.VectorSearchResult> vectorResults =
                        knowledgeBaseTools.vectorSearch(questionEmbedding.vector(), routing.domain().name());
                List<DocumentChunk> bm25Results =
                        knowledgeBaseTools.bm25Search(originalQuestion, routing.domain().name());
                List<KnowledgeBaseTools.RankedChunk> merged =
                        knowledgeBaseTools.rrfMerge(vectorResults, bm25Results);
                String retrieved = knowledgeBaseTools.formatAndCapture(merged, routing.domain().name(), 5);
                String retrievedContext = KnowledgeBaseTools.drainRetrievedContext();

                if (retrieved.startsWith("No relevant documents found")) {
                    String msg = "I could not find relevant information in the knowledge base for this question.";
                    emitter.send(SseEmitter.event().data(msg));
                    emitter.send(SseEmitter.event().name("done").data(
                            new ChatResponse(msg, "LOW", List.of(), routing.domain().name(),
                                    routing.fallback(), "COMPLETED", List.of())));
                    emitter.complete();
                    return;
                }

                String history = historyFuture.join();
                StringBuilder prompt = buildPrompt(originalQuestion, history, retrieved);
                String promptStr = prompt.toString();
                StringBuilder fullAnswer = new StringBuilder();

                final String domainName = routing.domain().name();
                final boolean fallback = routing.fallback();
                final String rCtx = retrievedContext;

                // ── Stream the LLM response ──────────────────────────────────
                streamingChatModel.generate(
                        List.of(UserMessage.from(promptStr)),
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                fullAnswer.append(token);
                                try {
                                    emitter.send(SseEmitter.event().data(token));
                                } catch (IOException ignored) {}
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
                                try {
                                    String answer = fullAnswer.toString();
                                    int estIn = (int) Math.ceil(promptStr.length() / 4.0);
                                    int estOut = (int) Math.ceil(answer.length() / 4.0);
                                    metrics.recordLlmCall(estIn, estOut);

                                    List<String> sources = extractSources(rCtx);

                                    memoryService.saveMessage(conversationId, "user", originalQuestion);
                                    memoryService.saveMessage(conversationId, "assistant", answer);

                                    if (semanticCacheEnabled) {
                                        ChatResponse resp = new ChatResponse(answer, "MEDIUM", sources,
                                                domainName, fallback, "COMPLETED", List.of());
                                        int pv = promptService.getActiveVersion("chat-system");
                                        semanticCache.put(questionEmbedding, originalQuestion, domainName, resp, pv);
                                    }

                                    metrics.totalLatencyMs = System.currentTimeMillis() - startTime;
                                    logQueryWithMetrics(originalQuestion, domainName, answer,
                                            metrics, "MEDIUM", fallback);

                                    ChatResponse chatResp = new ChatResponse(answer, "MEDIUM", sources,
                                            domainName, fallback, "COMPLETED", List.of());
                                    emitter.send(SseEmitter.event().name("done").data(chatResp));
                                    emitter.complete();

                                    // Async grounding check (label only — no decision)
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            String conf = groundingCheck(originalQuestion, answer, rCtx, metrics);
                                            log.info("Streaming grounding async done: confidence={}", conf);
                                        } catch (Exception ex) {
                                            log.warn("Streaming grounding failed: {}", ex.getMessage());
                                        }
                                    }, pipelineExecutor);
                                } catch (Exception e) {
                                    try { emitter.completeWithError(e); } catch (Exception ignored) {}
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                try { emitter.completeWithError(error); } catch (Exception ignored) {}
                            }
                        });
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    // ── LLM answer generation ────────────────────────────────────────────────

    private String generateLlmAnswer(String question, String history, String retrieved,
                                      PipelineMetrics metrics, Span parentSpan) {
        StringBuilder prompt = buildPrompt(question, history, retrieved);

        if (!selfConsistencyEnabled || selfConsistencySamples <= 1) {
            return tracingService.traceLlmCall("llm_call", parentSpan, metrics,
                    prompt.toString(), () -> chatLanguageModel.generate(prompt.toString()));
        }

        log.info("Self-consistency: {} independent samples", selfConsistencySamples);
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < selfConsistencySamples; i++) {
            int idx = i;
            answers.add(tracingService.traceLlmCall("llm_call_sample_" + idx, parentSpan, metrics,
                    prompt.toString(), () -> chatLanguageModel.generate(prompt.toString())));
        }

        StringBuilder selectorPrompt = new StringBuilder();
        selectorPrompt.append("These answers were independently generated for the same question.\n")
                .append("Pick the most accurate one based only on the retrieved documents.\n")
                .append("Return ONLY that answer text with no prefix.\n\n");
        for (int i = 0; i < answers.size(); i++) {
            selectorPrompt.append("Answer ").append(i + 1).append(":\n")
                    .append(answers.get(i)).append("\n\n");
        }

        try {
            return tracingService.traceLlmCall("llm_call_selector", parentSpan, metrics,
                    selectorPrompt.toString(), () -> chatLanguageModel.generate(selectorPrompt.toString()));
        } catch (Exception e) {
            log.warn("Consistency selection failed, returning first sample: {}", e.getMessage());
            return answers.get(0);
        }
    }

    private StringBuilder buildPrompt(String question, String history, String retrieved) {
        String systemPrompt = promptService.getPrompt("chat-system");
        StringBuilder prompt = new StringBuilder();
        prompt.append(systemPrompt).append("\n\n")
                .append("Question:\n").append(question).append("\n\n")
                .append("Conversation history:\n").append(history).append("\n\n")
                .append("Retrieved documents:\n").append(retrieved).append("\n\n")
                .append("Return only the final answer text for the user.");
        return prompt;
    }

    // ── DB logging ───────────────────────────────────────────────────────────

    private QueryLog logQueryWithMetrics(String question, String domain, String answer,
                                      PipelineMetrics metrics, String confidence, boolean fallback) {
        try {
            QueryLog ql = new QueryLog(question, domain, answer, metrics.cacheHit, metrics.totalLatencyMs);
            ql.setLlmCalls(metrics.llmCalls.get());
            ql.setInputTokens((int) metrics.inputTokens.get());
            ql.setOutputTokens((int) metrics.outputTokens.get());
            ql.setCostUsd(metrics.costUsd);
            ql.setPredictedDomain(metrics.predictedDomain);
            ql.setFinalDomain(metrics.finalDomain);
            ql.setMisclassified(metrics.isMisclassified);
            ql.setCacheLatencyMs(metrics.cacheLatencyMs);
            ql.setEmbeddingLatencyMs(metrics.embeddingLatencyMs);
            ql.setRetrievalLatencyMs(metrics.retrievalLatencyMs);
            ql.setRerankingLatencyMs(metrics.rerankingLatencyMs);
            ql.setLlmLatencyMs(metrics.llmLatencyMs);
            ql.setGroundingLatencyMs(metrics.groundingLatencyMs);
            ql.setRagasAnswerRelevancy(metrics.ragasAnswerRelevancy);
            ql.setRagasFaithfulness(metrics.ragasFaithfulness);
            ql.setRagasContextPrecision(metrics.ragasContextPrecision);
            ql.setRagasContextRecall(metrics.ragasContextRecall);
            if (metrics.promptVersions != null && metrics.promptVersions.containsKey("chat-system")) {
                ql.setPromptVersion(metrics.promptVersions.get("chat-system"));
            }
            return queryLogRepository.save(ql);
        } catch (Exception e) {
            log.warn("Failed to persist query log: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unused")
    private ChatResponse chatFallback(ChatRequest request, Exception ex) {
        log.error("LLM call failed after retries: {}", ex.getMessage(), ex);
        throw new ChatProcessingException("The AI service is temporarily unavailable. Please try again shortly.", ex);
    }

    // ── Grounding check ──────────────────────────────────────────────────────

    private String groundingCheck(String question, String answer, String retrievedContext,
                                    PipelineMetrics metrics) {
        if (retrievedContext == null || retrievedContext.isBlank()) {
            return "LOW";
        }

        String prompt = "Retrieved documents:\n" + retrievedContext + "\n\n" +
                "Question: " + question + "\n" +
                "Answer: " + answer + "\n\n" +
                "Is every factual claim in this answer supported by the retrieved documents? " +
                "Reply with one word on the first line: SUPPORTED, PARTIAL, or UNSUPPORTED.";

        try {
            String raw = chatLanguageModel.generate(prompt);
            int estInput = (int) Math.ceil(prompt.length() / 4.0);
            int estOutput = (int) Math.ceil(raw.length() / 4.0);
            metrics.recordLlmCall(estInput, estOutput);

            String verdict = raw.strip().split("\n")[0].strip().toUpperCase();
            String confidence = switch (verdict) {
                case "SUPPORTED"   -> "HIGH";
                case "PARTIAL"     -> "MEDIUM";
                case "UNSUPPORTED" -> "LOW";
                default            -> "MEDIUM";
            };
            log.info("Grounding: {} → confidence {}", verdict, confidence);
            return confidence;
        } catch (Exception e) {
            log.warn("Grounding check failed: {}", e.getMessage());
            return "MEDIUM";
        }
    }

    // ── Source extraction ────────────────────────────────────────────────────

    private List<String> extractSources(String retrievedContext) {
        if (retrievedContext == null || retrievedContext.isBlank()) return List.of();
        Matcher matcher = SOURCE_PATTERN.matcher(retrievedContext);
        return matcher.results()
                .map(m -> m.group(1).trim())
                .distinct()
                .collect(Collectors.toList());
    }
}
