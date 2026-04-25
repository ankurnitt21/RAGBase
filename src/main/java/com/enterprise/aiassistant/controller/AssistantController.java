package com.enterprise.aiassistant.controller;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.IngestionResponse;
import com.enterprise.aiassistant.service.ChatService;
import com.enterprise.aiassistant.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "RAGBase", description = "RAG-based document Q&A with domain routing")
public class AssistantController {

    private final ChatService chatService;
    private final IngestionService ingestionService;

    public AssistantController(ChatService chatService, IngestionService ingestionService) {
        this.chatService = chatService;
        this.ingestionService = ingestionService;
    }

    @Operation(summary = "Chat", description = "Ask a question. Domain (HR / PRODUCT / AI) is auto-detected via LLM routing.")
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @Operation(summary = "Ingest document (sync)", description = "Upload a single PDF or .txt file into a domain namespace. Blocks until done.")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResponse ingestDocument(
            @RequestParam MultipartFile file,
            @RequestParam Domain domain) {
        ingestionService.ingest(file, domain);
        return new IngestionResponse(file.getOriginalFilename(), domain, "COMPLETED",
                "Ingested successfully into " + domain + " namespace");
    }

    @Operation(summary = "Ingest documents (async bulk)", description = "Upload multiple files. Returns immediately; ingestion runs in background.")
    @PostMapping(value = "/documents/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<IngestionResponse> ingestBulk(
            @RequestParam MultipartFile[] files,
            @RequestParam Domain domain) throws IOException {
        List<IngestionResponse> responses = new ArrayList<>();
        for (MultipartFile file : files) {
            ingestionService.ingestAsync(file.getBytes(), file.getOriginalFilename(), domain);
            responses.add(new IngestionResponse(file.getOriginalFilename(), domain, "ACCEPTED",
                    "Queued for async ingestion into " + domain + " namespace"));
        }
        return responses;
    }

    @Operation(summary = "List domains", description = "Returns supported domain namespaces.")
    @GetMapping("/domains")
    public List<String> getDomains() {
        return Arrays.stream(Domain.values()).map(Enum::name).toList();
    }
}
