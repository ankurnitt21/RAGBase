package com.enterprise.aiassistant.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic detector that decides whether a user question is self-contained or
 * requires conversation history to be understood.
 *
 * A question is considered ambiguous when any of the following is true:
 *   1. It is very short (≤ 4 words), making it likely a follow-up fragment.
 *   2. It starts with a known follow-up phrase ("what about", "tell me more", …).
 *   3. It contains an anaphoric pronoun (it, that, this, they, those, there)
 *      without a clear noun in the same sentence that could serve as an antecedent.
 *
 * No LLM call is made here — all checks are regex / set lookups.
 */
@Component
public class QueryAmbiguityDetector {

    private static final int SHORT_MESSAGE_WORD_THRESHOLD = 4;

    /** Phrases that almost always reference something said earlier. */
    private static final Set<String> FOLLOWUP_PREFIXES = Set.of(
            "what about", "how about", "tell me more", "and then", "what then",
            "how so", "why so", "go on", "elaborate", "explain more",
            "can you elaborate", "more details", "give more", "what else",
            "what next", "anything else", "continue", "go ahead"
    );

    /** Anaphoric pronouns that require an antecedent from prior context. */
    private static final Pattern ANAPHORIC_PRONOUN = Pattern.compile(
            "\\b(it|that|this|they|those|them|there|these|such|the same)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Common nouns/determiners that anchor a pronoun locally (crude but fast). */
    private static final Pattern LOCAL_NOUN = Pattern.compile(
            "\\b(the|a|an|my|our|your|his|her|their)\\s+\\w+",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Returns true if the question cannot be understood without conversation history.
     *
     * @param question raw user question (never null)
     * @param hasHistory whether the conversation has any prior messages
     */
    public boolean isAmbiguous(String question, boolean hasHistory) {
        if (!hasHistory) return false; // no history to resolve against — treat as fresh

        String trimmed = question.strip();
        String lower   = trimmed.toLowerCase();

        // Rule 1 — very short message
        if (wordCount(trimmed) <= SHORT_MESSAGE_WORD_THRESHOLD) {
            return true;
        }

        // Rule 2 — starts with a known follow-up phrase
        for (String prefix : FOLLOWUP_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }

        // Rule 3 — contains anaphoric pronoun with no local noun anchor
        if (ANAPHORIC_PRONOUN.matcher(lower).find() && !LOCAL_NOUN.matcher(lower).find()) {
            return true;
        }

        return false;
    }

    private static int wordCount(String text) {
        if (text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
