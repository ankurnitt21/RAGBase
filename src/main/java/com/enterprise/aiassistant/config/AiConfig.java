package com.enterprise.aiassistant.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableAsync;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.service.ChatAssistant;
import com.enterprise.aiassistant.service.KnowledgeBaseTools;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;

/**
 * Wires all AI infrastructure beans: ChatLanguageModel (gpt-4o-mini),
 * EmbeddingModel (text-embedding-3-small), ChatAssistant (AiServices proxy
 * with tool registration and system prompt), and per-domain Pinecone embedding
 * stores with in-memory fallback when Pinecone is unreachable.
 */
@Configuration
@EnableAsync
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${pinecone.api-key}")
    private String pineconeApiKey;

    @Value("${pinecone.index-name}")
    private String pineconeIndexName;

    // Groq — chat LLM via OpenAI-compatible API
    @Value("${groq.api-key}")
    private String groqApiKey;

    @Value("${groq.base-url}")
    private String groqBaseUrl;

    @Value("${groq.chat-model}")
    private String chatModelName;

    @Value("${groq.chat-temperature}")
    private double chatTemperature;

    @Value("${groq.chat-max-tokens}")
    private int chatMaxTokens;

    @Value("${groq.fast-model}")
    private String fastModelName;

    // OpenAI — no longer needed for embeddings (using local ONNX model)

    @Value("${app.prompts.chat-system}")
    private String chatSystemPromptPath;

    private final ResourceLoader resourceLoader;

    public AiConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Bean
    @Primary
    ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(groqApiKey)
                .modelName(chatModelName)
                .temperature(chatTemperature)
                .maxTokens(chatMaxTokens)
                .build();
    }

    @Bean("fastChatModel")
    ChatLanguageModel fastChatModel() {
        log.info("Fast ambiguity model: {} (via Groq)", fastModelName);
        return OpenAiChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(groqApiKey)
                .modelName(fastModelName)
                .temperature(0.0)
                .maxTokens(1024)
                .build();
    }

    @Bean
    StreamingChatLanguageModel streamingChatLanguageModel() {
        log.info("Streaming chat model: {} (via Groq)", chatModelName);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(groqApiKey)
                .modelName(chatModelName)
                .temperature(chatTemperature)
                .maxTokens(chatMaxTokens)
                .build();
    }

    @Bean
    ChatAssistant chatAssistant(ChatLanguageModel chatLanguageModel, KnowledgeBaseTools knowledgeBaseTools) {
        String systemPrompt = loadPrompt(chatSystemPromptPath);
        log.info("Loaded chat system prompt from: {}", chatSystemPromptPath);
        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(knowledgeBaseTools)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel() {
        log.info("Using local ONNX embedding model: all-MiniLM-L6-v2 (384-dim)");
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    Map<Domain, EmbeddingStore<TextSegment>> domainEmbeddingStores() {
        Map<Domain, EmbeddingStore<TextSegment>> stores = new EnumMap<>(Domain.class);
        for (Domain domain : Domain.values()) {
            String namespace = domain.name().toLowerCase();
            try {
                EmbeddingStore<TextSegment> store = PineconeEmbeddingStore.builder()
                        .apiKey(pineconeApiKey)
                        .index(pineconeIndexName)
                        .nameSpace(namespace)
                        .build();
                stores.put(domain, store);
                log.info("Pinecone store initialized for domain [{}] namespace '{}'", domain, namespace);
            } catch (Exception e) {
                log.warn("Pinecone unavailable for domain [{}] ({}). Using in-memory fallback. " +
                         "Documents uploaded to this domain will NOT be persisted across restarts.",
                         domain, e.getMessage());
                stores.put(domain, new InMemoryEmbeddingStore<>());
            }
        }
        return stores;
    }

    /**
     * Virtual-thread executor for the chat pipeline's parallel phase.
     * Each task (history load, hasHistory check, domain routing) gets its own
     * virtual thread, so blocking I/O (database, OpenAI) does not consume
     * platform threads while waiting.
     */
    @Bean("pipelineExecutor")
    public Executor pipelineExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private String loadPrompt(String path) {
        try {
            Resource resource = resourceLoader.getResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt from: " + path, e);
        }
    }
}
