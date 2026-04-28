package com.enterprise.aiassistant.util;

/**
 * Lightweight token estimator using character-count approximation.
 *
 * The cl100k_base tokenizer (used by gpt-4o-mini and gpt-4o) averages roughly
 * 4 chars/token for English text. This estimator uses a conservative ratio of
 * 3.5 chars/token to account for short words, punctuation, and mixed-script
 * text — intentionally over-estimating to reduce the risk of silently blowing
 * past a context-window budget.
 *
 * This is NOT a replacement for exact tokenization (tiktoken-java would give
 * exact counts), but it avoids adding a heavy dependency and is accurate enough
 * for token-budget-based history management and overflow detection.
 *
 * Usage:
 *   int tokens = TokenEstimator.estimate("Hello, how are you?"); // → 6
 *   int total  = TokenEstimator.estimateMessages(messages);
 */
public final class TokenEstimator {

    /**
     * Conservative chars-per-token ratio (cl100k_base averages ~4, we use 3.5
     * so we over-estimate slightly and stay safely inside budget).
     */
    public static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {}

    /** Estimates the token count of a single string. */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** Sums token estimates across an iterable of strings. */
    public static int estimateAll(Iterable<String> texts) {
        int total = 0;
        for (String t : texts) {
            total += estimate(t);
        }
        return total;
    }
}
