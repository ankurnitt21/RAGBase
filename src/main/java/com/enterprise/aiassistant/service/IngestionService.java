package com.enterprise.aiassistant.service;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.enterprise.aiassistant.dto.Domain;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;
    private final EmbeddingModel embeddingModel;

    public IngestionService(Map<Domain, EmbeddingStore<TextSegment>> domainStores, EmbeddingModel embeddingModel) {
        this.domainStores = domainStores;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Synchronous ingestion for a single file into a specific domain namespace.
     */
    public void ingest(MultipartFile file, Domain domain) {
        try {
            String filename = file.getOriginalFilename();
            log.info("Ingesting '{}' into domain [{}]", filename, domain);

            Map<String, String> meta = new HashMap<>();
            meta.put("source", filename != null ? filename : "unknown");
            meta.put("ingested_at", Instant.now().toString());
            meta.put("domain", domain.name());

            Document document;
            if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                Document parsed = new ApachePdfBoxDocumentParser().parse(file.getInputStream());
                String text = parsed.text();
                log.info("PDF '{}': extracted {} characters of text", filename, text.length());
                if (text.isBlank()) {
                    throw new RuntimeException(
                        "PDF '" + filename + "' appears to be a scanned/image-based PDF. " +
                        "No text could be extracted. Please use a text-based PDF.");
                }
                document = Document.from(text, Metadata.from(meta));
            } else {
                String content = new String(file.getBytes());
                document = Document.from(content, Metadata.from(meta));
            }

            // Split into ~300-token chunks with 100-token overlap
            DocumentSplitter splitter = DocumentSplitters.recursive(300, 100);
            List<TextSegment> chunks = splitter.split(document);
            log.info("'{}' split into {} chunks for domain [{}]", filename, chunks.size(), domain);

            EmbeddingStore<TextSegment> store = domainStores.get(domain);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build();

            ingestor.ingest(document);
            log.info("Successfully ingested '{}' ({} chunks) into domain [{}]", filename, chunks.size(), domain);

        } catch (IOException e) {
            throw new RuntimeException("Failed to ingest document: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * Async ingestion — returns immediately, processes in background.
     * Use this for bulk/multi-file uploads.
     */
    @Async
    public CompletableFuture<String> ingestAsync(byte[] fileBytes, String filename, Domain domain) {
        try {
            log.info("Async ingesting '{}' into domain [{}]", filename, domain);

            Map<String, String> meta = new HashMap<>();
            meta.put("source", filename != null ? filename : "unknown");
            meta.put("ingested_at", Instant.now().toString());
            meta.put("domain", domain.name());

            Document document;
            if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                Document parsed = new ApachePdfBoxDocumentParser()
                        .parse(new java.io.ByteArrayInputStream(fileBytes));
                String text = parsed.text();
                log.info("Async PDF '{}': extracted {} characters of text", filename, text.length());
                if (text.isBlank()) {
                    return CompletableFuture.completedFuture(
                        "FAILED: '" + filename + "' is a scanned/image-based PDF, no text extracted.");
                }
                document = Document.from(text, Metadata.from(meta));
            } else {
                String content = new String(fileBytes);
                document = Document.from(content, Metadata.from(meta));
            }

            DocumentSplitter splitter = DocumentSplitters.recursive(300, 100);
            List<TextSegment> chunks = splitter.split(document);
            log.info("Async '{}' split into {} chunks for domain [{}]", filename, chunks.size(), domain);
            EmbeddingStore<TextSegment> store = domainStores.get(domain);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build();

            ingestor.ingest(document);
            log.info("Async ingestion complete: '{}' → [{}]", filename, domain);
            return CompletableFuture.completedFuture("Ingested: " + filename);

        } catch (Exception e) {
            log.error("Async ingestion failed for '{}': {}", filename, e.getMessage(), e);
            return CompletableFuture.completedFuture("Failed: " + filename + " — " + e.getMessage());
        }
    }
}
