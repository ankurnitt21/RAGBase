package com.enterprise.aiassistant.service;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.DomainRoutingResult;

/**
 * Classifies an incoming question into one of the three knowledge domains
 * (HR / PRODUCT / AI) using a keyword-based classifier.
 *
 * No LLM call is made. The question is lowercased and checked for membership
 * in per-domain keyword sets. The domain with the most keyword hits wins.
 * Ties are broken by the order HR > PRODUCT > AI. If no keyword matches at
 * all, PRODUCT is returned with domainFallback=true.
 *
 * This replaces the previous LLM-based router, saving one LLM call per request
 * on the common path while maintaining the same fallback behaviour.
 */
@Service
public class DomainRouterService {

    private static final Logger log = LoggerFactory.getLogger(DomainRouterService.class);

    private static final Map<Domain, Set<String>> DOMAIN_KEYWORDS = Map.of(
        Domain.HR, Set.of(
            "leave", "pto", "vacation", "holiday", "absence", "salary", "payroll",
            "benefits", "bonus", "compensation", "employee", "staff", "workforce",
            "onboarding", "offboarding", "hiring", "recruitment", "interview",
            "handbook", "policy", "workplace", "performance", "review", "appraisal",
            "termination", "resignation", "hr", "human resources", "pension",
            "insurance", "health", "dental", "maternity", "paternity", "disciplinary",
            "grievance", "overtime", "remote work", "work from home"
        ),
        Domain.PRODUCT, Set.of(
            "product", "price", "pricing", "cost", "fee", "charge", "feature",
            "specification", "spec", "inventory", "stock", "availability", "order",
            "shipping", "delivery", "return", "refund", "warranty", "guarantee",
            "purchase", "buy", "catalog", "catalogue", "item", "sku", "model",
            "version", "release", "upgrade", "subscription", "plan", "tier",
            "discount", "promo", "offer", "demo", "trial"
        ),
        Domain.AI, Set.of(
            "ai", "artificial intelligence", "machine learning", "ml", "llm",
            "large language model", "neural", "neural network", "deep learning",
            "data science", "nlp", "natural language", "embedding", "vector",
            "rag", "retrieval", "fine-tuning", "fine tuning", "training", "inference",
            "transformer", "gpt", "bert", "model", "dataset", "algorithm",
            "classification", "regression", "clustering", "prompt", "token",
            "hallucination", "grounding", "attention", "encoder", "decoder"
        )
    );

    public DomainRoutingResult route(String question) {
        String lower = question.toLowerCase();

        int hrScore      = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.HR));
        int productScore = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.PRODUCT));
        int aiScore      = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.AI));

        log.debug("Domain keyword scores — HR:{} PRODUCT:{} AI:{} for question='{}'",
                  hrScore, productScore, aiScore, question);

        if (hrScore == 0 && productScore == 0 && aiScore == 0) {
            log.debug("No keyword match — falling back to PRODUCT");
            return new DomainRoutingResult(Domain.PRODUCT, true);
        }

        Domain domain;
        if (hrScore >= productScore && hrScore >= aiScore) {
            domain = Domain.HR;
        } else if (productScore >= aiScore) {
            domain = Domain.PRODUCT;
        } else {
            domain = Domain.AI;
        }

        log.info("Question routed to domain: {} (fallback=false)", domain);
        return new DomainRoutingResult(domain, false);
    }

    private static int countMatches(String lower, Set<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) count++;
        }
        return count;
    }
}
