package com.enterprise.aiassistant.repository;

import com.enterprise.aiassistant.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for chat_messages table.
 * Provides derived queries used by MemoryService and ChatService:
 *   existsByConversationId — fast EXISTS check (avoids loading full history just for hasHistory flag)
 *   findTop10...Desc       — fetches the most recent 10 messages (legacy, kept for compatibility)
 *   findBy...Asc           — fetches the full ordered history for hybrid context building
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    boolean existsByConversationId(String conversationId);

    List<ChatMessage> findTop10ByConversationIdOrderByCreatedAtDesc(String conversationId);

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
