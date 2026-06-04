package ai.inquery.server.domain.core.lineage;

import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.TableLineageDO;
import ai.inquery.server.domain.repository.mapper.TableLineageMapper;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.ConnectionPool;
import ai.inquery.spi.sql.InqueryContext;
import java.sql.SQLException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrator service for auto-detecting lineage.
 * Handles context setup, SQL execution, persistence, and status tracking.
 */
@Slf4j
@Service
public class LineageDetectService {

    @Autowired
    private LineageDetectorFactory detectorFactory;

    private static final ConcurrentHashMap<Long, LineageDetectStatus> statusMap = new ConcurrentHashMap<>();

    /**
     * In-memory lineage graph cache, shared with DataCatalogController.
     */
    private static final ConcurrentHashMap<Long, LineageGraph> lineageGraphCache = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<Long, LineageGraph> getLineageGraphCache() {
        return lineageGraphCache;
    }

    /**
     * Detect lineage for a data source and persist results.
     * Caller must ensure Dbutils.setSession() is active.
     */
    public void detectAndPersist(Long dataSourceId, ConnectInfo connectInfo) {
        String dbType = connectInfo.getDbType();
        Optional<LineageDetector> detectorOpt = detectorFactory.getDetector(dbType);
        if (detectorOpt.isEmpty()) {
            log.info("No lineage detector available for dbType={}, dataSourceId={}", dbType, dataSourceId);
            return;
        }

        LineageDetectStatus status = new LineageDetectStatus();
        status.setDataSourceId(dataSourceId);
        status.setState(LineageDetectStatus.State.RUNNING);
        status.setLastRunTime(new Date());
        statusMap.put(dataSourceId, status);

        try {
            InqueryContext.putContext(connectInfo);

            LineageSqlExecutor executor = sql -> executeQuery(dataSourceId, connectInfo, sql);
            LineageGraph graph = detectorOpt.get().detectLineage(dataSourceId, executor);

            if (graph != null && graph.getNodes() != null && !graph.getNodes().isEmpty()) {
                persistLineageGraph(dataSourceId, graph);
                lineageGraphCache.put(dataSourceId, graph);

                status.setNodeCount(graph.getNodes().size());
                status.setEdgeCount(graph.getEdges().size());
                log.info("Lineage detected for dataSourceId={}: {} nodes, {} edges",
                        dataSourceId, graph.getNodes().size(), graph.getEdges().size());
            } else {
                log.info("No lineage detected for dataSourceId={}, dbType={}", dataSourceId, dbType);
            }

            status.setState(LineageDetectStatus.State.COMPLETED);
        } catch (Exception e) {
            log.error("Lineage detection failed for dataSourceId={}", dataSourceId, e);
            status.setState(LineageDetectStatus.State.FAILED);
            status.setErrorMessage(e.getMessage());
        } finally {
            InqueryContext.removeContext();
            statusMap.put(dataSourceId, status);
        }
    }

    public LineageDetectStatus getStatus(Long dataSourceId) {
        return statusMap.get(dataSourceId);
    }

