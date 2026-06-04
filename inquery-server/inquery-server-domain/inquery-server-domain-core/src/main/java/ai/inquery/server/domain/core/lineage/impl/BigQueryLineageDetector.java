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
 * BigQuery lineage detector using INFORMATION_SCHEMA.JOBS.
 * Discovers dataset regions dynamically via INFORMATION_SCHEMA.SCHEMATA,
 * then queries job history per region to extract table-level lineage.
 * Handles dbt __dbt_tmp temporary tables by bridging them to final table names.
 */
@Slf4j
@Component
public class BigQueryLineageDetector implements LineageDetector {

    /**
     * %s is replaced with the region qualifier (e.g., "region-us").
     * Uses UNNEST(referenced_tables) for source tables and destination_table STRUCT for target.
     * ARRAY_AGG with ORDER BY + LIMIT 1 retrieves the most recent compiled SQL per lineage pair.
     */
    private static final String LINEAGE_SQL_TEMPLATE = """
            WITH raw_lineage AS (
                SELECT
                    CONCAT(ref.project_id, '.', ref.dataset_id, '.', ref.table_id) AS source_table,
                    ref.project_id AS source_database,
                    ref.dataset_id AS source_schema,
                    ref.table_id AS source_name,
                    CASE
                        WHEN STRPOS(UPPER(destination_table.table_id), '__DBT_TMP') > 0
                        THEN CONCAT(
                            destination_table.project_id, '.',
                            destination_table.dataset_id, '.',
                            REPLACE(UPPER(destination_table.table_id), '__DBT_TMP', '')
                        )
                        ELSE CONCAT(destination_table.project_id, '.', destination_table.dataset_id, '.', destination_table.table_id)
                    END AS target_table,
                    destination_table.project_id AS target_database,
                    destination_table.dataset_id AS target_schema,
                    CASE
                        WHEN STRPOS(UPPER(destination_table.table_id), '__DBT_TMP') > 0
                        THEN REPLACE(UPPER(destination_table.table_id), '__DBT_TMP', '')
                        ELSE destination_table.table_id
                    END AS target_name,
                    query AS compiled_query,
                    job_id,
                    creation_time
                FROM `%s`.INFORMATION_SCHEMA.JOBS,
                    UNNEST(referenced_tables) AS ref
                WHERE creation_time >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
                    AND job_type = 'QUERY'
                    AND state = 'DONE'
                    AND error_result IS NULL
                    AND destination_table.table_id IS NOT NULL
                    AND referenced_tables IS NOT NULL
                    AND ARRAY_LENGTH(referenced_tables) > 0
                    AND statement_type != 'SCRIPT'
                    AND STRPOS(UPPER(ref.table_id), '__DBT_TMP') = 0
                    AND STRPOS(UPPER(ref.table_id), 'GE_TEMP_') = 0
                    AND STRPOS(UPPER(destination_table.table_id), 'GE_TEMP_') = 0
            ),
            aggregated AS (
                SELECT
                    source_table,
                    target_table,
                    source_database,
                    source_schema,
                    source_name,
                    target_database,
                    target_schema,
                    target_name,
                    ARRAY_AGG(compiled_query ORDER BY creation_time DESC LIMIT 1)[SAFE_OFFSET(0)] AS compiled_query,
                    COUNT(DISTINCT job_id) AS query_count
                FROM raw_lineage
                WHERE source_table != target_table
                GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
                HAVING COUNT(DISTINCT job_id) >= 1
            )
            SELECT
                source_table AS SOURCE_TABLE,
                target_table AS TARGET_TABLE,
                source_database AS SOURCE_DATABASE,
                source_schema AS SOURCE_SCHEMA,
                source_name AS SOURCE_NAME,
                target_database AS TARGET_DATABASE,
                target_schema AS TARGET_SCHEMA,
                target_name AS TARGET_NAME,
                compiled_query AS COMPILED_QUERY,
                query_count AS QUERY_COUNT
            FROM aggregated
            ORDER BY query_count DESC
            """;

