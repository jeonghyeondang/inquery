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
 * Snowflake lineage detector using ACCOUNT_USAGE.ACCESS_HISTORY + QUERY_HISTORY.
 * Extracts table-level lineage including compiled SQL from dbt __dbt_tmp creation queries.
 */
@Slf4j
@Component
public class SnowflakeLineageDetector implements LineageDetector {

    private static final String LINEAGE_SQL = """
            WITH raw_lineage AS (
                SELECT
                    src.value:"objectName"::STRING AS source_table,
                    CASE
                        WHEN POSITION('__DBT_TMP' IN UPPER(tgt.value:"objectName"::STRING)) > 0
                        THEN REPLACE(UPPER(tgt.value:"objectName"::STRING), '__DBT_TMP', '')
                        ELSE tgt.value:"objectName"::STRING
                    END AS target_table,
                    ah.query_id,
                    ah.query_start_time
                FROM snowflake.account_usage.access_history ah,
                    LATERAL FLATTEN(input => ah.base_objects_accessed) AS src,
                    LATERAL FLATTEN(input => ah.objects_modified) AS tgt
                WHERE ah.query_start_time >= DATEADD('day', -30, CURRENT_TIMESTAMP())
                  AND src.value:"objectDomain"::STRING IN ('Table', 'View')
                  AND tgt.value:"objectDomain"::STRING IN ('Table', 'View')
                  AND src.value:"objectName"::STRING != tgt.value:"objectName"::STRING
                  AND POSITION('__DBT_TMP' IN UPPER(src.value:"objectName"::STRING)) = 0
                  AND POSITION('GE_TEMP_' IN UPPER(src.value:"objectName"::STRING)) = 0
                  AND POSITION('GE_TEMP_' IN UPPER(tgt.value:"objectName"::STRING)) = 0
            ),
            filtered_lineage AS (
                SELECT
                    source_table,
                    target_table,
                    COUNT(DISTINCT query_id) AS query_count,
                    MAX(query_start_time) AS last_seen,
                    MIN(query_start_time) AS first_seen
                FROM raw_lineage
                GROUP BY 1, 2
                HAVING query_count >= 1
            ),
            ranked_merge AS (
                SELECT
                    r.source_table,
                    r.target_table,
                    r.query_id,
                    ROW_NUMBER() OVER (
                        PARTITION BY r.source_table, r.target_table
                        ORDER BY r.query_start_time DESC
                    ) AS rn
                FROM raw_lineage r
                INNER JOIN filtered_lineage f
                    ON r.source_table = f.source_table
                   AND r.target_table = f.target_table
            ),
            compiled_queries AS (
                SELECT
                    query_text,
                    start_time,
                    UPPER(REPLACE(REGEXP_SUBSTR(query_text, '([^ ]+)__dbt_tmp', 1, 1, 'ei', 1), '"', '')) AS base_table,
                    ROW_NUMBER() OVER (
                        PARTITION BY UPPER(REPLACE(REGEXP_SUBSTR(query_text, '([^ ]+)__dbt_tmp', 1, 1, 'ei', 1), '"', ''))
                        ORDER BY start_time DESC
                    ) AS rn
                FROM snowflake.account_usage.query_history
                WHERE start_time >= DATEADD('day', -30, CURRENT_TIMESTAMP())
                  AND LOWER(query_text) LIKE 'create%%'
                  AND POSITION('__dbt_tmp' IN LOWER(query_text)) > 0
            )
            SELECT
                f.source_table,
                f.target_table,
                SPLIT_PART(f.target_table, '.', 1) AS target_database,
                SPLIT_PART(f.target_table, '.', 2) AS target_schema,
                SPLIT_PART(f.target_table, '.', 3) AS target_name,
                SPLIT_PART(f.source_table, '.', 1) AS source_database,
                SPLIT_PART(f.source_table, '.', 2) AS source_schema,
                SPLIT_PART(f.source_table, '.', 3) AS source_name,
                f.query_count,
                merge_qh.query_text AS merge_query,
                cq.query_text AS compiled_query
            FROM filtered_lineage f
            LEFT JOIN ranked_merge rm
                ON f.source_table = rm.source_table
               AND f.target_table = rm.target_table
               AND rm.rn = 1
            LEFT JOIN snowflake.account_usage.query_history merge_qh
                ON rm.query_id = merge_qh.query_id
            LEFT JOIN compiled_queries cq
                ON cq.base_table = UPPER(f.target_table)
               AND cq.rn = 1
            ORDER BY f.query_count DESC
            """;

    private static final String VIEWS_SQL = """
            SELECT
                TABLE_CATALOG AS DATABASE_NAME,
                TABLE_SCHEMA AS SCHEMA_NAME,
                TABLE_NAME AS VIEW_NAME,
                VIEW_DEFINITION
            FROM SNOWFLAKE.ACCOUNT_USAGE.VIEWS
            WHERE DELETED IS NULL
              AND TABLE_SCHEMA != 'INFORMATION_SCHEMA'
              AND VIEW_DEFINITION IS NOT NULL
            """;

