package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.entity.ChatMessage;
import com.enterprise.aiassistant.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final ChatMessageRepository repository;

    public MemoryService(ChatMessageRepository repository) {
        this.repository = repository;
    }

    public List<ChatMessage> getRecentMessages(String conversationId) {
        List<ChatMessage> messages =
                repository.findTop10ByConversationIdOrderByCreatedAtDesc(conversationId);
        Collections.reverse(messages);
        return messages;
    }

    public void saveMessage(String conversationId, String role, String content) {
        repository.save(new ChatMessage(conversationId, role, content));
        log.debug("Saved {} message for conversation {}", role, conversationId);
    }
}
