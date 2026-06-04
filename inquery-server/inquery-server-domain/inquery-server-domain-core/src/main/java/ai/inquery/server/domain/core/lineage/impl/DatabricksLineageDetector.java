package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageDetector;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Databricks lineage detector using Unity Catalog's system.access.table_lineage.
 * This system table directly provides parsed source/target table relationships,
 * making it the most straightforward implementation among all database types.
 *
 * Leverages source_type/target_type (TABLE, VIEW, MATERIALIZED_VIEW, STREAMING_TABLE)
 * for accurate materialization inference. Handles dbt __dbt_tmp temporary tables.
 *
 * Requires Unity Catalog enabled and system tables access permissions.
 */
@Slf4j
@Component
public class DatabricksLineageDetector implements LineageDetector {

    /**
     * Filters on event_date (partitioned column) for efficient partition pruning.
     * Excludes PATH-type sources/targets (cloud storage direct reads).
     * Bridges __dbt_tmp tables to their final names in target_table and target_name.
     */
    private static final String LINEAGE_SQL = """
            SELECT
                source_table_full_name AS source_table,
                source_table_catalog AS source_database,
                source_table_schema AS source_schema,
                source_table_name AS source_name,
                source_type,
                CASE
                    WHEN POSITION('__DBT_TMP' IN UPPER(target_table_full_name)) > 0
                    THEN REPLACE(UPPER(target_table_full_name), '__DBT_TMP', '')
                    ELSE target_table_full_name
                END AS target_table,
                target_table_catalog AS target_database,
                target_table_schema AS target_schema,
                CASE
                    WHEN POSITION('__DBT_TMP' IN UPPER(target_table_name)) > 0
                    THEN REPLACE(UPPER(target_table_name), '__DBT_TMP', '')
                    ELSE target_table_name
                END AS target_name,
                target_type,
                COUNT(*) AS query_count
            FROM system.access.table_lineage
            WHERE event_date >= date_sub(current_date(), 30)
                AND source_table_full_name IS NOT NULL
                AND target_table_full_name IS NOT NULL
                AND source_table_full_name != target_table_full_name
                AND source_type != 'PATH'
                AND target_type != 'PATH'
                AND POSITION('__DBT_TMP' IN UPPER(source_table_full_name)) = 0
                AND POSITION('GE_TEMP_' IN UPPER(source_table_full_name)) = 0
                AND POSITION('GE_TEMP_' IN UPPER(target_table_full_name)) = 0
            GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
            HAVING COUNT(*) >= 1
            ORDER BY query_count DESC
            """;

    @Override
    public boolean supports(String dbType) {
        return "DATABRICKS".equalsIgnoreCase(dbType);
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting Databricks lineage for dataSourceId={}", dataSourceId);

        List<Map<String, String>> rows;
        try {
            rows = executor.execute(LINEAGE_SQL);
        } catch (Exception e) {
            log.warn("Databricks system.access.table_lineage query failed for dataSourceId={}: {}",
                    dataSourceId, e.getMessage());
            return emptyGraph();
        }

        if (rows.isEmpty()) {
            log.info("No lineage rows from system.access.table_lineage for dataSourceId={}", dataSourceId);
            return emptyGraph();
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String sourceTable = row.get("SOURCE_TABLE");
            String targetTable = row.get("TARGET_TABLE");
            if (sourceTable == null || targetTable == null) continue;

            String srcDb = row.get("SOURCE_DATABASE");
            String srcSchema = row.get("SOURCE_SCHEMA");
            String srcName = row.get("SOURCE_NAME");
            String sourceType = row.get("SOURCE_TYPE");
            String tgtDb = row.get("TARGET_DATABASE");
            String tgtSchema = row.get("TARGET_SCHEMA");
            String tgtName = row.get("TARGET_NAME");
            String targetType = row.get("TARGET_TYPE");

            String sourceKey = sourceTable.toUpperCase();
            String targetKey = targetTable.toUpperCase();

            if (sourceKey.equals(targetKey)) continue;

            if (!nodeMap.containsKey(sourceKey)) {
                LineageNode node = new LineageNode();
                node.setUniqueId("source." + sourceKey);
                node.setName(srcName != null ? srcName : sourceTable);
                node.setDatabase(srcDb);
                node.setSchema(srcSchema);
                node.setResourceType("source");
                node.setMaterialization(inferMaterialization(sourceType));
                nodeMap.put(sourceKey, node);
            }

            if (!nodeMap.containsKey(targetKey)) {
                LineageNode node = new LineageNode();
                node.setUniqueId("model." + targetKey);
                node.setName(tgtName != null ? tgtName : targetTable);
                node.setDatabase(tgtDb);
                node.setSchema(tgtSchema);
                node.setResourceType("model");
                node.setMaterialization(inferMaterialization(targetType));
                nodeMap.put(targetKey, node);
            }

            LineageEdge edge = new LineageEdge();
            edge.setSourceId(nodeMap.get(sourceKey).getUniqueId());
            edge.setTargetId(nodeMap.get(targetKey).getUniqueId());
            edges.add(edge);
        }

        promoteModels(nodeMap, edges);
        List<LineageEdge> dedupedEdges = deduplicateEdges(edges);

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(dedupedEdges);
        log.info("Databricks lineage detected: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }

    /**
     * Nodes that appear as both source and target should be "model" not "source".
     */
    private void promoteModels(Map<String, LineageNode> nodeMap, List<LineageEdge> edges) {
        Set<String> targetUniqueIds = new HashSet<>();
        for (LineageEdge edge : edges) {
            targetUniqueIds.add(edge.getTargetId());
        }

        for (LineageNode node : nodeMap.values()) {
            if ("source".equals(node.getResourceType()) && targetUniqueIds.contains(node.getUniqueId())) {
                String oldId = node.getUniqueId();
                String newId = oldId.replaceFirst("^source\\.", "model.");
                node.setResourceType("model");
                node.setUniqueId(newId);
                for (LineageEdge edge : edges) {
                    if (oldId.equals(edge.getSourceId())) edge.setSourceId(newId);
                    if (oldId.equals(edge.getTargetId())) edge.setTargetId(newId);
                }
            }
        }
    }

    private List<LineageEdge> deduplicateEdges(List<LineageEdge> edges) {
        Set<String> seen = new HashSet<>();
        List<LineageEdge> unique = new ArrayList<>();
        for (LineageEdge edge : edges) {
            String key = edge.getSourceId() + "|" + edge.getTargetId();
            if (seen.add(key)) unique.add(edge);
        }
        return unique;
    }

    /**
     * Maps Unity Catalog entity types to lineage materialization types.
     */
    private String inferMaterialization(String entityType) {
        if (entityType == null) return "table";
        return switch (entityType.toUpperCase()) {
            case "VIEW" -> "view";
            case "MATERIALIZED_VIEW" -> "materialized_view";
            case "STREAMING_TABLE" -> "streaming_table";
            default -> "table";
        };
    }

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>());
        graph.setEdges(new ArrayList<>());
        return graph;
    }
}