    /**
     * Executes SQL directly via JDBC, bypassing Druid SQL parser which can't
     * handle vendor-specific syntax (e.g. Snowflake variant access).
     */
    private List<Map<String, String>> executeQuery(Long dataSourceId, ConnectInfo connectInfo, String sql) throws Exception {
        Connection conn = getValidConnection(dataSourceId, connectInfo);

        List<Map<String, String>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(300);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> headers = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    headers.add(meta.getColumnLabel(i).toUpperCase());
                }
                while (rs.next()) {
                    Map<String, String> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < colCount; i++) {
                        String val = rs.getString(i + 1);
                        rowMap.put(headers.get(i), val);
                    }
                    rows.add(rowMap);
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("Statement is closed")) {
                log.warn("Stale connection detected for dataSourceId={}, retrying with fresh connection", dataSourceId);
                conn = forceNewConnection(dataSourceId, connectInfo);
                return executeQueryWithConnection(conn, sql);
            }
            throw e;
        }
        return rows;
    }

    private Connection getValidConnection(Long dataSourceId, ConnectInfo connectInfo) throws Exception {
        Connection conn = ConnectionPool.getConnection(connectInfo);
        if (conn == null || conn.isClosed()) {
            log.warn("Pool connection null/closed for dataSourceId={}, creating fresh connection", dataSourceId);
            conn = forceNewConnection(dataSourceId, connectInfo);
        }
        try {
            if (!conn.isValid(10)) {
                log.warn("Pool connection invalid for dataSourceId={}, creating fresh connection", dataSourceId);
                conn = forceNewConnection(dataSourceId, connectInfo);
            }
        } catch (Exception e) {
            log.warn("isValid() check failed for dataSourceId={}, creating fresh connection", dataSourceId);
            conn = forceNewConnection(dataSourceId, connectInfo);
        }
        return conn;
    }

    private Connection forceNewConnection(Long dataSourceId, ConnectInfo connectInfo) throws Exception {
        connectInfo.setConnection(null);
        Connection conn = ConnectionPool.getConnection(connectInfo);
        if (conn == null || conn.isClosed()) {
            throw new RuntimeException("Failed to establish JDBC connection for dataSourceId=" + dataSourceId);
        }
        return conn;
    }

    private List<Map<String, String>> executeQueryWithConnection(Connection conn, String sql) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(300);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> headers = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    headers.add(meta.getColumnLabel(i).toUpperCase());
                }
                while (rs.next()) {
                    Map<String, String> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < colCount; i++) {
                        String val = rs.getString(i + 1);
                        rowMap.put(headers.get(i), val);
                    }
                    rows.add(rowMap);
                }
            }
        }
        return rows;
    }

    private void persistLineageGraph(Long dataSourceId, LineageGraph graph) {
        TableLineageMapper mapper = Dbutils.getMapper(TableLineageMapper.class);

        // Build the row set first. If the new detection result has nothing to insert
        // (e.g. only "source" nodes, or no nodes with parents), KEEP the existing rows
        // instead of wiping them. This prevents transient/empty detection runs from
        // erasing previously persisted lineage.
        Map<String, LineageNode> nodeMap = new HashMap<>();
        for (LineageNode node : graph.getNodes()) {
            nodeMap.put(node.getUniqueId(), node);
        }

        List<TableLineageDO> rowsToInsert = new ArrayList<>();
        Date now = new Date();
        for (LineageNode node : graph.getNodes()) {
            if ("source".equals(node.getResourceType())) continue;

            List<String> parentNames = graph.getEdges().stream()
                    .filter(e -> e.getTargetId().equals(node.getUniqueId()))
                    .map(e -> {
                        LineageNode parent = nodeMap.get(e.getSourceId());
                        if (parent == null) return e.getSourceId();
                        String db = parent.getDatabase();
                        String schema = parent.getSchema();
                        String name = parent.getName();
                        if (db != null && schema != null) return db + "." + schema + "." + name;
                        return name;
                    })
                    .collect(Collectors.toList());

            if (parentNames.isEmpty()) continue;

            TableLineageDO lineage = new TableLineageDO();
            lineage.setDataSourceId(dataSourceId);
            lineage.setDatabaseName(node.getDatabase());
            lineage.setSchemaName(node.getSchema());
            lineage.setTableName(node.getName());
            lineage.setSourceTables(String.join(",", parentNames));
            lineage.setSourceQuery(node.getCompiledSql());
            lineage.setDescription(node.getDescription());
            lineage.setGmtCreate(now);
            lineage.setGmtModified(now);
            rowsToInsert.add(lineage);
        }

        if (rowsToInsert.isEmpty()) {
            log.info("Skip persistLineageGraph for dataSourceId={}: detection produced no insertable rows ({} total nodes); keeping existing rows.",
                    dataSourceId, graph.getNodes().size());
            return;
        }

        LambdaQueryWrapper<TableLineageDO> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(TableLineageDO::getDataSourceId, dataSourceId);
        mapper.delete(deleteWrapper);

        for (TableLineageDO row : rowsToInsert) {
            mapper.insert(row);
        }
    }
}
