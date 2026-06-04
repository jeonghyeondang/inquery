package ai.inquery.server.domain.core.dbt;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Parses dbt manifest.json to extract table-level lineage (DAG).
 *
 * Extracts nodes, sources, and their dependency edges from the
 * parent_map / child_map / depends_on structures.
 */
@Slf4j
public class DbtManifestParser {

    public static LineageGraph parse(String manifestJson) {
        JSONObject root = JSON.parseObject(manifestJson);
        if (root == null) {
            throw new IllegalArgumentException("Invalid manifest.json: empty or malformed JSON");
        }

        Map<String, LineageNode> nodeMap = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();

        // 1. Extract model/seed/snapshot nodes
        JSONObject nodes = root.getJSONObject("nodes");
        if (nodes != null) {
            for (Map.Entry<String, Object> entry : nodes.entrySet()) {
                String uniqueId = entry.getKey();
                JSONObject node = (JSONObject) entry.getValue();
                String resourceType = node.getString("resource_type");

                if (!"model".equals(resourceType) && !"seed".equals(resourceType)
                        && !"snapshot".equals(resourceType)) {
                    continue;
                }

                LineageNode ln = buildLineageNode(uniqueId, node, resourceType);
                nodeMap.put(uniqueId, ln);
            }
        }

        // 2. Extract source nodes
        JSONObject sources = root.getJSONObject("sources");
        if (sources != null) {
            for (Map.Entry<String, Object> entry : sources.entrySet()) {
                String uniqueId = entry.getKey();
                JSONObject source = (JSONObject) entry.getValue();

                LineageNode ln = new LineageNode();
                ln.setUniqueId(uniqueId);
                ln.setName(source.getString("name"));
                ln.setDatabase(source.getString("database"));
                ln.setSchema(source.getString("schema"));
                ln.setResourceType("source");
                ln.setDescription(source.getString("description"));
                ln.setMaterialization(null);
                nodeMap.put(uniqueId, ln);
            }
        }

        // 3. Build edges from parent_map (preferred) or depends_on
        JSONObject parentMap = root.getJSONObject("parent_map");
        if (parentMap != null) {
            for (Map.Entry<String, Object> entry : parentMap.entrySet()) {
                String childId = entry.getKey();
                if (!nodeMap.containsKey(childId)) continue;

                JSONArray parents = (JSONArray) entry.getValue();
                if (parents == null) continue;

                for (int i = 0; i < parents.size(); i++) {
                    String parentId = parents.getString(i);
                    if (!nodeMap.containsKey(parentId)) continue;

                    LineageEdge edge = new LineageEdge();
                    edge.setSourceId(parentId);
                    edge.setTargetId(childId);
                    edges.add(edge);
                }
            }
        } else if (nodes != null) {
            // Fallback: extract from depends_on.nodes
            for (Map.Entry<String, Object> entry : nodes.entrySet()) {
                String uniqueId = entry.getKey();
                if (!nodeMap.containsKey(uniqueId)) continue;

                JSONObject node = (JSONObject) entry.getValue();
                JSONObject dependsOn = node.getJSONObject("depends_on");
                if (dependsOn == null) continue;

                JSONArray depNodes = dependsOn.getJSONArray("nodes");
                if (depNodes == null) continue;

                for (int i = 0; i < depNodes.size(); i++) {
                    String parentId = depNodes.getString(i);
                    if (!nodeMap.containsKey(parentId)) continue;

                    LineageEdge edge = new LineageEdge();
                    edge.setSourceId(parentId);
                    edge.setTargetId(uniqueId);
                    edges.add(edge);
                }
            }
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);

        log.info("Parsed dbt manifest: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return graph;
    }

    private static LineageNode buildLineageNode(String uniqueId, JSONObject node, String resourceType) {
        LineageNode ln = new LineageNode();
        ln.setUniqueId(uniqueId);
        ln.setName(node.getString("name"));
        ln.setDatabase(node.getString("database"));
        ln.setSchema(node.getString("schema"));
        ln.setResourceType(resourceType);
        ln.setDescription(node.getString("description"));

        JSONObject config = node.getJSONObject("config");
        if (config != null) {
            ln.setMaterialization(config.getString("materialized"));
        }

        // compiled_code (dbt >= 1.3) or compiled_sql (older)
        String compiledSql = node.getString("compiled_code");
        if (compiledSql == null || compiledSql.isBlank()) {
            compiledSql = node.getString("compiled_sql");
        }
        ln.setCompiledSql(compiledSql);

        return ln;
    }
}
