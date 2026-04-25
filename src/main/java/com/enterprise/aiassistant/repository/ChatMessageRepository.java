package com.enterprise.aiassistant.repository;

import com.enterprise.aiassistant.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop10ByConversationIdOrderByCreatedAtDesc(String conversationId);
}
