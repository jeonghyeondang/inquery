package ai.inquery.server.web.api.controller.ai;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.param.ShowCreateTableParam;
import ai.inquery.server.domain.api.param.TablePageQueryParam;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.param.TableSelector;
import ai.inquery.server.domain.api.param.TableVectorParam;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.tools.base.enums.WhiteListTypeEnum;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.ai.inquery.client.InqueryAIClient;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse;
import ai.inquery.server.web.api.controller.ai.pinecone.bm25.BM25SparseEncoder;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import ai.inquery.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.inquery.server.web.api.controller.rdb.request.TableBriefQueryRequest;
import ai.inquery.server.web.api.controller.rdb.request.TableMilvusQueryRequest;
import ai.inquery.server.web.api.controller.rdb.request.TableQueryRequest;
import ai.inquery.server.web.api.http.GatewayClientService;
import ai.inquery.server.web.api.http.request.EsTableSchemaRequest;
import ai.inquery.server.web.api.http.request.TableSchemaRequest;
import ai.inquery.server.web.api.http.request.WhiteListRequest;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import ai.inquery.spi.model.Table;
import cn.hutool.json.JSONUtil;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 */
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/ai/embedding")
@Slf4j
public class EmbeddingController extends ChatController {


    @Resource
    private GatewayClientService gatewayClientService;

    @Autowired
    private RdbWebConverter rdbWebConverter;

    @Autowired
    private DataCatalogService dataCatalogService;

    @Autowired
    private ai.inquery.server.domain.api.service.DataSourceService dataSourceService;

    @Autowired
    private ai.inquery.server.web.api.service.VectorStoreRegistry vectorStoreRegistry;

    @Autowired
    private ai.inquery.server.domain.api.service.VectorSearchService vectorSearchService;

    @Autowired
    private ai.inquery.server.domain.repository.mapper.TableLineageMapper tableLineageMapper;

    /**
     * check if in white list
     */
    @GetMapping("/white/check")
    public DataResult<Boolean> checkInWhite(WhiteListRequest request) {
        request.setWhiteType(WhiteListTypeEnum.VECTOR.getCode());
        if (StringUtils.isBlank(request.getApiKey())) {
            return DataResult.of(false);
        }
        try {
            DataResult<Boolean> res = gatewayClientService.checkInWhite(request);
        } catch (Exception ex) {
            log.error("checkInWhite error", ex);
        }
        return DataResult.of(false);
    }

    /**
     * save datasource embeddings
     *
     * @param request
     * @return
     * @throws IOException
     */
    @PostMapping("/datasource")
    @CrossOrigin
    public ActionResult embeddings(@Valid TableMilvusQueryRequest request)
        throws Exception {
        log.info("embeddings() called - dataSourceId: {}, databaseName: {}, schemaName: {}",
            request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName());

        // Detect BigQuery for sharded table handling
        boolean isBigQuery = isBigQueryDataSource(request.getDataSourceId());

        // query tables
        request.setPageNo(1);
        request.setPageSize(1000);
        TablePageQueryParam queryParam = rdbWebConverter.tablePageRequest2param(request);
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(false);
        tableSelector.setIndexList(false);
        PageResult<Table> tableDTOPageResult = tableService.pageQuery(queryParam, tableSelector);

        List<Table> tables = tableDTOPageResult.getData();
        if (CollectionUtils.isEmpty(tables)) {
            log.info("embeddings() - no tables found, returning success");
            return ActionResult.isSuccess();
        }

        log.info("embeddings() - found {} tables (total: {}), starting to save embeddings",
            tables.size(), tableDTOPageResult.getTotal());

        // Track processed wildcard names to deduplicate BigQuery sharded tables
        Set<String> processedNames = new HashSet<>();

        TableSchemaRequest tableSchemaRequest = new TableSchemaRequest();
        tableSchemaRequest.setDataSourceId(request.getDataSourceId());
        tableSchemaRequest.setApiKey(request.getApikey());
        tableSchemaRequest.setDeleteBeforeInsert(true);
        tableSchemaRequest.setDataSourceSchema(request.getSchemaName());
        tableSchemaRequest.setDatabaseName(request.getDatabaseName());

        // Process first page
        boolean firstSaved = false;
        for (Table table : tables) {
            if (processAndSaveTable(table.getName(), request, tableSchemaRequest, isBigQuery, processedNames)) {
                if (!firstSaved) {
                    firstSaved = true;
                    tableSchemaRequest.setDeleteBeforeInsert(false);
                }
            }
        }

        // Process remaining pages
        Long totalTableCount = tableDTOPageResult.getTotal();
        Integer pageSize = queryParam.getPageSize();
        if (pageSize < totalTableCount) {
            for (int i = 2; i <= totalTableCount / pageSize + 1; i++) {
                queryParam.setPageNo(i);
                tableDTOPageResult = tableService.pageQuery(queryParam, tableSelector);
                tables = tableDTOPageResult.getData();
                if (CollectionUtils.isEmpty(tables)) break;
                for (Table table : tables) {
                    processAndSaveTable(table.getName(), request, tableSchemaRequest, isBigQuery, processedNames);
                }
            }
        }

        return ActionResult.isSuccess();
    }

