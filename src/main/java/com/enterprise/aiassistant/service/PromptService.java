package com.enterprise.aiassistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.aiassistant.entity.PromptTemplate;
import com.enterprise.aiassistant.repository.PromptTemplateRepository;

import jakarta.annotation.PostConstruct;

/**
 * Prompt management service with DB-backed versioning.
 *
 * Architecture:
 *   - Prompts are stored in prompt_templates table with name, version, content, active flag
 *   - Only ONE version per prompt name can be active at a time
 *   - On startup: loads active prompts from DB into in-memory cache
 *   - If DB is empty (first boot): seeds from classpath .st files as version 1
 *   - Dynamic reload: call refresh() to reload from DB without restart
 *   - Fallback: if DB fails at runtime, serves from in-memory cache (last-known-good)
 *
 * LangSmith integration:
 *   - Each prompt's active version is tracked in span metadata as "prompt.{name}.version"
 *   - Enables filtering traces by prompt version in LangSmith dashboard
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    private final PromptTemplateRepository repository;
    private final ResourceLoader resourceLoader;

    /** In-memory cache: name → active PromptTemplate (content + version). */
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    @Value("${app.prompts.chat-system}")
    private String chatSystemPath;

    @Value("${app.prompts.domain-router}")
    private String domainRouterPath;

    @Value("${app.prompts.query-rewriter}")
    private String queryRewriterPath;

    @Value("${app.prompts.ambiguity-check}")
    private String ambiguityCheckPath;

    public PromptService(PromptTemplateRepository repository, ResourceLoader resourceLoader) {
        this.repository = repository;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        seedIfEmpty();
        loadActivePrompts();
    }

    /**
     * Get the active prompt content for a given name.
     * Returns from in-memory cache (loaded from DB).
     */
    public String getPrompt(String name) {
        PromptTemplate pt = cache.get(name);
        if (pt == null) {
            throw new IllegalStateException("No active prompt found for: " + name);
        }
        return pt.getContent();
    }

    /**
     * Get the active version number for a prompt.
     */
    public int getActiveVersion(String name) {
        PromptTemplate pt = cache.get(name);
        return pt != null ? pt.getVersion() : 0;
    }

    /**
     * Get all active prompt versions as a map (name → version).
     * Used for metadata propagation to traces, cache, and DB logs.
     */
    public Map<String, Integer> getActiveVersions() {
        Map<String, Integer> versions = new ConcurrentHashMap<>();
        cache.forEach((name, pt) -> versions.put(name, pt.getVersion()));
        return versions;
    }

    /**
     * Create a new prompt version. Does NOT activate it automatically.
     */
    @Transactional
    public PromptTemplate createVersion(String name, String content, String description) {
        int nextVersion = repository.findByNameOrderByVersionDesc(name).stream()
                .findFirst()
                .map(pt -> pt.getVersion() + 1)
                .orElse(1);

        PromptTemplate pt = new PromptTemplate(name, nextVersion, content, false, description);
        PromptTemplate saved = repository.save(pt);
        log.info("Created prompt '{}' version {} (inactive)", name, nextVersion);
        return saved;
    }

    /**
     * Activate a specific version. Deactivates all other versions of that prompt.
     */
    @Transactional
    public void activateVersion(String name, int version) {
        repository.deactivateAllByName(name);
        PromptTemplate pt = repository.findByNameAndVersion(name, version)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + name + " v" + version));
        pt.setActive(true);
        repository.save(pt);
        cache.put(name, pt);
        log.info("Activated prompt '{}' version {}", name, version);
    }

    /**
     * Reload all active prompts from DB into memory.
     * Called on startup and can be triggered via API for hot-reload.
     */
    public void refresh() {
        loadActivePrompts();
        log.info("Prompt cache refreshed — {} active prompts loaded", cache.size());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void loadActivePrompts() {
        try {
            repository.findByActiveTrue().forEach(pt -> cache.put(pt.getName(), pt));
            log.info("Loaded {} active prompts from DB: {}", cache.size(), cache.keySet());
        } catch (Exception e) {
            log.warn("Failed to load prompts from DB — using cached values: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (repository.count() > 0) {
            log.info("Prompt templates already seeded in DB — skipping");
            return;
        }

        log.info("Seeding prompt templates from classpath files...");
        seedPrompt("chat-system", chatSystemPath);
        seedPrompt("domain-router", domainRouterPath);
        seedPrompt("query-rewriter", queryRewriterPath);
        seedPrompt("ambiguity-check", ambiguityCheckPath);
    }

    private void seedPrompt(String name, String classpathPath) {
        try {
            String content = resourceLoader.getResource(classpathPath)
                    .getContentAsString(StandardCharsets.UTF_8);
            PromptTemplate pt = new PromptTemplate(name, 1, content, true, "Initial seed from classpath");
            repository.save(pt);
            log.info("Seeded prompt '{}' v1 from {}", name, classpathPath);
        } catch (IOException e) {
            log.error("Failed to seed prompt '{}' from {}: {}", name, classpathPath, e.getMessage());
        }
    }
}
