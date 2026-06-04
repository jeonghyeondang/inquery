package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VectorStoreProvider implementation wrapping the existing PineconeClient.
 * All existing Pinecone features (hybrid search, rerank, BM25) are preserved.
 */
@Slf4j
@Component
public class PineconeVectorStoreProvider implements VectorStoreProvider {

    @Override
    public VectorStoreType getType() {
        return VectorStoreType.PINECONE;
    }

    // ── Core CRUD ──────────────────────────────────────────────

    @Override
    public boolean upsert(List<VectorData> vectors, String namespace) {
        PineconeClient client = PineconeClient.getInstance();
        if (!client.isConfigured()) return false;

        List<PineconeClient.VectorData> pineconeVectors = vectors.stream()
                .map(v -> v.hasSparseVector()
                        ? new PineconeClient.VectorData(v.getId(), v.getValues(), v.getMetadata(),
                                v.getSparseIndices(), v.getSparseValues())
                        : new PineconeClient.VectorData(v.getId(), v.getValues(), v.getMetadata()))
                .collect(Collectors.toList());

        return client.upsertVectors(pineconeVectors, namespace);
    }

    @Override
    public List<VectorSearchResult> search(List<Float> queryVector, int topK,
                                           String namespace, Map<String, Object> filter) {
        PineconeClient client = PineconeClient.getInstance();
        if (!client.isConfigured()) return new ArrayList<>();

        List<PineconeClient.SearchResult> results = client.searchVectors(queryVector, topK, namespace, filter);
        return results.stream()
                .map(r -> new VectorSearchResult(r.getId(), r.getScore(), r.getMetadata()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(List<String> ids, String namespace) {
        return PineconeClient.getInstance().deleteVectors(ids, namespace);
    }

    @Override
    public boolean updateMetadata(String vectorId, Map<String, String> metadata, String namespace) {
        return PineconeClient.getInstance().updateVectorMetadata(vectorId, metadata, namespace);
    }

    @Override
    public int batchUpdateMetadata(List<String> vectorIds, Map<String, String> metadata,
                                   String namespace) {
        return PineconeClient.getInstance().batchUpdateVectorMetadata(vectorIds, metadata, namespace);
    }

    // ── Management ─────────────────────────────────────────────

    @Override
    public List<String> listAllVectors(String namespace) {
        return PineconeClient.getInstance().listAllVectors(namespace);
    }

    @Override
    public List<VectorData> fetchVectors(List<String> ids, String namespace) {
        List<PineconeClient.VectorData> fetched = PineconeClient.getInstance().fetchAllVectors(ids, namespace);
        return fetched.stream()
                .map(v -> new VectorData(v.getId(), v.getValues(), v.getMetadata()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isConfigured() {
        return PineconeClient.getInstance().isConfigured();
    }

    @Override
    public void refresh() {
        PineconeClient.refresh();
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            var response = PineconeClient.getInstance().getIndexStats();
            if (response != null) {
                stats.put("totalVectorCount", response.getTotalVectorCount());
                stats.put("dimension", response.getDimension());
                Map<String, Object> namespaces = new HashMap<>();
                response.getNamespacesMap().forEach((ns, nsStats) ->
                        namespaces.put(ns, nsStats.getVectorCount()));
                stats.put("namespaces", namespaces);
            }
        } catch (Exception e) {
            log.warn("Failed to get Pinecone index stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    @Override
    public void ensureConnection() {
        if (PineconeClient.getInstance().isConfigured()) {
            try {
                PineconeClient.getInstance().getIndex();
            } catch (Exception e) {
                log.debug("Pinecone warmup failed: {}", e.getMessage());
            }
        }
    }

    // ── Hybrid / Rerank (Pinecone-specific) ────────────────────

    @Override
    public List<VectorSearchResult> searchHybrid(
            List<Float> denseVector, List<Long> sparseIndices, List<Float> sparseValues,
            double alpha, int topK, String namespace, Map<String, Object> filter) {

        PineconeClient client = PineconeClient.getInstance();
        if (!client.isConfigured()) return new ArrayList<>();

        List<PineconeClient.SearchResult> results = client.searchVectorsHybrid(
                denseVector, sparseIndices, sparseValues, alpha, topK, namespace, filter);
        return results.stream()
                .map(r -> new VectorSearchResult(r.getId(), r.getScore(), r.getMetadata()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isHybridEnabled() {
        return PineconeClient.getInstance().isHybridEnabled();
    }

    @Override
    public boolean isRerankEnabled() {
        return PineconeClient.getInstance().isRerankEnabled();
    }

    @Override
    public int getRerankTopN() {
        return PineconeClient.getInstance().getRerankTopN();
    }

    @Override
    public String getHybridBm25Params() {
        return PineconeClient.getInstance().getHybridBm25Params();
    }

    @Override
    public double getHybridAlpha() {
        return PineconeClient.getInstance().getHybridAlpha();
    }

    /**
     * Expose rerank capability for Pinecone-specific consumers.
     */
    public List<PineconeClient.RerankResult> rerank(String queryText,
                                                     List<PineconeClient.RerankDocument> candidates,
                                                     int topN) {
        return PineconeClient.getInstance().rerank(queryText, candidates, topN);
    }
}
