package ai.inquery.server.domain.core.lineage.impl;

import ai.inquery.server.domain.core.dbt.LineageEdge;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.dbt.LineageNode;
import ai.inquery.server.domain.core.lineage.LineageDetector;
import ai.inquery.server.domain.core.lineage.LineageSqlExecutor;
import ai.inquery.server.domain.core.lineage.SqlLineageParser;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Base class for lineage detectors that work by parsing VIEW definitions.
 * Subclasses provide the SQL to query view definitions from the database's system catalog.
 */
@Slf4j
public abstract class AbstractViewBasedDetector implements LineageDetector {

    /**
     * Return the SQL query that fetches view definitions.
     * Expected columns: VIEW_DATABASE, VIEW_SCHEMA, VIEW_NAME, VIEW_DEFINITION
     * VIEW_DATABASE and VIEW_SCHEMA may be null for databases that don't support them.
     */
    protected abstract String getViewDefinitionsSql();

    @Override
    public LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception {
        String sql = getViewDefinitionsSql();
        log.info("Detecting view-based lineage for dataSourceId={}, dbType={}", dataSourceId, getDbType());

        List<Map<String, String>> rows;
        try {
            rows = executor.execute(sql);
        } catch (Exception e) {
            log.warn("Failed to query view definitions for dataSourceId={}: {}", dataSourceId, e.getMessage());
            return emptyGraph();
        }

        if (rows.isEmpty()) {
            log.info("No views found for dataSourceId={}", dataSourceId);
            return emptyGraph();
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String viewDb = row.getOrDefault("VIEW_DATABASE", "");
            String viewSchema = row.getOrDefault("VIEW_SCHEMA", "");
            String viewName = row.getOrDefault("VIEW_NAME", "");
            String viewDef = row.getOrDefault("VIEW_DEFINITION", "");

            if (viewName.isEmpty() || viewDef.isEmpty()) continue;

            String viewKey = buildKey(viewDb, viewSchema, viewName);

            if (!nodeMap.containsKey(viewKey)) {
                LineageNode viewNode = new LineageNode();
                viewNode.setUniqueId("model." + viewKey);
                viewNode.setName(viewName);
                viewNode.setDatabase(viewDb.isEmpty() ? null : viewDb);
                viewNode.setSchema(viewSchema.isEmpty() ? null : viewSchema);
                viewNode.setResourceType("model");
                viewNode.setMaterialization("view");
                viewNode.setCompiledSql(viewDef);
                nodeMap.put(viewKey, viewNode);
            }

            Set<String> sourceTables = SqlLineageParser.extractSourceTables(viewDef);
            for (String sourceRef : sourceTables) {
                String sourceKey = resolveSourceKey(sourceRef, viewDb, viewSchema);
                String sourceName = sourceRef.contains(".") ? sourceRef.substring(sourceRef.lastIndexOf('.') + 1) : sourceRef;

                if (sourceKey.equals(viewKey)) continue;

                if (!nodeMap.containsKey(sourceKey)) {
                    LineageNode srcNode = new LineageNode();
                    srcNode.setUniqueId("source." + sourceKey);
                    srcNode.setName(sourceName);
                    if (sourceRef.contains(".")) {
                        String[] parts = sourceRef.split("\\.");
                        if (parts.length == 3) {
                            srcNode.setDatabase(parts[0]);
                            srcNode.setSchema(parts[1]);
                        } else if (parts.length == 2) {
                            srcNode.setSchema(parts[0]);
                        }
                    } else {
                        srcNode.setDatabase(viewDb.isEmpty() ? null : viewDb);
                        srcNode.setSchema(viewSchema.isEmpty() ? null : viewSchema);
                    }
                    srcNode.setResourceType("source");
                    nodeMap.put(sourceKey, srcNode);
                }

                LineageEdge edge = new LineageEdge();
                edge.setSourceId(nodeMap.get(sourceKey).getUniqueId());
                edge.setTargetId(nodeMap.get(viewKey).getUniqueId());
                edges.add(edge);
            }
        }

        promoteIntermediateNodes(nodeMap, edges);

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(deduplicateEdges(edges));
        log.info("View-based lineage detected: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }

    protected abstract String getDbType();

    private String buildKey(String db, String schema, String name) {
        StringBuilder sb = new StringBuilder();
        if (!db.isEmpty()) sb.append(db).append(".");
        if (!schema.isEmpty()) sb.append(schema).append(".");
        sb.append(name);
        return sb.toString().toUpperCase();
    }

    private String resolveSourceKey(String sourceRef, String defaultDb, String defaultSchema) {
        int dotCount = sourceRef.chars().filter(c -> c == '.').sum();
        if (dotCount >= 2) return sourceRef.toUpperCase();
        if (dotCount == 1 && !defaultDb.isEmpty()) return (defaultDb + "." + sourceRef).toUpperCase();
        if (dotCount == 0) return buildKey(defaultDb, defaultSchema, sourceRef);
        return sourceRef.toUpperCase();
    }

    private void promoteIntermediateNodes(Map<String, LineageNode> nodeMap, List<LineageEdge> edges) {
        Set<String> targetIds = new HashSet<>();
        for (LineageEdge edge : edges) targetIds.add(edge.getTargetId());

        for (LineageNode node : nodeMap.values()) {
            if ("source".equals(node.getResourceType()) && targetIds.contains(node.getUniqueId())) {
                node.setResourceType("model");
                String oldId = node.getUniqueId();
                String newId = oldId.replaceFirst("^source\\.", "model.");
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
            String key = edge.getSourceId() + "->" + edge.getTargetId();
            if (seen.add(key)) unique.add(edge);
        }
        return unique;
    }

    protected LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>());
        graph.setEdges(new ArrayList<>());
        return graph;
    }
}
