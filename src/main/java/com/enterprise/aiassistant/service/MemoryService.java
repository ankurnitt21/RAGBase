package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.entity.ChatMessage;
import com.enterprise.aiassistant.repository.ChatMessageRepository;
import com.enterprise.aiassistant.util.TokenEstimator;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
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
 * Manages conversation history using a token-budget-based strategy with
 * incremental summarization for overflow.
 *
 * Storage: every message is persisted to PostgreSQL (durable).
 *
 * getContext() assembles the history string passed to the LLM:
 *
 *   Step 1 — Walk messages newest→oldest, collect verbatim until recentTokenBudget
 *             is exhausted. These become the "recent" block.
 *
 *   Step 2 — If there are older messages beyond the recent window, they are always
 *             compressed via incremental LLM summarization (bounded to summaryMaxTokens).
 *             On a threshold the summary keeps updating incrementally — only new
 *             overflow messages are sent to the LLM, not the entire history.
 *
 *   Step 3 — If ALL messages fit within recentTokenBudget, return full verbatim
 *             history (no split, no overhead).
 *
 * Configurable via application.yml / env vars:
 *   MEMORY_RECENT_TOKENS, MEMORY_SUMMARY_MAX
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Token budget for verbatim recent messages (newest first, always included). */
    @Value("${app.memory.recent-token-budget:1500}")
    private int recentTokenBudget;

    /** Maximum tokens the generated (or fallback-truncated) summary may occupy. */
    @Value("${app.memory.summary-max-tokens:400}")
    private int summaryMaxTokens;

    private final ChatMessageRepository repository;
    private final ChatLanguageModel chatLanguageModel;

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
                         ChatLanguageModel chatLanguageModel) {
        this.repository = repository;
        this.chatLanguageModel = chatLanguageModel;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds the history context string to inject into the LLM prompt, bounded
     * by token budgets. Older messages are always summarized incrementally.
     */
    public String getContext(String conversationId) {
        List<ChatMessage> all = repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (all.isEmpty()) return "No prior conversation.";

        // ── Step 1: Fill "recent" block newest → oldest within recentTokenBudget ──
        int recentUsed = 0;
        int splitIndex = 0;

        for (int i = all.size() - 1; i >= 0; i--) {
            int t = TokenEstimator.estimate(messageText(all.get(i)));
            if (recentUsed + t > recentTokenBudget) {
                splitIndex = i + 1;
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

        // ── Step 2: Summarize older messages incrementally ───────────────────
        StringBuilder context = new StringBuilder();
        String summary = getOrUpdateSummary(conversationId, older);
        context.append("[Summary of earlier conversation]\n").append(summary).append("\n\n");
        log.debug("[{}] Incremental summary injected ({} older msgs → ≤{} summary tokens)",
                  conversationId, older.size(), summaryMaxTokens);

        // ── Step 3: Always append the recent verbatim block ──────────────────
        context.append("[Recent messages]\n");
        recent.forEach(m -> context.append(messageText(m)).append("\n"));

        return context.toString();
    }

    /**
     * @deprecated Use {@link #getContext(String)} instead. Kept for backward compatibility.
     */
    @Deprecated
    public String getHybridContext(String conversationId, String currentQuestion) {
        return getContext(conversationId);
    }

    public void saveMessage(String conversationId, String role, String content) {
        repository.save(new ChatMessage(conversationId, role, content));
        log.debug("Saved {} message for conversation {}", role, conversationId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

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
     */
    private String getOrUpdateSummary(String conversationId, List<ChatMessage> olderMessages) {
        IncrementalSummary cached = summaryCache.get(conversationId);

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

    private String truncateToTokens(String text, int maxTokens) {
        if (TokenEstimator.estimate(text) <= maxTokens) return text;
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