    /**
     * Process a single table for embedding. Handles BigQuery sharded table deduplication.
     * Returns true if the table was saved (not skipped).
     */
    private boolean processAndSaveTable(String actualTableName, TableBriefQueryRequest request,
            TableSchemaRequest tableSchemaRequest, boolean isBigQuery, Set<String> processedNames) throws Exception {
        String vectorTableName = isBigQuery ? toBigQueryWildcard(actualTableName) : actualTableName;

        // Skip if this sharded group was already processed
        if (!processedNames.add(vectorTableName)) {
            return false;
        }

        String tableSchema = buildTableSchemaText(actualTableName, request);
        if (StringUtils.isBlank(tableSchema)) {
            return false;
        }

        // For BigQuery sharded tables, replace table name with wildcard and add _TABLE_SUFFIX note
        if (isBigQuery && !vectorTableName.equals(actualTableName)) {
            tableSchema = tableSchema.replace("Table: " + actualTableName, "Table: " + vectorTableName);
            tableSchema += "\nIMPORTANT: This is a date-sharded table. Query using wildcard syntax: "
                + "FROM `" + request.getDatabaseName() + "." + request.getSchemaName() + "." + vectorTableName
                + "` WHERE _TABLE_SUFFIX BETWEEN 'YYYYMMDD' AND 'YYYYMMDD'";
        }

        tableSchemaRequest.setTableName(vectorTableName);
        if (isBigQuery) {
            tableSchemaRequest.setDbType("BIGQUERY");
        }
        saveTableEmbedding(tableSchema, tableSchemaRequest);
        return true;
    }

    /**
     * save datasource schema
     *
     * @param request
     * @return
     * @throws IOException
     */
    @PostMapping("/datasource/es")
    @CrossOrigin
    public ActionResult es(@Valid EsTableSchemaRequest request)
            throws Exception {

        // query tables
        TablePageQueryParam queryParam = rdbWebConverter.schemaReq2page(request);
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(false);
        tableSelector.setIndexList(false);
        queryParam.setPageNo(1);
        queryParam.setPageSize(1000);
        PageResult<Table> tableDTOPageResult = tableService.pageQuery(queryParam, tableSelector);

        List<Table> tables = tableDTOPageResult.getData();
        if (CollectionUtils.isEmpty(tables)) {
            return ActionResult.isSuccess();
        }
        String tableName = tables.get(0).getName();
        String tableSchema = queryTableDdlByEs(tableName, request);
        request.setTableName(tableName);
        request.setTableSchemaContent(tableSchema);

        if (StringUtils.isBlank(tableSchema)) {
            throw new ParamBusinessException("tableSchema is empty");
        }

        // save first table embedding
        request.setDeleteBeforeInsert(true);
        saveTableEs(request);

        // save other table embedding
        request.setDeleteBeforeInsert(false);
        for (int i = 1; i < tables.size(); i++) {
            tableName = tables.get(i).getName();
            tableSchema = queryTableDdlByEs(tableName, request);
            if (StringUtils.isBlank(tableSchema)) {
                continue;
            }
            request.setTableName(tableName);
            request.setTableSchemaContent(tableSchema);
            saveTableEs(request);
        }

        // query all the tables
        Long totalTableCount = tableDTOPageResult.getTotal();
        Integer pageSize = queryParam.getPageSize();
        if (pageSize < totalTableCount) {
            for (int i = 2; i < totalTableCount/pageSize + 1; i++) {
                queryParam.setPageNo(i);
                tableDTOPageResult = tableService.pageQuery(queryParam, tableSelector);
                tables = tableDTOPageResult.getData();
                for (Table table : tables) {
                    tableName = table.getName();
                    tableSchema = queryTableDdlByEs(tableName, request);
                    if (StringUtils.isBlank(tableSchema)) {
                        continue;
                    }
                    request.setTableName(tableName);
                    request.setTableSchemaContent(tableSchema);
                    saveTableEs(request);
                }
            }
        }

        return ActionResult.isSuccess();
    }

