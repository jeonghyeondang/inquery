package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.service.VectorSearchService;
import ai.inquery.server.web.api.controller.ai.pinecone.bm25.BM25SparseEncoder;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of VectorSearchService using Pinecone.
 */
/**
 * @deprecated Replaced by {@link DelegatingVectorSearchServiceImpl} which delegates
 * to the active VectorStoreProvider. Kept for reference only.
 */
@Deprecated
@Slf4j
public class PineconeVectorSearchServiceImpl implements VectorSearchService {

    @Override
    public void ensureConnection() {
        // Pre-warm Pinecone connection if not already cached
        if (PineconeClient.getInstance().isConfigured()) {
            try {
                PineconeClient.getInstance().getIndex(); // This triggers connection/cache
            } catch (Exception e) {
                log.debug("Pinecone connection warmup failed (will retry on search): {}", e.getMessage());
            }
        }
    }

    @Override
    public List<Double> generateEmbedding(String text) {
        log.info("Generating embedding for text: {}", text);
        try {
            // Use Gemini for faster embeddings from Asia regions
            ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient geminiClient = 
                ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.getInstance();
            
            List<java.math.BigDecimal> embedding = geminiClient.generateEmbedding(text);
            return embedding.stream()
                    .map(java.math.BigDecimal::doubleValue)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Gemini embedding failed, falling back to OpenAI: {}", e.getMessage());
            try {
                // Fallback to OpenAI
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
        
        // If not configured, try refresh once (settings may have been saved after startup)
        if (!PineconeClient.getInstance().isConfigured()) {
            PineconeClient.refresh();
        }

        if (!PineconeClient.getInstance().isConfigured()) {
            log.warn("Pinecone is not configured, returning empty search results");
            return new ArrayList<>();
        }

        try {
            // Get dbType from context to use as namespace (e.g., "snowflake", "mysql")
            String namespace = "";
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            if (connectInfo != null && StringUtils.isNotBlank(connectInfo.getDbType())) {
                namespace = connectInfo.getDbType().toLowerCase();
                log.info("Using namespace from context: {}", namespace);
            } else {
                // Fallback: use "snowflake" as default namespace (most common case for this project)
                namespace = "snowflake";
                log.warn("No dbType found in context, using 'snowflake' as fallback namespace");
            }

            // Convert List<Double> to List<Float> for PineconeClient
            List<Float> floatVector = queryVector.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            long searchStartTime = System.currentTimeMillis();

            // Server-side filter: only return vectors with active=true
            // Inactive vectors are toggled off via Pinecone metadata update API
            java.util.Map<String, Object> activeFilter = new java.util.HashMap<>();
            java.util.Map<String, Object> eqFilter = new java.util.HashMap<>();
            eqFilter.put("$eq", "true");
            activeFilter.put("active", eqFilter);

            PineconeClient pineconeClientForSearch = PineconeClient.getInstance();
            List<PineconeClient.SearchResult> results;

            // Hybrid search: BM25 sparse + dense vector
            if (StringUtils.isNotBlank(queryText)
                    && pineconeClientForSearch.isHybridEnabled()
                    && StringUtils.isNotBlank(pineconeClientForSearch.getHybridBm25Params())) {
                try {
                    BM25SparseEncoder bm25 = new BM25SparseEncoder();
                    bm25.deserialize(pineconeClientForSearch.getHybridBm25Params());
                    BM25SparseEncoder.SparseVector sparse = bm25.encodeQuery(queryText);

                    if (!sparse.isEmpty()) {
                        double alpha = pineconeClientForSearch.getHybridAlpha();
                        log.info("Using hybrid search: alpha={}, sparseSize={}", alpha, sparse.getIndices().size());
                        results = pineconeClientForSearch.searchVectorsHybrid(
                            floatVector, sparse.getIndices(), sparse.getValues(),
                            alpha, limit, namespace, activeFilter);
                        // Fallback to dense-only if hybrid returned empty (e.g., index doesn't support sparse)
                        if (results.isEmpty()) {
                            log.warn("Hybrid search returned 0 results, falling back to dense-only search");
                            results = pineconeClientForSearch.searchVectors(floatVector, limit, namespace, activeFilter);
                        }
                    } else {
                        log.info("BM25 sparse vector empty for query, falling back to dense-only search");
                        results = pineconeClientForSearch.searchVectors(floatVector, limit, namespace, activeFilter);
                    }
                } catch (Exception e) {
                    log.warn("Hybrid search failed, falling back to dense-only: {}", e.getMessage());
                    results = pineconeClientForSearch.searchVectors(floatVector, limit, namespace, activeFilter);
                }
            } else {
                results = pineconeClientForSearch.searchVectors(floatVector, limit, namespace, activeFilter);
            }

            log.info("⏱️ Pinecone search took {}ms, returned {} results (hybrid={})",
                System.currentTimeMillis() - searchStartTime, results.size(),
                pineconeClientForSearch.isHybridEnabled());

            log.info("Pinecone search returned {} results for namespace: {}", results.size(), namespace);

            // Build schema contexts and keep id mapping for rerank
            java.util.Map<String, String> idToSchemaContext = new java.util.HashMap<>();
            List<String> schemaContexts = results.stream()
                    .map(result -> {
                        if (result.getMetadata() == null) {
                            log.warn("Result has no metadata: {}", result.getId());
                            String v = result.getId();
                            idToSchemaContext.put(result.getId(), v);
                            return v;
                        }

                        // Build schema context with full table path info
                        StringBuilder schemaInfo = new StringBuilder();

                        // Add database.schema.table path info
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

                        // Add DB type info
                        if (dbType != null) {
                            schemaInfo.append("[DB Type: ").append(dbType.toUpperCase()).append("] ");
                        }

                        // Add tableSchema content (strip redundant "Table: ...\n" if Table Path already present)
                        if (result.getMetadata().containsKey("tableSchema")) {
                            String tableSchema = result.getMetadata().get("tableSchema");
                            if (hasTablePath && tableSchema != null && tableSchema.startsWith("Table: ")) {
                                int newlineIdx = tableSchema.indexOf('\n');
                                if (newlineIdx >= 0) {
                                    tableSchema = tableSchema.substring(newlineIdx + 1);
                                }
                            }
                            schemaInfo.append(tableSchema);
                            log.debug("Schema context for {}: {} chars of tableSchema", tableName, tableSchema != null ? tableSchema.length() : 0);
                        } else if (result.getMetadata().containsKey("text")) {
                            schemaInfo.append(result.getMetadata().get("text"));
                            log.warn("Using 'text' metadata instead of 'tableSchema' for {}", tableName);
                        } else {
                            log.warn("No tableSchema or text metadata found for {}", tableName);
                        }

                        String finalSchema = schemaInfo.length() > 0 ? schemaInfo.toString() : result.getId();
                        log.info("Built schema context: {} chars", finalSchema.length());
                        idToSchemaContext.put(result.getId(), finalSchema);
                        return finalSchema;
                    })
                    .collect(Collectors.toList());

            // Optional: Pinecone Native Rerank (topK -> topN)
            PineconeClient pineconeClient = PineconeClient.getInstance();
            if (StringUtils.isNotBlank(queryText) && pineconeClient.isRerankEnabled() && !results.isEmpty()) {
                long rerankStartTime = System.currentTimeMillis();
                int topN = pineconeClient.getRerankTopN();
                // Build rerank candidates from the SAME text we feed as schema context (stable behavior)
                List<PineconeClient.RerankDocument> docs = new ArrayList<>();
                for (PineconeClient.SearchResult r : results) {
                    String txt = idToSchemaContext.get(r.getId());
                    if (txt == null) txt = r.getId();
                    docs.add(new PineconeClient.RerankDocument(r.getId(), txt));
                }
                List<PineconeClient.RerankResult> reranked = pineconeClient.rerank(queryText, docs, topN);
                log.info("⏱️ Pinecone rerank took {}ms", System.currentTimeMillis() - rerankStartTime);
                if (reranked != null && !reranked.isEmpty()) {
                    List<String> rerankedContexts = new ArrayList<>();
                    for (PineconeClient.RerankResult rr : reranked) {
                        String ctx = idToSchemaContext.get(rr.id);
                        if (ctx != null) {
                            rerankedContexts.add(ctx);
                        }
                    }
                    if (!rerankedContexts.isEmpty()) {
                        log.info("Applied Pinecone native rerank: candidates={}, topN={}, returned={}",
                            results.size(), topN, rerankedContexts.size());
                        log.info("⏱️ Total Pinecone search + rerank took {}ms", System.currentTimeMillis() - totalStartTime);
                        return rerankedContexts;
                    }
                }
            }

            // Log the first schema context (if any) to verify it contains column information
            if (!schemaContexts.isEmpty()) {
                String firstSchema = schemaContexts.get(0);
                log.info("First schema context (first 500 chars): {}",
                    firstSchema.length() > 500 ? firstSchema.substring(0, 500) + "..." : firstSchema);
            }

            log.info("⏱️ Total Pinecone search (no rerank) took {}ms", System.currentTimeMillis() - totalStartTime);
            return schemaContexts;
        } catch (Exception e) {
            log.error("Error searching Pinecone", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> searchByTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return new ArrayList<>();
        }

        if (!PineconeClient.getInstance().isConfigured()) {
            PineconeClient.refresh();
        }
        if (!PineconeClient.getInstance().isConfigured()) {
            log.warn("Pinecone is not configured for table name search");
            return new ArrayList<>();
        }

        try {
            String namespace = "";
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            if (connectInfo != null && StringUtils.isNotBlank(connectInfo.getDbType())) {
                namespace = connectInfo.getDbType().toLowerCase();
            } else {
                namespace = "snowflake";
            }

            // Extract just the table name (strip schema/database prefix if present)
            List<String> cleanTableNames = tableNames.stream()
                .map(name -> {
                    String[] parts = name.split("\\.");
                    return parts[parts.length - 1]; // Last part is the table name
                })
                .collect(Collectors.toList());

            // Generate a dummy embedding (Pinecone query API requires a vector)
            List<Double> dummyEmbedding = generateEmbedding("table schema");
            if (dummyEmbedding.isEmpty()) {
                log.warn("Failed to generate dummy embedding for table name search");
                return new ArrayList<>();
            }
            List<Float> floatVector = dummyEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            // Metadata filter: { "tableName": { "$in": ["table1", "table2"] } }
            java.util.Map<String, Object> filter = new java.util.HashMap<>();
            java.util.Map<String, Object> inFilter = new java.util.HashMap<>();
            inFilter.put("$in", cleanTableNames);
            filter.put("tableName", inFilter);

            log.info("Searching Pinecone by table names: {} in namespace: {}", cleanTableNames, namespace);
            long startTime = System.currentTimeMillis();

            List<PineconeClient.SearchResult> results = PineconeClient.getInstance()
                .searchVectors(floatVector, cleanTableNames.size() * 2, namespace, filter);

            log.info("Pinecone table name search took {}ms, returned {} results for {} tables",
                System.currentTimeMillis() - startTime, results.size(), cleanTableNames.size());

            // Build schema contexts (same format as regular search)
            return results.stream()
                .map(result -> {
                    if (result.getMetadata() == null) {
                        return result.getId();
                    }

                    StringBuilder schemaInfo = new StringBuilder();
                    String databaseName = result.getMetadata().get("databaseName");
                    String schemaName = result.getMetadata().get("schemaName");
                    String tableName = result.getMetadata().get("tableName");
                    String dbType = result.getMetadata().get("dbType");

                    boolean hasTablePath2 = false;
                    if (databaseName != null || schemaName != null || tableName != null) {
                        schemaInfo.append("[Table Path: ");
                        if (databaseName != null) schemaInfo.append(databaseName);
                        if (schemaName != null) schemaInfo.append(".").append(schemaName);
                        if (tableName != null) schemaInfo.append(".").append(tableName);
                        schemaInfo.append("] ");
                        hasTablePath2 = true;
                    }

                    if (dbType != null) {
                        schemaInfo.append("[DB Type: ").append(dbType.toUpperCase()).append("] ");
                    }

                    if (result.getMetadata().containsKey("tableSchema")) {
                        String tableSchema = result.getMetadata().get("tableSchema");
                        if (hasTablePath2 && tableSchema != null && tableSchema.startsWith("Table: ")) {
                            int newlineIdx = tableSchema.indexOf('\n');
                            if (newlineIdx >= 0) {
                                tableSchema = tableSchema.substring(newlineIdx + 1);
                            }
                        }
                        schemaInfo.append(tableSchema);
                    } else if (result.getMetadata().containsKey("text")) {
                        schemaInfo.append(result.getMetadata().get("text"));
                    }

                    return schemaInfo.length() > 0 ? schemaInfo.toString() : result.getId();
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Pinecone search by table names failed", e);
            return new ArrayList<>();
        }
    }
}
