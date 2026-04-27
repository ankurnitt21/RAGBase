package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.exception.DocumentIngestionException;
import dev.langchain4j.data.document.Document;
import io.github.resilience4j.retry.annotation.Retry;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;
    private final EmbeddingModel embeddingModel;
    private final IngestionJobStore jobStore;

    public IngestionService(Map<Domain, EmbeddingStore<TextSegment>> domainStores,
                            EmbeddingModel embeddingModel,
                            IngestionJobStore jobStore) {
        this.domainStores = domainStores;
        this.embeddingModel = embeddingModel;
        this.jobStore = jobStore;
    }

    @Retry(name = "pinecone", fallbackMethod = "ingestFallback")
    public void ingest(MultipartFile file, Domain domain) {
        String filename = file.getOriginalFilename();
        log.info("Ingesting '{}' into domain [{}]", filename, domain);
        try {
            Document document = parseFile(file.getInputStream(), filename,
                    buildMetadata(filename, domain));
            ingestDocument(document, filename, domain);
        } catch (DocumentIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentIngestionException("Failed to read file: " + filename, e);
        }
    }

    @SuppressWarnings("unused")
    private void ingestFallback(MultipartFile file, Domain domain, Exception ex) {
        String filename = file.getOriginalFilename();
        log.error("Ingestion failed for '{}' after retries: {}", filename, ex.getMessage(), ex);
        throw new DocumentIngestionException(
                "Ingestion failed after retries. Please try again later.", ex);
    }

    @Async
    public void ingestAsync(String jobId, byte[] fileBytes, String filename, Domain domain) {
        log.info("Async ingesting job [{}] '{}' into domain [{}]", jobId, filename, domain);
        try {
            Document document = parseFile(new java.io.ByteArrayInputStream(fileBytes), filename,
                    buildMetadata(filename, domain));
            ingestDocument(document, filename, domain);
            jobStore.complete(jobId);
            log.info("Async ingestion completed for job [{}] '{}'", jobId, filename);
        } catch (Exception e) {
            log.error("Async ingestion failed for job [{}] '{}': {}", jobId, filename, e.getMessage(), e);
            jobStore.fail(jobId, e.getMessage());
        }
    }

    private Document parseFile(java.io.InputStream stream, String filename,
                               Map<String, String> metadata) throws IOException {
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            Document parsed = new ApachePdfBoxDocumentParser().parse(stream);
            String text = parsed.text();
            if (text.isBlank()) {
                throw new DocumentIngestionException(
                        "'" + filename + "' appears to be a scanned/image-based PDF. " +
                        "No text could be extracted. Please use a text-based PDF.");
            }
            log.info("PDF '{}': extracted {} characters", filename, text.length());
            return Document.from(text, Metadata.from(metadata));
        }
        return Document.from(new String(stream.readAllBytes()), Metadata.from(metadata));
    }

    private void ingestDocument(Document document, String filename, Domain domain) {
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 100);
        List<TextSegment> chunks = splitter.split(document);
        log.info("'{}' split into {} chunks for domain [{}]", filename, chunks.size(), domain);

        EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(domainStores.get(domain))
                .build()
                .ingest(document);

        log.info("Successfully ingested '{}' ({} chunks) into domain [{}]", filename, chunks.size(), domain);
    }

    private Map<String, String> buildMetadata(String filename, Domain domain) {
        Map<String, String> meta = new HashMap<>();
        meta.put("source", filename != null ? filename : "unknown");
        meta.put("ingested_at", Instant.now().toString());
        meta.put("domain", domain.name());
        return meta;
    }
}
