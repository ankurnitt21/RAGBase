package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.entity.ChatMessage;
import com.enterprise.aiassistant.repository.ChatMessageRepository;
import com.enterprise.aiassistant.util.TokenEstimator;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cached incremental summary for a single conversation.
 * Stores the summary text and the database id of the last message included,
 * so subsequent requests only summarise NEW overflow messages instead of
 * re-summarising the entire history on every turn.
 */
record IncrementalSummary(String text, long lastIncludedMessageId) {}

/**
 * Manages conversation history using a token-budget-based hybrid strategy.
 *
 * Storage: every message is persisted to PostgreSQL (durable) and also embedded
 * into a per-conversation InMemoryEmbeddingStore (ephemeral vector cache).
 *
 * getHybridContext() assembles the history string passed to the LLM:
 *
 *   Step 1 — Walk messages newest→oldest, collect verbatim until recentTokenBudget
 *             is exhausted. These become the "recent" block.
 *
 *   Step 2 — Remaining "older" messages are handled based on their total token size:
 *     a) olderTokens > summaryThresholdTokens  → LLM summarizes them (≤ summaryMaxTokens)
 *     b) olderTokens ≤ summaryThresholdTokens  → vector search picks semantically
 *        relevant older messages that fit within semanticTokenBudget tokens
 *
 *   Step 3 — If ALL messages fit within recentTokenBudget, return full verbatim
 *             history (no split, no overhead).
 *
 *   Cold-start (vector cache empty after restart): semantic retrieval skips gracefully;
 *   only the recent verbatim block is returned — no crash, no error.
 *
 * All four budgets are configurable via application.yml / env vars:
 *   MEMORY_RECENT_TOKENS, MEMORY_SEMANTIC_TOKENS,
 *   MEMORY_SUMMARY_THRESHOLD, MEMORY_SUMMARY_MAX
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Minimum cosine similarity required to inject an older message via semantic search. */
    private static final double RELEVANCE_MIN_SCORE = 0.6;

    /**
     * How many extra candidates to fetch from the vector store beyond the semantic
     * budget need — ensures we still have enough after filtering out recent messages.
     */
    private static final int SEMANTIC_FETCH_EXTRA = 10;

    /** Token budget for verbatim recent messages (newest first, always included). */
    @Value("${app.memory.recent-token-budget:1500}")
    private int recentTokenBudget;

    /** Token budget for semantically relevant older messages (vector search). */
    @Value("${app.memory.semantic-token-budget:800}")
    private int semanticTokenBudget;

    /**
     * If older messages (outside the recent window) exceed this many tokens,
     * summarize them instead of running semantic search.
     */
    @Value("${app.memory.summary-threshold-tokens:1500}")
    private int summaryThresholdTokens;

    /** Maximum tokens the generated (or fallback-truncated) summary may occupy. */
    @Value("${app.memory.summary-max-tokens:400}")
    private int summaryMaxTokens;

    private final ChatMessageRepository repository;
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;

    /** Per-conversation vector cache. Lost on restart; degrades gracefully to recency-only. */
    private final ConcurrentHashMap<String, InMemoryEmbeddingStore<TextSegment>> vectorCache =
            new ConcurrentHashMap<>();

    /**
     * Per-conversation incremental summary cache.
     * Stores the last generated summary and the id of the last message included,
     * so only genuinely new overflow messages are sent to the LLM each turn.
     * Lost on restart — regenerated from scratch on first post-restart request
     * that needs a summary (which is correct: full history is still in PostgreSQL).
     */
    private final ConcurrentHashMap<String, IncrementalSummary> summaryCache =
            new ConcurrentHashMap<>();

    public MemoryService(ChatMessageRepository repository,
                         EmbeddingModel embeddingModel,
                         ChatLanguageModel chatLanguageModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds the history context string to inject into the LLM prompt, bounded
     * by token budgets rather than fixed message counts.
     *
     * Budget layout (all configurable):
     *   recentTokenBudget    → verbatim newest messages
     *   semanticTokenBudget  → relevant older messages via vector search
     *   summaryThresholdTokens + summaryMaxTokens → summarisation gate
     */
    public String getHybridContext(String conversationId, String currentQuestion) {
        List<ChatMessage> all = repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (all.isEmpty()) return "No prior conversation.";

        // ── Step 1: Fill "recent" block newest → oldest within recentTokenBudget ──
        int recentUsed = 0;
        int splitIndex = 0; // default: all messages are "recent" (fits entirely in budget)

        for (int i = all.size() - 1; i >= 0; i--) {
            int t = TokenEstimator.estimate(messageText(all.get(i)));
            if (recentUsed + t > recentTokenBudget) {
                splitIndex = i + 1; // messages [splitIndex..end] fit; [0..splitIndex) overflow
                break;
            }
            recentUsed += t;
        }

        List<ChatMessage> recent = all.subList(splitIndex, all.size());
        List<ChatMessage> older  = all.subList(0, splitIndex);

        log.debug("[{}] Token split — recent {} msgs / {} tokens, older {} msgs",
                  conversationId, recent.size(), recentUsed, older.size());

        // All messages fit in recent budget → return full verbatim history
        if (older.isEmpty()) {
            return buildHistoryText(all);
        }

        // ── Step 2: Handle older messages ────────────────────────────────────
        int olderTotalTokens = older.stream()
                .mapToInt(m -> TokenEstimator.estimate(messageText(m)))
                .sum();

        StringBuilder context = new StringBuilder();

        if (olderTotalTokens > summaryThresholdTokens) {
            // Too much older content — compress to a bounded incremental summary
            String summary = getOrUpdateSummary(conversationId, older);
            context.append("[Summary of earlier conversation]\n").append(summary).append("\n\n");
            log.debug("[{}] Incremental summary injected ({} older msgs, {} tokens → ≤{} summary tokens)",
                      conversationId, older.size(), olderTotalTokens, summaryMaxTokens);
        } else {
            // Manageable older content — inject semantically relevant messages
            List<String> relevant = findRelevantOlderMessages(
                    conversationId, currentQuestion, recent, semanticTokenBudget);
            if (!relevant.isEmpty()) {
                context.append("[Relevant earlier messages]\n");
                relevant.forEach(r -> context.append(r).append("\n"));
                context.append("\n");
                log.debug("[{}] Injected {} semantically relevant older messages",
                          conversationId, relevant.size());
            }
        }

        // ── Step 3: Always append the recent verbatim block ──────────────────
        context.append("[Recent messages]\n");
        recent.forEach(m -> context.append(messageText(m)).append("\n"));

        return context.toString();
    }

    public void saveMessage(String conversationId, String role, String content) {
        repository.save(new ChatMessage(conversationId, role, content));
        embedAndCache(conversationId, role, content);
        log.debug("Saved {} message for conversation {}", role, conversationId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Semantic vector search over older messages, bounded by tokenBudget.
     * Returns messages in relevance order, skipping any already in the recent block,
     * stopping when the next candidate would exceed the token budget.
     */
    private List<String> findRelevantOlderMessages(String conversationId,
                                                    String question,
                                                    List<ChatMessage> recentMessages,
                                                    int tokenBudget) {
        InMemoryEmbeddingStore<TextSegment> store = vectorCache.get(conversationId);
        if (store == null) {
            log.debug("[{}] No vector cache (cold start after restart). Skipping semantic retrieval.",
                      conversationId);
            return List.of();
        }

        Set<String> recentTexts = recentMessages.stream()
                .map(this::messageText)
                .collect(Collectors.toSet());

        try {
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            // Fetch extra so that filtering out recent messages still leaves candidates
                            .maxResults(recentMessages.size() + SEMANTIC_FETCH_EXTRA)
                            .build()
            ).matches();

            List<String> result = new ArrayList<>();
            int usedTokens = 0;

            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match.score() < RELEVANCE_MIN_SCORE) continue;
                String text = match.embedded().text();
                if (recentTexts.contains(text)) continue;

                int t = TokenEstimator.estimate(text);
                if (usedTokens + t > tokenBudget) continue; // over budget — skip, try next

                result.add(text);
                usedTokens += t;
            }
            return result;

        } catch (Exception e) {
            log.warn("[{}] Semantic memory retrieval failed: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private void embedAndCache(String conversationId, String role, String content) {
        try {
            String text = role + ": " + content;
            Embedding embedding = embeddingModel.embed(text).content();
            vectorCache.computeIfAbsent(conversationId, k -> new InMemoryEmbeddingStore<>())
                       .add(embedding, TextSegment.from(text));
        } catch (Exception e) {
            log.warn("Failed to embed message for conversation {} — semantic retrieval will degrade: {}",
                     conversationId, e.getMessage());
        }
    }

    /**
     * Returns an up-to-date summary for the given overflow messages, using incremental
     * summarization to avoid re-processing already-summarized content on every turn.
     *
     * Algorithm:
     *   1. Look up the cached IncrementalSummary for this conversation.
     *   2. Identify messages that are NEW (id > lastIncludedMessageId in the cache).
     *   3. If no new messages exist, return the cached summary unchanged (zero LLM cost).
     *   4. If new messages exist and there is no prior summary, summarize all from scratch.
     *   5. If new messages exist and a prior summary exists, send both to the LLM with
     *      an instruction to extend/update the existing summary with the new messages.
     *   6. Truncate the result to summaryMaxTokens, persist in the in-memory cache,
     *      and return.
     *
     * Cold-start behaviour: the cache is empty after a restart, so the first call that
     * reaches this path generates a fresh summary from all older messages. Subsequent
     * calls in the same JVM lifetime are incremental.
     */
    private String getOrUpdateSummary(String conversationId, List<ChatMessage> olderMessages) {
        IncrementalSummary cached = summaryCache.get(conversationId);

        // Determine which messages are not yet covered by the cached summary
        List<ChatMessage> unsummarized;
        if (cached == null) {
            unsummarized = olderMessages;
        } else {
            unsummarized = olderMessages.stream()
                    .filter(m -> m.getId() != null && m.getId() > cached.lastIncludedMessageId())
                    .toList();
        }

        // Nothing new — return cached summary without any LLM call
        if (unsummarized.isEmpty() && cached != null) {
            log.debug("[{}] Incremental summary cache hit — no new messages to summarise", conversationId);
            return cached.text();
        }

        // Generate or extend the summary
        String updatedSummary;
        if (cached == null || cached.text().isBlank()) {
            log.debug("[{}] No prior summary — summarising {} messages from scratch",
                      conversationId, unsummarized.size());
            updatedSummary = summarizeFromScratch(olderMessages);
        } else {
            log.debug("[{}] Extending existing summary with {} new messages",
                      conversationId, unsummarized.size());
            updatedSummary = summarizeIncremental(cached.text(), unsummarized);
        }

        // Bound the result and cache it
        updatedSummary = truncateToTokens(updatedSummary, summaryMaxTokens);
        long lastId = olderMessages.get(olderMessages.size() - 1).getId();
        summaryCache.put(conversationId, new IncrementalSummary(updatedSummary, lastId));

        return updatedSummary;
    }

    /**
     * Cold-start path: asks the LLM to summarize a list of messages from scratch.
     * Falls back to character-level truncation if the LLM call fails.
     */
    private String summarizeFromScratch(List<ChatMessage> messages) {
        String historyText = buildHistoryText(messages);
        try {
            return chatLanguageModel.generate(
                    "Summarize the following conversation history in 3-5 concise sentences. " +
                    "Preserve key facts, decisions, and topics discussed. " +
                    "Do not add commentary or greetings.\n\n" + historyText
            );
        } catch (Exception e) {
            log.warn("Summarisation (from-scratch) LLM call failed, falling back to truncation: {}",
                     e.getMessage());
            return truncateToTokens(historyText, summaryMaxTokens);
        }
    }

    /**
     * Incremental path: extends an existing summary with a list of new messages.
     * If the LLM call fails, appends the new messages as plain text and truncates.
     */
    private String summarizeIncremental(String existingSummary, List<ChatMessage> newMessages) {
        try {
            return chatLanguageModel.generate(
                    "You have a running summary of a conversation:\n" +
                    existingSummary + "\n\n" +
                    "The following new messages have been added to the conversation:\n" +
                    buildHistoryText(newMessages) + "\n\n" +
                    "Update the summary to incorporate the new messages. " +
                    "Keep it 3-5 concise sentences. Preserve key facts and decisions. " +
                    "Do not add commentary or greetings."
            );
        } catch (Exception e) {
            log.warn("Incremental summarisation LLM call failed, appending and truncating: {}",
                     e.getMessage());
            return truncateToTokens(existingSummary + "\n" + buildHistoryText(newMessages), summaryMaxTokens);
        }
    }

    /**
     * Trims text to fit within maxTokens. Prefers cutting at a sentence boundary
     * so the truncated summary stays grammatically readable.
     */
    private String truncateToTokens(String text, int maxTokens) {
        if (TokenEstimator.estimate(text) <= maxTokens) return text;
        // Conservative char limit: use integer part of CHARS_PER_TOKEN (3) to stay inside budget
        int charLimit = (int) (maxTokens * Math.floor(TokenEstimator.CHARS_PER_TOKEN));
        String cut = text.substring(0, Math.min(charLimit, text.length()));
        int lastPeriod = cut.lastIndexOf('.');
        if (lastPeriod > cut.length() / 2) {
            cut = cut.substring(0, lastPeriod + 1);
        }
        return cut + " [...]";
    }

    private String buildHistoryText(List<ChatMessage> messages) {
        return messages.stream()
                .map(this::messageText)
                .collect(Collectors.joining("\n"));
    }

    private String messageText(ChatMessage m) {
        return m.getRole() + ": " + m.getContent();
    }
}
