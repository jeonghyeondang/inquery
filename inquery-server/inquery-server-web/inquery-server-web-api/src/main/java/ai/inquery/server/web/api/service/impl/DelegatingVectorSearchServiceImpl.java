package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.VectorSearchService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.web.api.controller.ai.pinecone.bm25.BM25SparseEncoder;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import ai.inquery.server.web.api.service.VectorStoreRegistry;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Delegates vector search operations to whichever VectorStoreProvider is currently active.
 * Replaces PineconeVectorSearchServiceImpl.
 * Embedding generation (Gemini / OpenAI) is provider-agnostic and unchanged.
 */
@Service
@Primary
@Slf4j
public class DelegatingVectorSearchServiceImpl implements VectorSearchService {

    private final VectorStoreRegistry registry;
    private volatile EmbeddingModel localEmbeddingModel;

    public DelegatingVectorSearchServiceImpl(VectorStoreRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void ensureConnection() {
        registry.getActiveProvider().ensureConnection();
        VectorStoreType activeType = registry.getActiveType();
        if (activeType == VectorStoreType.PGVECTOR || activeType == VectorStoreType.QDRANT) {
            List<Double> warmup = generateLocalEmbedding("schema search warmup");
            log.info("Local ONNX embedding warmup completed: dims={}", warmup.size());
        }
    }

    private EmbeddingModel getLocalEmbeddingModel() {
        if (localEmbeddingModel == null) {
            synchronized (this) {
                if (localEmbeddingModel == null) {
                    log.info("Initializing local ONNX embedding model (all-MiniLM-L6-v2-q, 384 dims)...");
                    localEmbeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
                    log.info("Local ONNX embedding model ready");
                }
            }
        }
        return localEmbeddingModel;
    }

    @Override
    public List<Double> generateEmbedding(String text) {
        VectorStoreType activeType = registry.getActiveType();

        if (activeType == VectorStoreType.PGVECTOR || activeType == VectorStoreType.QDRANT) {
            return generateLocalEmbedding(text);
        }
        return generateApiEmbedding(text);
    }

    private List<Double> generateLocalEmbedding(String text) {
        try {
            EmbeddingModel model = getLocalEmbeddingModel();
            Response<Embedding> response = model.embed(text);
            float[] vector = response.content().vector();
            List<Double> result = new ArrayList<>(vector.length);
            for (float v : vector) {
                result.add((double) v);
            }
            return result;
        } catch (Exception e) {
            log.error("Local ONNX embedding failed", e);
            return new ArrayList<>();
        }
    }

    private List<Double> generateApiEmbedding(String text) {
        log.info("Generating API embedding for text (Pinecone mode)");
        try {
            ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient geminiClient =
                ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.getInstance();
            List<java.math.BigDecimal> embedding = geminiClient.generateEmbedding(text);
            return embedding.stream()
                    .map(java.math.BigDecimal::doubleValue)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Gemini embedding failed, falling back to OpenAI: {}", e.getMessage());
            try {
                ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse response =
                    ai.inquery.server.web.api.controller.ai.openai.client.DirectOpenAIClient.getInstance().embeddings(text);
                if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                    List<java.math.BigDecimal> embedding = response.getData().get(0).getEmbedding();
                    return embedding.stream()
                            .map(java.math.BigDecimal::doubleValue)
                            .collect(Collectors.toList());
                }
            } catch (Exception ex) {
                log.error("OpenAI embedding fallback also failed", ex);
            }
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> search(List<Double> queryVector, int limit) {
        return search(queryVector, limit, null);
    }

    @Override
    public List<String> search(List<Double> queryVector, int limit, List<String> excludedTables) {
        return search(queryVector, null, limit, excludedTables);
    }

    @Override
    public List<String> search(List<Double> queryVector, String queryText, int limit, List<String> excludedTables) {
        long totalStartTime = System.currentTimeMillis();

        VectorStoreProvider provider = registry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
        }
        if (!provider.isConfigured()) {
            log.warn("Vector store ({}) is not configured, returning empty search results", provider.getType());
            return new ArrayList<>();
        }

        try {
            String namespace = resolveNamespace();

            List<Float> floatVector = queryVector.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            // Active-only filter (matches existing Pinecone behaviour)
            Map<String, Object> activeFilter = new HashMap<>();
            Map<String, Object> eqFilter = new HashMap<>();
            eqFilter.put("$eq", "true");
            activeFilter.put("active", eqFilter);

            long searchStartTime = System.currentTimeMillis();
            List<VectorSearchResult> results;

            // Hybrid search (BM25 sparse + dense) for providers that support it
            if (StringUtils.isNotBlank(queryText)
                    && provider.isHybridEnabled()
                    && StringUtils.isNotBlank(provider.getHybridBm25Params())) {
                try {
                    BM25SparseEncoder bm25 = new BM25SparseEncoder();
                    bm25.deserialize(provider.getHybridBm25Params());
                    BM25SparseEncoder.SparseVector sparse = bm25.encodeQuery(queryText);

                    if (!sparse.isEmpty()) {
                        double alpha = provider.getHybridAlpha();
                        log.info("Using hybrid search: alpha={}, sparseSize={}", alpha, sparse.getIndices().size());
                        results = provider.searchHybrid(
                            floatVector, sparse.getIndices(), sparse.getValues(),
                            alpha, limit, namespace, activeFilter);
                        if (results.isEmpty()) {
                            log.warn("Hybrid search returned 0 results, falling back to dense-only");
                            results = provider.search(floatVector, limit, namespace, activeFilter);
                        }
                    } else {
                        results = provider.search(floatVector, limit, namespace, activeFilter);
                    }
                } catch (Exception e) {
                    log.warn("Hybrid search failed, falling back to dense-only: {}", e.getMessage());
                    results = provider.search(floatVector, limit, namespace, activeFilter);
                }
            } else {
                results = provider.search(floatVector, limit, namespace, activeFilter);
            }

            log.info("Vector search via {} took {}ms, returned {} results",
                provider.getType(), System.currentTimeMillis() - searchStartTime, results.size());

            // Build schema contexts and keep id mapping for rerank
            Map<String, String> idToSchemaContext = new HashMap<>();
            List<String> schemaContexts = results.stream()
                    .map(result -> buildSchemaContext(result, idToSchemaContext))
                    .collect(Collectors.toList());

            // Optional: Rerank (Pinecone-specific)
            if (StringUtils.isNotBlank(queryText) && provider.isRerankEnabled() && !results.isEmpty()
                    && provider instanceof PineconeVectorStoreProvider) {
                long rerankStartTime = System.currentTimeMillis();
                int topN = provider.getRerankTopN();
                List<PineconeClient.RerankDocument> docs = new ArrayList<>();
                for (VectorSearchResult r : results) {
                    String txt = idToSchemaContext.get(r.getId());
                    if (txt == null) txt = r.getId();
                    docs.add(new PineconeClient.RerankDocument(r.getId(), txt));
                }
                List<PineconeClient.RerankResult> reranked =
                    ((PineconeVectorStoreProvider) provider).rerank(queryText, docs, topN);
                log.info("Rerank took {}ms", System.currentTimeMillis() - rerankStartTime);
                if (reranked != null && !reranked.isEmpty()) {
                    List<String> rerankedContexts = new ArrayList<>();
                    for (PineconeClient.RerankResult rr : reranked) {
                        String ctx = idToSchemaContext.get(rr.id);
                        if (ctx != null) rerankedContexts.add(ctx);
                    }
                    if (!rerankedContexts.isEmpty()) {
                        log.info("Applied rerank: candidates={}, topN={}, returned={}",
                            results.size(), topN, rerankedContexts.size());
                        return rerankedContexts;
                    }
                }
            }

            log.info("Total vector search took {}ms", System.currentTimeMillis() - totalStartTime);
            return schemaContexts;
        } catch (Exception e) {
            log.error("Error in vector search via {}", provider.getType(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> searchByTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) return new ArrayList<>();

        VectorStoreProvider provider = registry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
        }
        if (!provider.isConfigured()) {
            log.warn("Vector store not configured for table name search");
            return new ArrayList<>();
        }

        try {
            String namespace = resolveNamespace();

            List<String> cleanTableNames = tableNames.stream()
                .map(name -> {
                    String[] parts = name.split("\\.");
                    return parts[parts.length - 1];
                })
                .collect(Collectors.toList());

            List<Double> dummyEmbedding = generateEmbedding("table schema");
            if (dummyEmbedding.isEmpty()) return new ArrayList<>();

            List<Float> floatVector = dummyEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            // tableName $in filter
            Map<String, Object> filter = new HashMap<>();
            Map<String, Object> inFilter = new HashMap<>();
            inFilter.put("$in", cleanTableNames);
            filter.put("tableName", inFilter);

            List<VectorSearchResult> results = provider.search(
                floatVector, cleanTableNames.size() * 2, namespace, filter);

            return results.stream()
                    .map(result -> {
                        Map<String, String> dummy = new HashMap<>();
                        return buildSchemaContext(result, dummy);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Table name search failed via {}", provider.getType(), e);
            return new ArrayList<>();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private String resolveNamespace() {
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        if (connectInfo != null && StringUtils.isNotBlank(connectInfo.getDbType())) {
            return connectInfo.getDbType().toLowerCase();
        }
        return "snowflake";
    }

    private String buildSchemaContext(VectorSearchResult result, Map<String, String> idToSchemaContext) {
        if (result.getMetadata() == null) {
            String v = result.getId();
            idToSchemaContext.put(result.getId(), v);
            return v;
        }

        StringBuilder schemaInfo = new StringBuilder();
        String databaseName = result.getMetadata().get("databaseName");
        String schemaName = result.getMetadata().get("schemaName");
        String tableName = result.getMetadata().get("tableName");
        String dbType = result.getMetadata().get("dbType");

        boolean hasTablePath = false;
        if (databaseName != null || schemaName != null || tableName != null) {
            schemaInfo.append("[Table Path: ");
            if (databaseName != null) schemaInfo.append(databaseName);
            if (schemaName != null) schemaInfo.append(".").append(schemaName);
            if (tableName != null) schemaInfo.append(".").append(tableName);
            schemaInfo.append("] ");
            hasTablePath = true;
        }
        if (dbType != null) {
            schemaInfo.append("[DB Type: ").append(dbType.toUpperCase()).append("] ");
        }
        if (result.getMetadata().containsKey("tableSchema")) {
            String tableSchema = result.getMetadata().get("tableSchema");
            if (hasTablePath && tableSchema != null && tableSchema.startsWith("Table: ")) {
                int newlineIdx = tableSchema.indexOf('\n');
                if (newlineIdx >= 0) {
                    tableSchema = tableSchema.substring(newlineIdx + 1);
                }
            }
            schemaInfo.append(tableSchema);
        } else if (result.getMetadata().containsKey("text")) {
            schemaInfo.append(result.getMetadata().get("text"));
        }

        String finalSchema = schemaInfo.length() > 0 ? schemaInfo.toString() : result.getId();
        idToSchemaContext.put(result.getId(), finalSchema);
        return finalSchema;
    }
}
