package ai.inquery.server.web.api.controller.ai.pinecone.client;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.configs.PineconeConfig;
import io.pinecone.configs.PineconeConnection;
import io.pinecone.proto.DescribeIndexStatsResponse;
import io.pinecone.proto.UpsertResponse;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static io.pinecone.commons.IndexInterface.buildUpsertVectorWithUnsignedIndices;

/**
 * Pinecone Client for vector database operations
 *
 */
@Slf4j
public class PineconeClient {

    public static final String PINECONE_API_KEY = "pinecone.apiKey";
    public static final String PINECONE_HOST = "pinecone.host";
    public static final String PINECONE_INDEX_NAME = "pinecone.indexName";
    public static final String PINECONE_NAMESPACE = "pinecone.namespace";

    // Pinecone Inference Rerank (Native Rerank)
    public static final String PINECONE_RERANK_ENABLED = "pinecone.rerank.enabled";
    public static final String PINECONE_RERANK_MODEL = "pinecone.rerank.model";
    public static final String PINECONE_RERANK_TOP_N = "pinecone.rerank.topN";
    public static final String PINECONE_RERANK_API_HOST = "pinecone.rerank.apiHost";

    // Hybrid search (BM25 sparse + dense vectors)
    public static final String PINECONE_HYBRID_ENABLED = "pinecone.hybrid.enabled";
    public static final String PINECONE_HYBRID_ALPHA = "pinecone.hybrid.alpha";
    public static final String PINECONE_HYBRID_BM25_PARAMS = "pinecone.hybrid.bm25Params";

    private static volatile PineconeClient instance;
    private Pinecone pinecone;
    private String apiKey;
    private String host;
    private String indexName;
    private String namespace;

    private boolean rerankEnabled = false;
    // Default rerank model: best choice for English-only corpora in many cases
    private String rerankModel = "cohere-rerank-3.5";
    private int rerankTopN = 5;
    private String rerankApiHost = "https://api.pinecone.io";

