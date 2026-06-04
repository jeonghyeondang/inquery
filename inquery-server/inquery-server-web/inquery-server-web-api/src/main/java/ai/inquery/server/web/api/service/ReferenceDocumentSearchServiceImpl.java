package ai.inquery.server.web.api.service;

import ai.inquery.server.domain.api.model.ReferenceDocumentChunkHit;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.ReferenceDocumentSearchService;
import ai.inquery.server.domain.api.service.VectorSearchService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.domain.core.reference.ReferenceDocumentService;
import ai.inquery.server.domain.repository.entity.ReferenceDocumentDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReferenceDocumentSearchServiceImpl implements ReferenceDocumentSearchService {

    @Autowired
    private VectorStoreRegistry vectorStoreRegistry;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private ReferenceDocumentService referenceDocumentService;

    @Override
    public List<ReferenceDocumentChunkHit> search(Long userId, String query, int topK) {
        if (userId == null || StringUtils.isBlank(query) || topK <= 0) {
            return emptyHits();
        }

        Set<Long> ownedDocIds = referenceDocumentService.listMeta(userId).stream()
                .filter(d -> "indexed".equalsIgnoreCase(d.getIndexStatus()))
                .filter(d -> d.getChunkCount() != null && d.getChunkCount() > 0)
                .map(ReferenceDocumentDO::getId)
                .collect(Collectors.toSet());
        if (ownedDocIds.isEmpty()) {
            return emptyHits();
        }

        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
        }
        if (!provider.isConfigured()) {
            log.debug("Reference document search skipped: vector store not configured");
            return emptyHits();
        }

        List<Double> embedding = vectorSearchService.generateEmbedding(query.trim());
        if (embedding == null || embedding.isEmpty()) {
            return emptyHits();
        }

        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

        int fetchK = Math.min(Math.max(topK * 3, topK), 30);
        List<VectorSearchResult> raw = provider.search(queryVector, fetchK, NAMESPACE, null);

        List<ReferenceDocumentChunkHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (VectorSearchResult result : raw) {
            if (result.getMetadata() == null) continue;

            String docIdStr = result.getMetadata().get("documentId");
            if (docIdStr == null) continue;

            Long docId;
            try {
                docId = Long.parseLong(docIdStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!ownedDocIds.contains(docId)) continue;

            String text = result.getMetadata().get("text");
            String dedup = docId + ":" + result.getMetadata().getOrDefault("chunkIndex", "0") + ":" + text;
            if (!seen.add(dedup)) continue;

            int chunkIndex = 0;
            try {
                chunkIndex = Integer.parseInt(result.getMetadata().getOrDefault("chunkIndex", "0"));
            } catch (NumberFormatException ignored) {}

            hits.add(new ReferenceDocumentChunkHit(
                    docId,
                    result.getMetadata().get("filename"),
                    chunkIndex,
                    text,
                    result.getScore()));
            if (hits.size() >= topK) break;
        }

        log.info("Reference document search userId={} query='{}' returned {} hits", userId, query, hits.size());
        return hits;
    }

    @Override
    public boolean hasIndexedDocuments(Long userId) {
        if (userId == null) return false;
        return referenceDocumentService.listMeta(userId).stream()
                .anyMatch(d -> "indexed".equalsIgnoreCase(d.getIndexStatus())
                        && d.getChunkCount() != null && d.getChunkCount() > 0);
    }
}
