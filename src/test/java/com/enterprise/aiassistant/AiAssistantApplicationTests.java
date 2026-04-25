package com.enterprise.aiassistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.IngestionResponse;

class AiAssistantApplicationTests {

    @Test
    void chatRequestRecordWorks() {
        ChatRequest req = new ChatRequest("conv-1", "Hello?");
        assertEquals("conv-1", req.conversationId());
        assertEquals("Hello?", req.question());
    }

    @Test
    void chatRequestAllowsNullConversationId() {
        ChatRequest req = new ChatRequest(null, "Hello?");
        assertNull(req.conversationId());
        assertEquals("Hello?", req.question());
    }

    @Test
    void chatResponseRecordIncludesRoutedDomain() {
        ChatResponse res = new ChatResponse("answer", "HIGH", List.of("doc.pdf"), "HR");
        assertEquals("answer", res.answer());
        assertEquals("HIGH", res.confidence());
        assertEquals(List.of("doc.pdf"), res.sources());
        assertEquals("HR", res.routedDomain());
    }

    @Test
    void domainEnumValues() {
        Domain[] domains = Domain.values();
        assertEquals(3, domains.length);
        assertEquals(Domain.HR, Domain.valueOf("HR"));
        assertEquals(Domain.PRODUCT, Domain.valueOf("PRODUCT"));
        assertEquals(Domain.AI, Domain.valueOf("AI"));
    }

    @Test
    void ingestionResponseRecordWorks() {
        IngestionResponse res = new IngestionResponse("test.pdf", Domain.AI, "COMPLETED", "ok");
        assertEquals("test.pdf", res.filename());
        assertEquals(Domain.AI, res.domain());
        assertEquals("COMPLETED", res.status());
        assertEquals("ok", res.message());
    }
}
