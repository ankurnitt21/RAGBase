package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.entity.ChatMessage;
import com.enterprise.aiassistant.exception.ChatProcessingException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatAssistant chatAssistant;
    private final MemoryService memoryService;
    private final DomainRouterService domainRouter;

    public ChatService(ChatAssistant chatAssistant,
                       MemoryService memoryService,
                       DomainRouterService domainRouter) {
        this.chatAssistant = chatAssistant;
        this.memoryService = memoryService;
        this.domainRouter = domainRouter;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "chatFallback")
    @Retry(name = "llm")
    public ChatResponse chat(ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        Domain domain = domainRouter.route(request.question());
        log.info("Question routed to domain: {}", domain);

        List<ChatMessage> history = memoryService.getRecentMessages(conversationId);
        String conversationHistory = buildHistory(history);

        String answer = chatAssistant.chat(request.question(), conversationHistory);
        log.info("LLM answered for domain [{}]", domain);

        memoryService.saveMessage(conversationId, "user", request.question());
        memoryService.saveMessage(conversationId, "assistant", answer);

        return new ChatResponse(answer, "TOOL_BASED", List.of(), domain.name());
    }

    @SuppressWarnings("unused")
    private ChatResponse chatFallback(ChatRequest request, Exception ex) {
        log.error("LLM call failed after retries: {}", ex.getMessage(), ex);
        throw new ChatProcessingException("The AI service is temporarily unavailable. Please try again shortly.", ex);
    }

    private String buildHistory(List<ChatMessage> history) {
        if (history.isEmpty()) return "No prior conversation.";
        return history.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
