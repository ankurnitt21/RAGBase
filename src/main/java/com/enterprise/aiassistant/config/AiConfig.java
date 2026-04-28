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
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableAsync;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.service.ChatAssistant;
import com.enterprise.aiassistant.service.KnowledgeBaseTools;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
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

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.chat-model}")
    private String chatModelName;

    @Value("${openai.chat-temperature}")
    private double chatTemperature;

    @Value("${openai.chat-max-tokens}")
    private int chatMaxTokens;

    @Value("${openai.embedding-model}")
    private String embeddingModelName;

    @Value("${app.prompts.chat-system}")
    private String chatSystemPromptPath;

    private final ResourceLoader resourceLoader;

    public AiConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Bean
    ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
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
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiApiKey)
                .modelName(embeddingModelName)
                .build();
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
