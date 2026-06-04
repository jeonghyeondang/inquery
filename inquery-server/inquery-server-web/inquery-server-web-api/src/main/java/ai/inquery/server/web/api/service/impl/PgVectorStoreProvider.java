package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VectorStoreProvider implementation using pgvector (PostgreSQL extension).
 * Uses the existing application PostgreSQL database -- no additional infrastructure needed.
 * The extension and table are lazily initialised on first use.
 */
@Slf4j
@Component
public class PgVectorStoreProvider implements VectorStoreProvider {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean initialised = false;
    private volatile boolean available = false;

    public PgVectorStoreProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public VectorStoreType getType() {
        return VectorStoreType.PGVECTOR;
    }

    // ── Lazy initialisation ────────────────────────────────────

    private synchronized void ensureInitialised() {
        if (initialised) return;
        try (Connection conn = dataSource.getConnection()) {
            // Register PGvector JDBC type
            PGvector.addVectorType(conn);

            conn.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");

            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS vector_embeddings (" +
                "  id VARCHAR(512) PRIMARY KEY," +
                "  embedding vector(384)," +
                "  namespace VARCHAR(128) NOT NULL DEFAULT 'default'," +
                "  metadata JSONB," +
                "  active BOOLEAN DEFAULT TRUE," +
                "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");

            // Indexes (IF NOT EXISTS)
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_ve_namespace ON vector_embeddings (namespace)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_ve_active ON vector_embeddings (active)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_ve_metadata ON vector_embeddings USING gin (metadata)");

            // IVFFlat index for approximate nearest-neighbour search.
            // Requires at least some rows in the table; safe to attempt here.
            try {
                conn.createStatement().execute(
                    "CREATE INDEX IF NOT EXISTS idx_ve_embedding ON vector_embeddings " +
                    "USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)");
            } catch (SQLException e) {
                log.info("IVFFlat index creation deferred (table may be empty): {}", e.getMessage());
            }

            available = true;
            log.info("pgvector initialised successfully");
        } catch (Exception e) {
            log.warn("pgvector not available: {}. Install the extension to use this provider.", e.getMessage());
            available = false;
        }
        initialised = true;
    }

    // ── Core CRUD ──────────────────────────────────────────────

