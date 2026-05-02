package com.enterprise.aiassistant.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.DomainRoutingResult;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;

/**
 * Two-phase domain classifier — fast and accurate, no LLM call required.
 *
 * Phase 1: Keyword match (instant, ~0 ms). If keywords produce a clear winner
 *          (score >= 2 or at least 2× the runner-up), that domain is returned.
 *
 * Phase 2: Embedding similarity (local ONNX, ~5-10 ms). The question is embedded
 *          and compared against pre-computed domain prototype embeddings using
 *          cosine similarity. The domain with the highest similarity wins.
 *
 * This gives the accuracy of an LLM-based router with the speed of a keyword
 * classifier. The detected domain is used consistently in Redis, Pinecone, and
 * PostgreSQL queries.
 */
@Service
public class DomainRouterService {

    private static final Logger log = LoggerFactory.getLogger(DomainRouterService.class);

    private final EmbeddingModel embeddingModel;

    /** Pre-computed prototype embeddings for each domain. */
    private final Map<Domain, Embedding> domainPrototypes = new EnumMap<>(Domain.class);

    /**
     * Prototype phrases that capture each domain's semantic space.
     * Concatenated and embedded once at startup.
     */
    private static final Map<Domain, String> DOMAIN_PROTOTYPES_TEXT = Map.of(
        Domain.HR,
            "employee leave policy vacation PTO salary payroll benefits compensation " +
            "hiring recruitment onboarding offboarding performance review appraisal " +
            "termination resignation HR human resources handbook workplace policy " +
            "pension insurance health dental maternity paternity remote work",
        Domain.PRODUCT,
            "product pricing cost feature specification inventory stock availability " +
            "order shipping delivery return refund warranty purchase catalog item " +
            "subscription plan discount offer demo trial upgrade version release",
        Domain.AI,
            "artificial intelligence machine learning deep learning neural network " +
            "LLM large language model NLP natural language processing embedding vector " +
            "RAG retrieval augmented generation fine-tuning training inference " +
            "transformer GPT BERT model prompt token hallucination grounding " +
            "reciprocal rank fusion RRF reranking semantic search hybrid search " +
            "cosine similarity vector database chunking text splitting agent " +
            "chain of thought reasoning knowledge graph ontology computer vision"
    );

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
            "transformer", "gpt", "bert", "dataset", "algorithm",
            "classification", "regression", "clustering", "prompt", "token",
            "hallucination", "grounding", "attention", "encoder", "decoder",
            "reranking", "rerank", "fusion", "rrf", "semantic search",
            "chunking", "text splitting", "agent", "chain of thought",
            "knowledge graph", "ontology", "computer vision", "diffusion"
        )
    );

    public DomainRouterService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    void initPrototypes() {
        for (Domain domain : Domain.values()) {
            String text = DOMAIN_PROTOTYPES_TEXT.get(domain);
            Embedding emb = embeddingModel.embed(text).content();
            domainPrototypes.put(domain, emb);
            log.info("Domain prototype embedding computed for [{}]", domain);
        }
    }

    public DomainRoutingResult route(String question) {
        String lower = question.toLowerCase();

        // ── Phase 1: Keyword match (instant) ─────────────────────────────────
        int hrScore      = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.HR));
        int productScore = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.PRODUCT));
        int aiScore      = countMatches(lower, DOMAIN_KEYWORDS.get(Domain.AI));

        int maxKeyword = Math.max(hrScore, Math.max(productScore, aiScore));
        int secondMax = List.of(hrScore, productScore, aiScore).stream()
                .sorted((a, b) -> b - a).skip(1).findFirst().orElse(0);

        // Strong keyword signal: clear winner with exclusive match or strong lead
        if (maxKeyword >= 1 && (secondMax == 0 || maxKeyword >= 2 * secondMax)) {
            Domain domain;
            if (hrScore == maxKeyword) domain = Domain.HR;
            else if (productScore == maxKeyword) domain = Domain.PRODUCT;
            else domain = Domain.AI;
            log.info("Domain routed via keywords: {} (hr={} product={} ai={})", domain, hrScore, productScore, aiScore);
            return new DomainRoutingResult(domain, false);
        }

        // ── Phase 2: Embedding similarity (local ONNX, ~5-10 ms) ────────────
        Embedding questionEmb = embeddingModel.embed(question).content();

        Domain bestDomain = Domain.PRODUCT;
        double bestSim = -1.0;

        for (Domain domain : Domain.values()) {
            double sim = cosineSimilarity(questionEmb.vector(), domainPrototypes.get(domain).vector());
            log.debug("Domain similarity — {} = {}", domain, String.format("%.4f", sim));
            if (sim > bestSim) {
                bestSim = sim;
                bestDomain = domain;
            }
        }

        // If best similarity is too low, treat as fallback
        boolean fallback = bestSim < 0.3;
        if (fallback) {
            bestDomain = Domain.PRODUCT;
            log.info("Domain routing fallback to PRODUCT (best similarity {} < 0.3)", String.format("%.4f", bestSim));
        } else {
            log.info("Domain routed via embedding: {} (similarity={})", bestDomain, String.format("%.4f", bestSim));
        }

        return new DomainRoutingResult(bestDomain, fallback);
    }

    private static int countMatches(String lower, Set<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) count++;
        }
        return count;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