    @Override
    public boolean supports(String dbType) {
        return "SNOWFLAKE".equalsIgnoreCase(dbType);
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting Snowflake lineage for dataSourceId={}", dataSourceId);

        List<Map<String, String>> rows = executor.execute(LINEAGE_SQL);
        if (rows.isEmpty()) {
            log.info("No lineage rows returned from ACCESS_HISTORY for dataSourceId={}", dataSourceId);
            return emptyGraph();
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String sourceTable = row.get("SOURCE_TABLE");
            String targetTable = row.get("TARGET_TABLE");
            if (sourceTable == null || targetTable == null) continue;

            String targetDb = row.get("TARGET_DATABASE");
            String targetSchema = row.get("TARGET_SCHEMA");
            String targetName = row.get("TARGET_NAME");
            String sourceDb = row.get("SOURCE_DATABASE");
            String sourceSchema = row.get("SOURCE_SCHEMA");
            String sourceName = row.get("SOURCE_NAME");

            String compiledQuery = row.get("COMPILED_QUERY");
            String mergeQuery = row.get("MERGE_QUERY");
            String queryCountStr = row.get("QUERY_COUNT");

            String compiledSql = compiledQuery != null ? compiledQuery : mergeQuery;

            String materialization = inferMaterialization(mergeQuery, compiledQuery);

            if (!nodeMap.containsKey(targetTable)) {
                LineageNode targetNode = new LineageNode();
                targetNode.setUniqueId("model." + targetTable);
                targetNode.setName(targetName != null ? targetName : targetTable);
                targetNode.setDatabase(targetDb);
                targetNode.setSchema(targetSchema);
                targetNode.setResourceType("model");
                targetNode.setMaterialization(materialization);
                targetNode.setCompiledSql(compiledSql);
                nodeMap.put(targetTable, targetNode);
            }

            if (!nodeMap.containsKey(sourceTable)) {
                LineageNode sourceNode = new LineageNode();
                sourceNode.setUniqueId("source." + sourceTable);
                sourceNode.setName(sourceName != null ? sourceName : sourceTable);
                sourceNode.setDatabase(sourceDb);
                sourceNode.setSchema(sourceSchema);
                sourceNode.setResourceType("source");
                nodeMap.put(sourceTable, sourceNode);
            }

            LineageEdge edge = new LineageEdge();
            edge.setSourceId(nodeMap.get(sourceTable).getUniqueId());
            edge.setTargetId(nodeMap.get(targetTable).getUniqueId());
            edges.add(edge);
        }

        try {
            List<Map<String, String>> viewRows = executor.execute(VIEWS_SQL);
            enrichWithViewLineage(nodeMap, edges, viewRows);
        } catch (Exception e) {
            log.warn("Failed to enrich with ACCOUNT_USAGE.VIEWS: {}", e.getMessage());
        }

        promoteModels(nodeMap, edges);

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);

        log.info("Snowflake lineage detected: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
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

    /**
     * Bridge ACCESS_HISTORY edges through intermediate Views using ACCOUNT_USAGE.VIEWS definitions.
     * ACCESS_HISTORY resolves views to base tables (transparent), so we re-insert view nodes
     * by matching: source's base table appears in view definition AND view appears in target's compiled SQL.
     */
    private void enrichWithViewLineage(Map<String, LineageNode> nodeMap, List<LineageEdge> edges,
                                       List<Map<String, String>> viewRows) {
        if (viewRows == null || viewRows.isEmpty()) return;

        Map<String, Set<String>> viewSourceMap = new LinkedHashMap<>();
        Map<String, String> viewDefMap = new LinkedHashMap<>();

        for (Map<String, String> vr : viewRows) {
            String db = vr.get("DATABASE_NAME");
            String schema = vr.get("SCHEMA_NAME");
            String viewName = vr.get("VIEW_NAME");
            String viewDef = vr.get("VIEW_DEFINITION");
            if (db == null || schema == null || viewName == null || viewDef == null) continue;

            String viewFQ = (db + "." + schema + "." + viewName).toUpperCase();
            viewDefMap.put(viewFQ, viewDef);

            Set<String> parsedSources = SqlLineageParser.extractSourceTables(viewDef);
            Set<String> fqSources = new HashSet<>();
            for (String src : parsedSources) {
                fqSources.add(fullyQualify(src, db, schema));
            }
            viewSourceMap.put(viewFQ, fqSources);
        }

        Map<String, Set<String>> sourceToViews = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : viewSourceMap.entrySet()) {
            for (String src : entry.getValue()) {
                sourceToViews.computeIfAbsent(src, k -> new HashSet<>()).add(entry.getKey());
            }
        }

        Map<String, String> sourceNameToViewFQ = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : viewSourceMap.entrySet()) {
            String viewFQ = entry.getKey();
            String[] vParts = viewFQ.split("\\.");
            if (vParts.length < 3) continue;
            String viewDb = vParts[0];
            String viewSimpleName = vParts[2];

            for (String src : entry.getValue()) {
                String[] sParts = src.split("\\.");
                String srcSimpleName = sParts.length >= 3 ? sParts[2] : sParts[sParts.length - 1];
                String srcDb = sParts.length >= 3 ? sParts[0] : "";
                if (srcSimpleName.equals(viewSimpleName) && viewDb.equals(srcDb)) {
                    sourceNameToViewFQ.put(src, viewFQ);
                }
            }
        }

