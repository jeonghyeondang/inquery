package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.model.VectorSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for vector store operations across different providers
 * (Pinecone, pgvector, Qdrant, etc.)
 */
public interface VectorStoreProvider {

    /**
     * @return the type of this vector store provider
     */
    VectorStoreType getType();

    // ── Core CRUD ──────────────────────────────────────────────

    boolean upsert(List<VectorData> vectors, String namespace);

    List<VectorSearchResult> search(List<Float> queryVector, int topK,
                                    String namespace, Map<String, Object> filter);

    boolean delete(List<String> ids, String namespace);

    boolean updateMetadata(String vectorId, Map<String, String> metadata, String namespace);

    default int batchUpdateMetadata(List<String> vectorIds, Map<String, String> metadata,
                                    String namespace) {
        int count = 0;
        for (String id : vectorIds) {
            if (updateMetadata(id, metadata, namespace)) count++;
        }
        return count;
    }

    // ── Management ─────────────────────────────────────────────

    List<String> listAllVectors(String namespace);

    List<VectorData> fetchVectors(List<String> ids, String namespace);

    boolean isConfigured();

    void refresh();

    Map<String, Object> getStats();

    default void ensureConnection() {}

    // ── Hybrid / Rerank (optional, Pinecone-specific) ──────────

    default List<VectorSearchResult> searchHybrid(
            List<Float> denseVector, List<Long> sparseIndices, List<Float> sparseValues,
            double alpha, int topK, String namespace, Map<String, Object> filter) {
        return search(denseVector, topK, namespace, filter);
    }

    default boolean isHybridEnabled() { return false; }

    default boolean isRerankEnabled() { return false; }

    default int getRerankTopN() { return 5; }

    default String getHybridBm25Params() { return null; }

    default double getHybridAlpha() { return 0.7; }
}
