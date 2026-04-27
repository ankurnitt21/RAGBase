package com.enterprise.aiassistant.service;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.Domain;

import dev.langchain4j.model.chat.ChatLanguageModel;

@Service
public class DomainRouterService {

    private static final Logger log = LoggerFactory.getLogger(DomainRouterService.class);
    private final ChatLanguageModel chatModel;

    private static final String ROUTING_PROMPT = """
            You are a domain classifier. Given a user question, respond with EXACTLY one word — the domain that best matches the question.

            Available domains:
            - HR: Human resources, hiring, leave policy, payroll, benefits, employee handbook, workplace rules, onboarding, PTO
            - PRODUCT: Product catalog, pricing, features, specifications, inventory, orders, shipping, returns, warranties
            - AI: Artificial intelligence, machine learning, LLMs, neural networks, data science, NLP, embeddings, RAG, models

            Rules:
            - Respond with ONLY the domain name in uppercase: HR, PRODUCT, or AI
            - No explanation, no punctuation, no extra text
            - If unsure, pick the closest match

            User question: %s
            """;

    public DomainRouterService(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Retry(name = "llm", fallbackMethod = "routeFallback")
    public Domain route(String question) {
        String response = chatModel.generate(ROUTING_PROMPT.formatted(question));
        String cleaned = response.strip().toUpperCase().replaceAll("[^A-Z]", "");
        log.debug("Domain routing: question='{}' → raw='{}' → parsed='{}'", question, response, cleaned);
        return Domain.valueOf(cleaned);
    }

    @SuppressWarnings("unused")
    private Domain routeFallback(String question, Exception ex) {
        log.warn("Domain routing failed after retries, defaulting to PRODUCT: {}", ex.getMessage());
        return Domain.PRODUCT;
    }
}