        List<LineageEdge> edgesToRemove = new ArrayList<>();
        List<LineageEdge> edgesToAdd = new ArrayList<>();
        Set<String> addedEdgeKeys = new HashSet<>();

        for (LineageEdge edge : edges) {
            String sourceFQ = edge.getSourceId().replaceFirst("^(source|model)\\.", "").toUpperCase();
            String targetId = edge.getTargetId();

            Set<String> candidateViews = sourceToViews.getOrDefault(sourceFQ, Collections.emptySet());
            if (candidateViews.isEmpty()) continue;

            String matchedView = null;

            LineageNode targetNode = findNodeByUniqueId(nodeMap, targetId);
            if (targetNode != null && targetNode.getCompiledSql() != null) {
                Set<String> targetRefs = SqlLineageParser.extractSourceTables(targetNode.getCompiledSql());
                Set<String> targetRefsUpper = new HashSet<>();
                for (String ref : targetRefs) {
                    targetRefsUpper.add(ref.toUpperCase());
                }

                for (String viewFQ : candidateViews) {
                    String[] vParts = viewFQ.split("\\.");
                    if (vParts.length < 3) continue;
                    if (targetRefsUpper.contains(viewFQ)
                            || targetRefsUpper.contains(vParts[1] + "." + vParts[2])
                            || targetRefsUpper.contains(vParts[2])) {
                        matchedView = viewFQ;
                        break;
                    }
                }
            }

            if (matchedView == null) {
                matchedView = sourceNameToViewFQ.get(sourceFQ);
                if (matchedView != null && !candidateViews.contains(matchedView)) {
                    matchedView = null;
                }
            }

            if (matchedView == null) continue;

            String[] vParts = matchedView.split("\\.");
            if (vParts.length < 3) continue;

            edgesToRemove.add(edge);

            if (!nodeMap.containsKey(matchedView)) {
                LineageNode viewNode = new LineageNode();
                viewNode.setUniqueId("model." + matchedView);
                viewNode.setName(vParts[2]);
                viewNode.setDatabase(vParts[0]);
                viewNode.setSchema(vParts[1]);
                viewNode.setResourceType("model");
                viewNode.setMaterialization("view");
                viewNode.setCompiledSql(viewDefMap.get(matchedView));
                nodeMap.put(matchedView, viewNode);
            }

            String viewNodeId = nodeMap.get(matchedView).getUniqueId();

            String srcViewKey = edge.getSourceId() + "|" + viewNodeId;
            if (addedEdgeKeys.add(srcViewKey)) {
                LineageEdge srcToView = new LineageEdge();
                srcToView.setSourceId(edge.getSourceId());
                srcToView.setTargetId(viewNodeId);
                edgesToAdd.add(srcToView);
            }

            String viewTgtKey = viewNodeId + "|" + targetId;
            if (addedEdgeKeys.add(viewTgtKey)) {
                LineageEdge viewToTarget = new LineageEdge();
                viewToTarget.setSourceId(viewNodeId);
                viewToTarget.setTargetId(targetId);
                edgesToAdd.add(viewToTarget);
            }
        }

        edges.removeAll(edgesToRemove);
        edges.addAll(edgesToAdd);

        log.info("View enrichment: bridged {} edges through views, {} view nodes total",
                edgesToRemove.size(), nodeMap.values().stream()
                        .filter(n -> "view".equals(n.getMaterialization())).count());
    }

    private String fullyQualify(String name, String defaultDb, String defaultSchema) {
        String upper = name.toUpperCase();
        long dots = upper.chars().filter(c -> c == '.').count();
        if (dots >= 2) return upper;
        if (dots == 1) return defaultDb.toUpperCase() + "." + upper;
        return defaultDb.toUpperCase() + "." + defaultSchema.toUpperCase() + "." + upper;
    }

    private LineageNode findNodeByUniqueId(Map<String, LineageNode> nodeMap, String uniqueId) {
        for (LineageNode node : nodeMap.values()) {
            if (node.getUniqueId().equals(uniqueId)) return node;
        }
        return null;
    }

    private String inferMaterialization(String mergeQuery, String compiledQuery) {
        if (mergeQuery != null && mergeQuery.toLowerCase().startsWith("merge")) {
            return "incremental";
        }
        if (compiledQuery != null) {
            String lower = compiledQuery.toLowerCase().trim();
            if (lower.startsWith("create or replace transient table") || lower.startsWith("create or replace table")) {
                return "table";
            }
            if (lower.contains("view")) {
                return "view";
            }
        }
        return "table";
    }

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>());
        graph.setEdges(new ArrayList<>());
        return graph;
    }
}
