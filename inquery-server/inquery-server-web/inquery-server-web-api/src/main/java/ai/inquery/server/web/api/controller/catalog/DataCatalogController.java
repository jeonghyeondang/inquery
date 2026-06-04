package ai.inquery.server.web.api.controller.catalog;

import ai.inquery.server.domain.api.param.DataCatalogSaveParam;
import ai.inquery.server.domain.api.param.DataCatalogColumnSaveParam;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.core.dbt.DbtManifestParser;
import ai.inquery.server.domain.core.dbt.LineageGraph;
import ai.inquery.server.domain.core.lineage.LineageDetectService;
import ai.inquery.server.domain.core.lineage.LineageDetectStatus;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.aspect.ConnectionInfoHandler;
import ai.inquery.server.web.api.controller.ai.EmbeddingController;
import ai.inquery.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.inquery.server.web.api.controller.rdb.request.DataCatalogSaveRequest;
import ai.inquery.server.web.api.controller.rdb.request.DataCatalogColumnSaveRequest;
import ai.inquery.server.web.api.controller.rdb.request.LineageQueryRequest;
import ai.inquery.server.web.api.controller.rdb.request.LineageSaveRequest;
import ai.inquery.server.web.api.controller.rdb.request.TableBriefQueryRequest;
import ai.inquery.server.web.api.controller.rdb.request.TableDetailQueryRequest;
import ai.inquery.server.domain.repository.entity.TableLineageDO;
import ai.inquery.server.domain.repository.mapper.TableLineageMapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import com.alibaba.fastjson2.JSON;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Data catalog controller
 *
 * @since 2025-01-27
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/catalog")
@RestController
public class DataCatalogController {

    @Autowired
    private DataCatalogService dataCatalogService;

    @Autowired
    private RdbWebConverter rdbWebConverter;

    @Autowired
    private ConnectionInfoHandler connectionInfoHandler;

    @Autowired
    private EmbeddingController embeddingController;

    @Autowired
    private CollectionJobTracker collectionJobTracker;

    @Autowired
    private ai.inquery.server.web.api.service.VectorStoreRegistry vectorStoreRegistry;

    @Autowired
    private LineageDetectService lineageDetectService;

    /**
     * Query catalog information for a table
     *
     * @param request table detail query request
     * @return catalog data
     */
    @GetMapping("/query")
    public DataResult<Map<String, Object>> queryCatalog(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        return dataCatalogService.queryCatalog(queryParam);
    }

    /**
     * Save or update catalog information
     *
     * @param request catalog save request
     * @return action result
     */
    @PostMapping("/save")
    public ActionResult saveCatalog(@Valid @RequestBody DataCatalogSaveRequest request) {
        DataCatalogSaveParam param = convertToSaveParam(request);
        ActionResult result = dataCatalogService.saveCatalog(param);
        
        // If save successful, update vector embedding for this table in background
        if (result.getSuccess()) {
            // Capture request context (includes loginUser + datasource info) for background thread
            // IMPORTANT: Background threads don't have request security context, so avoid calling toInfo() there.
            ConnectInfo requestConnectInfo = InqueryContext.getConnectInfo();
            ConnectInfo requestConnectInfoCopy = requestConnectInfo != null ? requestConnectInfo.copy() : null;

            // Update vector embedding asynchronously
            new Thread(() -> {
                Dbutils.setSession();
                try {
                    if (requestConnectInfoCopy != null) {
                        InqueryContext.putContext(requestConnectInfoCopy);
                    } else {
                        log.warn("No ConnectInfo available in request context, skip embedding update. dataSourceId: {}, db: {}, schema: {}, table: {}",
                            request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(), request.getTableName());
                        return;
                    }

                    embeddingController.updateTableVectorEmbedding(
                        request.getDataSourceId(),
                        request.getDatabaseName(),
                        request.getSchemaName(),
                        request.getTableName()
                    );
                } catch (Exception e) {
                    log.error("Failed to update vector embedding after catalog save", e);
                } finally {
                    InqueryContext.removeContext();
                    Dbutils.removeSession();
                }
            }).start();
        }
        
        return result;
    }

