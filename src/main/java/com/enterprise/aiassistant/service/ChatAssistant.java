package com.enterprise.aiassistant.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ChatAssistant {

    @SystemMessage("""
            You are a helpful enterprise knowledge assistant. \
            Always call the searchKnowledgeBase tool before answering factual questions. \
            You may call it multiple times with different queries to gather enough context. \
            Always cite the source document name in your answer. \
            If no relevant documents are found, say so clearly.
            """)
    @UserMessage("""
            {{question}}

            [Conversation history]
            {{history}}
            """)
    String chat(@V("question") String question, @V("history") String history);
}
