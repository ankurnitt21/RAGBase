package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.Domain;
import com.enterprise.aiassistant.entity.DocumentChunk;
import com.enterprise.aiassistant.entity.DocumentEntity;
import com.enterprise.aiassistant.exception.DocumentIngestionException;
import com.enterprise.aiassistant.repository.DocumentChunkRepository;
import com.enterprise.aiassistant.repository.DocumentRepository;
import com.enterprise.aiassistant.repository.VectorSearchRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import io.github.resilience4j.retry.annotation.Retry;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
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


/**
 * Parses uploaded files, splits them into chunks, and writes them to two
 * stores in parallel: Pinecone (vector embeddings for semantic search) and
 * PostgreSQL document_chunks table (full-text for keyword search).
 *
 * Supports two ingestion modes:
 *   ingest()      — synchronous, called from POST /api/v1/documents
 *   ingestAsync() — async (@Async), called from POST /api/v1/documents/bulk,
 *                   job status tracked in IngestionJobStore
 *
 * Supported file types: PDF (Apache PDFBox) and plain text. Scanned/image
 * PDFs are rejected with DocumentIngestionException.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Map<Domain, EmbeddingStore<TextSegment>> domainStores;
    private final EmbeddingModel embeddingModel;
    private final IngestionJobStore jobStore;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;

    public IngestionService(Map<Domain, EmbeddingStore<TextSegment>> domainStores,
                            EmbeddingModel embeddingModel,
                            IngestionJobStore jobStore,
                            DocumentChunkRepository documentChunkRepository,
                            DocumentRepository documentRepository,
                            VectorSearchRepository vectorSearchRepository) {
        this.domainStores = domainStores;
        this.embeddingModel = embeddingModel;
        this.jobStore = jobStore;
        this.documentChunkRepository = documentChunkRepository;
        this.documentRepository = documentRepository;
        this.vectorSearchRepository = vectorSearchRepository;
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

        // Create parent document record
        DocumentEntity docEntity = documentRepository.save(
                new DocumentEntity(filename != null ? filename : "unknown", domain.name(),
                        filename != null ? filename : "unknown", "1.0"));
        Long documentId = docEntity.getId();

        // Generate embeddings for all chunks (single batch call)
        List<Embedding> embeddings = embeddingModel.embedAll(chunks).content();

        // PostgreSQL — store text + embeddings for hybrid search (HNSW + BM25 FTS)
        List<DocumentChunk> dbChunks = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            dbChunks.add(new DocumentChunk(documentId, domain.name(), chunks.get(i).text(),
                    filename != null ? filename : "unknown", i));
        }
        List<DocumentChunk> saved = documentChunkRepository.saveAll(dbChunks);

        // Batch-update the embedding column via pgvector
        List<Long> ids = saved.stream().map(DocumentChunk::getId).toList();
        List<float[]> embeddingArrays = embeddings.stream().map(Embedding::vector).toList();
        vectorSearchRepository.batchUpdateEmbeddings(ids, embeddingArrays);

        // Pinecone — redundant store for data safety (non-blocking; failure is tolerated)
        try {
            EmbeddingStore<TextSegment> store = domainStores.get(domain);
            for (int i = 0; i < chunks.size(); i++) {
                // Enrich Pinecone metadata: document_id, chunk_index, created_at, tags, author, version
                Metadata enrichedMeta = chunks.get(i).metadata().copy();
                enrichedMeta.put("document_id", String.valueOf(documentId));
                enrichedMeta.put("chunk_index", String.valueOf(i));
                enrichedMeta.put("created_at", Instant.now().toString());
                enrichedMeta.put("tags", domain.name().toLowerCase());
                enrichedMeta.put("author", "system");
                enrichedMeta.put("version", "1.0");
                TextSegment enriched = TextSegment.from(chunks.get(i).text(), enrichedMeta);
                store.add(embeddings.get(i), enriched);
            }
        } catch (Exception e) {
            log.warn("Pinecone sync failed for '{}' — PostgreSQL has the data: {}", filename, e.getMessage());
        }

        log.info("Ingested '{}' ({} chunks, docId={}) into domain [{}] — PostgreSQL (text+embeddings) + Pinecone",
                 filename, chunks.size(), documentId, domain);
    }

    private Map<String, String> buildMetadata(String filename, Domain domain) {
        Map<String, String> meta = new HashMap<>();
        meta.put("source", filename != null ? filename : "unknown");
        meta.put("ingested_at", Instant.now().toString());
        meta.put("domain", domain.name());
        return meta;
    }
}
