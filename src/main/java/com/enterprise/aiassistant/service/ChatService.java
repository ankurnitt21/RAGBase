package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.DomainRoutingResult;
import com.enterprise.aiassistant.exception.ChatProcessingException;
import com.enterprise.aiassistant.repository.ChatMessageRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the full chat request pipeline:
 *
 *   0.  Semantic cache lookup — embed the question; return immediately on a hit,
 *       saving all downstream LLM / DB / Pinecone calls.
 *
 *   1.  Parallel phase (all three tasks start concurrently on virtual threads):
 *         a. Full hybrid history load  (DB + optional LLM summarization — slowest)
 *         b. Cheap hasHistory check    (single SQL EXISTS query)
 *         c. Keyword domain routing    (pure CPU, no I/O)
 *
 *   2.  Ambiguity detection — uses hasHistory (from 1b); starts as soon as 1b
 *       completes, without waiting for the full history load (1a).
 *
 *   2a. Query rewrite (LLM) — fired only when ambiguous; waits for 1a, then
 *       re-fetches history with the rewritten question for better semantic recall.
 *
 *   3.  Answer generation — single pass or self-consistency sampling.
 *
 *   4.  Grounding check (SUPPORTED / PARTIAL / UNSUPPORTED → HIGH / MEDIUM / LOW).
 *
 *   5.  Source filename extraction from tool output.
 *
 *   6.  Conversation persistence and semantic cache population.
 *
 * LLM calls per request:
 *   Cache hit:                                  0  — skip everything
 *   Happy path (clear question, short history): 2  — answer + grounding
 *   Ambiguous question:                         3  — rewrite + answer + grounding
 *   Long conversation (summarization):          3  — summarization + answer + grounding
 *   Ambiguous + long conversation:              4  — rewrite + summarization + answer + grounding
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
    private final QueryRewriterService queryRewriter;
    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final SemanticCacheService semanticCache;
    private final ChatMessageRepository chatMessageRepository;
    private final Executor pipelineExecutor;

    public ChatService(KnowledgeBaseTools knowledgeBaseTools,
                       MemoryService memoryService,
                       DomainRouterService domainRouter,
                       QueryAmbiguityDetector ambiguityDetector,
                       QueryRewriterService queryRewriter,
                       ChatLanguageModel chatLanguageModel,
                       EmbeddingModel embeddingModel,
                       SemanticCacheService semanticCache,
                       ChatMessageRepository chatMessageRepository,
                       @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.knowledgeBaseTools = knowledgeBaseTools;
        this.memoryService = memoryService;
        this.domainRouter = domainRouter;
        this.ambiguityDetector = ambiguityDetector;
        this.queryRewriter = queryRewriter;
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.semanticCache = semanticCache;
        this.chatMessageRepository = chatMessageRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "chatFallback")
    @Retry(name = "llm")
    public ChatResponse chat(ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        String originalQuestion = request.question();

        // ── Step 0: Semantic cache — embed + lookup, return early on hit ──────
        // Embedding is cheap (~100 ms) and avoids all downstream I/O on a hit.
        Embedding questionEmbedding = null;
        if (semanticCacheEnabled) {
            questionEmbedding = embeddingModel.embed(originalQuestion).content();
            Optional<ChatResponse> cached = semanticCache.lookup(questionEmbedding);
            if (cached.isPresent()) {
                log.info("[{}] Semantic cache hit — skipping full pipeline", conversationId);
                return cached.get();
            }
        }

        // ── Step 1: Parallel phase ────────────────────────────────────────────
        // Two tasks start concurrently:
        //   historyFuture    — full hybrid context (DB + optional LLM summarization)
        //   hasHistoryFuture — single SQL EXISTS check, completes in a few ms
        //
        // Domain routing (Task C) is intentionally NOT started here yet.
        // If the question is ambiguous it will be re-run on the rewritten question
        // so that routedDomain in the response is accurate.
        // If the question is not ambiguous it is run immediately after hasHistory
        // is known (still effectively instant since it is pure CPU).
        CompletableFuture<String> historyFuture = CompletableFuture.supplyAsync(
                () -> memoryService.getHybridContext(conversationId, originalQuestion),
                pipelineExecutor);

        CompletableFuture<Boolean> hasHistoryFuture = CompletableFuture.supplyAsync(
                () -> chatMessageRepository.existsByConversationId(conversationId),
                pipelineExecutor);

        // ── Step 2: Ambiguity detection ───────────────────────────────────────
        // Unblocked — waits only for the fast EXISTS check (~5 ms), not the full
        // history load. Two sub-cases:
        //
        //   a) Ambiguous + no history → ask the user to expand the question.
        //      No LLM call, no Pinecone, no further processing.
        //
        //   b) Ambiguous + has history → wait for full history, rewrite via LLM,
        //      re-fetch history for the rewritten question, THEN run domain routing
        //      on the rewritten question so the result is meaningful.
        boolean hasHistory = hasHistoryFuture.join();
        String effectiveQuestion = originalQuestion;

        if (ambiguityDetector.isAmbiguous(originalQuestion, hasHistory)) {
            if (!hasHistory) {
                // No prior context to rewrite against — ask the user to clarify
                // rather than sending a meaningless question through the pipeline.
                log.info("[{}] Ambiguous question with no history — requesting clarification", conversationId);
                return new ChatResponse(
                        "Could you please expand your question? I need a bit more context to help you.",
                        "LOW", List.of(), "UNKNOWN", true);
            }

            // Has history — rewrite using LLM, then run domain routing on the result
            String history = historyFuture.join();
            log.info("[{}] Ambiguous question detected — rewriting with history context", conversationId);
            String rewritten = queryRewriter.rewrite(originalQuestion, history);

            if (!rewritten.equals(originalQuestion)) {
                effectiveQuestion = rewritten;
                // Re-fetch history with the rewritten question so semantic recall
                // inside getHybridContext uses the cleaner, self-contained query.
                final String q = effectiveQuestion;
                historyFuture = CompletableFuture.supplyAsync(
                        () -> memoryService.getHybridContext(conversationId, q),
                        pipelineExecutor);
            }
        }

        // ── Step 3: Domain routing on the effective (possibly rewritten) question
        // Running here — after any rewrite — ensures routedDomain reflects the
        // actual question content rather than the raw ambiguous fragment.
        DomainRoutingResult routing = domainRouter.route(effectiveQuestion);
        log.info("Question routed to domain: {} (fallback={})", routing.domain(), routing.fallback());

        String history = historyFuture.join();

        // ── Steps 3–5: Generate answer, grounding check, source extraction ────
        KnowledgeBaseTools.clearRetrievedContext();
        String answer = generateAnswer(effectiveQuestion, history, routing.domain().name());
        String retrievedContext = KnowledgeBaseTools.drainRetrievedContext();

        String confidence = groundingCheck(effectiveQuestion, answer, retrievedContext);
        List<String> sources = extractSources(retrievedContext);

        // ── Step 6: Persist original question (not rewritten) to memory ───────
        memoryService.saveMessage(conversationId, "user", originalQuestion);
        memoryService.saveMessage(conversationId, "assistant", answer);

        ChatResponse response = new ChatResponse(
                answer, confidence, sources, routing.domain().name(), routing.fallback());

        // ── Populate semantic cache for future identical/similar questions ─────
        if (semanticCacheEnabled && questionEmbedding != null) {
            semanticCache.put(questionEmbedding, originalQuestion, response);
        }

        return response;
    }

    @SuppressWarnings("unused")
    private ChatResponse chatFallback(ChatRequest request, Exception ex) {
        log.error("LLM call failed after retries: {}", ex.getMessage(), ex);
        throw new ChatProcessingException("The AI service is temporarily unavailable. Please try again shortly.", ex);
    }

    // ── Self-consistency ─────────────────────────────────────────────────────

    private String generateAnswer(String question, String history, String domain) {
        String retrieved = knowledgeBaseTools.searchKnowledgeBase(question, domain);
        if (retrieved.startsWith("No relevant documents found in domain [")) {
            return "I could not find relevant information in the knowledge base for this question.";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful enterprise knowledge assistant.\n")
                .append("Use ONLY the retrieved documents below to answer the question.\n")
                .append("If information is missing, say it is not available in the retrieved documents.\n")
                .append("Cite source names in your answer when stating facts.\n\n")
                .append("Question:\n").append(question).append("\n\n")
                .append("Conversation history:\n").append(history).append("\n\n")
                .append("Retrieved documents:\n").append(retrieved).append("\n\n")
                .append("Return only the final answer text for the user.");

        if (!selfConsistencyEnabled || selfConsistencySamples <= 1) {
            return chatLanguageModel.generate(prompt.toString());
        }

        log.info("Self-consistency: {} independent samples", selfConsistencySamples);
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < selfConsistencySamples; i++) {
            answers.add(chatLanguageModel.generate(prompt.toString()));
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
            return chatLanguageModel.generate(selectorPrompt.toString());
        } catch (Exception e) {
            log.warn("Consistency selection failed, returning first sample: {}", e.getMessage());
            return answers.get(0);
        }
    }

    // ── Grounding check ──────────────────────────────────────────────────────

    private String groundingCheck(String question, String answer, String retrievedContext) {
        if (retrievedContext == null || retrievedContext.isBlank()) {
            return "LOW";
        }

        String prompt = "Retrieved documents:\n" + retrievedContext + "\n\n" +
                "Question: " + question + "\n" +
                "Answer: " + answer + "\n\n" +
                "Is every factual claim in this answer supported by the retrieved documents? " +
                "Reply with one word on the first line: SUPPORTED, PARTIAL, or UNSUPPORTED.";

        try {
            String verdict = chatLanguageModel.generate(prompt)
                    .strip().split("\n")[0].strip().toUpperCase();
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
