package com.enterprise.aiassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * Rewrites an ambiguous user question into a fully self-contained question
 * using the recent conversation history as context.
 *
 * Prompts are loaded dynamically from the DB via PromptService (versioned, hot-reloadable).
 *
 * If the LLM call fails for any reason, the original question is returned unchanged
 * so the pipeline degrades gracefully rather than failing hard.
 */
@Service
public class QueryRewriterService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriterService.class);

    private final ChatLanguageModel chatModel;
    private final PromptService promptService;

    public QueryRewriterService(ChatLanguageModel chatModel, PromptService promptService) {
        this.chatModel = chatModel;
        this.promptService = promptService;
    }

    /**
     * Rewrites {@code question} into a self-contained question using {@code recentHistory}.
     * Returns the original question unchanged if the rewrite LLM call fails.
     */
    public String rewrite(String question, String recentHistory) {
        String rewriterPrompt = promptService.getPrompt("query-rewriter");
        String prompt = rewriterPrompt.formatted(recentHistory, question);
        try {
            String rewritten = chatModel.generate(prompt).strip();
            if (rewritten.isBlank()) {
                log.warn("Query rewriter returned blank output — using original question");
                return question;
            }
            log.debug("Query rewrite: '{}' → '{}' [prompt v{}]", question, rewritten,
                    promptService.getActiveVersion("query-rewriter"));
            return rewritten;
        } catch (Exception e) {
            log.warn("Query rewriter LLM call failed — using original question: {}", e.getMessage());
            return question;
        }
    }
}
