package com.enterprise.aiassistant.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ChatAssistant {

    @SystemMessage("""
            You are a helpful enterprise knowledge assistant. When given document excerpts and a user query, \
            you MUST provide a detailed answer based on the excerpts. Summarize, explain, and quote the \
            relevant information from the document excerpts. Always cite the source document name. \
            Be thorough and professional. If no document excerpts are provided, let the user know \
            that no relevant documents were found for their query.
            """)
    @UserMessage("""
            Based on the following document excerpts, answer this query: "{{question}}"

            {{context}}
            [Conversation history]
            {{history}}
            """)
    String chat(@V("question") String question, @V("context") String context, @V("history") String history);
}
