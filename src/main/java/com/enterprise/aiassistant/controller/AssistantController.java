package com.enterprise.aiassistant.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.enterprise.aiassistant.dto.ChatRequest;
import com.enterprise.aiassistant.dto.ChatResponse;
import com.enterprise.aiassistant.dto.ClarifyRequest;
import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.dto.IngestionResponse;
import com.enterprise.aiassistant.exception.UnsupportedFileTypeException;
import com.enterprise.aiassistant.service.ChatService;
import com.enterprise.aiassistant.service.IngestionJobStore;
import com.enterprise.aiassistant.service.IngestionService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST API surface for RAGBase. Exposes six endpoints:
 *   POST /api/v1/chat                     — ask a question (domain auto-detected)
 *   POST /api/v1/chat/clarify             — re-submit with a resolved clarification
 *   POST /api/v1/documents                — ingest a single file (sync, blocks until done)
 *   POST /api/v1/documents/bulk           — ingest multiple files (async, returns jobIds)
 *   GET  /api/v1/documents/status/{jobId} — poll async ingestion job status
 *   GET  /api/v1/domains                  — list available domain namespaces
 *
 * All endpoints are rate-limited (30 req/min shared). MIME type validation
 * runs before ingestion; only application/pdf and text/plain are accepted.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "RAGBase", description = "RAG-based document Q&A with domain routing")
public class AssistantController {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf", "text/plain");

    private final ChatService chatService;
    private final IngestionService ingestionService;
    private final IngestionJobStore jobStore;

    public AssistantController(ChatService chatService, IngestionService ingestionService,
                               IngestionJobStore jobStore) {
        this.chatService = chatService;
        this.ingestionService = ingestionService;
        this.jobStore = jobStore;
    }

    @Operation(summary = "Chat", description = "Ask a question. Domain (HR / PRODUCT / AI) is auto-detected via keyword routing.")
    @PostMapping("/chat")
    @RateLimiter(name = "api")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @Operation(summary = "Chat (streaming)", description = "Ask a question with streaming LLM response via Server-Sent Events.")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "api")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(request);
    }

    @Operation(
        summary = "Clarify ambiguous question",
        description = "When /chat returns status=AWAITING_CLARIFICATION, the client displays the "
                    + "clarificationOptions to the user. The user picks one or types their own. "
                    + "Send the chosen question text here to re-enter the pipeline with the same conversationId.")
    @PostMapping("/chat/clarify")
    @RateLimiter(name = "api")
    public ChatResponse clarify(@Valid @RequestBody ClarifyRequest request) {
        ChatRequest chatRequest = new ChatRequest(request.conversationId(), request.chosenQuery());
        return chatService.chat(chatRequest);
    }

    @Operation(summary = "Ingest document (sync)", description = "Upload a single PDF or .txt file into a domain namespace. Blocks until done.")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimiter(name = "api")
    public IngestionResponse ingestDocument(
            @RequestParam MultipartFile file,
            @RequestParam Domain domain) {
        validateMimeType(file);
        ingestionService.ingest(file, domain);
        return new IngestionResponse(null, file.getOriginalFilename(), domain, "COMPLETED",
                "Ingested successfully into " + domain + " namespace");
    }

    @Operation(summary = "Ingest documents (async bulk)", description = "Upload multiple files. Returns immediately; use the returned jobId to poll status.")
    @PostMapping(value = "/documents/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimiter(name = "api")
    public List<IngestionResponse> ingestBulk(
            @RequestParam MultipartFile[] files,
            @RequestParam Domain domain) throws IOException {
        List<IngestionResponse> responses = new ArrayList<>();
        for (MultipartFile file : files) {
            validateMimeType(file);
            String jobId = UUID.randomUUID().toString();
            jobStore.register(jobId, file.getOriginalFilename(), domain);
            ingestionService.ingestAsync(jobId, file.getBytes(), file.getOriginalFilename(), domain);
            responses.add(new IngestionResponse(jobId, file.getOriginalFilename(), domain, "ACCEPTED",
                    "Queued for async ingestion. Poll /api/v1/documents/status/" + jobId));
        }
        return responses;
    }

    @Operation(summary = "Get ingestion job status", description = "Check the status of an async bulk ingestion job.")
    @GetMapping("/documents/status/{jobId}")
    @RateLimiter(name = "api")
    public IngestionJobStore.JobStatus getIngestionStatus(@PathVariable String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No ingestion job found with id: " + jobId));
    }

    @Operation(summary = "List domains", description = "Returns supported domain namespaces.")
    @GetMapping("/domains")
    @RateLimiter(name = "api")
    public List<String> getDomains() {
        return Arrays.stream(Domain.values()).map(Enum::name).toList();
    }

    private void validateMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedFileTypeException(
                    "Unsupported file type: '" + contentType + "'. Allowed types: application/pdf, text/plain");
        }
    }
}