    /**
     * Collect example values for columns
     *
     * @param request table detail query request
     * @return map of column names to example values
     */
    @PostMapping("/collect")
    public DataResult<Map<String, Object>> collectExampleValues(@Valid @RequestBody TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        return dataCatalogService.collectExampleValues(queryParam);
    }

    /**
     * Collect AI metadata for a table
     *
     * @param request table detail query request
     * @return action result
     */
    @PostMapping("/ai/collect")
    public DataResult<Map<String, Object>> collectAIMetadata(@Valid @RequestBody TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        return dataCatalogService.collectAIMetadata(queryParam);
    }

    /**
     * Collect AI metadata for all tables in a data source
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name (optional)
     * @return action result with count of processed tables
     */
    @PostMapping("/ai/collect-all")
    public DataResult<Map<String, Object>> collectAIMetadataForAllTables(
            @RequestParam Long dataSourceId,
            @RequestParam String databaseName,
            @RequestParam(required = false) String schemaName) {
        return dataCatalogService.collectAIMetadataForAllTables(dataSourceId, databaseName, schemaName);
    }

    /**
     * Start async AI metadata collection for all tables in a schema.
     * Returns a jobId immediately. Frontend polls /progress to track.
     */
    @PostMapping("/ai/collect-all-async")
    public DataResult<Map<String, String>> startAsyncCollection(
            @RequestParam Long dataSourceId,
            @RequestParam String databaseName,
            @RequestParam(required = false) String schemaName) {

        String jobId = UUID.randomUUID().toString();
        collectionJobTracker.createJob(jobId, databaseName, schemaName);

        // Capture user context for the background thread
        ai.inquery.server.tools.common.model.Context userContext = ContextUtils.queryContext();

        new Thread(() -> {
            Dbutils.setSession();
            try {
                if (userContext != null) {
                    ContextUtils.setContext(userContext);
                }
                // Create ConnectInfo ONCE (looks up data source from DB), then share across worker threads
                // ConnectionPool handles actual JDBC connections internally
                ConnectInfo sharedConnectInfo = connectionInfoHandler.toInfo(dataSourceId, null);
                InqueryContext.putContext(sharedConnectInfo);

                // Get table list using existing loadTablesBySchema
                TableQueryParam schemaParam = TableQueryParam.builder()
                    .dataSourceId(dataSourceId)
                    .databaseName(databaseName)
                    .schemaName(schemaName)
                    .build();

                DataResult<List<ai.inquery.spi.model.Table>> tablesResult =
                    dataCatalogService.loadTablesBySchema(schemaParam);

                if (!tablesResult.getSuccess() || tablesResult.getData() == null || tablesResult.getData().isEmpty()) {
                    collectionJobTracker.setTotalTables(jobId, 0);
                    collectionJobTracker.completeJob(jobId);
                    return;
                }

                List<ai.inquery.spi.model.Table> tables = tablesResult.getData();
                collectionJobTracker.setTotalTables(jobId, tables.size());

                // Process tables in parallel (up to 20 concurrent threads)
                int threadCount = Math.min(20, tables.size());
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();

                for (ai.inquery.spi.model.Table table : tables) {
                    java.util.concurrent.CompletableFuture<Void> future = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        // Reuse shared ConnectInfo (created once), set ThreadLocal for this worker
                        Dbutils.setSession();
                        if (userContext != null) ContextUtils.setContext(userContext);
                        InqueryContext.putContext(sharedConnectInfo);

                        boolean success = false;
                        try {
                            TableQueryParam tableParam = TableQueryParam.builder()
                                .dataSourceId(dataSourceId)
                                .databaseName(databaseName)
                                .schemaName(schemaName)
                                .tableName(table.getName())
                                .build();

                            DataResult<Map<String, Object>> aiResult = dataCatalogService.collectAIMetadata(tableParam);

                            if (aiResult.getSuccess() && aiResult.getData() != null) {
                                Map<String, Object> aiData = aiResult.getData();
                                DataCatalogSaveParam saveParam = new DataCatalogSaveParam();
                                saveParam.setDataSourceId(dataSourceId);
                                saveParam.setDatabaseName(databaseName);
                                saveParam.setSchemaName(schemaName);
                                saveParam.setTableName(table.getName());
                                saveParam.setTableDescription((String) aiData.get("tableDescription"));

                                @SuppressWarnings("unchecked")
                                Map<String, String> columnDescriptions = (Map<String, String>) aiData.get("columnDescriptions");
                                @SuppressWarnings("unchecked")
                                Map<String, List<String>> columnExampleValues = (Map<String, List<String>>) aiData.get("columnExampleValues");

                                if (columnDescriptions != null) {
                                    List<DataCatalogColumnSaveParam> columnParams = new ArrayList<>();
                                    for (Map.Entry<String, String> entry : columnDescriptions.entrySet()) {
                                        DataCatalogColumnSaveParam colParam = new DataCatalogColumnSaveParam();
                                        colParam.setColumnName(entry.getKey());
                                        colParam.setColumnDescription(entry.getValue() != null ? entry.getValue() : "");
                                        colParam.setSchemaInfo("");
                                        if (columnExampleValues != null && columnExampleValues.containsKey(entry.getKey())) {
                                            List<String> examples = columnExampleValues.get(entry.getKey());
                                            colParam.setExampleValues(examples != null && !examples.isEmpty()
                                                    ? com.alibaba.fastjson2.JSON.toJSONString(examples) : "");
                                        } else {
                                            colParam.setExampleValues("");
                                        }
                                        columnParams.add(colParam);
                                    }
                                    saveParam.setColumns(columnParams);
                                }

                                dataCatalogService.saveCatalog(saveParam);

                                // Update vector embedding
                                try {
                                    embeddingController.updateTableVectorEmbedding(
                                        dataSourceId, databaseName, schemaName, table.getName());
                                } catch (Exception e) {
                                    log.warn("Failed to update vector embedding for table: {}", table.getName(), e);
                                }

                                success = true;
                            }
                        } catch (Exception e) {
                            log.error("Error collecting AI metadata for table: {}", table.getName(), e);
                        } finally {
                            InqueryContext.removeContext();
                            ContextUtils.removeContext();
                            Dbutils.removeSession();
                        }
                        collectionJobTracker.onTableComplete(jobId, table.getName(), success);
                    }, executor);
                    futures.add(future);
                }

                // Wait for all tables to complete
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
                executor.shutdown();

                // After all tables are processed, trigger full BM25 refit
                try {
                    embeddingController.refitBM25AfterBulkOperation();
                } catch (Exception e) {
                    log.warn("BM25 full refit after bulk collection failed (non-fatal): {}", e.getMessage());
                }

                collectionJobTracker.completeJob(jobId);
            } catch (Exception e) {
                log.error("Async collection job failed: {}", jobId, e);
                collectionJobTracker.failJob(jobId, e.getMessage());
            } finally {
                InqueryContext.removeContext();
                ContextUtils.removeContext();
                Dbutils.removeSession();
            }
        }).start();

        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        return DataResult.of(response);
    }

    /**
     * Get progress of an async AI collection job
     */
    @GetMapping("/ai/collect-all/progress")
    public DataResult<CollectionJobTracker.JobProgress> getCollectionProgress(@RequestParam String jobId) {
        CollectionJobTracker.JobProgress progress = collectionJobTracker.getProgress(jobId);
        if (progress == null) {
            return DataResult.error("JOB_NOT_FOUND", "Job not found: " + jobId);
        }
        return DataResult.of(progress);
    }

    /**
     * Batch load all column information for tables and views in a schema
     * This is optimized for initial data-catalog page load (cached for 1 day)
     *
     * @param request table brief query request (databaseName and schemaName are required, tableName is not needed)
     * @return map of table/view names to their column lists
     *         Key format: "database.schema.table" (e.g., "PROD.APP.FCT_STUDIO_TS")
     *         Value: list of TableColumn objects
     */
    @GetMapping("/batch-columns")
    public DataResult<Map<String, List<ai.inquery.spi.model.TableColumn>>> batchLoadColumns(@Valid TableBriefQueryRequest request) {
        // Map TableBriefQueryRequest to TableQueryParam (tableName left null)
        TableQueryParam queryParam = TableQueryParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .tableName(null) // batchLoadColumns loads all tables/views in the schema; tableName not needed
            .refresh(request.isRefresh())
            .build();
        return dataCatalogService.batchLoadColumns(queryParam);
    }

    /**
     * Load all tables and views for a data source using INFORMATION_SCHEMA
     * This is optimized for data-catalog page initial load (cached for 1 day)
     * Called when the data-catalog page is first loaded
     *
     * @param dataSourceId data source ID
     * @param refresh whether to refresh cache (default: false)
     * @return map of schema keys to their table/view lists
     *         Key format: "database.schema" (e.g., "mydb.public")
     *         Value: list of Table objects (both BASE TABLE and VIEW)
     */
    @GetMapping("/load-all-tables-views")
    public DataResult<Map<String, List<ai.inquery.spi.model.Table>>> loadAllTablesAndViews(
            @RequestParam Long dataSourceId,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        // Set connection context manually since this method doesn't use DataSourceBaseRequest
        ConnectInfo connectInfo = null;
        try {
            connectInfo = connectionInfoHandler.toInfo(dataSourceId, null);
            InqueryContext.putContext(connectInfo);
            return dataCatalogService.loadAllTablesAndViews(dataSourceId, refresh);
        } finally {
            if (connectInfo != null) {
                InqueryContext.removeContext();
            }
        }
    }

    /**
     * Load tables and views for a specific schema
     *
     * @param request table brief query request
     * @return list of Table objects
     */
    @GetMapping("/tables-by-schema")
    public DataResult<List<ai.inquery.spi.model.Table>> loadTablesBySchema(@Valid TableBriefQueryRequest request) {
        TableQueryParam queryParam = TableQueryParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .build();
        return dataCatalogService.loadTablesBySchema(queryParam);
    }

    /**
     * Load columns for a specific table
     *
     * @param request table detail query request
     * @return list of TableColumn objects
     */
    @GetMapping("/columns-by-table")
    public DataResult<List<ai.inquery.spi.model.TableColumn>> loadColumnsByTable(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        return dataCatalogService.loadColumnsByTable(queryParam);
    }

    private DataCatalogSaveParam convertToSaveParam(DataCatalogSaveRequest request) {
        DataCatalogSaveParam param = new DataCatalogSaveParam();
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        param.setTableName(request.getTableName());
        param.setTableDescription(request.getTableDescription());
        
        if (request.getColumns() != null) {
            List<DataCatalogColumnSaveParam> columnParams = request.getColumns().stream()
                    .map(this::convertColumnParam)
                    .collect(Collectors.toList());
            param.setColumns(columnParams);
        }
        
        return param;
    }

    private DataCatalogColumnSaveParam convertColumnParam(DataCatalogColumnSaveRequest request) {
        DataCatalogColumnSaveParam param = new DataCatalogColumnSaveParam();
        param.setColumnName(request.getColumnName());
        param.setColumnDescription(request.getColumnDescription());
        param.setSchemaInfo(request.getSchemaInfo());
        param.setExampleValues(request.getExampleValues());
        return param;
    }

    // ==================== Lineage API ====================

    /**
     * Shared lineage graph cache, accessible from LineageDetectService.
     */
    private ConcurrentHashMap<Long, LineageGraph> getLineageGraphCache() {
        return LineageDetectService.getLineageGraphCache();
    }

    /**
     * Upload a dbt manifest.json file and parse it into a lineage graph.
     * The parsed graph is stored in memory keyed by dataSourceId.
     * Also persists individual table lineage rows to the database.
     */
    @PostMapping("/lineage/upload-manifest")
    public DataResult<LineageGraph> uploadManifest(
            @RequestParam Long dataSourceId,
            @RequestParam("file") MultipartFile file) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            LineageGraph graph = DbtManifestParser.parse(content);
            getLineageGraphCache().put(dataSourceId, graph);

            // Persist to table_lineage rows for each non-source node
            Long userId = ContextUtils.getUserId();
            for (var node : graph.getNodes()) {
                if ("source".equals(node.getResourceType())) continue;

                List<String> parentIds = graph.getEdges().stream()
                        .filter(e -> e.getTargetId().equals(node.getUniqueId()))
                        .map(e -> {
                            var parentNode = graph.getNodes().stream()
                                    .filter(n -> n.getUniqueId().equals(e.getSourceId()))
                                    .findFirst().orElse(null);
                            return parentNode != null ? parentNode.getName() : e.getSourceId();
                        })
                        .collect(java.util.stream.Collectors.toList());

                if (parentIds.isEmpty()) continue;

                LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(TableLineageDO::getDataSourceId, dataSourceId)
                        .eq(node.getDatabase() != null, TableLineageDO::getDatabaseName, node.getDatabase())
                        .eq(node.getSchema() != null, TableLineageDO::getSchemaName, node.getSchema())
                        .eq(TableLineageDO::getTableName, node.getName());

                TableLineageDO existing = getLineageMapper().selectOne(wrapper);
                String sourceTablesStr = String.join(",", parentIds);

                String desc = node.getDescription() != null && !node.getDescription().isBlank()
                        ? node.getDescription() : null;

                if (existing != null) {
                    existing.setSourceTables(sourceTablesStr);
                    existing.setSourceQuery(node.getCompiledSql());
                    existing.setDescription(desc);
                    existing.setGmtModified(new Date());
                    existing.setUserId(userId);
                    getLineageMapper().updateById(existing);
                } else {
                    TableLineageDO lineage = new TableLineageDO();
                    lineage.setDataSourceId(dataSourceId);
                    lineage.setDatabaseName(node.getDatabase());
                    lineage.setSchemaName(node.getSchema());
                    lineage.setTableName(node.getName());
                    lineage.setSourceTables(sourceTablesStr);
                    lineage.setSourceQuery(node.getCompiledSql());
                    lineage.setDescription(desc);
                    lineage.setGmtCreate(new Date());
                    lineage.setGmtModified(new Date());
                    lineage.setUserId(userId);
                    getLineageMapper().insert(lineage);
                }
            }

            log.info("Uploaded dbt manifest for dataSourceId={}: {} nodes, {} edges",
                    dataSourceId, graph.getNodes().size(), graph.getEdges().size());
            return DataResult.of(graph);
        } catch (Exception e) {
            log.error("Failed to parse dbt manifest.json", e);
            return DataResult.error("PARSE_ERROR", "Failed to parse manifest.json: " + e.getMessage());
        }
    }

    /**
     * Get the full lineage graph for a data source.
     * Returns the cached graph from manifest upload, or builds one from DB rows.
     */
    @GetMapping("/lineage/graph")
    public DataResult<LineageGraph> getLineageGraph(@RequestParam Long dataSourceId) {
        LineageGraph cached = getLineageGraphCache().get(dataSourceId);
        if (cached != null) {
            return DataResult.of(cached);
        }

        // Build graph from persisted table_lineage rows
        LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableLineageDO::getDataSourceId, dataSourceId);
        List<TableLineageDO> rows = getLineageMapper().selectList(wrapper);

        if (rows == null || rows.isEmpty()) {
            return DataResult.of(new LineageGraph() {{
                setNodes(new ArrayList<>());
                setEdges(new ArrayList<>());
            }});
        }

        // Build nodes and edges from DB rows using fully-qualified keys to avoid cross-DB collisions
        Map<String, ai.inquery.server.domain.core.dbt.LineageNode> nodeMap = new LinkedHashMap<>();
        List<ai.inquery.server.domain.core.dbt.LineageEdge> edges = new ArrayList<>();

        for (TableLineageDO row : rows) {
            String db = row.getDatabaseName() != null ? row.getDatabaseName() : "";
            String schema = row.getSchemaName() != null ? row.getSchemaName() : "";
            String targetKey = db + "." + schema + "." + row.getTableName();

            if (!nodeMap.containsKey(targetKey)) {
                var node = new ai.inquery.server.domain.core.dbt.LineageNode();
                node.setUniqueId("model." + targetKey);
                node.setName(row.getTableName());
                node.setDatabase(row.getDatabaseName());
                node.setSchema(row.getSchemaName());
                node.setResourceType("model");
                node.setDescription(row.getDescription());
                node.setCompiledSql(row.getSourceQuery());
                nodeMap.put(targetKey, node);
            }

            if (row.getSourceTables() != null && !row.getSourceTables().isEmpty()) {
                for (String src : row.getSourceTables().split(",")) {
                    String srcName = src.trim();
                    if (srcName.isEmpty()) continue;

                    String srcKey = srcName.contains(".") ? srcName : db + "." + schema + "." + srcName;

                    if (!nodeMap.containsKey(srcKey)) {
                        var srcNode = new ai.inquery.server.domain.core.dbt.LineageNode();
                        srcNode.setUniqueId("source." + srcKey);
                        String displayName = srcName.contains(".") ? srcName.substring(srcName.lastIndexOf('.') + 1) : srcName;
                        srcNode.setName(displayName);
                        if (srcName.contains(".")) {
                            String[] parts = srcName.split("\\.", -1);
                            if (parts.length >= 3) {
                                srcNode.setDatabase(parts[0]);
                                srcNode.setSchema(parts[1]);
                            } else if (parts.length == 2) {
                                srcNode.setDatabase(parts[0]);
                                srcNode.setSchema(parts[1]);
                            }
                        } else {
                            srcNode.setDatabase(db);
                            srcNode.setSchema(schema);
                        }
                        srcNode.setResourceType("source");
                        nodeMap.put(srcKey, srcNode);
                    }

                    var edge = new ai.inquery.server.domain.core.dbt.LineageEdge();
                    edge.setSourceId(nodeMap.get(srcKey).getUniqueId());
                    edge.setTargetId(nodeMap.get(targetKey).getUniqueId());
                    edges.add(edge);
                }
            }
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);
        getLineageGraphCache().put(dataSourceId, graph);
        return DataResult.of(graph);
    }

    /**
     * Trigger auto-detect lineage for a data source.
     */
    @PostMapping("/lineage/auto-detect")
    public DataResult<Map<String, Object>> autoDetectLineage(@RequestParam Long dataSourceId) {
        try {
            ConnectInfo connectInfo = connectionInfoHandler.toInfo(dataSourceId, null);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                Dbutils.setSession();
                try {
                    lineageDetectService.detectAndPersist(dataSourceId, connectInfo);
                } catch (Exception e) {
                    log.error("Auto-detect lineage failed for dataSourceId={}", dataSourceId, e);
                } finally {
                    Dbutils.removeSession();
                }
            });
            Map<String, Object> response = new HashMap<>();
            response.put("status", "started");
            response.put("dataSourceId", dataSourceId);
            return DataResult.of(response);
        } catch (Exception e) {
            return DataResult.error("DETECT_ERROR", e.getMessage());
        }
    }

    /**
     * Get lineage detection status for a data source.
     */
    @GetMapping("/lineage/status")
    public DataResult<Map<String, Object>> getLineageStatus(@RequestParam Long dataSourceId) {
        LineageDetectStatus status = lineageDetectService.getStatus(dataSourceId);
        Map<String, Object> result = new HashMap<>();
        if (status != null) {
            result.put("state", status.getState().name());
            result.put("lastRunTime", status.getLastRunTime());
            result.put("nodeCount", status.getNodeCount());
            result.put("edgeCount", status.getEdgeCount());
            result.put("errorMessage", status.getErrorMessage());
        } else {
            result.put("state", "IDLE");
        }
        return DataResult.of(result);
    }

    private TableLineageMapper getLineageMapper() {
        return Dbutils.getMapper(TableLineageMapper.class);
    }

    /**
     * Query lineage information for a table
     *
     * @param request lineage query request
     * @return lineage data or null if not found
     */
    @GetMapping("/lineage/query")
    public DataResult<TableLineageDO> queryLineage(@Valid LineageQueryRequest request) {
        LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableLineageDO::getDataSourceId, request.getDataSourceId())
                .eq(request.getDatabaseName() != null, TableLineageDO::getDatabaseName, request.getDatabaseName())
                .eq(request.getSchemaName() != null, TableLineageDO::getSchemaName, request.getSchemaName())
                .eq(TableLineageDO::getTableName, request.getTableName());
        
        TableLineageDO lineage = getLineageMapper().selectOne(wrapper);
        return DataResult.of(lineage);
    }

    /**
     * Save or update lineage information for a table
     *
     * @param request lineage save request
     * @return action result
     */
    @PostMapping("/lineage/save")
    public ActionResult saveLineage(@Valid @RequestBody LineageSaveRequest request) {
        Long userId = ContextUtils.getUserId();
        
        // Check if lineage exists
        LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableLineageDO::getDataSourceId, request.getDataSourceId())
                .eq(request.getDatabaseName() != null, TableLineageDO::getDatabaseName, request.getDatabaseName())
                .eq(request.getSchemaName() != null, TableLineageDO::getSchemaName, request.getSchemaName())
                .eq(TableLineageDO::getTableName, request.getTableName());
        
        TableLineageDO existing = getLineageMapper().selectOne(wrapper);
        
        if (existing != null) {
            // Update existing lineage
            existing.setSourceQuery(request.getSourceQuery());
            existing.setSourceTables(request.getSourceTables());
            existing.setDescription(request.getDescription());
            existing.setGmtModified(new Date());
            existing.setUserId(userId);
            getLineageMapper().updateById(existing);
            log.info("Updated lineage for table: {}.{}.{}", 
                request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        } else {
            // Create new lineage
            TableLineageDO lineage = new TableLineageDO();
            lineage.setDataSourceId(request.getDataSourceId());
            lineage.setDatabaseName(request.getDatabaseName());
            lineage.setSchemaName(request.getSchemaName());
            lineage.setTableName(request.getTableName());
            lineage.setSourceQuery(request.getSourceQuery());
            lineage.setSourceTables(request.getSourceTables());
            lineage.setDescription(request.getDescription());
            lineage.setGmtCreate(new Date());
            lineage.setGmtModified(new Date());
            lineage.setUserId(userId);
            getLineageMapper().insert(lineage);
            log.info("Created lineage for table: {}.{}.{}", 
                request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        }
        
        return ActionResult.isSuccess();
    }

    /**
     * Delete lineage information for a table
     *
     * @param request lineage query request
     * @return action result
     */
    @DeleteMapping("/lineage/delete")
    public ActionResult deleteLineage(@Valid LineageQueryRequest request) {
        LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableLineageDO::getDataSourceId, request.getDataSourceId())
                .eq(request.getDatabaseName() != null, TableLineageDO::getDatabaseName, request.getDatabaseName())
                .eq(request.getSchemaName() != null, TableLineageDO::getSchemaName, request.getSchemaName())
                .eq(TableLineageDO::getTableName, request.getTableName());
        
        int deleted = getLineageMapper().delete(wrapper);
        log.info("Deleted {} lineage record(s) for table: {}.{}.{}", 
            deleted, request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        
        return ActionResult.isSuccess();
    }

    // ==================== Table Active State API ====================

    /**
     * Get active states for all tables in a schema
     * Returns map of table keys to active states
     *
     * @param dataSourceId data source ID
     * @param databaseName database name (optional)
     * @param schemaName schema name (optional)
     * @return map of table keys to active states
     */
    @GetMapping("/active-states")
    public DataResult<Map<String, Boolean>> getTableActiveStates(
            @RequestParam Long dataSourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String schemaName) {
        return dataCatalogService.getTableActiveStates(dataSourceId, databaseName, schemaName);
    }

    /**
     * Save active state for a single table
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param active whether the table is active for AI queries
     * @return action result
     */
    @PostMapping("/active-state")
    public ActionResult saveTableActiveState(
            @RequestParam Long dataSourceId,
            @RequestParam String databaseName,
            @RequestParam(required = false) String schemaName,
            @RequestParam String tableName,
            @RequestParam Boolean active) {
        ActionResult result = dataCatalogService.saveTableActiveState(dataSourceId, databaseName, schemaName, tableName, active);

        // Also update vector store metadata so the active filter takes effect immediately
        if (result.getSuccess()) {
            try {
                var provider = vectorStoreRegistry.getActiveProvider();
                if (provider.isConfigured()) {
                    String dbType = "snowflake";
                    try {
                        var dsMapper = Dbutils.getMapper(ai.inquery.server.domain.repository.mapper.DataSourceMapper.class);
                        var ds = dsMapper.selectById(dataSourceId);
                        if (ds != null && org.apache.commons.lang3.StringUtils.isNotBlank(ds.getType())) {
                            dbType = ds.getType();
                        }
                    } catch (Exception e) {
                        log.debug("Could not look up dbType for dataSourceId {}: {}", dataSourceId, e.getMessage());
                    }

                    String vectorId = ai.inquery.server.web.api.http.GatewayClientService.generateTableVectorId(
                            databaseName, schemaName, tableName);
                    String namespace = dbType.toLowerCase();
                    Map<String, String> metadata = new java.util.HashMap<>();
                    metadata.put("active", String.valueOf(active));
                    provider.updateMetadata(vectorId, metadata, namespace);
                    log.info("Updated vector active metadata: vectorId={}, active={}, namespace={}", vectorId, active, namespace);
                }
            } catch (Exception e) {
                log.warn("Failed to update vector active metadata for table {}: {}", tableName, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Batch save active states for multiple tables
     *
     * @param dataSourceId data source ID
     * @param activeStates map of table keys to active states (key format: "database.schema.table")
     * @return action result
     */
    @PostMapping("/active-states/batch")
    public ActionResult batchSaveTableActiveStates(
            @RequestParam Long dataSourceId,
            @RequestBody Map<String, Boolean> activeStates) {
        return dataCatalogService.batchSaveTableActiveStates(dataSourceId, activeStates);
    }

    /**
     * Get list of active tables for AI queries
     *
     * @param dataSourceId data source ID
     * @param databaseName database name (optional)
     * @param schemaName schema name (optional)
     * @return list of active table names
     */
    @GetMapping("/active-tables")
    public DataResult<List<String>> getActiveTables(
            @RequestParam Long dataSourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String schemaName) {
        return dataCatalogService.getActiveTables(dataSourceId, databaseName, schemaName);
    }
}
