package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL Server lineage detector using sys.sql_expression_dependencies.
 */
@Slf4j
@Component
public class SqlServerLineageDetector extends AbstractViewBasedDetector {

    private static final String DEPENDENCY_SQL = """
            SELECT
                OBJECT_SCHEMA_NAME(d.referenced_id) AS source_schema,
                OBJECT_NAME(d.referenced_id) AS source_name,
                OBJECT_SCHEMA_NAME(d.referencing_id) AS target_schema,
                OBJECT_NAME(d.referencing_id) AS target_name,
                m.definition AS view_definition
            FROM sys.sql_expression_dependencies d
            JOIN sys.objects o ON d.referencing_id = o.object_id
            LEFT JOIN sys.sql_modules m ON d.referencing_id = m.object_id
            WHERE o.type IN ('V', 'P', 'FN', 'IF', 'TF')
              AND d.referenced_id IS NOT NULL
              AND OBJECT_SCHEMA_NAME(d.referenced_id) NOT IN ('sys', 'INFORMATION_SCHEMA')
            """;

    @Override
    public boolean supports(String dbType) {
        return "SQLSERVER".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "SQLSERVER";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    DB_NAME() AS VIEW_DATABASE,
                    TABLE_SCHEMA AS VIEW_SCHEMA,
                    TABLE_NAME AS VIEW_NAME,
                    VIEW_DEFINITION AS VIEW_DEFINITION
                FROM INFORMATION_SCHEMA.VIEWS
                WHERE TABLE_SCHEMA NOT IN ('sys', 'INFORMATION_SCHEMA')
                """;
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting SQL Server lineage for dataSourceId={}", dataSourceId);

        List<Map<String, String>> depRows;
        try {
            depRows = executor.execute(DEPENDENCY_SQL);
        } catch (Exception e) {
            log.warn("sys.sql_expression_dependencies query failed, falling back to VIEW parsing: {}", e.getMessage());
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
            String viewDef = row.getOrDefault("VIEW_DEFINITION", "");

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
                node.setCompiledSql(viewDef);
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
        log.info("SQL Server lineage: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }
}
