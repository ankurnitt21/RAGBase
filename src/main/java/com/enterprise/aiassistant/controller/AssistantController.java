package com.enterprise.aiassistant.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.IngestionResponse;
import com.enterprise.aiassistant.service.ChatService;
import com.enterprise.aiassistant.service.IngestionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api")
@Tag(name = "Knowledge Assistant", description = "RAG-based chat and document ingestion with domain namespaces")
public class AssistantController {

    private final ChatService chatService;
    private final IngestionService ingestionService;

    public AssistantController(ChatService chatService, IngestionService ingestionService) {
        this.chatService = chatService;
        this.ingestionService = ingestionService;
    }

    @Operation(summary = "Chat", description = "Send a question through the RAG pipeline. The system auto-routes your question to the correct domain (HR, PRODUCT, AI) using the LLM.")
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @Operation(summary = "Ingest document (sync)", description = "Upload a single PDF or text file into a specific domain namespace. Blocking — waits until ingestion completes.")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResponse ingestDocument(
            @Parameter(description = "PDF or .txt file to ingest") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Target domain namespace") @RequestParam("domain") Domain domain) {
        ingestionService.ingest(file, domain);
        return new IngestionResponse(file.getOriginalFilename(), domain, "COMPLETED",
                "Document ingested successfully into " + domain + " namespace");
    }

    @Operation(summary = "Ingest documents (async bulk)", description = "Upload multiple files into a domain namespace. Returns immediately — ingestion runs in background.")
    @PostMapping(value = "/documents/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<IngestionResponse> ingestBulk(
            @Parameter(description = "One or more PDF/.txt files") @RequestParam("files") MultipartFile[] files,
            @Parameter(description = "Target domain namespace") @RequestParam("domain") Domain domain) throws IOException {
        List<IngestionResponse> responses = new ArrayList<>();
        for (MultipartFile file : files) {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            ingestionService.ingestAsync(bytes, filename, domain);
            responses.add(new IngestionResponse(filename, domain, "ACCEPTED",
                    "Queued for async ingestion into " + domain + " namespace"));
        }
        return responses;
    }

    @Operation(summary = "List domains", description = "Returns the list of available domain namespaces.")
    @GetMapping("/domains")
    public Domain[] getDomains() {
        return Domain.values();
    }
}
