package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VectorStoreProvider implementation using Qdrant REST API.
 * Avoids gRPC dependency conflicts by using HTTP/JSON exclusively.
 */
@Slf4j
@Component
public class QdrantVectorStoreProvider implements VectorStoreProvider {

    public static final String QDRANT_HOST = "qdrant.host";
    public static final String QDRANT_PORT = "qdrant.port";
    public static final String QDRANT_API_KEY = "qdrant.apiKey";
    public static final String QDRANT_COLLECTION = "qdrant.collectionName";
    public static final String QDRANT_USE_TLS = "qdrant.useTls";

    private static final int VECTOR_DIMENSION = 384;
    private static final Gson GSON = new Gson();

    private volatile HttpClient httpClient;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String collectionName = "table-schemas";
    private volatile boolean configured = false;

    @Override
    public VectorStoreType getType() {
        return VectorStoreType.QDRANT;
    }

    @Override
    public void refresh() {
        loadConfig();
        if (configured) {
            initClient();
        }
    }

    private void loadConfig() {
        try {
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);

            String host = readConfig(configService, QDRANT_HOST, null);
            String portStr = readConfig(configService, QDRANT_PORT, "6333");
            int port = Integer.parseInt(portStr);
            apiKey = readConfig(configService, QDRANT_API_KEY, null);
            collectionName = readConfig(configService, QDRANT_COLLECTION, "table-schemas");
            boolean useTls = "true".equalsIgnoreCase(readConfig(configService, QDRANT_USE_TLS, "false"));

            if (StringUtils.isNotBlank(host)) {
                String scheme = useTls ? "https" : "http";
                baseUrl = scheme + "://" + host + ":" + port;
                configured = true;
            } else {
                configured = false;
            }
        } catch (Exception e) {
            log.warn("Failed to load Qdrant config: {}", e.getMessage());
            configured = false;
        }
    }

    private String readConfig(ConfigService configService, String key, String defaultValue) {
        try {
            Config cfg = configService.find(key).getData();
            if (cfg != null && StringUtils.isNotBlank(cfg.getContent())) {
                return cfg.getContent().trim();
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private void initClient() {
        try {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            ensureCollectionExists();
            log.info("Qdrant REST client initialized: baseUrl={}, collection={}", baseUrl, collectionName);
        } catch (Exception e) {
            log.error("Failed to initialise Qdrant REST client", e);
            httpClient = null;
            configured = false;
        }
    }

    private void ensureCollectionExists() {
        try {
            HttpResponse<String> resp = doGet("/collections/" + collectionName);
            if (resp.statusCode() == 404) {
                JsonObject body = new JsonObject();
                JsonObject vectors = new JsonObject();
                vectors.addProperty("size", VECTOR_DIMENSION);
                vectors.addProperty("distance", "Cosine");
                body.add("vectors", vectors);

                HttpResponse<String> createResp = doPut("/collections/" + collectionName, body.toString());
                if (createResp.statusCode() >= 200 && createResp.statusCode() < 300) {
                    log.info("Created Qdrant collection: {} (dim={}, cosine)", collectionName, VECTOR_DIMENSION);
                } else {
                    log.error("Failed to create Qdrant collection: {}", createResp.body());
                }
            }
        } catch (Exception e) {
            log.error("Failed to ensure Qdrant collection exists", e);
        }
    }

    @Override
    public boolean upsert(List<VectorData> vectorDataList, String namespace) {
        if (!isReady() || vectorDataList == null || vectorDataList.isEmpty()) return false;

        try {
            JsonArray points = new JsonArray();
            for (VectorData v : vectorDataList) {
                JsonObject point = new JsonObject();
                point.addProperty("id", toUUID(v.getId()).toString());

                JsonArray vector = new JsonArray();
                for (Float val : v.getValues()) vector.add(val);
                point.add("vector", vector);

                JsonObject payload = new JsonObject();
                payload.addProperty("_id", v.getId());
                payload.addProperty("_namespace", namespace != null ? namespace : "default");
                if (v.getMetadata() != null) {
                    v.getMetadata().forEach(payload::addProperty);
                }
                point.add("payload", payload);
                points.add(point);
            }

            JsonObject body = new JsonObject();
            body.add("points", points);

            HttpResponse<String> resp = doPut("/collections/" + collectionName + "/points?wait=true", body.toString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Upserted {} vectors to Qdrant collection: {}", vectorDataList.size(), collectionName);
                return true;
            } else {
                log.error("Qdrant upsert failed: {}", resp.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Qdrant upsert failed", e);
            return false;
        }
    }

    @Override
    public List<VectorSearchResult> search(List<Float> queryVector, int topK,
                                           String namespace, Map<String, Object> filter) {
        if (!isReady() || queryVector == null) return new ArrayList<>();

        try {
            JsonObject body = new JsonObject();
            JsonArray vector = new JsonArray();
            for (Float v : queryVector) vector.add(v);
            body.add("vector", vector);
            body.addProperty("limit", topK);
            body.addProperty("with_payload", true);

            JsonObject filterObj = buildFilter(namespace, filter);
            body.add("filter", filterObj);

            HttpResponse<String> resp = doPost("/collections/" + collectionName + "/points/search", body.toString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.error("Qdrant search failed: {}", resp.body());
                return new ArrayList<>();
            }

            JsonObject respBody = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray results = respBody.getAsJsonArray("result");
            if (results == null) return new ArrayList<>();

            List<VectorSearchResult> searchResults = new ArrayList<>();
            for (JsonElement el : results) {
                JsonObject sp = el.getAsJsonObject();
                VectorSearchResult r = new VectorSearchResult();
                JsonObject payload = sp.getAsJsonObject("payload");
                if (payload != null && payload.has("_id")) {
                    r.setId(payload.get("_id").getAsString());
                }
                r.setScore(sp.get("score").getAsFloat());
                Map<String, String> meta = new HashMap<>();
                if (payload != null) {
                    payload.entrySet().forEach(entry -> {
                        if (!entry.getKey().startsWith("_") && entry.getValue().isJsonPrimitive()) {
                            meta.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    });
                }
                r.setMetadata(meta);
                searchResults.add(r);
            }
            return searchResults;
        } catch (Exception e) {
            log.error("Qdrant search failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean delete(List<String> ids, String namespace) {
        if (!isReady() || ids == null || ids.isEmpty()) return false;

        try {
            JsonObject body = new JsonObject();
            JsonArray points = new JsonArray();
            for (String id : ids) points.add(toUUID(id).toString());
            body.add("points", points);

            HttpResponse<String> resp = doPost("/collections/" + collectionName + "/points/delete?wait=true", body.toString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.error("Qdrant delete failed", e);
            return false;
        }
    }

    @Override
    public boolean updateMetadata(String vectorId, Map<String, String> metadata, String namespace) {
        if (!isReady()) return false;

        try {
            JsonObject body = new JsonObject();
            JsonObject payload = new JsonObject();
            metadata.forEach(payload::addProperty);
            body.add("payload", payload);

            JsonArray points = new JsonArray();
            points.add(toUUID(vectorId).toString());
            body.add("points", points);

            HttpResponse<String> resp = doPost(
                    "/collections/" + collectionName + "/points/payload?wait=true", body.toString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.error("Qdrant updateMetadata failed for {}", vectorId, e);
            return false;
        }
    }

    @Override
    public List<String> listAllVectors(String namespace) {
        if (!isReady()) return new ArrayList<>();

        try {
            List<String> allIds = new ArrayList<>();
            String offsetId = null;

            while (true) {
                JsonObject body = new JsonObject();
                body.addProperty("limit", 100);
                body.addProperty("with_payload", true);

                JsonObject filterObj = new JsonObject();
                JsonArray must = new JsonArray();
                must.add(matchKeyword("_namespace", namespace != null ? namespace : "default"));
                filterObj.add("must", must);
                body.add("filter", filterObj);

                if (offsetId != null) {
                    body.addProperty("offset", offsetId);
                }

                HttpResponse<String> resp = doPost(
                        "/collections/" + collectionName + "/points/scroll", body.toString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) break;

                JsonObject respBody = GSON.fromJson(resp.body(), JsonObject.class);
                JsonObject result = respBody.getAsJsonObject("result");
                JsonArray points = result.getAsJsonArray("points");

                for (JsonElement el : points) {
                    JsonObject point = el.getAsJsonObject();
                    JsonObject payload = point.getAsJsonObject("payload");
                    if (payload != null && payload.has("_id")) {
                        allIds.add(payload.get("_id").getAsString());
                    } else {
                        allIds.add(point.get("id").getAsString());
                    }
                }

                if (result.has("next_page_offset") && !result.get("next_page_offset").isJsonNull()) {
                    offsetId = result.get("next_page_offset").getAsString();
                } else {
                    break;
                }
            }
            return allIds;
        } catch (Exception e) {
            log.error("Qdrant listAllVectors failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<VectorData> fetchVectors(List<String> ids, String namespace) {
        if (!isReady() || ids == null || ids.isEmpty()) return new ArrayList<>();

        try {
            JsonObject body = new JsonObject();
            JsonArray idArray = new JsonArray();
            for (String id : ids) idArray.add(toUUID(id).toString());
            body.add("ids", idArray);
            body.addProperty("with_payload", true);

            HttpResponse<String> resp = doPost(
                    "/collections/" + collectionName + "/points", body.toString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return new ArrayList<>();

            JsonObject respBody = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray results = respBody.getAsJsonArray("result");
            if (results == null) return new ArrayList<>();

            return results.asList().stream().map(el -> {
                JsonObject p = el.getAsJsonObject();
                VectorData v = new VectorData();
                JsonObject payload = p.getAsJsonObject("payload");
                if (payload != null && payload.has("_id")) {
                    v.setId(payload.get("_id").getAsString());
                }
                Map<String, String> meta = new HashMap<>();
                if (payload != null) {
                    payload.entrySet().forEach(entry -> {
                        if (!entry.getKey().startsWith("_") && entry.getValue().isJsonPrimitive()) {
                            meta.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    });
                }
                v.setMetadata(meta);
                return v;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Qdrant fetchVectors failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isConfigured() {
        if (!configured) {
            loadConfig();
        }
        return configured && httpClient != null;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        if (!isReady()) {
            stats.put("configured", false);
            return stats;
        }

        try {
            HttpResponse<String> resp = doGet("/collections/" + collectionName);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonObject respBody = GSON.fromJson(resp.body(), JsonObject.class);
                JsonObject result = respBody.getAsJsonObject("result");
                stats.put("configured", true);
                stats.put("totalVectorCount", result.has("points_count") ? result.get("points_count").getAsLong() : 0);
                stats.put("dimension", VECTOR_DIMENSION);
                stats.put("collection", collectionName);
                stats.put("status", result.has("status") ? result.get("status").getAsString() : "unknown");
            }
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    @Override
    public void ensureConnection() {
        if (configured && httpClient == null) {
            initClient();
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────

    private boolean isReady() {
        if (httpClient == null && configured) initClient();
        return httpClient != null && configured;
    }

    private HttpResponse<String> doGet(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        addHeaders(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> doPost(String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        addHeaders(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> doPut(String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        addHeaders(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void addHeaders(HttpRequest.Builder builder) {
        builder.header("Content-Type", "application/json");
        if (StringUtils.isNotBlank(apiKey)) {
            builder.header("api-key", apiKey);
        }
    }

    // ── Filter / UUID helpers ───────────────────────────────────

    private JsonObject buildFilter(String namespace, Map<String, Object> filter) {
        JsonObject filterObj = new JsonObject();
        JsonArray must = new JsonArray();

        must.add(matchKeyword("_namespace", namespace != null ? namespace : "default"));

        if (filter != null && filter.containsKey("active")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> activeFilter = (Map<String, Object>) filter.get("active");
            if (activeFilter != null && "true".equals(activeFilter.get("$eq"))) {
                must.add(matchKeyword("active", "true"));
            }
        } else {
            must.add(matchKeyword("active", "true"));
        }

        filterObj.add("must", must);
        return filterObj;
    }

    private JsonObject matchKeyword(String key, String value) {
        JsonObject condition = new JsonObject();
        condition.addProperty("key", key);
        JsonObject match = new JsonObject();
        match.addProperty("value", value);
        condition.add("match", match);
        return condition;
    }

    private UUID toUUID(String stringId) {
        return UUID.nameUUIDFromBytes(stringId.getBytes(StandardCharsets.UTF_8));
    }
}
