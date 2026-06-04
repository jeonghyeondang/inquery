package ai.inquery.server.web.api.service;

import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.service.VectorSearchService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.domain.core.reference.ReferenceDocumentService;
import ai.inquery.server.domain.repository.entity.ReferenceDocumentDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexes reference-document chunks into the active vector store under {@code ref_docs}.
 */
@Slf4j
@Service
public class ReferenceDocumentVectorIndexer {

    public static final String NAMESPACE = "ref_docs";

    @Autowired
    private VectorStoreRegistry vectorStoreRegistry;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private ReferenceDocumentService referenceDocumentService;

    public void indexDocument(ReferenceDocumentDO document) {
        if (document == null || document.getId() == null) return;

        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
        }
        if (!provider.isConfigured()) {
            referenceDocumentService.updateIndexResult(
                    document.getId(), 0, "skipped",
                    "Vector store not configured. Open Settings → Vector DB, select pgvector, and run Test Connection.");
            return;
        }

        List<String> chunks = referenceDocumentService.chunksForDocument(document);
        if (chunks.isEmpty()) {
            referenceDocumentService.updateIndexResult(
                    document.getId(), 0, "error", "No text chunks to index");
            return;
        }

        try {
            List<VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (StringUtils.isBlank(chunk)) continue;

                List<Double> embedding = vectorSearchService.generateEmbedding(chunk);
                if (embedding == null || embedding.isEmpty()) {
                    referenceDocumentService.updateIndexResult(
                            document.getId(), 0, "error", "Embedding generation failed");
                    return;
                }

                List<Float> values = embedding.stream()
                        .map(Double::floatValue)
                        .collect(Collectors.toList());

                Map<String, String> metadata = new HashMap<>();
                metadata.put("documentId", String.valueOf(document.getId()));
                metadata.put("userId", String.valueOf(document.getUserId()));
                metadata.put("filename", document.getFilename());
                metadata.put("chunkIndex", String.valueOf(i));
                metadata.put("text", truncate(chunk, 2000));
                metadata.put("sourceType", "reference_document");

                VectorData vd = new VectorData(
                        ReferenceDocumentService.vectorId(document.getId(), i),
                        values,
                        metadata);
                vectors.add(vd);
            }

            if (!provider.upsert(vectors, NAMESPACE)) {
                referenceDocumentService.updateIndexResult(
                        document.getId(), 0, "error", "Vector upsert failed");
                return;
            }

            referenceDocumentService.updateIndexResult(
                    document.getId(), vectors.size(), "indexed", null);
            log.info("Indexed reference document id={} chunks={} namespace={}",
                    document.getId(), vectors.size(), NAMESPACE);
        } catch (Exception e) {
            log.error("Reference document indexing failed for id={}", document.getId(), e);
            referenceDocumentService.updateIndexResult(
                    document.getId(), 0, "error", e.getMessage());
        }
    }

    public void deleteVectors(Long documentId, int chunkCount) {
        if (documentId == null || chunkCount <= 0) return;

        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
        }
        if (!provider.isConfigured()) {
            log.warn("Vector store not configured; skipping vector delete for document {}", documentId);
            return;
        }

        List<String> ids = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            ids.add(ReferenceDocumentService.vectorId(documentId, i));
        }
        provider.delete(ids, NAMESPACE);
        log.info("Deleted {} vectors for reference document id={}", ids.size(), documentId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
