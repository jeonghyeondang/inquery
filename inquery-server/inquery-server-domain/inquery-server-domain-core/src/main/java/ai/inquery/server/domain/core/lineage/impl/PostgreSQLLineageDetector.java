package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import ai.inquery.server.domain.core.lineage.SqlLineageParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * PostgreSQL lineage detector using pg_depend + information_schema.views.
 */
@Slf4j
@Component
public class PostgreSQLLineageDetector extends AbstractViewBasedDetector {

    private static final String DEPENDENCY_SQL = """
            SELECT DISTINCT
                dep_ns.nspname AS source_schema,
                dep_cl.relname AS source_name,
                ref_ns.nspname AS target_schema,
                ref_cl.relname AS target_name
            FROM pg_depend d
            JOIN pg_rewrite r ON d.objid = r.oid
            JOIN pg_class ref_cl ON r.ev_class = ref_cl.oid
            JOIN pg_namespace ref_ns ON ref_cl.relnamespace = ref_ns.oid
            JOIN pg_class dep_cl ON d.refobjid = dep_cl.oid
            JOIN pg_namespace dep_ns ON dep_cl.relnamespace = dep_ns.oid
            WHERE dep_cl.oid != ref_cl.oid
              AND dep_ns.nspname NOT IN ('pg_catalog', 'information_schema')
              AND ref_ns.nspname NOT IN ('pg_catalog', 'information_schema')
              AND dep_cl.relkind IN ('r', 'v', 'm')
              AND ref_cl.relkind IN ('v', 'm')
            """;

    private static final String VIEW_SQL = """
            SELECT
                table_catalog AS VIEW_DATABASE,
                table_schema AS VIEW_SCHEMA,
                table_name AS VIEW_NAME,
                view_definition AS VIEW_DEFINITION
            FROM information_schema.views
            WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
            """;

    @Override
    public boolean supports(String dbType) {
        return "POSTGRESQL".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "POSTGRESQL";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return VIEW_SQL;
    }

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        log.info("Detecting PostgreSQL lineage for dataSourceId={}", dataSourceId);

        List<Map<String, String>> depRows;
        try {
            depRows = executor.execute(DEPENDENCY_SQL);
        } catch (Exception e) {
            log.warn("pg_depend query failed, falling back to VIEW parsing: {}", e.getMessage());
            return super.detectLineage(dataSourceId, executor);
        }

        if (depRows.isEmpty()) {
            return super.detectLineage(dataSourceId, executor);
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        List<Map<String, String>> viewRows;
        Map<String, String> viewDefMap = new HashMap<>();
        try {
            viewRows = executor.execute(VIEW_SQL);
            for (Map<String, String> row : viewRows) {
                String key = (row.getOrDefault("VIEW_SCHEMA", "") + "." + row.getOrDefault("VIEW_NAME", "")).toUpperCase();
                viewDefMap.put(key, row.getOrDefault("VIEW_DEFINITION", ""));
            }
        } catch (Exception e) {
            log.warn("VIEW definition query failed: {}", e.getMessage());
        }

        for (Map<String, String> row : depRows) {
            String srcSchema = row.getOrDefault("SOURCE_SCHEMA", "");
            String srcName = row.getOrDefault("SOURCE_NAME", "");
            String tgtSchema = row.getOrDefault("TARGET_SCHEMA", "");
            String tgtName = row.getOrDefault("TARGET_NAME", "");

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
                node.setCompiledSql(viewDefMap.get(targetKey));
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
        log.info("PostgreSQL lineage: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }
}
