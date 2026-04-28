package com.enterprise.aiassistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;

/**
 * Rewrites an ambiguous user question into a fully self-contained question
 * using the recent conversation history as context.
 *
 * This is an LLM call that runs only when QueryAmbiguityDetector.isAmbiguous()
 * returns true. The rewritten question replaces the raw input for all downstream
 * steps: domain classification, embedding, and retrieval.
 *
 * If the LLM call fails for any reason, the original question is returned unchanged
 * so the pipeline degrades gracefully rather than failing hard.
 */
@Service
public class QueryRewriterService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriterService.class);

    private final ChatLanguageModel chatModel;
    private final ResourceLoader resourceLoader;

    @Value("${app.prompts.query-rewriter}")
    private String queryRewriterPromptPath;

    private String rewriterPrompt;

    public QueryRewriterService(ChatLanguageModel chatModel, ResourceLoader resourceLoader) {
        this.chatModel = chatModel;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void loadPrompt() {
        try {
            Resource resource = resourceLoader.getResource(queryRewriterPromptPath);
            rewriterPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("Loaded query rewriter prompt from: {}", queryRewriterPromptPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load query rewriter prompt from: " + queryRewriterPromptPath, e);
        }
    }

    /**
     * Rewrites {@code question} into a self-contained question using {@code recentHistory}.
     * Returns the original question unchanged if the rewrite LLM call fails.
     *
     * @param question      raw ambiguous user question
     * @param recentHistory last few turns of conversation (plain text)
     * @return self-contained question safe to pass to domain routing and retrieval
     */
    public String rewrite(String question, String recentHistory) {
        String prompt = rewriterPrompt.formatted(recentHistory, question);
        try {
            String rewritten = chatModel.generate(prompt).strip();
            if (rewritten.isBlank()) {
                log.warn("Query rewriter returned blank output — using original question");
                return question;
            }
            log.debug("Query rewrite: '{}' → '{}'", question, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("Query rewriter LLM call failed — using original question: {}", e.getMessage());
            return question;
        }
    }
}
