package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.entity.ChatMessage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;
    private final MemoryService memoryService;
    private final DomainRouterService domainRouter;

    private static final String SYSTEM_PROMPT = """
            You are a helpful enterprise knowledge assistant. When given document excerpts and a user query, \
            you MUST provide a detailed answer based on the excerpts. Summarize, explain, and quote the \
            relevant information from the document excerpts. Always cite the source document name. \
            Be thorough and professional. If no document excerpts are provided, let the user know \
            that no relevant documents were found for their query.
            """;

    public ChatService(ChatLanguageModel chatModel,
                       EmbeddingModel embeddingModel,
                       Map<Domain, EmbeddingStore<TextSegment>> domainStores,
                       MemoryService memoryService,
                       DomainRouterService domainRouter) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.domainStores = domainStores;
        this.memoryService = memoryService;
        this.domainRouter = domainRouter;
    }

    public ChatResponse chat(ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        // 1. Route question to a domain using the LLM
        Domain domain = domainRouter.route(request.question());
        log.info("Question routed to domain: {}", domain);

        EmbeddingStore<TextSegment> store = domainStores.get(domain);

        // 2. Load conversation history (sliding window: last 10 messages)
        List<ChatMessage> history = memoryService.getRecentMessages(conversationId);

        // 3. RAG — embed the question and search the domain's Pinecone namespace
        Embedding questionEmbedding = embeddingModel.embed(request.question()).content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(5)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = store.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches().stream()
                .filter(m -> m.score() >= 0.7)
                .collect(Collectors.toList());
        log.info("Pinecone search in domain [{}]: found {} matching chunks (after score>=0.7 filter)", domain, matches.size());
        if (!matches.isEmpty()) {
            matches.forEach(m -> log.debug("  score={} source={} text={}",
                    String.format("%.4f", m.score()),
                    m.embedded().metadata().toMap().get("source"),
                    m.embedded().text().substring(0, Math.min(100, m.embedded().text().length()))));
        }

        // 4. Build user message with context (system instructions sent separately via SystemMessage role)
        String context = buildContext(matches);
        String conversationHistory = buildHistory(history);
        String userContent = """
                Based on the following document excerpts, answer this query: "%s"

                %s
                [Conversation history]
                %s
                """.formatted(request.question(), context, conversationHistory);

        log.debug("Full user message to LLM:\n{}", userContent);

        // 5. Call LLM with proper system/user roles
        String answer;
        try {
            var messages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            messages.add(UserMessage.from(userContent));
            answer = chatModel.generate(messages).content().text();
            log.info("LLM answered successfully for domain [{}]", domain);
        } catch (Exception e) {
            log.error("LLM call failed [{}]: {}", e.getClass().getName(), e.getMessage(), e);
            answer = "Sorry, the AI service is temporarily unavailable. Please try again shortly.";
        }

        // 6. Persist messages to memory
        memoryService.saveMessage(conversationId, "user", request.question());
        memoryService.saveMessage(conversationId, "assistant", answer);

        // 7. Build response with sources, confidence, and routed domain
        List<String> sources = matches.stream()
                .filter(match -> match.embedded() != null)
                .map(match -> {
                    Object source = match.embedded().metadata().toMap().get("source");
                    return source != null ? source.toString() : "unknown";
                })
                .distinct()
                .collect(Collectors.toList());

        String confidence = matches.isEmpty() ? "LOW"
                : matches.size() >= 3 ? "HIGH" : "MEDIUM";

        return new ChatResponse(answer, confidence, sources, domain.name());
    }

    private String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches.isEmpty()) {
            return "[No relevant document excerpts found for this query.]\n\n";
        }
        StringBuilder sb = new StringBuilder("Relevant document excerpts:\n\n");
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            Object source = match.embedded().metadata().toMap().get("source");
            sb.append("[Excerpt %d] (source: %s)\n%s\n\n"
                    .formatted(i + 1, source != null ? source : "unknown", match.embedded().text()));
        }
        return sb.toString();
    }

    private String buildHistory(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "No prior conversation.";
        }
        return history.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