    private static final String REGIONS_SQL = """
            SELECT DISTINCT location
            FROM INFORMATION_SCHEMA.SCHEMATA
            WHERE location IS NOT NULL
            """;

    @Override
    public boolean supports(String dbType) {
        return "BIGQUERY".equalsIgnoreCase(dbType);
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting BigQuery lineage for dataSourceId={}", dataSourceId);

        List<String> regions = discoverRegions(executor);
        if (regions.isEmpty()) {
            regions = List.of("us", "eu");
            log.info("No regions discovered via SCHEMATA, trying defaults: {}", regions);
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();
        boolean anySuccess = false;

        for (String region : regions) {
            try {
                String regionQualifier = "region-" + region.toLowerCase();
                String sql = String.format(LINEAGE_SQL_TEMPLATE, regionQualifier);
                List<Map<String, String>> rows = executor.execute(sql);

                if (!rows.isEmpty()) {
                    buildGraph(rows, nodeMap, edges);
                    anySuccess = true;
                    log.info("BigQuery lineage found in region {}: {} rows", region, rows.size());
                }
            } catch (Exception e) {
                log.debug("BigQuery JOBS query failed for region {}: {}", region, e.getMessage());
            }
        }

        if (!anySuccess) {
            log.info("No lineage from INFORMATION_SCHEMA.JOBS for dataSourceId={}", dataSourceId);
            return emptyGraph();
        }

        promoteModels(nodeMap, edges);
        List<LineageEdge> dedupedEdges = deduplicateEdges(edges);

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(dedupedEdges);
        log.info("BigQuery lineage detected: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }

    private List<String> discoverRegions(LineageSqlExecutor executor) {
        List<String> regions = new ArrayList<>();
        try {
            List<Map<String, String>> rows = executor.execute(REGIONS_SQL);
            for (Map<String, String> row : rows) {
                String location = row.get("LOCATION");
                if (location != null && !location.isBlank()) {
                    regions.add(location);
                }
            }
            log.info("Discovered BigQuery dataset regions: {}", regions);
        } catch (Exception e) {
            log.warn("Failed to discover BigQuery regions from SCHEMATA: {}", e.getMessage());
        }
        return regions;
    }

    private void buildGraph(List<Map<String, String>> rows, Map<String, LineageNode> nodeMap, List<LineageEdge> edges) {
        for (Map<String, String> row : rows) {
            String sourceTable = row.get("SOURCE_TABLE");
            String targetTable = row.get("TARGET_TABLE");
            if (sourceTable == null || targetTable == null) continue;

            String srcDb = row.get("SOURCE_DATABASE");
            String srcSchema = row.get("SOURCE_SCHEMA");
            String srcName = row.get("SOURCE_NAME");
            String tgtDb = row.get("TARGET_DATABASE");
            String tgtSchema = row.get("TARGET_SCHEMA");
            String tgtName = row.get("TARGET_NAME");
            String compiledQuery = row.get("COMPILED_QUERY");

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
                nodeMap.put(sourceKey, node);
            }

            if (!nodeMap.containsKey(targetKey)) {
                LineageNode node = new LineageNode();
                node.setUniqueId("model." + targetKey);
                node.setName(tgtName != null ? tgtName : targetTable);
                node.setDatabase(tgtDb);
                node.setSchema(tgtSchema);
                node.setResourceType("model");
                node.setCompiledSql(compiledQuery);
                node.setMaterialization(inferMaterialization(compiledQuery));
                nodeMap.put(targetKey, node);
            }

            LineageEdge edge = new LineageEdge();
            edge.setSourceId(nodeMap.get(sourceKey).getUniqueId());
            edge.setTargetId(nodeMap.get(targetKey).getUniqueId());
            edges.add(edge);
        }
    }

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

    private String inferMaterialization(String compiledQuery) {
        if (compiledQuery == null) return "table";
        String lower = compiledQuery.toLowerCase().trim();
        if (lower.startsWith("create or replace view") || lower.startsWith("create view")) return "view";
        if (lower.startsWith("merge")) return "incremental";
        return "table";
    }

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>());
        graph.setEdges(new ArrayList<>());
        return graph;
    }
}
