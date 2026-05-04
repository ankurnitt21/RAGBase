package com.enterprise.aiassistant.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.DomainRoutingResult;

import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;

/**
 * Domain classifier that uses the fast LLM (llama-3.1-8b-instant) to:
 *   1. Detect all relevant domains (HR / PRODUCT / AI) in the question.
 *   2. Split the question into domain-specific sub-questions.
 *
 * LLM output format â€” one line per domain:
 *   DOMAIN,sub-question text
 *
 * Example:
 *   HR,What leave benefits do employees get?
 *   PRODUCT,What is the AI product pricing?
 *
 * Fallback: if the LLM fails, route to PRODUCT with the original question unchanged.
 */
@Service
public class DomainRouterService {

    private static final Logger log = LoggerFactory.getLogger(DomainRouterService.class);

    private final ChatLanguageModel fastLlm;

    private static final String ROUTER_PROMPT =
            """
            You are a domain classifier and question splitter for an enterprise knowledge base.
            Three domains exist:
              HR      â€” employee leave, vacation, payroll, benefits, policies, hiring, onboarding, performance reviews
              PRODUCT â€” product catalog, pricing, features, inventory, orders, shipping, returns, warranties
              AI      â€” artificial intelligence, machine learning, LLMs, neural networks, NLP, embeddings, RAG, data science

            Task:
              1. Identify ALL relevant domains for the user question.
              2. For each domain, write the sub-question relevant to ONLY that domain.
              3. Return EXACTLY one line per domain in this format:
                   DOMAIN,sub-question text
                 - DOMAIN must be exactly: HR, PRODUCT, or AI (uppercase, no spaces)
                 - sub-question is the part of the original question that belongs to that domain
                 - If the full question belongs to one domain, return that domain with the full question

            Rules:
              - Output ONLY the lines. No explanation, no blank lines, no extra text.
              - One comma after the domain name; the rest is the sub-question text.

            Examples:
              Q: "What is the vacation leave policy?"
              HR,What is the vacation leave policy?

              Q: "What is the AI product pricing and what leave benefits do employees get?"
              HR,What leave benefits do employees get?
              PRODUCT,What is the AI product pricing?

              Q: "How does our RAG system work and what does the subscription plan cost?"
              PRODUCT,What does the subscription plan cost?
              AI,How does our RAG system work?

              Q: "What is the difference between fine-tuning and RAG, and which plan includes it?"
              PRODUCT,Which subscription plan includes fine-tuning or RAG?
              AI,What is the difference between fine-tuning and RAG?

            User question: %s
            """;

    public DomainRouterService(@Qualifier("fastChatModel") ChatLanguageModel fastLlm) {
        this.fastLlm = fastLlm;
    }

    @PostConstruct
    void init() {
        log.info("DomainRouterService ready â€” fast LLM splits question per domain");
    }

    public DomainRoutingResult route(String question) {
        try {
            String raw = fastLlm.generate(ROUTER_PROMPT.formatted(question)).strip();

            Map<Domain, String> domainQuestions = new LinkedHashMap<>();
            for (String line : raw.split("\\r?\\n")) {
                line = line.strip();
                if (line.isBlank()) continue;
                int commaIdx = line.indexOf(',');
                if (commaIdx <= 0) continue;
                String domainToken = line.substring(0, commaIdx).strip().toUpperCase();
                String subQuestion = line.substring(commaIdx + 1).strip();
                if (subQuestion.isBlank()) subQuestion = question;
                try {
                    domainQuestions.put(Domain.valueOf(domainToken), subQuestion);
                } catch (IllegalArgumentException ignored) {
                    // skip unrecognized token
                }
            }

            if (!domainQuestions.isEmpty()) {
                Domain primary = domainQuestions.keySet().iterator().next();
                log.info("Domain routing â€” domains={} splits=[{}]",
                        domainQuestions.keySet(),
                        raw.replace("\n", " | "));
                return new DomainRoutingResult(primary, domainQuestions, false);
            }

            log.warn("Fast LLM returned unrecognized output: '{}' â€” fallback to PRODUCT", raw);

        } catch (Exception e) {
            log.warn("Fast LLM domain routing failed: {} â€” fallback to PRODUCT", e.getMessage());
        }

        // Fallback: single domain PRODUCT with full question
        Map<Domain, String> fallback = new LinkedHashMap<>();
        fallback.put(Domain.PRODUCT, question);
        return new DomainRoutingResult(Domain.PRODUCT, fallback, true);
    }
}
