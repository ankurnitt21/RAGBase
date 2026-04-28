package com.enterprise.aiassistant.service;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiServices interface. At runtime, AiServices.builder() generates
 * a proxy that handles the full tool-calling loop: assembles the prompt from
 * chat-user.st, sends it to gpt-4o-mini along with registered tool schemas,
 * executes any tool calls the model requests (searchKnowledgeBase), and loops
 * until the model produces a final text answer.
 */
public interface ChatAssistant {

    @UserMessage(fromResource = "prompts/chat-user.st")
    String chat(@V("question") String question, @V("history") String history);
}
