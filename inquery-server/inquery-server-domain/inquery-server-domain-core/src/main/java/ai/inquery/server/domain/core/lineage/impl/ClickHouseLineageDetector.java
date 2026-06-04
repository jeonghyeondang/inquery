package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageDetector;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import ai.inquery.server.domain.core.lineage.SqlLineageParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ClickHouse lineage detector using system.query_log + system.tables.
 */
@Slf4j
@Component
public class ClickHouseLineageDetector implements LineageDetector {

    private static final String TABLES_SQL = """
            SELECT
                database AS VIEW_DATABASE,
                database AS VIEW_SCHEMA,
                name AS VIEW_NAME,
                as_select AS VIEW_DEFINITION,
                engine AS ENGINE
            FROM system.tables
            WHERE (engine IN ('View', 'MaterializedView') OR as_select != '')
              AND database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')
            """;

    @Override
    public boolean supports(String dbType) {
        return "CLICKHOUSE".equalsIgnoreCase(dbType);
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting ClickHouse lineage for dataSourceId={}", dataSourceId);

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        try {
            List<Map<String, String>> rows = executor.execute(TABLES_SQL);
            for (Map<String, String> row : rows) {
                String db = row.getOrDefault("VIEW_DATABASE", "");
                String name = row.getOrDefault("VIEW_NAME", "");
                String viewDef = row.getOrDefault("VIEW_DEFINITION", "");
                String engine = row.getOrDefault("ENGINE", "");

                if (name.isEmpty() || viewDef.isEmpty()) continue;

                String viewKey = (db + "." + name).toUpperCase();
                String materialization = "MaterializedView".equals(engine) ? "table" : "view";

                if (!nodeMap.containsKey(viewKey)) {
                    LineageNode node = new LineageNode();
                    node.setUniqueId("model." + viewKey);
                    node.setName(name);
                    node.setDatabase(db);
                    node.setResourceType("model");
                    node.setMaterialization(materialization);
                    node.setCompiledSql(viewDef);
                    nodeMap.put(viewKey, node);
                }

                for (String srcRef : SqlLineageParser.extractSourceTables(viewDef)) {
                    String sourceKey = srcRef.contains(".") ? srcRef : (db + "." + srcRef).toUpperCase();
                    if (sourceKey.equals(viewKey)) continue;

                    if (!nodeMap.containsKey(sourceKey)) {
                        String srcName = srcRef.contains(".") ? srcRef.substring(srcRef.lastIndexOf('.') + 1) : srcRef;
                        LineageNode srcNode = new LineageNode();
                        srcNode.setUniqueId("source." + sourceKey);
                        srcNode.setName(srcName);
                        srcNode.setDatabase(db);
                        srcNode.setResourceType("source");
                        nodeMap.put(sourceKey, srcNode);
                    }

                    LineageEdge edge = new LineageEdge();
                    edge.setSourceId(nodeMap.get(sourceKey).getUniqueId());
                    edge.setTargetId(nodeMap.get(viewKey).getUniqueId());
                    edges.add(edge);
                }
            }
        } catch (Exception e) {
            log.warn("ClickHouse system.tables query failed: {}", e.getMessage());
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);
        log.info("ClickHouse lineage: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }
}