    // Hybrid search config
    private boolean hybridEnabled = false;
    private double hybridAlpha = 0.7; // 70% dense, 30% sparse
    private String hybridBm25Params = null; // Serialized BM25 fitted params

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(30))
        .build();

    // Cached Index for performance - avoid repeated auto-discover
    private volatile Index cachedIndex;
    private volatile long cachedIndexTimestamp;
    private static final long INDEX_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours cache TTL

    private PineconeClient() {
        // Private constructor for singleton
    }

    public static PineconeClient getInstance() {
        if (instance == null) {
            synchronized (PineconeClient.class) {
                if (instance == null) {
                    instance = new PineconeClient();
                    refresh();
                }
            }
        }
        return instance;
    }

    public static void refresh() {
        // Ensure instance is created before accessing its fields
        if (instance == null) {
            synchronized (PineconeClient.class) {
                if (instance == null) {
                    instance = new PineconeClient();
                }
            }
        }
        
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        
        String apiKey = "";
        String host = "";
        String indexName = "table-schemas";
        String namespace = "default";
        
        Config apiKeyConfig = configService.find(PINECONE_API_KEY).getData();
        if (apiKeyConfig != null && StringUtils.isNotBlank(apiKeyConfig.getContent())) {
            apiKey = apiKeyConfig.getContent();
        }
        
        Config hostConfig = configService.find(PINECONE_HOST).getData();
        if (hostConfig != null && StringUtils.isNotBlank(hostConfig.getContent())) {
            host = hostConfig.getContent();
        }
        
        Config indexNameConfig = configService.find(PINECONE_INDEX_NAME).getData();
        if (indexNameConfig != null && StringUtils.isNotBlank(indexNameConfig.getContent())) {
            indexName = indexNameConfig.getContent();
        }
        
        Config namespaceConfig = configService.find(PINECONE_NAMESPACE).getData();
        if (namespaceConfig != null && StringUtils.isNotBlank(namespaceConfig.getContent())) {
            namespace = namespaceConfig.getContent();
        }

        // Optional: rerank configs (all safe defaults)
        try {
            DataResult<Config> enabledR = configService.find(PINECONE_RERANK_ENABLED);
            Config enabledCfg = enabledR != null ? enabledR.getData() : null;
            if (enabledCfg != null && StringUtils.isNotBlank(enabledCfg.getContent())) {
                instance.rerankEnabled = "true".equalsIgnoreCase(enabledCfg.getContent().trim());
            }
        } catch (Exception ignored) {}
        try {
            DataResult<Config> modelR = configService.find(PINECONE_RERANK_MODEL);
            Config modelCfg = modelR != null ? modelR.getData() : null;
            if (modelCfg != null && StringUtils.isNotBlank(modelCfg.getContent())) {
                instance.rerankModel = modelCfg.getContent().trim();
            }
        } catch (Exception ignored) {}
        try {
            DataResult<Config> topNR = configService.find(PINECONE_RERANK_TOP_N);
            Config topNCfg = topNR != null ? topNR.getData() : null;
            if (topNCfg != null && StringUtils.isNotBlank(topNCfg.getContent())) {
                instance.rerankTopN = Integer.parseInt(topNCfg.getContent().trim());
            }
        } catch (Exception ignored) {}
        try {
            DataResult<Config> hostR = configService.find(PINECONE_RERANK_API_HOST);
            Config hostCfg = hostR != null ? hostR.getData() : null;
            if (hostCfg != null && StringUtils.isNotBlank(hostCfg.getContent())) {
                instance.rerankApiHost = hostCfg.getContent().trim();
            }
        } catch (Exception ignored) {}

        // Optional: hybrid search configs
        try {
            DataResult<Config> hybridEnabledR = configService.find(PINECONE_HYBRID_ENABLED);
            Config hybridEnabledCfg = hybridEnabledR != null ? hybridEnabledR.getData() : null;
            if (hybridEnabledCfg != null && StringUtils.isNotBlank(hybridEnabledCfg.getContent())) {
                instance.hybridEnabled = "true".equalsIgnoreCase(hybridEnabledCfg.getContent().trim());
            }
        } catch (Exception ignored) {}
        try {
            DataResult<Config> alphaR = configService.find(PINECONE_HYBRID_ALPHA);
            Config alphaCfg = alphaR != null ? alphaR.getData() : null;
            if (alphaCfg != null && StringUtils.isNotBlank(alphaCfg.getContent())) {
                instance.hybridAlpha = Double.parseDouble(alphaCfg.getContent().trim());
            }
        } catch (Exception ignored) {}
        try {
            DataResult<Config> bm25R = configService.find(PINECONE_HYBRID_BM25_PARAMS);
            Config bm25Cfg = bm25R != null ? bm25R.getData() : null;
            if (bm25Cfg != null && StringUtils.isNotBlank(bm25Cfg.getContent())) {
                instance.hybridBm25Params = bm25Cfg.getContent();
            }
        } catch (Exception ignored) {}

        if (StringUtils.isBlank(apiKey)) {
            log.warn("Pinecone API key is not configured");
            instance.pinecone = null;
            return;
        }
        
        try {
            instance.apiKey = apiKey;
            instance.host = host;
            instance.indexName = indexName;
            instance.namespace = namespace;

            // Close and invalidate cached index when config changes
            if (instance.cachedIndex != null) {
                try {
                    instance.cachedIndex.close();
                } catch (Exception e) {
                    log.warn("Failed to close previous Pinecone index during refresh: {}", e.getMessage());
                }
            }
            instance.cachedIndex = null;
            instance.cachedIndexTimestamp = 0;

            // Note: withHost() is for control plane, not data plane queries
            // Data plane host is auto-discovered by SDK via getIndexConnection()
            // Caching handles the performance optimization instead
            instance.pinecone = new Pinecone.Builder(apiKey).build();
            log.info("Pinecone client initialized - index: {}, namespace: {} (data plane host will be auto-discovered and cached)", indexName, namespace);

            // Eager initialization: pre-connect to Index in background thread
            // This ensures first user request doesn't wait for auto-discover
            new Thread(() -> {
                try {
                    log.info("🚀 Pre-warming Pinecone index connection...");
                    long start = System.currentTimeMillis();
                    instance.getIndex(); // This will auto-discover and cache
                    log.info("✅ Pinecone index pre-warmed in {}ms", System.currentTimeMillis() - start);
                } catch (Exception e) {
                    log.warn("⚠️ Failed to pre-warm Pinecone index (will retry on first request): {}", e.getMessage());
                }
            }, "pinecone-prewarm").start();
        } catch (Exception e) {
            log.error("Failed to initialize Pinecone client", e);
            instance.pinecone = null;
        }
    }

    /**
     * Check if Pinecone is configured
     */
    public boolean isConfigured() {
        return pinecone != null && StringUtils.isNotBlank(apiKey);
    }

    public boolean isRerankEnabled() {
        return rerankEnabled && StringUtils.isNotBlank(apiKey);
    }

    public int getRerankTopN() {
        return rerankTopN;
    }

    public boolean isHybridEnabled() {
        return hybridEnabled && StringUtils.isNotBlank(apiKey);
    }

    public double getHybridAlpha() {
        return hybridAlpha;
    }

    public String getHybridBm25Params() {
        return hybridBm25Params;
    }

    /**
     * Pinecone Native Rerank (Inference API).
     * Reorders the given candidate documents by relevance to queryText.
     *
     * This is best-effort: on any error, returns candidates in original order.
     *
     * @param queryText original query text
     * @param candidates list of {id, text}
     * @param topN final top N to return
     * @return reordered subset (size <= topN)
     */
    public List<RerankResult> rerank(String queryText, List<RerankDocument> candidates, int topN) {
        if (!isRerankEnabled() || StringUtils.isBlank(queryText) || candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        int useTopN = Math.max(1, topN);

        try {
            // Build request payload. We include "parameters.truncate=END" for safety.
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", StringUtils.isNotBlank(rerankModel) ? rerankModel : "cohere-rerank-3.5");
            payload.put("query", queryText);
            payload.put("top_n", useTopN);
            payload.put("return_documents", true);

            Map<String, Object> params = new HashMap<>();
            params.put("truncate", "END");
            payload.put("parameters", params);

            List<Map<String, Object>> docs = new ArrayList<>();
            for (RerankDocument d : candidates) {
                if (d == null || StringUtils.isBlank(d.id) || d.text == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", d.id);
                m.put("text", d.text);
                docs.add(m);
            }
            payload.put("documents", docs);

            String bodyJson = objectMapper.writeValueAsString(payload);

            // Pinecone inference base URL.
            // The exact path can vary by Pinecone version; we attempt common paths.
            List<String> paths = List.of("/inference/rerank", "/rerank");
            for (String path : paths) {
                String url = rerankApiHost;
                if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                url = url + path;

                Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Api-Key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        // Try next path on 404; otherwise break.
                        if (response.code() == 404) {
                            continue;
                        }
                        log.warn("Pinecone rerank request failed: status={}, url={}", response.code(), url);
                        break;
                    }
                    String resp = response.body() != null ? response.body().string() : "";
                    if (StringUtils.isBlank(resp)) {
                        log.warn("Pinecone rerank empty response, url={}", url);
                        return new ArrayList<>();
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
                    Object data = parsed.get("data");
                    if (!(data instanceof List)) {
                        log.warn("Pinecone rerank unexpected response shape, url={}, keys={}", url, parsed.keySet());
                        return new ArrayList<>();
                    }
                    List<Map<String, Object>> items = (List<Map<String, Object>>) data;
                    List<RerankResult> results = new ArrayList<>();
                    for (Map<String, Object> item : items) {
                        if (item == null) continue;
                        Object scoreObj = item.get("score");
                        double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;
                        Object docObj = item.get("document");
                        String id = null;
                        if (docObj instanceof Map) {
                            Object idObj = ((Map) docObj).get("id");
                            if (idObj != null) id = String.valueOf(idObj);
                        }
                        if (StringUtils.isBlank(id)) {
                            // fall back to "id" at top-level if present
                            Object idObj = item.get("id");
                            if (idObj != null) id = String.valueOf(idObj);
                        }
                        if (StringUtils.isBlank(id)) continue;
                        results.add(new RerankResult(id, score));
                    }
                    return results;
                }
            }
        } catch (Exception e) {
            log.warn("Pinecone rerank failed (will fallback to vector order): {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    public static class RerankDocument {
        public final String id;
        public final String text;
        public RerankDocument(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    public static class RerankResult {
        public final String id;
        public final double score;
        public RerankResult(String id, double score) {
            this.id = id;
            this.score = score;
        }
    }

    /**
     * Get Pinecone index with caching for performance
     * Cached index is reused to avoid repeated auto-discover overhead (~4 seconds)
     * Based on Pinecone Java SDK 5.1.0 API: pinecone.getIndexConnection(indexName)
     */
    public Index getIndex() {
        if (!isConfigured()) {
            throw new IllegalStateException("Pinecone is not configured");
        }

        // Check if cached index is still valid
        long now = System.currentTimeMillis();
        if (cachedIndex != null && (now - cachedIndexTimestamp) < INDEX_CACHE_TTL_MS) {
            log.debug("Using cached Pinecone index (age: {}ms)", now - cachedIndexTimestamp);
            return cachedIndex;
        }

        // Close previous connection before creating a new one to avoid gRPC channel leak
        if (cachedIndex != null) {
            try {
                cachedIndex.close();
                log.info("Closed previous Pinecone index connection");
            } catch (Exception closeEx) {
                log.warn("Failed to close previous Pinecone index: {}", closeEx.getMessage());
            }
            cachedIndex = null;
        }

        // Need to get new index connection
        log.info("[PineconeClient] getIndex() - creating new connection (indexName: {}, host: {})",
            indexName, StringUtils.isNotBlank(host) ? host : "auto-discover");

        try {
            Index index = createIndexConnection();

            // Cache the index for future use
            cachedIndex = index;
            cachedIndexTimestamp = System.currentTimeMillis();
            log.info("✅ Pinecone index cached for reuse (TTL: {}ms)", INDEX_CACHE_TTL_MS);

            return index;
        } catch (Exception e) {
            log.error("Failed to get Pinecone index: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get index: " + indexName, e);
        }
    }

    /**
     * Create a new index connection (internal method)
     * If host is configured, uses PineconeConfig/PineconeConnection for direct connection (no auto-discover).
     * Otherwise falls back to SDK auto-discover.
     */
    private Index createIndexConnection() throws Exception {
        // If host is configured, use direct connection via PineconeConfig/PineconeConnection
        // This bypasses the ~4 second auto-discover overhead
        if (StringUtils.isNotBlank(host)) {
            log.info("🚀 Using direct host connection (no auto-discover): {}", host);
            try {
                PineconeConfig config = new PineconeConfig(apiKey);
                config.setHost(host);
                PineconeConnection connection = new PineconeConnection(config);
                // Index constructor: Index(PineconeConfig, PineconeConnection, String indexName)
                Index index = new Index(config, connection, indexName);
                log.info("✅ Successfully created Pinecone index with direct host: {}", host);
                return index;
            } catch (Exception e) {
                log.warn("⚠️ Failed to use direct host connection, falling back to auto-discover: {}", e.getMessage());
                // Fall through to auto-discover
            }
        }

        // Fallback: Use SDK auto-discover (slow but reliable)
        log.info("Using SDK auto-discover for Pinecone host...");
        try {
            java.lang.reflect.Method getIndexConnectionMethod = pinecone.getClass().getMethod("getIndexConnection", String.class);
            Index index = (Index) getIndexConnectionMethod.invoke(pinecone, indexName);
            log.info("Successfully retrieved Pinecone index using getIndexConnection(indexName)");
            return index;
        } catch (NoSuchMethodException e) {
            log.error("Method getIndexConnection(String) not found. Trying fallback...");
            // Fallback: try old method name
            try {
                java.lang.reflect.Method getIndexMethod = pinecone.getClass().getMethod("getIndex", String.class);
                Index index = (Index) getIndexMethod.invoke(pinecone, indexName);
                log.info("Successfully retrieved Pinecone index using getIndex(indexName) - fallback");
                return index;
            } catch (NoSuchMethodException e2) {
                throw new RuntimeException("Cannot find method to get index. Check Pinecone SDK version.");
            }
        }
    }

    /**
     * Invalidate cached index (call when config changes)
     */
    public void invalidateCache() {
        if (cachedIndex != null) {
            try {
                cachedIndex.close();
            } catch (Exception e) {
                log.warn("Failed to close Pinecone index during cache invalidation: {}", e.getMessage());
            }
        }
        cachedIndex = null;
        cachedIndexTimestamp = 0;
        log.info("Pinecone index cache invalidated");
    }

    /**
     * Upsert vectors to Pinecone
     * Using Pinecone Java SDK 5.1.0 API with VectorWithUnsignedIndices
     *
     * @param vectors List of vectors with metadata
     * @param namespace Namespace (optional, uses default if null)
     * @return success status
     */
    public boolean upsertVectors(List<VectorData> vectors, String namespace) {
        log.info("🔵 [PineconeClient] upsertVectors() ENTRY - vectors.size(): {}, namespace: {}", vectors != null ? vectors.size() : 0, namespace);
        try {
            if (!isConfigured()) {
                log.error("🔴 [PineconeClient] Pinecone is not configured");
                return false;
            }
            log.info("🔵 [PineconeClient] Pinecone is configured, proceeding...");
            
            log.info("🔵 [PineconeClient] Getting Pinecone index...");
            Index index = getIndex();
            log.info("🔵 [PineconeClient] Got Pinecone index successfully");
            String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;
            
            log.info("🔵 [PineconeClient] Attempting to upsert {} vectors to Pinecone namespace: {}", vectors != null ? vectors.size() : 0, useNamespace);
            
            if (vectors == null || vectors.isEmpty()) {
                log.warn("🔴 [PineconeClient] vectors is null or empty, returning false");
                return false;
            }
            
            // Convert VectorData to VectorWithUnsignedIndices format
            // Pinecone Java SDK 5.1.0 uses VectorWithUnsignedIndices with buildUpsertVectorWithUnsignedIndices helper
            List<VectorWithUnsignedIndices> pineconeVectors = new ArrayList<>();
            
            for (VectorData vectorData : vectors) {
                // Build metadata Struct
                Struct metadataStruct = null;
                if (vectorData.getMetadata() != null && !vectorData.getMetadata().isEmpty()) {
                    Struct.Builder structBuilder = Struct.newBuilder();
                    for (Map.Entry<String, String> entry : vectorData.getMetadata().entrySet()) {
                        structBuilder.putFields(entry.getKey(), 
                            Value.newBuilder()
                                .setStringValue(entry.getValue())
                                .build());
                    }
                    metadataStruct = structBuilder.build();
                }
                
                // Use buildUpsertVectorWithUnsignedIndices helper function
                // Signature: buildUpsertVectorWithUnsignedIndices(id, values, sparseIndices, sparseValues, metadata)
                VectorWithUnsignedIndices vector = buildUpsertVectorWithUnsignedIndices(
                    vectorData.getId(),
                    vectorData.getValues(),
                    vectorData.hasSparseVector() ? vectorData.getSparseIndices() : null,
                    vectorData.hasSparseVector() ? vectorData.getSparseValues() : null,
                    metadataStruct
                );
                
                pineconeVectors.add(vector);
            }
            
            // Call upsert using Pinecone SDK 5.1.0 API: index.upsert(vectors, namespace)
            log.info("Calling index.upsert() with {} vectors to namespace: {}", pineconeVectors.size(), useNamespace);
            log.info("🔵 [PineconeClient] About to call index.upsert() - index: {}, namespace: {}, vectors count: {}", indexName, useNamespace, pineconeVectors.size());
            try {
                log.debug("About to call index.upsert(), index: {}, namespace: {}", indexName, useNamespace);
                UpsertResponse upsertResponse = null;
                try {
                    upsertResponse = index.upsert(pineconeVectors, useNamespace);
                    log.info("🔵 [PineconeClient] index.upsert() returned successfully");
                } catch (Throwable t) {
                    log.error("🔴 [PineconeClient] index.upsert() threw exception: {}", t.getClass().getName(), t);
                    throw t;
                }
                log.info("index.upsert() call completed, response: {}", upsertResponse != null ? "not null" : "null");
                
                // Check response for success
                if (upsertResponse != null) {
                    long upsertedCount = upsertResponse.getUpsertedCount();
                    log.info("✅ Successfully upserted {} vectors to Pinecone index: {}, namespace: {}. Upserted count: {}", 
                        vectors.size(), indexName, useNamespace, upsertedCount);
                    return true;
                } else {
                    log.warn("⚠️ Upsert response is null - vectors may not have been upserted");
                    return false;
                }
            } catch (Exception upsertEx) {
                log.error("❌ Exception during index.upsert() call - message: {}, class: {}", 
                    upsertEx.getMessage(), upsertEx.getClass().getName(), upsertEx);
                log.error("❌ Exception stack trace:", upsertEx);
                throw upsertEx;
            }
        } catch (RuntimeException e) {
            log.error("🔴 [PineconeClient] Failed to upsert vectors to Pinecone (RuntimeException): {}", e.getMessage(), e);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            log.error("🔴 [PineconeClient] Failed to upsert vectors to Pinecone (Exception): {}", e.getMessage(), e);
            e.printStackTrace();
            return false;
        } finally {
            log.info("🔵 [PineconeClient] upsertVectors() EXIT");
        }
    }

    /**
     * Search vectors in Pinecone
     *
     * @param queryVector Query vector
     * @param topK Number of results to return
     * @param namespace Namespace (optional, uses default if null)
     * @param filterMetadata Optional metadata filter (supports complex operators like $nin)
     * @return List of search results
     */
    public List<SearchResult> searchVectors(List<Float> queryVector, int topK, String namespace, Map<String, Object> filterMetadata) {
        if (!isConfigured()) {
            log.error("Pinecone is not configured");
            return new ArrayList<>();
        }
        
        try {
            Index index = getIndex();
            String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;
            
            // Build filter Struct if provided
            com.google.protobuf.Struct filterStruct = null;
            if (filterMetadata != null && !filterMetadata.isEmpty()) {
                filterStruct = toStruct(filterMetadata);
            }
            
            // Execute search - Pinecone SDK 5.1.0 API
            io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices queryResponse;
            try {
                log.info("Calling index.queryByVector() with topK={}, namespace={}, vector dimension={}, hasFilter={}, includeMetadata=true",
                    topK, useNamespace, queryVector.size(), filterStruct != null);
                    
                if (filterStruct != null) {
                    queryResponse = index.queryByVector(topK, queryVector, useNamespace, filterStruct, true, true);
                } else {
                    queryResponse = index.queryByVector(topK, queryVector, useNamespace, true, true);
                }
                
                log.info("Successfully queried Pinecone, got {} matches", queryResponse.getMatchesList().size());
            } catch (Exception e) {
                log.error("Failed to query Pinecone vectors", e);
                throw new RuntimeException("Failed to query vectors: " + e.getMessage(), e);
            }

            // Convert results
            List<SearchResult> results = new ArrayList<>();
            for (io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices scoredVector : queryResponse.getMatchesList()) {
                SearchResult result = new SearchResult();
                result.setId(scoredVector.getId());
                result.setScore(scoredVector.getScore());

                // Extract metadata from Struct
                com.google.protobuf.Struct metadata = scoredVector.getMetadata();
                if (metadata != null && metadata.getFieldsCount() > 0) {
                    Map<String, String> metadataMap = new HashMap<>();
                    metadata.getFieldsMap().forEach((key, value) -> {
                        if (value.hasStringValue()) {
                            metadataMap.put(key, value.getStringValue());
                        }
                    });
                    result.setMetadata(metadataMap);
                }

                results.add(result);
            }
            
            log.info("Pinecone search returned {} results", results.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to search vectors in Pinecone", e);
            return new ArrayList<>();
        }
    }

    /**
     * Hybrid search: dense + sparse vectors combined.
     * Uses index.query() which supports sparse vector parameters.
     * Alpha scaling: dense * alpha, sparse * (1 - alpha).
     *
     * @param denseVector  Dense query vector (from embedding model)
     * @param sparseIndices Sparse vector indices (from BM25 encoder)
     * @param sparseValues  Sparse vector values (from BM25 encoder)
     * @param alpha        Weighting: 1.0 = pure dense, 0.0 = pure sparse
     * @param topK         Number of results
     * @param namespace    Namespace (optional)
     * @param filterMetadata Optional metadata filter
     * @return List of search results
     */
    public List<SearchResult> searchVectorsHybrid(
            List<Float> denseVector, List<Long> sparseIndices, List<Float> sparseValues,
            double alpha, int topK, String namespace, Map<String, Object> filterMetadata) {
        if (!isConfigured()) {
            log.error("Pinecone is not configured");
            return new ArrayList<>();
        }

        try {
            Index index = getIndex();
            String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;

            // Scale dense vector by alpha
            List<Float> scaledDense = new ArrayList<>(denseVector.size());
            for (Float v : denseVector) {
                scaledDense.add((float) (v * alpha));
            }

            // Scale sparse values by (1 - alpha)
            List<Float> scaledSparse = new ArrayList<>(sparseValues.size());
            for (Float v : sparseValues) {
                scaledSparse.add((float) (v * (1.0 - alpha)));
            }

            // Build filter
            Struct filterStruct = null;
            if (filterMetadata != null && !filterMetadata.isEmpty()) {
                filterStruct = toStruct(filterMetadata);
            }

            // Use index.query() which supports sparse vectors
            // Signature: query(topK, vector, sparseIndices, sparseValues, sparseId, namespace, filter, includeValues, includeMetadata)
            log.info("Calling hybrid search: topK={}, alpha={}, denseSize={}, sparseSize={}, namespace={}",
                topK, alpha, scaledDense.size(), scaledSparse.size(), useNamespace);

            io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices queryResponse =
                index.query(topK, scaledDense, sparseIndices, scaledSparse,
                    null, useNamespace, filterStruct, true, true);

            // Convert results
            List<SearchResult> results = new ArrayList<>();
            for (io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices scoredVector : queryResponse.getMatchesList()) {
                SearchResult result = new SearchResult();
                result.setId(scoredVector.getId());
                result.setScore(scoredVector.getScore());

                com.google.protobuf.Struct metadata = scoredVector.getMetadata();
                if (metadata != null && metadata.getFieldsCount() > 0) {
                    Map<String, String> metadataMap = new HashMap<>();
                    metadata.getFieldsMap().forEach((key, value) -> {
                        if (value.hasStringValue()) {
                            metadataMap.put(key, value.getStringValue());
                        }
                    });
                    result.setMetadata(metadataMap);
                }

                results.add(result);
            }

            log.info("Hybrid search returned {} results", results.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to perform hybrid search in Pinecone", e);
            return new ArrayList<>();
        }
    }

    private com.google.protobuf.Struct toStruct(Map<String, Object> map) {
        com.google.protobuf.Struct.Builder builder = com.google.protobuf.Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.putFields(entry.getKey(), toValue(entry.getValue()));
        }
        return builder.build();
    }

    private com.google.protobuf.Value toValue(Object value) {
        if (value == null) {
            return com.google.protobuf.Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
        } else if (value instanceof Boolean) {
            return com.google.protobuf.Value.newBuilder().setBoolValue((Boolean) value).build();
        } else if (value instanceof Number) {
            return com.google.protobuf.Value.newBuilder().setNumberValue(((Number) value).doubleValue()).build();
        } else if (value instanceof String) {
            return com.google.protobuf.Value.newBuilder().setStringValue((String) value).build();
        } else if (value instanceof Map) {
            return com.google.protobuf.Value.newBuilder().setStructValue(toStruct((Map<String, Object>) value)).build();
        } else if (value instanceof List) {
            com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
            for (Object item : (List<?>) value) {
                listBuilder.addValues(toValue(item));
            }
            return com.google.protobuf.Value.newBuilder().setListValue(listBuilder).build();
        } else {
            return com.google.protobuf.Value.newBuilder().setStringValue(value.toString()).build();
        }
    }

    /**
     * Delete vectors by IDs
     *
     * @param ids List of vector IDs to delete
     * @param namespace Namespace (optional, uses default if null)
     * @return success status
     */
    public boolean deleteVectors(List<String> ids, String namespace) {
        if (!isConfigured()) {
            log.error("Pinecone is not configured");
            return false;
        }
        
        try {
            Index index = getIndex();
            String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;
            
            // Pinecone SDK 5.1.0 delete API
            // Note: Delete API may vary by version
            // Using delete method with proper signature
            index.delete(ids, false, useNamespace, null);
            log.info("Deleted {} vectors from Pinecone index: {}, namespace: {}", 
                ids.size(), indexName, useNamespace);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete vectors from Pinecone", e);
            return false;
        }
    }

    /**
     * Update metadata for a single vector (without changing the vector values).
     * Used for toggling active/inactive state.
     *
     * @param vectorId The vector ID to update
     * @param metadata Metadata fields to update (merged with existing)
     * @param namespace Namespace (optional, uses default if null)
     * @return success status
     */
    public boolean updateVectorMetadata(String vectorId, Map<String, String> metadata, String namespace) {
        if (!isConfigured()) {
            log.error("Pinecone is not configured");
            return false;
        }

        try {
            Index index = getIndex();
            String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;

            // Build metadata Struct
            Struct.Builder structBuilder = Struct.newBuilder();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                structBuilder.putFields(entry.getKey(),
                    Value.newBuilder().setStringValue(entry.getValue()).build());
            }
            Struct metadataStruct = structBuilder.build();

            // Pinecone SDK 5.1.0 update: (id, values, metadata, namespace, sparseIndices, sparseValues)
            index.update(vectorId, null, metadataStruct, useNamespace, null, null);
            log.info("Updated metadata for vector: {} in namespace: {}, metadata: {}",
                vectorId, useNamespace, metadata);
            return true;
        } catch (Exception e) {
            log.error("Failed to update vector metadata in Pinecone: vectorId={}", vectorId, e);
            return false;
        }
    }

    /**
     * Batch update metadata for multiple vectors.
     *
     * @param vectorIds List of vector IDs to update
     * @param metadata Metadata fields to set on each vector
     * @param namespace Namespace (optional)
     * @return number of successfully updated vectors
     */
    public int batchUpdateVectorMetadata(List<String> vectorIds, Map<String, String> metadata, String namespace) {
        if (!isConfigured()) {
            log.error("Pinecone is not configured");
            return 0;
        }

        int successCount = 0;
        for (String vectorId : vectorIds) {
            if (updateVectorMetadata(vectorId, metadata, namespace)) {
                successCount++;
            }
        }
        log.info("Batch metadata update: {}/{} vectors updated in namespace: {}",
            successCount, vectorIds.size(), namespace);
        return successCount;
    }

    /**
     * Get index statistics
     */
    public DescribeIndexStatsResponse getIndexStats() {
        if (!isConfigured()) {
            throw new IllegalStateException("Pinecone is not configured");
        }
        
        try {
            Index index = getIndex();
            return index.describeIndexStats();
        } catch (Exception e) {
            log.error("Failed to get Pinecone index stats", e);
            throw new RuntimeException("Failed to get index stats", e);
        }
    }

    /**
     * List all vector IDs in a namespace using REST API
     * Pinecone list endpoint: GET /vectors/list
     *
     * @param namespace Namespace (optional, uses default if null)
     * @param limit Maximum number of IDs to return per page (max 100)
     * @param paginationToken Token for pagination (null for first page)
     * @return ListVectorsResult containing IDs and pagination token
     */
    public ListVectorsResult listVectors(String namespace, int limit, String paginationToken) {
        if (!isConfigured()) {
            throw new IllegalStateException("Pinecone is not configured");
        }

        String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;
        int useLimit = Math.min(Math.max(limit, 1), 100); // Pinecone max is 100

        try {
            // Build URL with query parameters
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(host);
            if (!host.endsWith("/")) urlBuilder.append("/");
            urlBuilder.append("vectors/list?");
            urlBuilder.append("namespace=").append(java.net.URLEncoder.encode(useNamespace, "UTF-8"));
            urlBuilder.append("&limit=").append(useLimit);
            if (StringUtils.isNotBlank(paginationToken)) {
                urlBuilder.append("&paginationToken=").append(java.net.URLEncoder.encode(paginationToken, "UTF-8"));
            }

            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("Api-Key", apiKey)
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Pinecone list vectors failed: status={}, url={}", response.code(), urlBuilder);
                    throw new RuntimeException("Failed to list vectors: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                if (StringUtils.isBlank(responseBody)) {
                    return new ListVectorsResult(new ArrayList<>(), null);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
                
                List<String> ids = new ArrayList<>();
                Object vectorsObj = parsed.get("vectors");
                if (vectorsObj instanceof List) {
                    for (Object item : (List<?>) vectorsObj) {
                        if (item instanceof Map) {
                            Object idObj = ((Map<?, ?>) item).get("id");
                            if (idObj != null) {
                                ids.add(String.valueOf(idObj));
                            }
                        }
                    }
                }

                String nextToken = null;
                Object paginationObj = parsed.get("pagination");
                if (paginationObj instanceof Map) {
                    Object nextObj = ((Map<?, ?>) paginationObj).get("next");
                    if (nextObj != null) {
                        nextToken = String.valueOf(nextObj);
                    }
                }

                log.info("Listed {} vectors from Pinecone namespace: {}, hasMore: {}", 
                    ids.size(), useNamespace, nextToken != null);
                return new ListVectorsResult(ids, nextToken);
            }
        } catch (Exception e) {
            log.error("Failed to list vectors from Pinecone", e);
            throw new RuntimeException("Failed to list vectors: " + e.getMessage(), e);
        }
    }

    /**
     * List ALL vector IDs in a namespace (handles pagination automatically)
     *
     * @param namespace Namespace (optional, uses default if null)
     * @return List of all vector IDs
     */
    public List<String> listAllVectors(String namespace) {
        List<String> allIds = new ArrayList<>();
        String paginationToken = null;
        
        do {
            ListVectorsResult result = listVectors(namespace, 100, paginationToken);
            allIds.addAll(result.getIds());
            paginationToken = result.getPaginationToken();
            
            log.info("Listed {} vectors so far, hasMore: {}", allIds.size(), paginationToken != null);
        } while (paginationToken != null);
        
        log.info("Total vectors listed from Pinecone: {}", allIds.size());
        return allIds;
    }

    /**
     * Fetch vectors by IDs with metadata using REST API
     * Pinecone fetch endpoint: GET /vectors/fetch
     *
     * @param ids List of vector IDs to fetch (max 1000)
     * @param namespace Namespace (optional, uses default if null)
     * @return List of VectorData with metadata
     */
    public List<VectorData> fetchVectors(List<String> ids, String namespace) {
        if (!isConfigured()) {
            throw new IllegalStateException("Pinecone is not configured");
        }

        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        String useNamespace = StringUtils.isNotBlank(namespace) ? namespace : this.namespace;
        
        // Pinecone fetch supports max 1000 IDs per request
        List<String> idsToFetch = ids.size() > 1000 ? ids.subList(0, 1000) : ids;

        try {
            // Build URL with query parameters
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(host);
            if (!host.endsWith("/")) urlBuilder.append("/");
            urlBuilder.append("vectors/fetch?");
            urlBuilder.append("namespace=").append(java.net.URLEncoder.encode(useNamespace, "UTF-8"));
            for (String id : idsToFetch) {
                urlBuilder.append("&ids=").append(java.net.URLEncoder.encode(id, "UTF-8"));
            }

            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("Api-Key", apiKey)
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Pinecone fetch vectors failed: status={}", response.code());
                    throw new RuntimeException("Failed to fetch vectors: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                if (StringUtils.isBlank(responseBody)) {
                    return new ArrayList<>();
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
                
                List<VectorData> results = new ArrayList<>();
                Object vectorsObj = parsed.get("vectors");
                if (vectorsObj instanceof Map) {
                    Map<String, Object> vectorsMap = (Map<String, Object>) vectorsObj;
                    for (Map.Entry<String, Object> entry : vectorsMap.entrySet()) {
                        String vectorId = entry.getKey();
                        if (entry.getValue() instanceof Map) {
                            Map<String, Object> vectorData = (Map<String, Object>) entry.getValue();
                            
                            // Extract values (optional, may be large)
                            List<Float> values = new ArrayList<>();
                            Object valuesObj = vectorData.get("values");
                            if (valuesObj instanceof List) {
                                for (Object v : (List<?>) valuesObj) {
                                    if (v instanceof Number) {
                                        values.add(((Number) v).floatValue());
                                    }
                                }
                            }
                            
                            // Extract metadata
                            Map<String, String> metadata = new HashMap<>();
                            Object metadataObj = vectorData.get("metadata");
                            if (metadataObj instanceof Map) {
                                for (Map.Entry<?, ?> metaEntry : ((Map<?, ?>) metadataObj).entrySet()) {
                                    if (metaEntry.getKey() != null && metaEntry.getValue() != null) {
                                        metadata.put(String.valueOf(metaEntry.getKey()), 
                                            String.valueOf(metaEntry.getValue()));
                                    }
                                }
                            }
                            
                            results.add(new VectorData(vectorId, values, metadata));
                        }
                    }
                }

                log.info("Fetched {} vectors from Pinecone namespace: {}", results.size(), useNamespace);
                return results;
            }
        } catch (Exception e) {
            log.error("Failed to fetch vectors from Pinecone", e);
            throw new RuntimeException("Failed to fetch vectors: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch ALL vectors by IDs (handles batching for > 1000 IDs)
     *
     * @param ids List of vector IDs to fetch
     * @param namespace Namespace (optional, uses default if null)
     * @return List of all VectorData with metadata
     */
    public List<VectorData> fetchAllVectors(List<String> ids, String namespace) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        List<VectorData> allResults = new ArrayList<>();
        int batchSize = 1000;
        
        for (int i = 0; i < ids.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, ids.size());
            List<String> batch = ids.subList(i, endIndex);
            List<VectorData> batchResults = fetchVectors(batch, namespace);
            allResults.addAll(batchResults);
            
            log.info("Fetched batch {}-{} of {}, total fetched: {}", 
                i, endIndex, ids.size(), allResults.size());
        }
        
        return allResults;
    }

    /**
     * Result class for list vectors operation
     */
    public static class ListVectorsResult {
        private List<String> ids;
        private String paginationToken;

        public ListVectorsResult(List<String> ids, String paginationToken) {
            this.ids = ids;
            this.paginationToken = paginationToken;
        }

        public List<String> getIds() { return ids; }
        public String getPaginationToken() { return paginationToken; }
    }

    /**
     * Vector data class (supports both dense-only and hybrid dense+sparse)
     */
    public static class VectorData {
        private String id;
        private List<Float> values;
        private Map<String, String> metadata;
        // Optional sparse vector fields for hybrid search
        private List<Long> sparseIndices;
        private List<Float> sparseValues;

        public VectorData(String id, List<Float> values, Map<String, String> metadata) {
            this.id = id;
            this.values = values;
            this.metadata = metadata;
        }

        public VectorData(String id, List<Float> values, Map<String, String> metadata,
                          List<Long> sparseIndices, List<Float> sparseValues) {
            this.id = id;
            this.values = values;
            this.metadata = metadata;
            this.sparseIndices = sparseIndices;
            this.sparseValues = sparseValues;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public List<Float> getValues() { return values; }
        public void setValues(List<Float> values) { this.values = values; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
        public List<Long> getSparseIndices() { return sparseIndices; }
        public void setSparseIndices(List<Long> sparseIndices) { this.sparseIndices = sparseIndices; }
        public List<Float> getSparseValues() { return sparseValues; }
        public void setSparseValues(List<Float> sparseValues) { this.sparseValues = sparseValues; }
        public boolean hasSparseVector() { return sparseIndices != null && !sparseIndices.isEmpty(); }
    }

    /**
     * Search result class
     */
    public static class SearchResult {
        private String id;
        private float score;
        private Map<String, String> metadata;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    }
}

