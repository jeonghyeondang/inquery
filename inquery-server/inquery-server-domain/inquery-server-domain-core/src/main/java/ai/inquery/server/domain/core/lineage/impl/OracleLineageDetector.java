package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Oracle lineage detector using ALL_DEPENDENCIES + ALL_VIEWS.
 */
@Slf4j
@Component
public class OracleLineageDetector extends AbstractViewBasedDetector {

    private static final String DEPENDENCY_SQL = """
            SELECT DISTINCT
                d.REFERENCED_OWNER AS source_schema,
                d.REFERENCED_NAME AS source_name,
                d.OWNER AS target_schema,
                d.NAME AS target_name
            FROM ALL_DEPENDENCIES d
            WHERE d.REFERENCED_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW')
              AND d.TYPE IN ('VIEW', 'MATERIALIZED VIEW', 'PROCEDURE', 'FUNCTION')
              AND d.OWNER NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'OUTLN', 'MDSYS', 'CTXSYS', 'XDB')
              AND d.REFERENCED_OWNER NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'OUTLN', 'MDSYS', 'CTXSYS', 'XDB')
            """;

    @Override
    public boolean supports(String dbType) {
        return "ORACLE".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "ORACLE";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    NULL AS VIEW_DATABASE,
                    OWNER AS VIEW_SCHEMA,
                    VIEW_NAME AS VIEW_NAME,
                    DBMS_METADATA.GET_DDL('VIEW', VIEW_NAME, OWNER) AS VIEW_DEFINITION
                FROM ALL_VIEWS
                WHERE OWNER NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'OUTLN', 'MDSYS', 'CTXSYS', 'XDB')
                AND ROWNUM <= 1000
                """;
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting Oracle lineage for dataSourceId={}", dataSourceId);

        List<Map<String, String>> depRows;
        try {
            depRows = executor.execute(DEPENDENCY_SQL);
        } catch (Exception e) {
            log.warn("ALL_DEPENDENCIES query failed, falling back to VIEW parsing: {}", e.getMessage());
            return super.detectLineage(dataSourceId, executor);
        }

        if (depRows.isEmpty()) {
            return super.detectLineage(dataSourceId, executor);
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        for (Map<String, String> row : depRows) {
            String srcSchema = row.getOrDefault("SOURCE_SCHEMA", "");
            String srcName = row.getOrDefault("SOURCE_NAME", "");
            String tgtSchema = row.getOrDefault("TARGET_SCHEMA", "");
            String tgtName = row.getOrDefault("TARGET_NAME", "");

            if (srcName.isEmpty() || tgtName.isEmpty()) continue;

            String sourceKey = (srcSchema + "." + srcName).toUpperCase();
            String targetKey = (tgtSchema + "." + tgtName).toUpperCase();

            if (!nodeMap.containsKey(sourceKey)) {
                LineageNode node = new LineageNode();
                node.setUniqueId("source." + sourceKey);
                node.setName(srcName);
                node.setSchema(srcSchema);
                node.setResourceType("source");
                nodeMap.put(sourceKey, node);
            }

            if (!nodeMap.containsKey(targetKey)) {
                LineageNode node = new LineageNode();
                node.setUniqueId("model." + targetKey);
                node.setName(tgtName);
                node.setSchema(tgtSchema);
                node.setResourceType("model");
                node.setMaterialization("view");
                nodeMap.put(targetKey, node);
            }

            LineageEdge edge = new LineageEdge();
            edge.setSourceId(nodeMap.get(sourceKey).getUniqueId());
            edge.setTargetId(nodeMap.get(targetKey).getUniqueId());
            edges.add(edge);
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);
        log.info("Oracle lineage: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }
}