    @Override
    public boolean upsert(List<VectorData> vectors, String namespace) {
        ensureInitialised();
        if (!available || vectors == null || vectors.isEmpty()) return false;

        String sql = "INSERT INTO vector_embeddings (id, embedding, namespace, metadata, active, updated_at) " +
                     "VALUES (?, ?::vector, ?, ?::jsonb, ?, CURRENT_TIMESTAMP) " +
                     "ON CONFLICT (id) DO UPDATE SET " +
                     "  embedding = EXCLUDED.embedding, " +
                     "  metadata = EXCLUDED.metadata, " +
                     "  active = EXCLUDED.active, " +
                     "  updated_at = CURRENT_TIMESTAMP";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (VectorData v : vectors) {
                ps.setString(1, v.getId());
                ps.setString(2, toVectorLiteral(v.getValues()));
                ps.setString(3, namespace != null ? namespace : "default");
                ps.setString(4, objectMapper.writeValueAsString(
                        v.getMetadata() != null ? v.getMetadata() : Collections.emptyMap()));
                String activeVal = v.getMetadata() != null ? v.getMetadata().get("active") : null;
                ps.setBoolean(5, activeVal == null || !"false".equalsIgnoreCase(activeVal));
                ps.addBatch();
            }
            ps.executeBatch();
            log.info("Upserted {} vectors to pgvector namespace: {}", vectors.size(), namespace);
            return true;
        } catch (Exception e) {
            log.error("pgvector upsert failed", e);
            return false;
        }
    }

    @Override
    public List<VectorSearchResult> search(List<Float> queryVector, int topK,
                                           String namespace, Map<String, Object> filter) {
        ensureInitialised();
        if (!available || queryVector == null || queryVector.isEmpty()) return new ArrayList<>();

        StringBuilder sql = new StringBuilder(
            "SELECT id, metadata, embedding <=> ?::vector AS distance " +
            "FROM vector_embeddings WHERE namespace = ?");

        // Default: active = true filter (matches Pinecone behaviour)
        if (filter != null && filter.containsKey("active")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> activeFilter = (Map<String, Object>) filter.get("active");
            if (activeFilter != null && "true".equals(activeFilter.get("$eq"))) {
                sql.append(" AND active = true");
            }
        } else {
            sql.append(" AND active = true");
        }

        // tableName $in filter
        if (filter != null && filter.containsKey("tableName")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tnFilter = (Map<String, Object>) filter.get("tableName");
            if (tnFilter != null && tnFilter.containsKey("$in")) {
                @SuppressWarnings("unchecked")
                List<String> names = (List<String>) tnFilter.get("$in");
                if (names != null && !names.isEmpty()) {
                    String placeholders = names.stream().map(n -> "?").collect(Collectors.joining(","));
                    sql.append(" AND metadata->>'tableName' IN (").append(placeholders).append(")");
                }
            }
        }

        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            String vecLiteral = toVectorLiteral(queryVector);
            ps.setString(idx++, vecLiteral);
            ps.setString(idx++, namespace != null ? namespace : "default");

            // Bind tableName $in values
            if (filter != null && filter.containsKey("tableName")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tnFilter = (Map<String, Object>) filter.get("tableName");
                if (tnFilter != null && tnFilter.containsKey("$in")) {
                    @SuppressWarnings("unchecked")
                    List<String> names = (List<String>) tnFilter.get("$in");
                    if (names != null) {
                        for (String name : names) {
                            ps.setString(idx++, name);
                        }
                    }
                }
            }

            ps.setString(idx++, vecLiteral);
            ps.setInt(idx, topK);

            List<VectorSearchResult> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VectorSearchResult r = new VectorSearchResult();
                    r.setId(rs.getString("id"));
                    r.setScore(1.0f - rs.getFloat("distance")); // cosine distance → similarity
                    String metaJson = rs.getString("metadata");
                    if (metaJson != null) {
                        r.setMetadata(objectMapper.readValue(metaJson,
                                new TypeReference<Map<String, String>>() {}));
                    }
                    results.add(r);
                }
            }
            log.info("pgvector search returned {} results (namespace={})", results.size(), namespace);
            return results;
        } catch (Exception e) {
            log.error("pgvector search failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean delete(List<String> ids, String namespace) {
        ensureInitialised();
        if (!available || ids == null || ids.isEmpty()) return false;

        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM vector_embeddings WHERE id IN (" + placeholders + ") AND namespace = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String id : ids) ps.setString(idx++, id);
            ps.setString(idx, namespace != null ? namespace : "default");
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            log.error("pgvector delete failed", e);
            return false;
        }
    }

    @Override
    public boolean updateMetadata(String vectorId, Map<String, String> metadata, String namespace) {
        ensureInitialised();
        if (!available) return false;

        // Merge metadata and handle 'active' flag
        String sql = "UPDATE vector_embeddings SET " +
                     "  metadata = metadata || ?::jsonb, " +
                     "  active = COALESCE((?::jsonb->>'active')::boolean, active), " +
                     "  updated_at = CURRENT_TIMESTAMP " +
                     "WHERE id = ? AND namespace = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String metaJson = objectMapper.writeValueAsString(metadata);
            ps.setString(1, metaJson);
            ps.setString(2, metaJson);
            ps.setString(3, vectorId);
            ps.setString(4, namespace != null ? namespace : "default");
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("pgvector updateMetadata failed for {}", vectorId, e);
            return false;
        }
    }

    // ── Management ─────────────────────────────────────────────

    @Override
    public List<String> listAllVectors(String namespace) {
        ensureInitialised();
        if (!available) return new ArrayList<>();

        String sql = "SELECT id FROM vector_embeddings WHERE namespace = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, namespace != null ? namespace : "default");
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("id"));
            }
            return ids;
        } catch (Exception e) {
            log.error("pgvector listAllVectors failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<VectorData> fetchVectors(List<String> ids, String namespace) {
        ensureInitialised();
        if (!available || ids == null || ids.isEmpty()) return new ArrayList<>();

        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, metadata FROM vector_embeddings WHERE id IN (" + placeholders + ") AND namespace = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String id : ids) ps.setString(idx++, id);
            ps.setString(idx, namespace != null ? namespace : "default");

            List<VectorData> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VectorData v = new VectorData();
                    v.setId(rs.getString("id"));
                    String metaJson = rs.getString("metadata");
                    if (metaJson != null) {
                        v.setMetadata(objectMapper.readValue(metaJson,
                                new TypeReference<Map<String, String>>() {}));
                    }
                    results.add(v);
                }
            }
            return results;
        } catch (Exception e) {
            log.error("pgvector fetchVectors failed", e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isConfigured() {
        ensureInitialised();
        return available;
    }

    @Override
    public void refresh() {
        initialised = false;
        available = false;
        ensureInitialised();
    }

    @Override
    public Map<String, Object> getStats() {
        ensureInitialised();
        Map<String, Object> stats = new HashMap<>();
        if (!available) {
            stats.put("available", false);
            return stats;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT namespace, COUNT(*) AS cnt FROM vector_embeddings GROUP BY namespace");
            Map<String, Object> namespaces = new HashMap<>();
            long total = 0;
            while (rs.next()) {
                long cnt = rs.getLong("cnt");
                namespaces.put(rs.getString("namespace"), cnt);
                total += cnt;
            }
            stats.put("available", true);
            stats.put("totalVectorCount", total);
            stats.put("dimension", 384);
            stats.put("namespaces", namespaces);
        } catch (Exception e) {
            log.error("pgvector getStats failed", e);
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    // ── Helpers ─────────────────────────────────────────────────

    private String toVectorLiteral(List<Float> values) {
        if (values == null || values.isEmpty()) return "[0]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