    /**
     * Check if schema is a system schema that should be skipped for vector embedding
     *
     * @param schemaName schema name to check
     * @param dataSourceId data source ID to get database type
     * @return true if system schema, false otherwise
     */
    private boolean isSystemSchema(String schemaName, Long dataSourceId) {
        if (StringUtils.isBlank(schemaName)) {
            return false;
        }
        
        String schemaUpper = schemaName.toUpperCase();
        
        // Common system schemas across most databases
        if (schemaUpper.equals("INFORMATION_SCHEMA")) {
            return true;
        }
        
        // Get database type to check DB-specific system schemas
        try {
            DataResult<ai.inquery.server.domain.api.model.DataSource> dataResult = 
                dataSourceService.queryById(dataSourceId);
            if (dataResult != null && dataResult.getData() != null) {
                String dbType = dataResult.getData().getType();
                if (StringUtils.isNotBlank(dbType)) {
                    String dbTypeUpper = dbType.toUpperCase();
                    
                    // MySQL/MariaDB system schemas
                    if (dbTypeUpper.contains("MYSQL") || dbTypeUpper.contains("MARIADB")) {
                        if (schemaUpper.equals("MYSQL") || 
                            schemaUpper.equals("PERFORMANCE_SCHEMA") || 
                            schemaUpper.equals("SYS") ||
                            schemaUpper.equals("INFORMATION_SCHEMA")) {
                            return true;
                        }
                    }
                    
                    // PostgreSQL system schemas
                    if (dbTypeUpper.contains("POSTGRESQL") || dbTypeUpper.contains("POSTGRES")) {
                        if (schemaUpper.equals("PG_CATALOG") || 
                            schemaUpper.equals("PG_TOAST") ||
                            schemaUpper.startsWith("PG_TEMP") ||
                            schemaUpper.startsWith("PG_TOAST_TEMP") ||
                            schemaUpper.equals("INFORMATION_SCHEMA")) {
                            return true;
                        }
                    }
                    
                    // SQL Server system schemas
                    if (dbTypeUpper.contains("SQLSERVER") || dbTypeUpper.contains("SQL_SERVER")) {
                        if (schemaUpper.equals("SYS") || 
                            schemaUpper.equals("DB_OWNER") ||
                            schemaUpper.equals("GUEST") ||
                            schemaUpper.equals("INFORMATION_SCHEMA")) {
                            return true;
                        }
                    }
                    
                    // Oracle system schemas
                    if (dbTypeUpper.contains("ORACLE")) {
                        if (schemaUpper.equals("SYS") || 
                            schemaUpper.equals("SYSTEM") ||
                            schemaUpper.equals("SYSMAN") ||
                            schemaUpper.startsWith("APEX_") ||
                            schemaUpper.equals("INFORMATION_SCHEMA")) {
                            return true;
                        }
                    }
                    
                    // Snowflake system schemas
                    if (dbTypeUpper.contains("SNOWFLAKE")) {
                        if (schemaUpper.equals("INFORMATION_SCHEMA")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check database type for system schema filtering, schema: {}", schemaName, e);
            // If we can't determine DB type, at least filter common INFORMATION_SCHEMA
        }
        
        return false;
    }

    /**
     * sync table vector
     *
     * @param param
     */
    public void syncTableVector(TableBriefQueryRequest param) throws Exception {
        log.info("Starting syncTableVector - dataSourceId: {}, databaseName: {}, schemaName: {}", 
            param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        
        // Skip system schemas
        String schemaName = param.getSchemaName();
        if (isSystemSchema(schemaName, param.getDataSourceId())) {
            log.info("syncTableVector: skipping system schema - dataSourceId: {}, databaseName: {}, schemaName: {}", 
                param.getDataSourceId(), param.getDatabaseName(), schemaName);
            return;
        }
        
        TableVectorParam vectorParam = rdbWebConverter.param2param(param);
        if (Objects.isNull(vectorParam.getDataSourceId())) {
            log.debug("syncTableVector: dataSourceId is null, skipping");
            return;
        }
        if (StringUtils.isBlank(vectorParam.getDatabase()) && StringUtils.isBlank(vectorParam.getSchema())) {
            log.debug("syncTableVector: database and schema are both blank, skipping");
            return;
        }

        String apiKey = getApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.info("syncTableVector: API key is not configured, skipping");
            return;
        }

        TableMilvusQueryRequest request = rdbWebConverter.request2request(param);
        request.setApikey(apiKey);

        vectorParam.setApiKey(apiKey);
        DataResult<Boolean> result = tableService.checkTableVector(vectorParam);
        if (result.getData()) {
            log.info("syncTableVector: vectors already exist for dataSourceId: {}, databaseName: {}, schemaName: {}, skipping", 
                param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
            return;
        }

        // Check if the active vector store is configured
        ai.inquery.server.domain.api.service.VectorStoreProvider activeProvider =
            vectorStoreRegistry.getActiveProvider();
        if (!activeProvider.isConfigured()) {
            activeProvider.refresh();
            if (!activeProvider.isConfigured()) {
                log.info("Vector store ({}) is not configured, skipping vector sync", activeProvider.getType());
                return;
            }
        }

        log.info("syncTableVector: calling embeddings() for dataSourceId: {}, databaseName: {}, schemaName: {}", 
            param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        try {
            embeddings(request);
            log.info("syncTableVector: embeddings() completed successfully");
        } catch (Exception e) {
            log.error("syncTableVector: embeddings() failed for dataSourceId: {}, databaseName: {}, schemaName: {}", 
                param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), e);
            throw e;
        }

        log.info("syncTableVector: saving table vector status for dataSourceId: {}, databaseName: {}, schemaName: {}", 
            param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        try {
            tableService.saveTableVector(vectorParam);
            log.info("syncTableVector: table vector status saved successfully");
        } catch (Exception e) {
            log.error("syncTableVector: saveTableVector() failed for dataSourceId: {}, databaseName: {}, schemaName: {}", 
                param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), e);
            throw e;
        }
        
        log.info("syncTableVector: completed successfully for dataSourceId: {}, databaseName: {}, schemaName: {}", 
            param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
    }

    /**
     * save table embedding
     *
     * @param tableSchema
     * @param tableSchemaRequest
     * @throws Exception
     */
    private void saveTableEmbedding(String tableSchema, TableSchemaRequest tableSchemaRequest) throws Exception{
        log.info("saveTableEmbedding() called - dataSourceId: {}, databaseName: {}, schemaName: {}, schemaLength: {}", 
            tableSchemaRequest.getDataSourceId(), tableSchemaRequest.getDatabaseName(), 
            tableSchemaRequest.getDataSourceSchema(), tableSchema != null ? tableSchema.length() : 0);
        
        List<String> schemaList = Lists.newArrayList(tableSchema);
        tableSchemaRequest.setSchemaList(schemaList);

        List<List<BigDecimal>> contentVector = new ArrayList<>();
        for(String str : schemaList){
            log.info("saveTableEmbedding() - requesting embedding for schema (length: {})", str.length());
            try {
                List<Double> embeddingDoubles = vectorSearchService.generateEmbedding(str);
                if (CollectionUtils.isEmpty(embeddingDoubles)) {
                    log.error("saveTableEmbedding() - embedding data is empty");
                    throw new ParamBusinessException("Embedding data is empty");
                }
                List<BigDecimal> embeddingBd = embeddingDoubles.stream()
                    .map(BigDecimal::valueOf)
                    .collect(java.util.stream.Collectors.toList());
                contentVector.add(embeddingBd);
                log.info("saveTableEmbedding() - embedding generated successfully (vector size: {})", embeddingBd.size());
            } catch (Exception e) {
                log.error("saveTableEmbedding() - failed to generate embedding", e);
                throw e;
            }
        }
        if (CollectionUtils.isEmpty(contentVector)) {
            log.error("saveTableEmbedding() - contentVector is empty after processing");
            throw new ParamBusinessException("Content vector is empty");
        }
        tableSchemaRequest.setSchemaVector(contentVector);

        // save table embedding
        log.info("saveTableEmbedding() - saving to Pinecone");
        try {
            ActionResult result = gatewayClientService.schemaVectorSave(tableSchemaRequest);
            if (!result.getSuccess()) {
                log.error("saveTableEmbedding() - schemaVectorSave failed: {}", result.getErrorMessage());
                throw new ParamBusinessException("Failed to save vector: " + result.getErrorMessage());
            }
            log.info("saveTableEmbedding() - saved to Pinecone successfully");
        } catch (Exception e) {
            log.error("saveTableEmbedding() - exception during schemaVectorSave", e);
            throw e;
        }
    }

    /**
     * sync table vector
     *
     * @param param
     */
    public void syncTableEs(TableBriefQueryRequest param) throws Exception {
        EsTableSchemaRequest esParam = rdbWebConverter.req2req(param);
        if (Objects.isNull(esParam.getDataSourceId())) {
            return;
        }
        if (StringUtils.isBlank(esParam.getDatabaseName()) && StringUtils.isBlank(esParam.getSchemaName())) {
            return;
        }

        String apiKey = getApiKey();
        if (StringUtils.isBlank(apiKey)) {
            return;
        }

        esParam.setApiKey(apiKey);
        es(esParam);
    }

    /**
     * save table schema
     *
     * @param tableSchemaRequest
     * @throws Exception
     */
    private void saveTableEs(EsTableSchemaRequest tableSchemaRequest) throws Exception{
        // save table es
        gatewayClientService.schemaEsSave(tableSchemaRequest);
    }

    /**
     * Build table schema text from data catalog (if saved) or DDL (if not saved)
     *
     * @param tableName
     * @param request
     * @return
     */
    /**
     * Truncate text to a maximum number of sentences.
     * Splits on sentence-ending punctuation (. ! ?) and keeps up to maxSentences.
     */
    private String truncateToSentences(String text, int maxSentences) {
        if (StringUtils.isBlank(text)) return text;
        // Split on sentence boundaries (period, exclamation, question mark followed by space or end)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= maxSentences) return text.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxSentences; i++) {
            if (i > 0) sb.append(" ");
            sb.append(sentences[i].trim());
        }
        return sb.toString();
    }

    private String buildTableSchemaText(String tableName, TableBriefQueryRequest request) {
        // First, try to get from data catalog
        TableQueryParam catalogParam = TableQueryParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .tableName(tableName)
            .build();
        
        DataResult<Map<String, Object>> catalogResult = dataCatalogService.queryCatalog(catalogParam);
        if (catalogResult.getSuccess() && catalogResult.getData() != null) {
            Map<String, Object> catalogData = catalogResult.getData();
            String tableDescription = (String) catalogData.get("tableDescription");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) catalogData.get("columns");
            
            // Build schema text from catalog if we have saved data
            if (StringUtils.isNotBlank(tableDescription) || (columns != null && columns.stream()
                .anyMatch(col -> StringUtils.isNotBlank((String) col.get("description"))))) {
                
                StringBuilder schemaText = new StringBuilder();
                schemaText.append("Table: ").append(tableName).append("\n");
                
                if (StringUtils.isNotBlank(tableDescription)) {
                    schemaText.append("Description: ").append(truncateToSentences(tableDescription, 5)).append("\n");
                }
                
                if (columns != null && !columns.isEmpty()) {
                    schemaText.append("Columns:\n");
                    for (Map<String, Object> column : columns) {
                        String colName = (String) column.get("name");
                        String colType = (String) column.get("columnType");
                        String colDescription = (String) column.get("description");
                        // Get exampleValues - can be List<String> or String (JSON)
                        List<String> exampleValues = null;
                        Object exampleValuesObj = column.get("exampleValues");
                        if (exampleValuesObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> listValues = (List<String>) exampleValuesObj;
                            exampleValues = listValues;
                        } else if (exampleValuesObj instanceof String) {
                            String exampleValuesStr = (String) exampleValuesObj;
                            if (StringUtils.isNotBlank(exampleValuesStr)) {
                                try {
                                    // Try to parse as JSON array first (new format: ["value1","value2"])
                                    if (exampleValuesStr.trim().startsWith("[")) {
                                        exampleValues = JSONUtil.toList(exampleValuesStr, String.class);
                                    } else {
                                        // Fallback: handle comma-separated format (old format: "value1, value2")
                                        exampleValues = java.util.Arrays.stream(exampleValuesStr.split(","))
                                            .map(String::trim)
                                            .filter(s -> !s.isEmpty())
                                            .collect(java.util.stream.Collectors.toList());
                                    }
                                } catch (Exception e) {
                                    log.debug("Failed to parse exampleValues: {}", exampleValuesStr, e);
                                    exampleValues = new ArrayList<>();
                                }
                            }
                        }
                        // Handle nullable - can be Boolean or Integer (0/1)
                        Boolean nullable = null;
                        Object nullableObj = column.get("nullable");
                        if (nullableObj instanceof Boolean) {
                            nullable = (Boolean) nullableObj;
                        } else if (nullableObj instanceof Integer) {
                            nullable = ((Integer) nullableObj) != 0;
                        } else if (nullableObj != null) {
                            nullable = Boolean.parseBoolean(String.valueOf(nullableObj));
                        }
                        // Handle primaryKey - can be Boolean or Integer (0/1)
                        Boolean primaryKey = null;
                        Object primaryKeyObj = column.get("primaryKey");
                        if (primaryKeyObj instanceof Boolean) {
                            primaryKey = (Boolean) primaryKeyObj;
                        } else if (primaryKeyObj instanceof Integer) {
                            primaryKey = ((Integer) primaryKeyObj) != 0;
                        } else if (primaryKeyObj != null) {
                            primaryKey = Boolean.parseBoolean(String.valueOf(primaryKeyObj));
                        }
                        
                        schemaText.append("  - ").append(colName);
                        if (StringUtils.isNotBlank(colType)) {
                            schemaText.append(" (").append(colType).append(")");
                        }
                        if (Boolean.TRUE.equals(primaryKey)) {
                            schemaText.append(" [PRIMARY KEY]");
                        }
                        if (Boolean.TRUE.equals(nullable)) {
                            schemaText.append(" [NULLABLE]");
                        }
                        if (StringUtils.isNotBlank(colDescription)) {
                            schemaText.append(": ").append(truncateToSentences(colDescription, 1));
                        }
                        // Add example values if available
                        if (exampleValues != null && !exampleValues.isEmpty()) {
                            schemaText.append(" [Examples: ").append(String.join(", ", exampleValues)).append("]");
                        }
                        schemaText.append("\n");
                    }
                }
                
                // Enrich with lineage data if available
                appendLineageData(schemaText, request.getDataSourceId(), request.getDatabaseName(),
                        request.getSchemaName(), tableName);

                log.info("Using data catalog information for table: {}", tableName);
                return schemaText.toString();
            }
        }
        
        // Fallback to DDL if no catalog data
        log.info("No catalog data found for table: {}, using DDL", tableName);
        return queryTableDdl(tableName, request);
    }

    /**
     * Append lineage information (upstream, downstream, source query) to metadata text.
     */
    private void appendLineageData(StringBuilder schemaText, Long dataSourceId, String databaseName,
                                    String schemaName, String tableName) {
        try {
            // Query upstream: this table's own lineage record
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.inquery.server.domain.repository.entity.TableLineageDO> upstreamQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            upstreamQuery.eq("data_source_id", dataSourceId)
                    .eq("table_name", tableName);
            if (databaseName != null) upstreamQuery.eq("database_name", databaseName);
            if (schemaName != null) upstreamQuery.eq("schema_name", schemaName);

            ai.inquery.server.domain.repository.entity.TableLineageDO lineage = tableLineageMapper.selectOne(upstreamQuery);

            // Query downstream: other tables that list this table in their source_tables (same database only)
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.inquery.server.domain.repository.entity.TableLineageDO> downstreamQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            downstreamQuery.eq("data_source_id", dataSourceId)
                    .like("source_tables", tableName);
            if (databaseName != null) downstreamQuery.eq("database_name", databaseName);
            if (schemaName != null) downstreamQuery.eq("schema_name", schemaName);

            List<ai.inquery.server.domain.repository.entity.TableLineageDO> downstreamList = tableLineageMapper.selectList(downstreamQuery);

            boolean hasUpstream = lineage != null && StringUtils.isNotBlank(lineage.getSourceTables());
            boolean hasDownstream = downstreamList != null && !downstreamList.isEmpty();
            boolean hasSourceQuery = lineage != null && StringUtils.isNotBlank(lineage.getSourceQuery());

            if (!hasUpstream && !hasDownstream && !hasSourceQuery) {
                return;
            }

            schemaText.append("\n[Lineage]\n");

            if (hasUpstream) {
                schemaText.append("Upstream (source tables): ").append(lineage.getSourceTables()).append("\n");
            }

            if (hasDownstream) {
                List<String> downstreamNames = downstreamList.stream()
                        .map(d -> {
                            StringBuilder fullPath = new StringBuilder();
                            if (StringUtils.isNotBlank(d.getDatabaseName())) {
                                fullPath.append(d.getDatabaseName()).append(".");
                            }
                            if (StringUtils.isNotBlank(d.getSchemaName())) {
                                fullPath.append(d.getSchemaName()).append(".");
                            }
                            fullPath.append(d.getTableName());
                            return fullPath.toString();
                        })
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());
                schemaText.append("Downstream (used by): ").append(String.join(", ", downstreamNames)).append("\n");
            }

            if (hasSourceQuery) {
                schemaText.append("Source Query:\n").append(lineage.getSourceQuery()).append("\n");
            }

            log.info("Lineage data appended for table: {} (upstream={}, downstream={}, sourceQuery={})",
                    tableName, hasUpstream, hasDownstream, hasSourceQuery);
        } catch (Exception e) {
            log.warn("Failed to append lineage data for table {}: {}", tableName, e.getMessage());
        }
    }

    /**
     * query table DDL
     *
     * @param tableName
     * @param request
     * @return
     */
    private String queryTableDdl(String tableName, TableBriefQueryRequest request) {
        ShowCreateTableParam param = new ShowCreateTableParam();
        param.setTableName(tableName);
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        DataResult<String> tableSchema = tableService.showCreateTable(param);
        return tableSchema.getData();
    }

    /**
     * query table schema
     *
     * @param tableName
     * @param request
     * @return
     */
    private String queryTableDdlByEs(String tableName, EsTableSchemaRequest request) {
        ShowCreateTableParam param = new ShowCreateTableParam();
        param.setTableName(tableName);
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        DataResult<String> tableSchema = tableService.showCreateTable(param);
        return tableSchema.getData();
    }

    /**
     * Update vector embedding for a single table after data catalog save
     * This is called when data catalog information is saved/updated
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @throws Exception if embedding generation or saving fails
     */
    public void updateTableVectorEmbedding(Long dataSourceId, String databaseName, String schemaName, String tableName) throws Exception {
        String apiKey = getApiKey();
        ai.inquery.server.domain.api.service.VectorStoreProvider vectorProvider =
            vectorStoreRegistry.getActiveProvider();
        boolean usesLocalEmbedding = vectorProvider.getType() == ai.inquery.server.domain.api.enums.VectorStoreType.PGVECTOR
            || vectorProvider.getType() == ai.inquery.server.domain.api.enums.VectorStoreType.QDRANT;
        // Pinecone embedding uses Gemini first, then OpenAI as fallback.
        // Check both API keys before rejecting.
        if (!usesLocalEmbedding && StringUtils.isBlank(apiKey)) {
            // Also check Gemini API key
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            Config geminiConfig = configService.find("gemini.apiKey").getData();
            if (geminiConfig == null || StringUtils.isBlank(geminiConfig.getContent())) {
                log.warn("AI API key is not configured (neither OpenAI nor Gemini), cannot generate embedding for table: {}", tableName);
                throw new IllegalStateException("AI API key is not configured. Please configure it in Settings → AI.");
            }
            // Gemini key exists, proceed (apiKey stays null but generateEmbedding will use Gemini)
        }

        // Check if the active vector store is configured
        if (!vectorProvider.isConfigured()) {
            vectorProvider.refresh();
            if (!vectorProvider.isConfigured()) {
                log.warn("Vector store ({}) is not configured, cannot generate embedding for table: {}",
                    vectorProvider.getType(), tableName);
                throw new IllegalStateException("Vector DB is not configured. Please configure it in Settings → Vector DB.");
            }
        }

        // Build request to get table schema
        TableBriefQueryRequest request = new TableBriefQueryRequest();
        request.setDataSourceId(dataSourceId);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);

        // Get database type for metadata
        String dbType = null;
        try {
            DataResult<ai.inquery.server.domain.api.model.DataSource> dataSourceResult = dataSourceService.queryById(dataSourceId);
            if (dataSourceResult.getSuccess() && dataSourceResult.getData() != null) {
                dbType = dataSourceResult.getData().getType();
                if (StringUtils.isNotBlank(dbType)) {
                    log.debug("Database type for dataSourceId {}: {}", dataSourceId, dbType);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get database type, continuing without it", e);
        }

        // For BigQuery sharded tables, convert to wildcard name
        boolean isBigQuery = "BIGQUERY".equalsIgnoreCase(dbType);
        String vectorTableName = isBigQuery ? toBigQueryWildcard(tableName) : tableName;

        // Get table schema text (will use data catalog if available)
        String tableSchema = buildTableSchemaText(tableName, request);
        if (StringUtils.isBlank(tableSchema)) {
            log.warn("Table schema is empty for table: {}, cannot generate embedding", tableName);
            throw new IllegalArgumentException("Table schema is empty. Cannot generate embedding.");
        }

        // For BigQuery sharded tables, replace table name and add _TABLE_SUFFIX note
        if (isBigQuery && !vectorTableName.equals(tableName)) {
            tableSchema = tableSchema.replace("Table: " + tableName, "Table: " + vectorTableName);
            tableSchema += "\nIMPORTANT: This is a date-sharded table. Query using wildcard syntax: "
                + "FROM `" + databaseName + "." + schemaName + "." + vectorTableName
                + "` WHERE _TABLE_SUFFIX BETWEEN 'YYYYMMDD' AND 'YYYYMMDD'";
        }

        // Generate embedding via VectorSearchService (routes to local ONNX or API based on active provider)
        List<Double> embeddingDoubles = vectorSearchService.generateEmbedding(tableSchema);
        if (CollectionUtils.isEmpty(embeddingDoubles)) {
            log.error("Failed to generate embedding for table: {}", tableName);
            throw new RuntimeException("Failed to generate embedding: embedding vector is empty");
        }
        List<BigDecimal> embedding = embeddingDoubles.stream()
            .map(BigDecimal::valueOf)
            .collect(java.util.stream.Collectors.toList());

        // Prepare table schema request
        TableSchemaRequest tableSchemaRequest = new TableSchemaRequest();
        tableSchemaRequest.setDataSourceId(dataSourceId);
        tableSchemaRequest.setApiKey(apiKey);
        tableSchemaRequest.setDeleteBeforeInsert(false); // Don't delete all, just upsert this table
        tableSchemaRequest.setDataSourceSchema(schemaName);
        tableSchemaRequest.setDatabaseName(databaseName);
        tableSchemaRequest.setTableName(vectorTableName); // Add table name for metadata (wildcard for BigQuery sharded tables)
        if (StringUtils.isNotBlank(dbType)) {
            tableSchemaRequest.setDbType(dbType); // Add database type for metadata
        }
        tableSchemaRequest.setSchemaList(Lists.newArrayList(tableSchema));
        // schemaVector is List<List<BigDecimal>>, so wrap embedding in a list
        List<List<BigDecimal>> schemaVectorList = new ArrayList<>();
        schemaVectorList.add(embedding);
        tableSchemaRequest.setSchemaVector(schemaVectorList);

        // Save to Pinecone
        ActionResult result = gatewayClientService.schemaVectorSave(tableSchemaRequest);
        if (!result.getSuccess()) {
            log.error("Failed to save vector embedding to Pinecone for table: {}.{}.{} - {}",
                databaseName, schemaName, tableName, result.getErrorMessage());
            throw new RuntimeException("Failed to save vector embedding to Pinecone: " + result.getErrorMessage());
        }

        log.info("Successfully generated and saved vector embedding for table: {}.{}.{}", databaseName, schemaName, tableName);

        // Auto-trigger: incrementally update BM25 model with the new schema text
        updateBM25Incremental(tableSchema);
    }

    /**
     * Incrementally update BM25 model after a single table vector save.
     * Loads existing BM25 params from config, adds the new document, saves back.
     * Non-blocking: failures are logged but don't affect the vector save.
     */
    private void updateBM25Incremental(String schemaText) {
        try {
            ai.inquery.server.domain.api.service.VectorStoreProvider provider =
                vectorStoreRegistry.getActiveProvider();
            if (!provider.isHybridEnabled()) {
                return;
            }

            String existingParams = provider.getHybridBm25Params();
            BM25SparseEncoder encoder = new BM25SparseEncoder();

            if (StringUtils.isNotBlank(existingParams)) {
                encoder.deserialize(existingParams);
                encoder.addDocument(schemaText);
            } else {
                encoder.fit(java.util.List.of(schemaText));
            }

            String serialized = encoder.serialize();
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            ai.inquery.server.domain.api.param.SystemConfigParam bm25Param =
                new ai.inquery.server.domain.api.param.SystemConfigParam();
            bm25Param.setCode(PineconeClient.PINECONE_HYBRID_BM25_PARAMS);
            bm25Param.setContent(serialized);
            configService.createOrUpdate(bm25Param);

            provider.refresh();
            log.info("BM25 incremental update complete: documentCount={}", encoder.isFitted() ? "fitted" : "not fitted");
        } catch (Exception e) {
            log.warn("BM25 incremental update failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Full BM25 refit triggered after bulk operations (e.g., async AI collection).
     * Called from DataCatalogController after all worker threads complete.
     * Re-fetches all Pinecone schemas and refits the entire BM25 model.
     */
    public void refitBM25AfterBulkOperation() {
        ai.inquery.server.domain.api.service.VectorStoreProvider provider =
            vectorStoreRegistry.getActiveProvider();
        if (!provider.isHybridEnabled() || !provider.isConfigured()) {
            log.debug("Hybrid search not enabled or vector store not configured, skipping BM25 refit");
            return;
        }

        log.info("Starting full BM25 refit after bulk operation...");
        ActionResult result = fitBM25();
        if (result.getSuccess()) {
            log.info("Full BM25 refit after bulk operation completed successfully");
        } else {
            log.warn("Full BM25 refit after bulk operation failed: {}", result.getErrorMessage());
        }
    }

    /**
     * Fit BM25 encoder on all existing schema texts in Pinecone.
     * Fetches all vectors' metadata, extracts tableSchema text, fits BM25 IDF model,
     * and saves serialized parameters to config DB.
     *
     * After fitting, enable hybrid search via config:
     *   pinecone.hybrid.enabled = true
     *   pinecone.hybrid.alpha = 0.7  (optional, default 0.7)
     */
    @PostMapping("/bm25/fit")
    public ActionResult fitBM25() {
        log.info("Starting BM25 encoder fitting from vector store metadata...");

        ai.inquery.server.domain.api.service.VectorStoreProvider provider =
            vectorStoreRegistry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
            if (!provider.isConfigured()) {
                return ActionResult.fail("Vector store not configured",
                    "Please configure the vector store first", "VECTORDB_NOT_CONFIGURED");
            }
        }

        try {
            log.info("Listing all vector IDs from {}...", provider.getType());
            List<String> allIds = provider.listAllVectors(null);
            if (allIds.isEmpty()) {
                return ActionResult.fail("No vectors found",
                    "Vector store is empty. Please save schema embeddings first.", "NO_VECTORS");
            }
            log.info("Found {} vectors in {}", allIds.size(), provider.getType());

            log.info("Fetching vector metadata...");
            List<ai.inquery.server.domain.api.model.VectorData> vectors = provider.fetchVectors(allIds, null);
            log.info("Fetched {} vectors with metadata", vectors.size());

            List<String> corpus = new ArrayList<>();
            for (ai.inquery.server.domain.api.model.VectorData v : vectors) {
                if (v.getMetadata() != null && StringUtils.isNotBlank(v.getMetadata().get("tableSchema"))) {
                    corpus.add(v.getMetadata().get("tableSchema"));
                }
            }
            if (corpus.isEmpty()) {
                return ActionResult.fail("No schema texts found",
                    "Vectors have no tableSchema metadata. Re-save embeddings.", "NO_SCHEMA_TEXT");
            }
            log.info("Extracted {} schema texts for BM25 fitting", corpus.size());

            BM25SparseEncoder encoder = new BM25SparseEncoder();
            encoder.fit(corpus);
            String serialized = encoder.serialize();
            log.info("BM25 encoder fitted and serialized ({} bytes)", serialized.length());

            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            ai.inquery.server.domain.api.param.SystemConfigParam bm25Param =
                new ai.inquery.server.domain.api.param.SystemConfigParam();
            bm25Param.setCode(PineconeClient.PINECONE_HYBRID_BM25_PARAMS);
            bm25Param.setContent(serialized);
            configService.createOrUpdate(bm25Param);
            log.info("BM25 params saved to config DB");

            provider.refresh();

            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("BM25 fitting failed", e);
            return ActionResult.fail("BM25 fitting failed", e.getMessage(), "BM25_FIT_ERROR");
        }
    }

    /**
     * Toggle a vector's active state in Pinecone.
     * Used by Data Catalog to include/exclude tables from AI search.
     *
     * @param databaseName Database name
     * @param schemaName Schema name
     * @param tableName Table name
     * @param active true to include in search, false to exclude
     * @param dbType Database type for namespace (e.g., "snowflake", "mysql")
     */
    @PostMapping("/vector/toggle")
    @CrossOrigin
    public ActionResult toggleVectorActive(
            @RequestParam String databaseName,
            @RequestParam String schemaName,
            @RequestParam String tableName,
            @RequestParam boolean active,
            @RequestParam(required = false, defaultValue = "snowflake") String dbType) {

        String vectorId = GatewayClientService.generateTableVectorId(databaseName, schemaName, tableName);
        String namespace = dbType.toLowerCase();

        log.info("Toggling vector active state: vectorId={}, active={}, namespace={}", vectorId, active, namespace);

        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("active", String.valueOf(active));

        ai.inquery.server.domain.api.service.VectorStoreProvider provider =
            vectorStoreRegistry.getActiveProvider();
        boolean success = provider.updateMetadata(vectorId, metadata, namespace);

        if (success) {
            return ActionResult.isSuccess();
        } else {
            return ActionResult.fail("Failed to update vector metadata",
                provider.getType() + " update failed for vectorId: " + vectorId, "VECTOR_TOGGLE_ERROR");
        }
    }

    /**
     * Batch toggle vectors' active state in Pinecone.
     * Used when toggling multiple tables at once (e.g., exclude all dev tables).
     *
     * @param request JSON body with databaseName, schemaName, tableNames[], active, dbType
     */
    @PostMapping("/vector/toggle/batch")
    @CrossOrigin
    public DataResult<Integer> batchToggleVectorActive(@RequestBody Map<String, Object> request) {
        String databaseName = (String) request.get("databaseName");
        String schemaName = (String) request.get("schemaName");
        @SuppressWarnings("unchecked")
        List<String> tableNames = (List<String>) request.get("tableNames");
        boolean active = Boolean.TRUE.equals(request.get("active"));
        String dbType = request.containsKey("dbType") ? (String) request.get("dbType") : "snowflake";

        if (tableNames == null || tableNames.isEmpty()) {
            return DataResult.of(0);
        }

        String namespace = dbType.toLowerCase();
        log.info("Batch toggling {} vectors: active={}, namespace={}", tableNames.size(), active, namespace);

        List<String> vectorIds = tableNames.stream()
            .map(t -> GatewayClientService.generateTableVectorId(databaseName, schemaName, t))
            .collect(java.util.stream.Collectors.toList());

        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("active", String.valueOf(active));

        int updated = vectorStoreRegistry.getActiveProvider()
            .batchUpdateMetadata(vectorIds, metadata, namespace);

        return DataResult.of(updated);
    }

    /**
     * Convert BigQuery sharded table name to wildcard format.
     * e.g. events_20260317 → events_*
     *      pseudonymous_users_20260317 → pseudonymous_users_*
     */
    static String toBigQueryWildcard(String tableName) {
        if (tableName != null && tableName.matches(".*_\\d{8}$")) {
            return tableName.replaceAll("_\\d{8}$", "_*");
        }
        return tableName;
    }

    private boolean isBigQueryDataSource(Long dataSourceId) {
        try {
            DataResult<ai.inquery.server.domain.api.model.DataSource> result = dataSourceService.queryById(dataSourceId);
            if (result.getSuccess() && result.getData() != null) {
                return "BIGQUERY".equalsIgnoreCase(result.getData().getType());
            }
        } catch (Exception e) {
            log.debug("Failed to get database type for dataSourceId: {}", dataSourceId, e);
        }
        return false;
    }

}
