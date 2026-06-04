package ai.inquery.server.domain.core.impl;


import ai.inquery.server.domain.core.catalog.BigQueryPredefinedCatalog;
import ai.inquery.server.domain.core.catalog.PredefinedTableMetadata;
import ai.inquery.server.domain.api.param.DataCatalogSaveParam;
import ai.inquery.server.domain.api.param.DataCatalogColumnSaveParam;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.domain.api.service.UserAIConfigService;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.model.ReferenceDocumentChunkHit;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.DatabaseService;
import ai.inquery.server.domain.api.service.ReferenceDocumentSearchService;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.spi.config.DriverConfig;
import ai.inquery.server.domain.api.param.datasource.DatabaseQueryAllParam;
import ai.inquery.server.domain.api.param.SchemaQueryParam;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.Database;
import ai.inquery.spi.model.Schema;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataCatalogTableDO;
import ai.inquery.server.domain.repository.entity.DataCatalogColumnDO;
import ai.inquery.server.domain.repository.entity.SchemaMetaCacheDO;
import ai.inquery.server.domain.repository.entity.TableMetaCacheDO;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.repository.mapper.ColumnMetaCacheMapper;
import ai.inquery.server.domain.repository.entity.TableLineageDO;
import ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper;
import ai.inquery.server.domain.repository.mapper.DataCatalogColumnMapper;
import ai.inquery.server.domain.repository.mapper.SchemaMetaCacheMapper;
import ai.inquery.server.domain.repository.mapper.TableLineageMapper;
import ai.inquery.server.domain.repository.mapper.TableMetaCacheMapper;
import ai.inquery.server.domain.repository.mapper.UserAIConfigMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.model.Context;
import ai.inquery.server.tools.common.util.ContextUtils;
import com.alibaba.fastjson2.JSON;
import ai.inquery.spi.model.Table;
import ai.inquery.spi.model.TableColumn;
import ai.inquery.spi.model.TableIndex;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.ConnectionPool;
import ai.inquery.spi.sql.SQLExecutor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Data catalog service implementation
 *
 * @since 2025-01-27
 */
@Slf4j
@Service
public class DataCatalogServiceImpl implements DataCatalogService {

    @Autowired
    @Lazy
    private TableService tableService;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private UserAIConfigService userAIConfigService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private DDLParserService ddlParserService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired(required = false)
    private ReferenceDocumentSearchService referenceDocumentSearchService;



    private DataCatalogTableMapper getTableMapper() {
        return Dbutils.getMapper(DataCatalogTableMapper.class);
    }

    private UserAIConfigMapper getUserAIConfigMapper() {
        return Dbutils.getMapper(UserAIConfigMapper.class);
    }

    private DataCatalogColumnMapper getColumnMapper() {
        return Dbutils.getMapper(DataCatalogColumnMapper.class);
    }

    private TableLineageMapper getTableLineageMapper() {
        return Dbutils.getMapper(TableLineageMapper.class);
    }

    @Override
    public DataResult<Map<String, Object>> queryCatalog(TableQueryParam param) {
        Map<String, Object> result = new HashMap<>();

        // Use catalogTableName for catalog storage if provided (for BigQuery sharded tables)
        // tableName is used for actual schema/column lookup from database
        String effectiveCatalogTableName = StringUtils.isNotBlank(param.getCatalogTableName()) 
            ? param.getCatalogTableName() 
            : param.getTableName();
        log.info("Query catalog - tableName: {}, catalogTableName: {}, effective: {}", 
            param.getTableName(), param.getCatalogTableName(), effectiveCatalogTableName);

        // Build catalog cache key using effective catalog table name
        String catalogCacheKey = String.format("%d:%s.%s.%s",
            param.getDataSourceId(),
            param.getDatabaseName(),
            param.getSchemaName() != null ? param.getSchemaName() : "PUBLIC",
            effectiveCatalogTableName);

        // Check catalog cache first (for metadata: description, exampleValues)
        String tableDescription = null;
        Map<String, DataCatalogColumnDO> columnMap = null;
        boolean cacheHit = false;

        CachedCatalogResult cached = catalogCache.get(catalogCacheKey);
        if (cached != null) {
            long age = System.currentTimeMillis() - cached.timestamp;
            if (age < CATALOG_CACHE_EXPIRY_MS) {
                tableDescription = cached.tableDescription;
                columnMap = cached.columnMap;
                cacheHit = true;
                log.info("Query catalog - Using cached metadata for table: {} (age: {}ms)", effectiveCatalogTableName, age);
            } else {
                catalogCache.remove(catalogCacheKey);
                log.info("Query catalog - Cache expired for table: {} (age: {}ms)", effectiveCatalogTableName, age);
            }
        }

        // If cache miss, query from database
        Long tableId = null;
        if (!cacheHit) {
            // Query table catalog using effective catalog table name
            LambdaQueryWrapper<DataCatalogTableDO> tableWrapper = new LambdaQueryWrapper<>();
            tableWrapper.eq(DataCatalogTableDO::getDataSourceId, param.getDataSourceId())
                    .eq(DataCatalogTableDO::getDatabaseName, param.getDatabaseName())
                    .eq(StringUtils.isNotBlank(param.getSchemaName()), DataCatalogTableDO::getSchemaName, param.getSchemaName())
                    .eq(DataCatalogTableDO::getTableName, effectiveCatalogTableName);
            DataCatalogTableDO tableDO = getTableMapper().selectOne(tableWrapper);

            if (tableDO != null) {
                tableDescription = tableDO.getTableDescription();
                tableId = tableDO.getId();
                log.info("Query catalog - Found table catalog (ID: {}), description length: {}",
                    tableId,
                    tableDescription != null ? tableDescription.length() : 0);

                // Query column catalog
                LambdaQueryWrapper<DataCatalogColumnDO> columnWrapper = new LambdaQueryWrapper<>();
                columnWrapper.eq(DataCatalogColumnDO::getTableId, tableId);
                List<DataCatalogColumnDO> columnDOList = getColumnMapper().selectList(columnWrapper);
                columnMap = columnDOList.stream()
                        .collect(Collectors.toMap(DataCatalogColumnDO::getColumnName, c -> c));

                // Cache the result
                catalogCache.put(catalogCacheKey, new CachedCatalogResult(tableDescription, columnMap, System.currentTimeMillis()));
            } else {
                log.info("Query catalog - No table catalog found for table: {}", param.getTableName());
                columnMap = new HashMap<>();
                // Read-time fallback: apply BigQuery predefined metadata (GA4, Firebase, etc.)
                // for well-known Google service tables that have not been auto-saved yet
                // (e.g. datasets added after the initial connection warmup). This keeps the
                // catalog self-healing without requiring a reconnect.
                ConnectInfo ciForPredef = InqueryContext.getConnectInfo();
                String dbTypeForPredef = ciForPredef != null ? ciForPredef.getDbType() : null;
                if ("BIGQUERY".equalsIgnoreCase(dbTypeForPredef)) {
                    String service = BigQueryPredefinedCatalog.detectService(param.getSchemaName(), param.getTableName());
                    PredefinedTableMetadata predefined = service != null
                        ? BigQueryPredefinedCatalog.getMetadata(service, param.getTableName())
                        : null;
                    if (predefined != null) {
                        // Column schema from predefined catalog; table description is AI-generated on Collect.
                        tableDescription = null;
                        int ordinal = 1;
                        for (PredefinedTableMetadata.PredefinedColumnMetadata col : predefined.getColumns()) {
                            DataCatalogColumnDO colDO = new DataCatalogColumnDO();
                            colDO.setColumnName(col.getColumnName());
                            colDO.setColumnDescription(col.getDescription());
                            colDO.setOrdinalPosition(ordinal++);
                            columnMap.put(col.getColumnName(), colDO);
                        }
                        log.info("Query catalog - Applied predefined metadata fallback for {}.{}.{} (service: {}, {} columns)",
                            param.getDatabaseName(), param.getSchemaName(), effectiveCatalogTableName, service, columnMap.size());
                    }
                }
                // Cache the result (predefined fallback if matched, otherwise empty) to avoid repeated DB queries
                catalogCache.put(catalogCacheKey, new CachedCatalogResult(tableDescription, columnMap, System.currentTimeMillis()));
            }
        }
        
        // Try to get columns from batchLoadColumns cache first (much faster)
        // Cache key is now database-level: "dataSourceId:database"
        List<TableColumn> dbColumns = null;
        String databaseCacheKey = String.format("%d:%s", 
            param.getDataSourceId(), 
            param.getDatabaseName());
        String tableKey = String.format("%s.%s.%s", 
            param.getDatabaseName(), 
            param.getSchemaName() != null ? param.getSchemaName() : "PUBLIC",
            param.getTableName());
        
        CachedColumnsResult cachedColumns = columnsCache.get(databaseCacheKey);
        if (cachedColumns != null) {
            long age = System.currentTimeMillis() - cachedColumns.timestamp;
            if (age < COLUMNS_CACHE_EXPIRY_MS) {
                // Cache is valid, get columns for this table
                List<TableColumn> cachedCols = cachedColumns.columnsMap.get(tableKey);
                if (cachedCols != null && !cachedCols.isEmpty()) {
                    dbColumns = cachedCols;
                    log.info("Query catalog - Using cached columns for table: {} (age: {}ms, {} columns)",
                        param.getTableName(), age, dbColumns.size());
                }
            }
        }
        
        // If not in cache, try to load from batchLoadColumns (synchronously)
        if (dbColumns == null) {
            log.info("Query catalog - Cache miss for table: {}, loading via batchLoadColumns", param.getTableName());
            try {
                TableQueryParam batchParam = new TableQueryParam();
                batchParam.setDataSourceId(param.getDataSourceId());
                batchParam.setDatabaseName(param.getDatabaseName());
                // schemaName is not needed - batchLoadColumns loads all schemas for the database
                batchParam.setSchemaName(null);
                batchParam.setRefresh(false);
                
                // Call batchLoadColumns synchronously to populate cache and get columns
                DataResult<Map<String, List<TableColumn>>> batchResult = batchLoadColumns(batchParam);
                // Check if batchLoadColumns succeeded (has data and no error)
                if (batchResult != null && Boolean.TRUE.equals(batchResult.success()) && batchResult.getData() != null) {
                    // Get columns for this table from the batch result
                    List<TableColumn> batchColumns = batchResult.getData().get(tableKey);
                    if (batchColumns != null && !batchColumns.isEmpty()) {
                        dbColumns = batchColumns;
                        log.info("Query catalog - Loaded {} columns via batchLoadColumns for table: {}", 
                            dbColumns.size(), param.getTableName());
                    } else {
                        // Table not found in batch result, skip to fallback
                        log.warn("Table {} not found in batchLoadColumns result for database: {}, falling back to direct query", tableKey, databaseCacheKey);
                    }
                } else {
                    // batchLoadColumns failed or returned error, skip to fallback
                    log.warn("batchLoadColumns failed or returned error for database: {} (success: {}, hasData: {}), falling back to direct query", 
                        databaseCacheKey, batchResult != null ? batchResult.success() : null, batchResult != null && batchResult.getData() != null);
                }
            } catch (Exception e) {
                log.warn("Failed to load columns via batchLoadColumns for database: {}, falling back to direct query", databaseCacheKey, e);
            }
            
            // Fallback: if batchLoadColumns failed or didn't return columns, query directly
            if (dbColumns == null) {
                dbColumns = tableService.queryColumns(param);
                log.info("Query catalog for table: {}, found {} columns (queried directly from database)", 
                    param.getTableName(), dbColumns != null ? dbColumns.size() : 0);
            }
        }
        
        // For BigQuery, flatten nested columns (STRUCT/RECORD)
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        String dbType = connectInfo != null ? connectInfo.getDbType() : null;
        if ("BIGQUERY".equalsIgnoreCase(dbType) && dbColumns != null) {
            log.info("Query catalog - BigQuery detected, flattening nested columns. Original count: {}", dbColumns.size());
            dbColumns = flattenColumns(dbColumns);
            log.info("Query catalog - Flattened column count: {}", dbColumns.size());
        }
        
        // Build column result using cached columnMap (from metadata cache or DB query above)
        List<Map<String, Object>> columns = new ArrayList<>();
        if (dbColumns == null) {
            dbColumns = new ArrayList<>();
        }
        // Use the live schema order from queryColumns (now DDL/ordinal order) as the single
        // source of truth for column ordering. The saved/predefined ordinalPosition can differ
        // from the real DDL order, so we derive ordinalPosition from the dbColumns iteration
        // order instead. This keeps the catalog consistent with the workspace schema/columns view.
        int catalogOrdinal = 1;
        for (TableColumn dbColumn : dbColumns) {
            Map<String, Object> columnInfo = new HashMap<>();
            columnInfo.put("name", dbColumn.getName());
            columnInfo.put("columnType", dbColumn.getColumnType());
            columnInfo.put("nullable", dbColumn.getNullable());
            columnInfo.put("primaryKey", dbColumn.getPrimaryKey());
            columnInfo.put("defaultValue", dbColumn.getDefaultValue());
            columnInfo.put("comment", dbColumn.getComment());

            // Get catalog info from cached columnMap (description / examples only)
            DataCatalogColumnDO columnDO = columnMap != null ? columnMap.get(dbColumn.getName()) : null;
            if (columnDO != null) {
                columnInfo.put("description", columnDO.getColumnDescription());
                columnInfo.put("schemaInfo", columnDO.getSchemaInfo());
                columnInfo.put("exampleValues", columnDO.getExampleValues());
            } else {
                columnInfo.put("description", "");
                columnInfo.put("schemaInfo", "");
                columnInfo.put("exampleValues", "");
            }
            columnInfo.put("ordinalPosition", catalogOrdinal++);
            columns.add(columnInfo);
        }
        
        result.put("tableDescription", tableDescription);
        result.put("columns", columns);
        log.info("Query catalog result - tableDescription length: {}, columns count: {}", 
            tableDescription != null ? tableDescription.length() : 0, 
            columns.size());
        return DataResult.of(result);
    }

    @Override
    public ActionResult saveCatalog(DataCatalogSaveParam param) {
        Long userId = ContextUtils.getUserId();

        // Invalidate catalog cache for this table
        String catalogCacheKey = String.format("%d:%s.%s.%s",
            param.getDataSourceId(),
            param.getDatabaseName(),
            param.getSchemaName() != null ? param.getSchemaName() : "PUBLIC",
            param.getTableName());
        catalogCache.remove(catalogCacheKey);
        log.info("Invalidated catalog cache for table: {}", catalogCacheKey);

        // Save or update table catalog
        LambdaQueryWrapper<DataCatalogTableDO> tableWrapper = new LambdaQueryWrapper<>();
        tableWrapper.eq(DataCatalogTableDO::getDataSourceId, param.getDataSourceId())
                .eq(DataCatalogTableDO::getDatabaseName, param.getDatabaseName())
                .eq(StringUtils.isNotBlank(param.getSchemaName()), DataCatalogTableDO::getSchemaName, param.getSchemaName())
                .eq(DataCatalogTableDO::getTableName, param.getTableName());
        DataCatalogTableDO tableDO = getTableMapper().selectOne(tableWrapper);
        
        if (tableDO == null) {
            tableDO = new DataCatalogTableDO();
            tableDO.setDataSourceId(param.getDataSourceId());
            tableDO.setDatabaseName(param.getDatabaseName());
            tableDO.setSchemaName(param.getSchemaName());
            tableDO.setTableName(param.getTableName());
            // Set description BEFORE insert to ensure it's included in INSERT statement
            tableDO.setTableDescription(param.getTableDescription() != null ? param.getTableDescription() : "");
            tableDO.setUserId(userId);
            log.info("Inserting new table catalog with description length: {}", tableDO.getTableDescription() != null ? tableDO.getTableDescription().length() : 0);
            try {
                getTableMapper().insert(tableDO);
                log.info("Table catalog inserted successfully, ID: {}", tableDO.getId());
            } catch (DuplicateKeyException e) {
                // Handle race condition: another request inserted the same table
                log.debug("Duplicate key detected for table '{}', falling back to update", param.getTableName());
                tableDO = getTableMapper().selectOne(tableWrapper);
            }
            // If description was empty, update it after insert (like columns do)
            if (param.getTableDescription() != null && !param.getTableDescription().isEmpty()) {
                tableDO.setTableDescription(param.getTableDescription());
                getTableMapper().updateById(tableDO);
                log.info("Table catalog description updated successfully");
            }
        } else {
            log.info("Updating existing table catalog (ID: {}) with description length: {}", tableDO.getId(), param.getTableDescription() != null ? param.getTableDescription().length() : 0);
            tableDO.setTableDescription(param.getTableDescription());
            getTableMapper().updateById(tableDO);
            log.info("Table catalog updated successfully");
        }
        
        // Save or update column catalog
        if (CollectionUtils.isNotEmpty(param.getColumns())) {
            // Fetch all existing columns for this table in one query
            LambdaQueryWrapper<DataCatalogColumnDO> allColumnsWrapper = new LambdaQueryWrapper<>();
            allColumnsWrapper.eq(DataCatalogColumnDO::getTableId, tableDO.getId());
            List<DataCatalogColumnDO> existingColumns = getColumnMapper().selectList(allColumnsWrapper);
            Map<String, DataCatalogColumnDO> existingColumnMap = existingColumns.stream()
                    .collect(java.util.stream.Collectors.toMap(DataCatalogColumnDO::getColumnName, c -> c, (a, b) -> a));

            for (DataCatalogColumnSaveParam columnParam : param.getColumns()) {
                DataCatalogColumnDO columnDO = existingColumnMap.get(columnParam.getColumnName());

                if (columnDO == null) {
                    columnDO = new DataCatalogColumnDO();
                    columnDO.setTableId(tableDO.getId());
                    columnDO.setColumnName(columnParam.getColumnName());
                    try {
                        getColumnMapper().insert(columnDO);
                    } catch (DuplicateKeyException e) {
                        log.debug("Duplicate key detected for column '{}', falling back to update", columnParam.getColumnName());
                        LambdaQueryWrapper<DataCatalogColumnDO> columnWrapper = new LambdaQueryWrapper<>();
                        columnWrapper.eq(DataCatalogColumnDO::getTableId, tableDO.getId())
                                .eq(DataCatalogColumnDO::getColumnName, columnParam.getColumnName());
                        columnDO = getColumnMapper().selectOne(columnWrapper);
                    }
                }

                columnDO.setColumnDescription(columnParam.getColumnDescription());
                columnDO.setSchemaInfo(columnParam.getSchemaInfo());
                columnDO.setExampleValues(columnParam.getExampleValues());
                columnDO.setOrdinalPosition(columnParam.getOrdinalPosition());
                getColumnMapper().updateById(columnDO);
            }
        }
        
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Map<String, Object>> collectExampleValues(TableQueryParam param) {
        Map<String, List<String>> result = new HashMap<>();
        Map<String, String> columnDescriptions = new HashMap<>();
        
        try {
            Connection connection = InqueryContext.getConnection();
            if (connection == null) {
                return DataResult.of(wrapExampleValuesResult(result, columnDescriptions));
            }
            
            // Get table columns
            List<TableColumn> columns = tableService.queryColumns(param);
            if (CollectionUtils.isEmpty(columns)) {
                return DataResult.of(wrapExampleValuesResult(result, columnDescriptions));
            }
            
            // Get database type from connection context
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            String dbType = connectInfo != null ? connectInfo.getDbType() : null;
            log.info("collectExampleValues - dbType={}, databaseName={}, schemaName={}, tableName={}", 
                    dbType, param.getDatabaseName(), param.getSchemaName(), param.getTableName());
            
            // BigQuery: Use TO_JSON_STRING approach for better handling of nested structures
            if ("BIGQUERY".equalsIgnoreCase(dbType)) {
                String qualifiedTableName = buildQualifiedTableName(dbType, param.getDatabaseName(), param.getSchemaName(), param.getTableName());
                
                // Flatten columns for BigQuery to handle nested STRUCT/ARRAY types
                List<TableColumn> flattenedColumns = flattenColumns(columns);
                log.info("BigQuery: Flattened {} columns to {} columns", columns.size(), flattenedColumns.size());
                
                // Build TableMetadata to use collectBigQuerySampleJson
                TableMetadata tableMetadata = new TableMetadata(param.getTableName());
                for (TableColumn column : flattenedColumns) {
                    ColumnMetadata colMetadata = new ColumnMetadata(column.getName(), column.getColumnType());
                    tableMetadata.getColumns().add(colMetadata);
                }
                
                // Collect sample data via JSON approach
                collectBigQuerySampleJson(connection, qualifiedTableName, tableMetadata);
                
                // Convert to result map (include per-event description when collectBigQueryEventNames ran)
                for (ColumnMetadata col : tableMetadata.getColumns()) {
                    if (col.getExampleValues() != null && !col.getExampleValues().isEmpty()) {
                        result.put(col.getColumnName(), col.getExampleValues());
                    }
                    if (col.getDescription() != null && !col.getDescription().isEmpty()) {
                        columnDescriptions.put(col.getColumnName(), col.getDescription());
                    }
                }
                
                log.info("BigQuery: Collected example values for {} columns via JSON approach", result.size());
                return DataResult.of(wrapExampleValuesResult(result, columnDescriptions));
            }
            
            // Non-BigQuery databases: Use per-column queries
            // Get table indexes to identify primary keys
            List<TableIndex> indexes = tableService.queryIndexes(param);
            Set<String> primaryKeyColumns = new HashSet<>();
            Set<String> uniqueKeyColumns = new HashSet<>();
            for (TableIndex index : indexes) {
                if ("Primary".equalsIgnoreCase(index.getType()) && CollectionUtils.isNotEmpty(index.getColumnList())) {
                    index.getColumnList().forEach(col -> primaryKeyColumns.add(col.getColumnName()));
                }
                if (Boolean.TRUE.equals(index.getUnique()) && CollectionUtils.isNotEmpty(index.getColumnList())) {
                    index.getColumnList().forEach(col -> uniqueKeyColumns.add(col.getColumnName()));
                }
            }
            
            log.info("collectExampleValues (non-BigQuery) - using per-column queries");
            
            // Build table name with schema if needed
            String qualifiedTableName = buildQualifiedTableName(dbType, param.getDatabaseName(), param.getSchemaName(), param.getTableName());
            
            // Find date column for filtering (priority: type-based first)
            String dateColumnName = findDateColumnForFilter(dbType, columns);
            if (dateColumnName != null) {
                log.info("collectExampleValues - found date column for filter: {}", dateColumnName);
            } else {
                log.info("collectExampleValues - no date column found, will use SAMPLE only");
            }
            
            // Build parent column types mapping for BigQuery nested columns
            Map<String, String> parentColumnTypes = new HashMap<>();
            for (TableColumn col : columns) {
                if (col.getChildren() != null && !col.getChildren().isEmpty()) {
                    parentColumnTypes.put(col.getName(), col.getColumnType());
                }
            }
            
            // Collect example values for each column in parallel
            List<CompletableFuture<Map.Entry<String, List<String>>>> futures = new ArrayList<>();
            
            for (TableColumn column : columns) {
                String columnName = column.getName();
                final String sql = buildSampleQuery(dbType, qualifiedTableName, columnName, column, primaryKeyColumns, uniqueKeyColumns, dateColumnName, parentColumnTypes);
                
                CompletableFuture<Map.Entry<String, List<String>>> future = CompletableFuture.supplyAsync(() -> {
                    List<String> exampleValues = new ArrayList<>();
                    
                    try {
                        if (sql != null) {
                            log.debug("Collecting example values for column: {}", columnName);
                            log.debug("SQL to execute: {}", sql);
                            SQLExecutor.getInstance().execute(connection, sql, rs -> {
                                try {
                                    int count = 0;
                                    while (rs.next()) {
                                        Object value = rs.getObject(1);
                                        if (value != null) {
                                            String strValue = value.toString();
                                            if (!exampleValues.contains(strValue)) {
                                                exampleValues.add(strValue);
                                                log.debug("Column {}: found value: {}", columnName, strValue);
                                            }
                                        }
                                        count++;
                                        // Limit to prevent too many values (increased to 20 for better examples)
                                        if (exampleValues.size() >= 20) {
                                            break;
                                        }
                                    }
                                    log.info("Column {}: SQL executed, found {} rows, {} unique values", columnName, count, exampleValues.size());
                                    if (count == 0) {
                                        log.warn("Column {}: SQL returned no rows. SQL was: {}", columnName, sql);
                                    }
                                } catch (SQLException e) {
                                    log.error("Error reading result set for column: " + columnName + ", SQL: " + sql, e);
                                }
                            });
                        } else {
                            log.warn("Column {}: buildSampleQuery returned null", columnName);
                        }
                    } catch (Exception e) {
                        log.error("Error collecting example values for column: " + columnName + ", SQL: " + 
                                 (sql != null ? sql : "null"), e);
                    }
                    
                    log.info("Final result for column {}: {} values", columnName, exampleValues.size());
                    return new AbstractMap.SimpleEntry<>(columnName, exampleValues);
                });
                
                futures.add(future);
            }
            
            // Wait for all futures to complete and collect results
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            for (CompletableFuture<Map.Entry<String, List<String>>> future : futures) {
                try {
                    Map.Entry<String, List<String>> entry = future.get();
                    result.put(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    log.error("Error getting result from future", e);
                }
            }
        } catch (Exception e) {
            log.error("Error collecting example values", e);
        }
        
        return DataResult.of(wrapExampleValuesResult(result, columnDescriptions));
    }

    private Map<String, Object> wrapExampleValuesResult(Map<String, List<String>> exampleValues,
                                                        Map<String, String> columnDescriptions) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("exampleValues", exampleValues);
        if (columnDescriptions != null && !columnDescriptions.isEmpty()) {
            payload.put("columnDescriptions", columnDescriptions);
        }
        return payload;
    }
    
    /**
     * Build a semantic-search query for uploaded reference documents during catalog Collect.
     */
    private String buildReferenceDocumentSearchQuery(TableQueryParam param) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(param.getCatalogTableName())) {
            sb.append(param.getCatalogTableName().trim());
        }
        if (StringUtils.isNotBlank(param.getTableName())
                && !StringUtils.equalsIgnoreCase(param.getTableName(), param.getCatalogTableName())) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(param.getTableName().trim());
        }
        if (StringUtils.isNotBlank(param.getSchemaName())) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(param.getSchemaName().trim());
        }
        return sb.length() > 0 ? sb.toString() : "table metadata";
    }

    /**
     * Build lineage text for AI collection prompt (same format as vector DB embedding).
     */
    private String buildLineageText(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        try {
            TableLineageMapper lineageMapper = getTableLineageMapper();

            LambdaQueryWrapper<TableLineageDO> upstreamQuery = new LambdaQueryWrapper<>();
            upstreamQuery.eq(TableLineageDO::getDataSourceId, dataSourceId)
                    .eq(TableLineageDO::getTableName, tableName);
            if (databaseName != null) upstreamQuery.eq(TableLineageDO::getDatabaseName, databaseName);
            if (schemaName != null) upstreamQuery.eq(TableLineageDO::getSchemaName, schemaName);

            TableLineageDO lineage = lineageMapper.selectOne(upstreamQuery);

            LambdaQueryWrapper<TableLineageDO> downstreamQuery = new LambdaQueryWrapper<>();
            downstreamQuery.eq(TableLineageDO::getDataSourceId, dataSourceId)
                    .like(TableLineageDO::getSourceTables, tableName);
            if (databaseName != null) downstreamQuery.eq(TableLineageDO::getDatabaseName, databaseName);
            if (schemaName != null) downstreamQuery.eq(TableLineageDO::getSchemaName, schemaName);

            List<TableLineageDO> downstreamList = lineageMapper.selectList(downstreamQuery);

            boolean hasUpstream = lineage != null && StringUtils.isNotBlank(lineage.getSourceTables());
            boolean hasDownstream = downstreamList != null && !downstreamList.isEmpty();
            boolean hasSourceQuery = lineage != null && StringUtils.isNotBlank(lineage.getSourceQuery());

            if (!hasUpstream && !hasDownstream && !hasSourceQuery) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[Lineage]\n");

            if (hasUpstream && lineage != null) {
                sb.append("Upstream (source tables): ").append(lineage.getSourceTables()).append("\n");
            }

            if (hasDownstream && downstreamList != null) {
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
                        .collect(Collectors.toList());
                sb.append("Downstream (used by): ").append(String.join(", ", downstreamNames)).append("\n");
            }

            if (hasSourceQuery && lineage != null) {
                sb.append("Source Query:\n").append(lineage.getSourceQuery()).append("\n");
            }

            log.info("Lineage text built for AI collection: {} (upstream={}, downstream={}, sourceQuery={})",
                    tableName, hasUpstream, hasDownstream, hasSourceQuery);
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build lineage text for table {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private String buildQualifiedTableName(String dbType, String databaseName, String schemaName, String tableName) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(databaseName)) {
            sb.append(escapeIdentifier(dbType, databaseName)).append(".");
        }
        if (StringUtils.isNotBlank(schemaName)) {
            sb.append(escapeIdentifier(dbType, schemaName)).append(".");
        }
        sb.append(escapeIdentifier(dbType, tableName));
        String qualifiedName = sb.toString();
        log.info("buildQualifiedTableName: dbType={}, databaseName={}, schemaName={}, tableName={}, result={}", 
                dbType, databaseName, schemaName, tableName, qualifiedName);
        return qualifiedName;
    }
    
    /**
     * Find date column for filtering based on column type (priority: type first, then name pattern)
     * Priority order:
     * 1. DATE type columns (prefer 'dt' or 'date' in name)
     * 2. TIMESTAMP type columns (prefer 'created', 'updated', 'timestamp' in name)
     * 3. DATETIME type columns
     */
    private String findDateColumnForFilter(String dbType, List<TableColumn> columns) {
        if (CollectionUtils.isEmpty(columns)) {
            return null;
        }

        // Single-pass with priority scoring:
        // 4 = DATE + preferred name (dt, date)
        // 3 = DATE (any name)
        // 2 = TIMESTAMP/DATETIME + preferred name (timestamp, created, updated, ts)
        // 1 = TIMESTAMP/DATETIME (any name)
        String bestColumn = null;
        int bestScore = 0;

        for (TableColumn column : columns) {
            String columnType = column.getColumnType() != null ? column.getColumnType().toLowerCase() : "";
            String columnName = column.getName() != null ? column.getName().toLowerCase() : "";

            // Skip complex types (ARRAY, STRUCT, RECORD)
            if (columnType.contains("array") || columnType.contains("struct") || columnType.contains("record")) {
                continue;
            }

            int score = 0;
            if (columnType.equals("date")) {
                score = (columnName.equals("dt") || columnName.contains("date")) ? 4 : 3;
            } else if (columnType.contains("timestamp") || columnType.contains("datetime")) {
                boolean hasPreferredName = columnName.equals("timestamp") || columnName.contains("created")
                        || columnName.contains("updated") || columnName.equals("ts");
                score = hasPreferredName ? 2 : 1;
            }

            if (score > bestScore) {
                bestScore = score;
                bestColumn = column.getName();
                if (bestScore == 4) {
                    break; // Maximum priority, no need to continue
                }
            }
        }

        if (bestColumn != null) {
            log.debug("findDateColumnForFilter: found column '{}' with priority score {}", bestColumn, bestScore);
        }
        return bestColumn;
    }
    
    /**
     * Flatten nested columns (BigQuery STRUCT/RECORD) into a flat list.
     * Each nested field will have its name set to the full field path (e.g., "event_params.key").
     * Duplicates are automatically removed based on column name.
     * 
     * @param columns List of columns (may contain nested children)
     * @return Flattened list of all columns including nested fields (no duplicates)
     */
    private List<TableColumn> flattenColumns(List<TableColumn> columns) {
        List<TableColumn> flattened = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();  // Track seen column names to prevent duplicates
        for (TableColumn column : columns) {
            flattenColumnRecursive(column, null, flattened, seenNames);
        }
        log.info("flattenColumns: input {} columns, output {} unique columns", columns.size(), flattened.size());
        return flattened;
    }
    
    /**
     * Recursively flatten a column and its children.
     * Skips columns that have already been added (based on column name).
     * 
     * @param column Current column to process
     * @param parentPath Parent path prefix (null for root columns)
     * @param result List to add flattened columns to
     * @param seenNames Set of column names already added (for deduplication)
     */
    private void flattenColumnRecursive(TableColumn column, String parentPath, List<TableColumn> result, Set<String> seenNames) {
        // Build full path for this column
        String fullPath = parentPath != null ? parentPath + "." + column.getName() : column.getName();
        
        // Use fieldPath if available, otherwise use computed fullPath
        String columnPath = column.getFieldPath() != null ? column.getFieldPath() : fullPath;
        
        // Skip if this column name was already added (prevents duplicates)
        if (seenNames.contains(columnPath)) {
            log.debug("flattenColumnRecursive: skipping duplicate column: {}", columnPath);
            return;
        }
        seenNames.add(columnPath);
        
        // Create a copy with the full path as name for AI processing
        TableColumn flatColumn = new TableColumn();
        flatColumn.setName(columnPath);
        flatColumn.setTableName(column.getTableName());
        flatColumn.setSchemaName(column.getSchemaName());
        flatColumn.setDatabaseName(column.getDatabaseName());
        flatColumn.setColumnType(column.getColumnType());
        flatColumn.setComment(column.getComment());
        flatColumn.setNullable(column.getNullable());
        flatColumn.setOrdinalPosition(column.getOrdinalPosition());
        flatColumn.setFieldPath(columnPath);
        flatColumn.setParentColumnName(parentPath);
        
        result.add(flatColumn);
        
        // Recursively process children
        if (column.getChildren() != null && !column.getChildren().isEmpty()) {
            for (TableColumn child : column.getChildren()) {
                flattenColumnRecursive(child, columnPath, result, seenNames);
            }
        }
    }
    
    private String escapeIdentifier(String dbType, String identifier) {
        if (identifier == null) {
            return null;
        }
        
        // Remove existing quotes (both backticks and double quotes) to prevent double-escaping
        String cleaned = identifier.trim();
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                // Remove existing quotes
                cleaned = cleaned.substring(1, cleaned.length() - 1);
                log.debug("escapeIdentifier: removed existing quotes from identifier, cleaned={}", cleaned);
            }
        }
        
        // Use database type to determine quoting style
        log.info("escapeIdentifier: original={}, cleaned={}, dbType={}", identifier, cleaned, dbType);
        
        if ("SNOWFLAKE".equalsIgnoreCase(dbType)) {
            // Snowflake uses double quotes
            String escaped = "\"" + cleaned + "\"";
            log.info("escapeIdentifier: Snowflake escaping, result={}", escaped);
            return escaped;
        } else {
            // MySQL, PostgreSQL, etc. use backticks (or default if dbType is null)
            String escaped = "`" + cleaned + "`";
            log.info("escapeIdentifier: Other DB escaping (dbType={}), result={}", dbType, escaped);
            return escaped;
        }
    }
    
    /**
     * Collect sample data from BigQuery table as JSON and extract example values per column.
     * Uses TO_JSON_STRING(ARRAY_AGG(t LIMIT N)) to get complete row samples
     * including all nested structures (ARRAY, STRUCT) in a single query.
     * 
     * For GA4 events tables, uses smart sampling to capture diverse event types
     * including e-commerce events (which contain items, ecommerce fields).
     * 
     * @param connection database connection
     * @param tableName qualified table name
     * @param tableMetadata TableMetadata to populate with example values
     */
    private void collectBigQuerySampleJson(Connection connection, String tableName, TableMetadata tableMetadata) {
        // Simple query: Get 10 sample rows as JSON
        String sql = String.format(
            "SELECT TO_JSON_STRING(ARRAY_AGG(t LIMIT 10), true) AS json_examples FROM %s AS t",
            tableName
        );
        
        log.info("BigQuery: Collecting sample JSON from table: {}", tableName);
        log.debug("BigQuery sample JSON query: {}", sql);
        
        StringBuilder result = new StringBuilder();
        try {
            SQLExecutor.getInstance().execute(connection, sql, rs -> {
                try {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        if (json != null) {
                            result.append(json);
                        }
                    }
                } catch (SQLException e) {
                    log.error("Error reading BigQuery sample JSON", e);
                }
            });
        } catch (Exception e) {
            log.error("Error executing BigQuery sample JSON query", e);
            return;
        }
        
        String jsonResult = result.toString();
        if (jsonResult.isEmpty()) {
            log.warn("No sample JSON data collected for table: {}", tableName);
            return;
        }
        
        // Store full JSON for prompt (truncated if needed)
        String sampleJsonForPrompt = jsonResult;
        if (sampleJsonForPrompt.length() > 30000) {
            sampleJsonForPrompt = sampleJsonForPrompt.substring(0, 30000) + "\n... (truncated)";
        }
        tableMetadata.setSampleJson(sampleJsonForPrompt);
        
        // Parse JSON and extract example values for each column
        try {
            extractExampleValuesFromJson(jsonResult, tableMetadata);
            log.info("Extracted example values from BigQuery sample JSON for {} columns", tableMetadata.getColumns().size());
        } catch (Exception e) {
            log.warn("Failed to parse BigQuery sample JSON for example values: {}", e.getMessage());
        }
        
        // Special handling for GA4 event_name column: collect all distinct event names from last 3 days
        collectBigQueryEventNames(connection, tableName, tableMetadata);
    }
    
    /**
     * Collect all distinct event_name values from BigQuery GA4 events table using wildcard query.
     * Only applies to tables with events_YYYYMMDD pattern.
     * 
     * @param connection database connection
     * @param tableName qualified table name (e.g., project.dataset.events_20260122)
     * @param tableMetadata TableMetadata to update event_name column
     */
    private void collectBigQueryEventNames(Connection connection, String tableName, TableMetadata tableMetadata) {
        // Check if event_name column exists
        ColumnMetadata eventNameColumn = tableMetadata.getColumns().stream()
            .filter(col -> "event_name".equalsIgnoreCase(col.getColumnName()))
            .findFirst()
            .orElse(null);
        
        if (eventNameColumn == null) {
            return; // No event_name column, skip
        }
        
        // Check if table name matches a GA4 events shard pattern, including the
        // intraday/fresh variants which share the same schema:
        //   events_YYYYMMDD, events_intraday_YYYYMMDD, events_fresh_YYYYMMDD
        // Remove backticks for pattern matching
        String cleanTableName = tableName.replace("`", "");
        if (!cleanTableName.matches(".*events(_intraday|_fresh)?_\\d{8}$")) {
            log.debug("Table {} does not match GA4 events shard pattern, skipping event_name collection", tableName);
            return;
        }
        
        // Query distinct event_name from recent shards (daily + intraday + fresh suffixes)
        String tableOnly = cleanTableName.substring(cleanTableName.lastIndexOf('.') + 1);
        String sql = BigQueryPredefinedCatalog.buildGa4EventNameDistinctSql(tableName, tableOnly);
        
        log.info("BigQuery: Collecting distinct event_name from recent shards for table: {}", tableOnly);
        log.debug("BigQuery event_name query: {}", sql);
        
        List<String> eventNames = new ArrayList<>();
        try {
            SQLExecutor.getInstance().execute(connection, sql, rs -> {
                try {
                    while (rs.next()) {
                        String eventName = rs.getString(1);
                        if (BigQueryPredefinedCatalog.isValidGa4EventName(eventName)) {
                            eventNames.add(eventName.trim());
                        }
                    }
                } catch (SQLException e) {
                    log.error("Error reading BigQuery event_name results", e);
                }
            });
            
            if (!eventNames.isEmpty()) {
                // Sort alphabetically for consistency
                eventNames.sort(String::compareTo);
                eventNameColumn.setExampleValues(eventNames);
                // Attach a per-event definition so the AI understands what each event value
                // means (known GA4 events get documented definitions, others are flagged custom).
                String eventDescription = BigQueryPredefinedCatalog.buildGa4EventNameDescription(eventNames);
                if (eventDescription != null && !eventDescription.isEmpty()) {
                    eventNameColumn.setDescription(eventDescription);
                }
                log.info("BigQuery: Collected {} distinct event_name values from last 3 days", eventNames.size());
            }
        } catch (Exception e) {
            log.warn("Failed to collect BigQuery event_name values: {}", e.getMessage());
        }
    }

    /** Run a single-column DISTINCT query and return non-empty string values. */
    private List<String> executeDistinctValueQuery(Connection connection, String sql) {
        List<String> values = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(30);
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String val = rs.getString(1);
                if (val != null && !val.isEmpty()) {
                    values.add(val);
                }
            }
        } catch (Exception e) {
            log.warn("Distinct value query failed: {}", e.getMessage());
        }
        return values;
    }
    
    /**
     * Parse JSON sample data and extract example values for each column in TableMetadata.
     * Handles nested structures (STRUCT, ARRAY<STRUCT>) by flattening paths.
     */
    private void extractExampleValuesFromJson(String jsonString, TableMetadata tableMetadata) {
        try {
            com.alibaba.fastjson2.JSONArray rows = com.alibaba.fastjson2.JSON.parseArray(jsonString);
            if (rows == null || rows.isEmpty()) {
                log.warn("extractExampleValuesFromJson: JSON array is null or empty");
                return;
            }
            
            log.info("extractExampleValuesFromJson: Processing {} rows", rows.size());
            
            // Build a map of column name -> example values
            Map<String, Set<String>> columnExamples = new HashMap<>();
            
            // Process each row
            for (int i = 0; i < rows.size(); i++) {
                com.alibaba.fastjson2.JSONObject row = rows.getJSONObject(i);
                if (row != null) {
                    extractValuesFromJsonObject(row, "", columnExamples);
                }
            }
            
            log.info("extractExampleValuesFromJson: Extracted values for {} unique paths from JSON", columnExamples.size());
            
            // Log sample of extracted paths for debugging
            if (log.isDebugEnabled()) {
                columnExamples.keySet().stream().limit(20).forEach(path -> 
                    log.debug("  JSON path: {} -> {} values", path, columnExamples.get(path).size()));
            }
            
            // Build a set of expected column names for quick lookup
            Set<String> expectedColumns = new HashSet<>();
            for (ColumnMetadata col : tableMetadata.getColumns()) {
                expectedColumns.add(col.getColumnName());
            }
            
            // Log sample of expected column names
            log.info("extractExampleValuesFromJson: Expecting {} columns", expectedColumns.size());
            if (log.isDebugEnabled()) {
                expectedColumns.stream().limit(20).forEach(name -> 
                    log.debug("  Expected column: {}", name));
            }
            
            // Populate ColumnMetadata with extracted example values
            int matchedCount = 0;
            for (ColumnMetadata col : tableMetadata.getColumns()) {
                Set<String> examples = columnExamples.get(col.getColumnName());
                if (examples != null && !examples.isEmpty()) {
                    // Limit to 5 examples per column
                    List<String> exampleList = examples.stream()
                        .limit(5)
                        .collect(java.util.stream.Collectors.toList());
                    col.setExampleValues(exampleList);
                    matchedCount++;
                }
            }
            
            log.info("extractExampleValuesFromJson: Matched {} columns with example values out of {} JSON paths and {} expected columns", 
                matchedCount, columnExamples.size(), expectedColumns.size());
            
            // Log unmatched paths for debugging (always at INFO level for troubleshooting)
            if (matchedCount < columnExamples.size() || matchedCount < expectedColumns.size()) {
                // Unmatched JSON paths (in JSON but not in expected columns)
                Set<String> unmatchedJsonPaths = new HashSet<>(columnExamples.keySet());
                unmatchedJsonPaths.removeAll(expectedColumns);
                if (!unmatchedJsonPaths.isEmpty()) {
                    log.info("extractExampleValuesFromJson: {} JSON paths NOT in expected columns (sample): {}", 
                        unmatchedJsonPaths.size(), 
                        unmatchedJsonPaths.stream().limit(20).collect(java.util.stream.Collectors.joining(", ")));
                }
                
                // Unmatched expected columns (in expected but not in JSON)
                Set<String> unmatchedColumns = new HashSet<>(expectedColumns);
                unmatchedColumns.removeAll(columnExamples.keySet());
                if (!unmatchedColumns.isEmpty()) {
                    log.info("extractExampleValuesFromJson: {} expected columns NOT in JSON paths (sample): {}", 
                        unmatchedColumns.size(), 
                        unmatchedColumns.stream().limit(20).collect(java.util.stream.Collectors.joining(", ")));
                }
            }
            
            // Log sample of JSON paths and expected columns for comparison
            log.info("extractExampleValuesFromJson: Sample JSON paths: {}", 
                columnExamples.keySet().stream().sorted().limit(30).collect(java.util.stream.Collectors.joining(", ")));
            log.info("extractExampleValuesFromJson: Sample expected columns: {}", 
                expectedColumns.stream().sorted().limit(30).collect(java.util.stream.Collectors.joining(", ")));
        } catch (Exception e) {
            log.warn("Error parsing JSON for example values: {}", e.getMessage());
        }
    }
    
    /**
     * Recursively extract values from JSON object, building flattened column paths.
     */
    private void extractValuesFromJsonObject(com.alibaba.fastjson2.JSONObject obj, String prefix, Map<String, Set<String>> columnExamples) {
        if (obj == null) return;
        
        for (String key : obj.keySet()) {
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = obj.get(key);
            
            if (value == null) {
                continue;
            } else if (value instanceof com.alibaba.fastjson2.JSONObject) {
                // Nested STRUCT - recurse
                extractValuesFromJsonObject((com.alibaba.fastjson2.JSONObject) value, fullPath, columnExamples);
            } else if (value instanceof com.alibaba.fastjson2.JSONArray) {
                // ARRAY - process first few elements
                com.alibaba.fastjson2.JSONArray arr = (com.alibaba.fastjson2.JSONArray) value;
                for (int i = 0; i < Math.min(arr.size(), 3); i++) {
                    Object elem = arr.get(i);
                    if (elem instanceof com.alibaba.fastjson2.JSONObject) {
                        extractValuesFromJsonObject((com.alibaba.fastjson2.JSONObject) elem, fullPath, columnExamples);
                    } else if (elem != null) {
                        addExampleValue(columnExamples, fullPath, elem.toString());
                    }
                }
            } else {
                // Primitive value
                addExampleValue(columnExamples, fullPath, value.toString());
            }
        }
    }
    
    /**
     * Add example value to the map, limiting value length.
     */
    private void addExampleValue(Map<String, Set<String>> columnExamples, String columnName, String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return;
        }
        
        // Truncate long values
        if (value.length() > 100) {
            value = value.substring(0, 100) + "...";
        }
        
        columnExamples.computeIfAbsent(columnName, k -> new LinkedHashSet<>()).add(value);
    }
    
    /**
     * Build query for BigQuery nested columns.
     * - ARRAY<STRUCT> types: Use UNNEST query
     * - Simple STRUCT types: Use direct field access (table.struct_col.field)
     * 
     * @param tableName qualified table name
     * @param columnName nested column name (e.g., "event_params.key")
     * @param parentColumnType parent column's type (e.g., "ARRAY<STRUCT<...>>" or "STRUCT<...>")
     */
    private String buildBigQueryNestedColumnQuery(String tableName, String columnName, String parentColumnType) {
        // Split column name: "event_params.key" → ["event_params", "key"]
        String[] parts = columnName.split("\\.", 2);
        if (parts.length < 2) {
            log.warn("Invalid nested column name for BigQuery: {}", columnName);
            return null;
        }
        
        String parentColumn = parts[0];  // e.g., "event_params"
        String nestedPath = parts[1];    // e.g., "key" or "value.string_value"
        
        // Check if parent column is ARRAY type
        boolean isArrayType = parentColumnType != null && 
                              parentColumnType.toUpperCase().startsWith("ARRAY");
        
        log.debug("BigQuery nested column: columnName={}, parentColumn={}, parentColumnType={}, isArrayType={}", 
                  columnName, parentColumn, parentColumnType, isArrayType);
        
        String sourceTable = String.format("(SELECT * FROM %s LIMIT 1000)", tableName);
        String sql;
        
        if (isArrayType) {
            // ARRAY<STRUCT<...>>: Use UNNEST
            // SELECT DISTINCT n.key FROM (SELECT * FROM table LIMIT 1000), UNNEST(event_params) AS n WHERE n.key IS NOT NULL LIMIT 100
            String alias = "n";
            sql = String.format(
                "SELECT DISTINCT %s.%s FROM %s, UNNEST(%s) AS %s WHERE %s.%s IS NOT NULL LIMIT 100",
                alias, nestedPath,
                sourceTable,
                parentColumn, alias,
                alias, nestedPath
            );
            log.debug("BigQuery ARRAY nested column query (UNNEST): {}", sql);
        } else {
            // Simple STRUCT<...>: Direct field access
            // SELECT DISTINCT table.struct_col.field FROM (SELECT * FROM table LIMIT 1000) WHERE table.struct_col.field IS NOT NULL LIMIT 100
            sql = String.format(
                "SELECT DISTINCT %s FROM %s AS t WHERE t.%s IS NOT NULL LIMIT 100",
                columnName,
                sourceTable,
                columnName
            );
            log.debug("BigQuery STRUCT nested column query (direct access): {}", sql);
        }
        
        return sql;
    }
    
    // ---------------------------------------------------------------------
    // Partition-aware example sampling
    //
    // Tables whose rows describe heterogeneous events (e.g. a single
    // `events` table with columns `event_name`, `value`, `params`...) suffer
    // from a sampling problem: a flat per-column distinct sample mixes
    // values whose semantics differ across event types. The LLM then sees
    // `value: [29.99, 0, 1, null]` and cannot tell that 29.99 is a purchase
    // amount, 0 is a page-view dwell time, and 1 is a click counter.
    //
    // We detect a low-cardinality categorical column ("event_name",
    // "event_type", "type", "category", ...) and, when present, group
    // example values by that column. The grouping is fed to the AI
    // metadata prompt so generated descriptions can disambiguate columns
    // whose meaning shifts across event types. Applies to all DBs.
    // ---------------------------------------------------------------------
    private static final List<String> PARTITION_COLUMN_PATTERNS = Arrays.asList(
            "event_name", "event_type", "event",
            "action_type", "action",
            "type", "category", "kind", "topic"
    );
    private static final int PARTITION_MAX_CARDINALITY = 50;
    private static final int PARTITION_SAMPLES_PER_VALUE = 5;
    private static final int PARTITION_MAX_VALUES = 30;

    /**
     * Detect a low-cardinality event-like categorical column in the table.
     * Returns the column name if a candidate is found and its distinct
     * count is bounded by {@link #PARTITION_MAX_CARDINALITY}; {@code null}
     * otherwise. Pattern matching and cardinality threshold are uniform
     * across all DB types.
     */
    private String findPartitionColumn(String dbType, String qualifiedTableName,
                                       List<TableColumn> columns, Connection connection) {
        if (connection == null || columns == null || columns.isEmpty()) {
            return null;
        }
        // Skip nested column paths (BigQuery STRUCT/RECORD leaves) — a
        // partition column must be a top-level scalar.
        Map<String, TableColumn> topLevel = new LinkedHashMap<>();
        for (TableColumn col : columns) {
            if (col.getName() == null || col.getName().contains(".")) continue;
            topLevel.put(col.getName().toLowerCase(), col);
        }
        if (topLevel.isEmpty()) return null;

        for (String pattern : PARTITION_COLUMN_PATTERNS) {
            TableColumn candidate = topLevel.get(pattern);
            if (candidate == null) continue;

            String colType = candidate.getColumnType() != null ? candidate.getColumnType().toLowerCase() : "";
            // Only string-ish or enum-ish columns count as event-like.
            // Numeric metric columns named "type" etc. shouldn't trigger this.
            boolean looksCategorical = colType.contains("string") || colType.contains("varchar")
                    || colType.contains("char") || colType.contains("text")
                    || colType.contains("enum");
            if (!looksCategorical) {
                log.debug("Partition candidate {} rejected: non-categorical type {}", candidate.getName(), colType);
                continue;
            }

            int distinctCount = countDistinct(dbType, qualifiedTableName, candidate.getName(), connection);
            if (distinctCount > 0 && distinctCount <= PARTITION_MAX_CARDINALITY) {
                log.info("Partition column detected for {}: {} (distinct={})",
                        qualifiedTableName, candidate.getName(), distinctCount);
                return candidate.getName();
            }
            log.debug("Partition candidate {} rejected (distinct={} > {})",
                    candidate.getName(), distinctCount, PARTITION_MAX_CARDINALITY);
        }
        return null;
    }

    /**
     * Bounded distinct-count probe. Uses APPROX_COUNT_DISTINCT on BigQuery
     * to avoid scanning huge sharded tables; uses a derived-table LIMIT
     * trick elsewhere so the query short-circuits past
     * {@code PARTITION_MAX_CARDINALITY + 1}.
     */
    private int countDistinct(String dbType, String qualifiedTableName,
                              String columnName, Connection connection) {
        String escapedColumn = escapeIdentifier(dbType, columnName);
        String sql;
        if ("BIGQUERY".equalsIgnoreCase(dbType)) {
            sql = String.format("SELECT APPROX_COUNT_DISTINCT(%s) FROM %s WHERE %s IS NOT NULL",
                    escapedColumn, qualifiedTableName, escapedColumn);
        } else {
            sql = String.format(
                    "SELECT COUNT(*) FROM (SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT %d) sub",
                    escapedColumn, qualifiedTableName, escapedColumn, PARTITION_MAX_CARDINALITY + 1);
        }
        int[] result = new int[]{Integer.MAX_VALUE};
        try {
            SQLExecutor.getInstance().execute(connection, sql, rs -> {
                try {
                    if (rs.next()) result[0] = rs.getInt(1);
                } catch (SQLException e) {
                    log.debug("countDistinct read failed for {}.{}: {}", qualifiedTableName, columnName, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("countDistinct query failed for {}.{}: {}", qualifiedTableName, columnName, e.getMessage());
        }
        return result[0];
    }

    /**
     * Build a partition-aware sample SQL: returns up to N samples per
     * partition value using {@code ROW_NUMBER() OVER (PARTITION BY ...)}.
     * Uses each DB's idiomatic random ordering so partitions with more
     * than N rows yield representative (not just lexically first) values.
     */
    private String buildPartitionedSampleQuery(String dbType, String tableName,
                                                String columnName, String partitionColumn) {
        String escCol = escapeIdentifier(dbType, columnName);
        String escPart = escapeIdentifier(dbType, partitionColumn);
        String orderFn;
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "SNOWFLAKE".equalsIgnoreCase(dbType)) {
            orderFn = "RANDOM()";
        } else if ("ORACLE".equalsIgnoreCase(dbType)) {
            orderFn = "DBMS_RANDOM.VALUE";
        } else {
            orderFn = "RAND()";
        }
        return String.format(
                "SELECT part, val FROM (" +
                "  SELECT %s AS part, %s AS val, " +
                "         ROW_NUMBER() OVER (PARTITION BY %s ORDER BY %s) AS rn " +
                "  FROM %s " +
                "  WHERE %s IS NOT NULL" +
                ") sub WHERE rn <= %d",
                escPart, escCol, escPart, orderFn, tableName, escCol, PARTITION_SAMPLES_PER_VALUE);
    }

    /**
     * Execute partition-aware sample queries for each column in parallel.
     * Skips the partition column itself, nested-path columns, and
     * non-groupable types (BLOB/CLOB/BINARY).
     *
     * @return Map of column name -> (partition value -> sample list).
     *         Columns with no successful samples are omitted.
     */
    private Map<String, Map<String, List<String>>> collectPartitionedSamples(
            Connection connection, String dbType, String qualifiedTableName,
            List<TableColumn> columns, String partitionColumn) {
        Map<String, Map<String, List<String>>> result = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (TableColumn column : columns) {
            String columnName = column.getName();
            if (columnName == null || columnName.contains(".")) continue;
            if (columnName.equalsIgnoreCase(partitionColumn)) continue;

            String ct = column.getColumnType() != null ? column.getColumnType().toLowerCase() : "";
            if (ct.contains("blob") || ct.contains("clob") || ct.contains("binary") || ct.contains("long ")) {
                continue;
            }

            String sql = buildPartitionedSampleQuery(dbType, qualifiedTableName, columnName, partitionColumn);
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                Map<String, List<String>> perPartition = new LinkedHashMap<>();
                try {
                    SQLExecutor.getInstance().execute(connection, sql, rs -> {
                        try {
                            int count = 0;
                            int hardCap = PARTITION_MAX_VALUES * PARTITION_SAMPLES_PER_VALUE;
                            while (rs.next() && count < hardCap) {
                                Object partObj = rs.getObject(1);
                                Object val = rs.getObject(2);
                                if (partObj == null || val == null) continue;
                                String partKey = partObj.toString();
                                String strVal = val.toString();
                                if (strVal.length() > 100) strVal = strVal.substring(0, 100) + "...";
                                perPartition.computeIfAbsent(partKey, k -> new ArrayList<>()).add(strVal);
                                count++;
                            }
                        } catch (SQLException e) {
                            log.debug("Error reading partitioned samples for {}: {}", columnName, e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.warn("Partitioned sample query failed for column {}: {}", columnName, e.getMessage());
                }
                if (!perPartition.isEmpty()) {
                    if (perPartition.size() > PARTITION_MAX_VALUES) {
                        Map<String, List<String>> capped = new LinkedHashMap<>();
                        int taken = 0;
                        for (Map.Entry<String, List<String>> e : perPartition.entrySet()) {
                            if (taken >= PARTITION_MAX_VALUES) break;
                            capped.put(e.getKey(), e.getValue());
                            taken++;
                        }
                        result.put(columnName, capped);
                    } else {
                        result.put(columnName, perPartition);
                    }
                }
            });
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Partitioned sampling completed for {}: {} columns have grouped examples",
                qualifiedTableName, result.size());
        return result;
    }

    private String buildSampleQuery(String dbType, String tableName, String columnName, TableColumn column, 
                                     Set<String> primaryKeyColumns, Set<String> uniqueKeyColumns, String dateColumnName,
                                     Map<String, String> parentColumnTypes) {
        // For BigQuery nested columns (e.g., "event_params.key", "event_params.value.string_value")
        // Generate UNNEST query (for ARRAY) or direct access (for STRUCT) with sampled source table
        if ("BIGQUERY".equalsIgnoreCase(dbType) && columnName.contains(".")) {
            // Get ROOT parent column name (first segment) and its type
            // e.g., "event_params.value.float_value" -> root parent is "event_params"
            String rootParentColumn = columnName.split("\\.")[0];
            String parentColumnType = parentColumnTypes != null ? parentColumnTypes.get(rootParentColumn) : null;
            return buildBigQueryNestedColumnQuery(tableName, columnName, parentColumnType);
        }
        
        String escapedColumnName = escapeIdentifier(dbType, columnName);
        String columnType = column.getColumnType() != null ? column.getColumnType().toLowerCase() : "";
        String columnNameLower = columnName.toLowerCase();
        
        // Check if it's a key column (by name pattern - contains "key")
        boolean isKeyColumnByName = columnNameLower.contains("key");
        
        // Check if it's a key column (by database metadata - only primary keys)
        boolean isKeyColumnByMetadata = primaryKeyColumns.contains(columnName);
        
        // Check if it's a time/date related column (DATE, TIME, TIMESTAMP, DATETIME, etc.)
        boolean isTimeDateColumn = columnType.contains("date") || columnType.contains("time") || 
                                   columnType.contains("timestamp") || columnType.contains("datetime");
        
        // Check if it's a numeric type column (NUMBER, NUMERIC, DOUBLE, FLOAT, INT, INTEGER, DECIMAL, REAL, etc.)
        boolean isNumericColumn = columnType.contains("number") || columnType.contains("numeric") ||
                                 columnType.contains("double") || columnType.contains("float") ||
                                 columnType.contains("int") || columnType.contains("integer") ||
                                 columnType.contains("decimal") || columnType.contains("real") ||
                                 columnType.contains("bigint") || columnType.contains("smallint") ||
                                 columnType.contains("tinyint") || columnType.contains("bit");
        
        // Check if it's a non-groupable column (BLOB, CLOB, LONG, BINARY - but NOT TEXT)
        // TEXT can be grouped with DISTINCT, so we exclude it from isNonGroupable
        boolean isNonGroupable = columnType.contains("blob") || columnType.contains("clob") || 
                                columnType.contains("long") || columnType.contains("binary");
        
        // For TEXT columns, only use LIMIT 1 if it's a key column (by name or metadata)
        // Otherwise, use DISTINCT to get multiple examples
        boolean isTextColumn = columnType.contains("text") || columnType.contains("varchar") || 
                              columnType.contains("char") || columnType.contains("string");
        
        // Build the source table expression with TABLESAMPLE and optional date filter
        String sourceTable = buildSampledSourceTable(dbType, tableName, dateColumnName);
        
        String sql;
        // For TEXT columns that are NOT keys, always use DISTINCT
        if (isTextColumn && !isKeyColumnByName && !isKeyColumnByMetadata) {
            // Use DISTINCT for TEXT columns that are not keys to get multiple examples
            sql = String.format("SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT 100", 
                    escapedColumnName, sourceTable, escapedColumnName);
            log.debug("Using DISTINCT for TEXT column: {} (columnType={}, isKeyByName={}, isKeyByMetadata={})", 
                    columnName, columnType, isKeyColumnByName, isKeyColumnByMetadata);
        } else if (isKeyColumnByName || isKeyColumnByMetadata || isTimeDateColumn || isNumericColumn || isNonGroupable) {
            // Use LIMIT 1 for key columns, time/date columns, numeric columns, and non-groupable columns (BLOB/CLOB)
            // For numeric columns, exclude 0 values
            if (isNumericColumn && !isKeyColumnByName && !isKeyColumnByMetadata) {
                sql = String.format("SELECT %s FROM %s WHERE %s IS NOT NULL AND %s != 0 LIMIT 1", 
                        escapedColumnName, sourceTable, escapedColumnName, escapedColumnName);
            } else {
                sql = String.format("SELECT %s FROM %s WHERE %s IS NOT NULL LIMIT 1", 
                        escapedColumnName, sourceTable, escapedColumnName);
            }
            log.debug("Using LIMIT 1 for column: {} (isKeyByName={}, isKeyByMetadata={}, isTimeDate={}, isNumeric={}, isNonGroupable={})", 
                    columnName, isKeyColumnByName, isKeyColumnByMetadata, isTimeDateColumn, isNumericColumn, isNonGroupable);
        } else {
            // Use DISTINCT for other columns (STRING, VARCHAR, CHAR, TEXT, etc.) to get multiple examples
            sql = String.format("SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT 100", 
                    escapedColumnName, sourceTable, escapedColumnName);
            log.debug("Using DISTINCT for column: {} (columnType={})", columnName, columnType);
        }
        
        log.info("buildSampleQuery: tableName={}, columnName={}, escapedColumnName={}, columnType={}, final SQL={}", 
                tableName, columnName, escapedColumnName, columnType, sql);
        
        return sql;
    }
    
    /**
     * Build sampled source table expression with optional date filter
     * Uses subquery with date filter (last 7 days) + TABLESAMPLE for performance
     * 
     * @param dbType database type (SNOWFLAKE, MYSQL, BIGQUERY, etc.)
     * @param tableName qualified table name
     * @param dateColumnName date column name for filtering (nullable)
     * @return source table expression (either subquery or direct table with SAMPLE)
     */
    private String buildSampledSourceTable(String dbType, String tableName, String dateColumnName) {
        boolean isSnowflake = "SNOWFLAKE".equalsIgnoreCase(dbType);
        boolean isBigQuery = "BIGQUERY".equalsIgnoreCase(dbType);
        
        if (dateColumnName != null) {
            // Use subquery: filter by date first, then sample
            String escapedDateColumn = escapeIdentifier(dbType, dateColumnName);
            
            if (isSnowflake) {
                // Snowflake: (SELECT * FROM table WHERE date >= DATEADD(day, -7, CURRENT_DATE())) SAMPLE (5)
                return String.format("(SELECT * FROM %s WHERE %s >= DATEADD(day, -7, CURRENT_DATE())) SAMPLE (5)", 
                        tableName, escapedDateColumn);
            } else if (isBigQuery) {
                // BigQuery: (SELECT * FROM table WHERE date >= DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY)) 
                // BigQuery uses TABLESAMPLE SYSTEM (PERCENT n) but it's not supported in subqueries
                // So we just use LIMIT for sampling after date filter
                return String.format("(SELECT * FROM %s WHERE %s >= DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY) LIMIT 1000) AS sampled_data", 
                        tableName, escapedDateColumn);
            } else {
                // MySQL/PostgreSQL: (SELECT * FROM table WHERE date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)) as t
                return String.format("(SELECT * FROM %s WHERE %s >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)) AS sampled_data", 
                        tableName, escapedDateColumn);
            }
        } else {
            // No date column: use SAMPLE only
            if (isSnowflake) {
                // Snowflake: table SAMPLE (5)
                return tableName + " SAMPLE (5)";
            } else if (isBigQuery) {
                // BigQuery: TABLESAMPLE SYSTEM (PERCENT n) - sample 5% of data
                return tableName + " TABLESAMPLE SYSTEM (5 PERCENT)";
            } else {
                // MySQL/PostgreSQL: just use the table directly (no native TABLESAMPLE)
                return tableName;
            }
        }
    }

    @Override
    public DataResult<Map<String, Object>> collectAIMetadata(TableQueryParam param) {
        try {
            // For well-known BigQuery tables (GA4, Firebase, etc.), collect predefined column
            // schema descriptions and custom-value examples, but still run AI for the table-level
            // description — same as any other table.
            ConnectInfo preCheckConnectInfo = InqueryContext.getConnectInfo();
            String preCheckDbType = preCheckConnectInfo != null ? preCheckConnectInfo.getDbType() : null;
            PredefinedColumnEvidence predefinedEvidence = null;
            if ("BIGQUERY".equalsIgnoreCase(preCheckDbType)) {
                predefinedEvidence = collectPredefinedColumnEvidence(param);
            }

            // Get user AI config
            Long userId = ContextUtils.getUserId();
            LambdaQueryWrapper<UserAIConfigDO> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(UserAIConfigDO::getUserId, userId);
            UserAIConfigDO userConfig = getUserAIConfigMapper().selectOne(configWrapper);
            
            if (userConfig == null) {
                log.warn("User AI config not found for user: {}", userId);
                return DataResult.error("AI configuration is missing. Please register settings first.", "AI_CONFIG_NOT_FOUND");
            }

            // Check Gemini API key
            String geminiApiKey = null;
            try {
                Config apiKeyConfig = configService.find("gemini.apiKey").getData();
                if (apiKeyConfig != null && apiKeyConfig.getContent() != null && !apiKeyConfig.getContent().isEmpty()) {
                    geminiApiKey = apiKeyConfig.getContent();
                }
            } catch (Exception e) {
                log.warn("Failed to get Gemini API key: {}", e.getMessage());
            }
            
            if (geminiApiKey == null || geminiApiKey.isEmpty()) {
                return DataResult.error("AI key is missing. Register a Gemini API key in system settings.", "GEMINI_API_KEY_NOT_FOUND");
            }
            
            // Set Gemini model in userConfig if not set
            Config geminiModelConfig = configService.find("gemini.model").getData();
            if (geminiModelConfig != null && geminiModelConfig.getContent() != null && !geminiModelConfig.getContent().isEmpty()) {
                userConfig.setGeminiModel(geminiModelConfig.getContent());
            } else {
                userConfig.setGeminiModel(ModelMapper.getDefaultPrimaryModel()); // Default model
            }

            // Get table columns to build TableMetadata
            List<TableColumn> dbColumns = tableService.queryColumns(param);
            if (CollectionUtils.isEmpty(dbColumns)) {
                return DataResult.error("Table columns not found.", "COLUMNS_NOT_FOUND");
            }

            // Get database type for BigQuery nested column handling
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            String dbType = connectInfo != null ? connectInfo.getDbType() : null;
            
            // Build parent column name -> type mapping before flattening (for BigQuery ARRAY vs STRUCT detection)
            Map<String, String> parentColumnTypes = new HashMap<>();
            for (TableColumn col : dbColumns) {
                if (col.getChildren() != null && !col.getChildren().isEmpty()) {
                    parentColumnTypes.put(col.getName(), col.getColumnType());
                }
            }
            
            // For BigQuery, flatten nested columns (STRUCT/RECORD) for AI processing
            if ("BIGQUERY".equalsIgnoreCase(dbType)) {
                log.info("BigQuery detected - flattening nested columns for AI processing. Original count: {}", dbColumns.size());
                log.debug("Parent column types: {}", parentColumnTypes);
                dbColumns = flattenColumns(dbColumns);
                log.info("Flattened column count: {}", dbColumns.size());
            }

            // Build TableMetadata from table columns
            TableMetadata tableMetadata = new TableMetadata(param.getTableName());
            for (TableColumn column : dbColumns) {
                ColumnMetadata colMetadata = new ColumnMetadata(column.getName(), column.getColumnType());
                colMetadata.setNullable(column.getNullable() != null && column.getNullable() != 0);
                tableMetadata.getColumns().add(colMetadata);
            }

            // Collect example values for columns (for better AI metadata generation)
            try {
                Connection connection = InqueryContext.getConnection();
                if (connection != null) {
                    String qualifiedTableName = buildQualifiedTableName(dbType, param.getDatabaseName(), param.getSchemaName(), param.getTableName());

                    // Detect a low-cardinality event-like categorical column. When present,
                    // we additionally collect partition-grouped samples per column so the
                    // metadata prompt can disambiguate columns whose meaning shifts across
                    // event types (e.g. `value` is a price for `purchase` but a dwell time
                    // for `page_view`). Applies uniformly to all DB types.
                    final String partitionColumn = findPartitionColumn(dbType, qualifiedTableName, dbColumns, connection);

                    // BigQuery: Use TO_JSON_STRING approach for better handling of nested structures
                    if ("BIGQUERY".equalsIgnoreCase(dbType)) {
                        collectBigQuerySampleJson(connection, qualifiedTableName, tableMetadata);
                        log.info("Collected BigQuery sample data via JSON approach");
                    } else {
                        // Other databases: Use per-column queries
                        List<TableIndex> indexes = tableService.queryIndexes(param);
                        Set<String> primaryKeyColumns = new HashSet<>();
                        Set<String> uniqueKeyColumns = new HashSet<>();
                        for (TableIndex index : indexes) {
                            if ("Primary".equalsIgnoreCase(index.getType()) && CollectionUtils.isNotEmpty(index.getColumnList())) {
                                index.getColumnList().forEach(col -> primaryKeyColumns.add(col.getColumnName()));
                            }
                            if (Boolean.TRUE.equals(index.getUnique()) && CollectionUtils.isNotEmpty(index.getColumnList())) {
                                index.getColumnList().forEach(col -> uniqueKeyColumns.add(col.getColumnName()));
                            }
                        }

                        String dateColumnName = findDateColumnForFilter(dbType, dbColumns);

                        // Collect example values in parallel (limit to 5 values per column for AI prompt)
                        List<CompletableFuture<Map.Entry<String, List<String>>>> exampleFutures = new ArrayList<>();
                        final Map<String, String> finalParentColumnTypes = parentColumnTypes;
                        for (TableColumn column : dbColumns) {
                            String columnName = column.getName();
                            final String sql = buildSampleQuery(dbType, qualifiedTableName, columnName, column, primaryKeyColumns, uniqueKeyColumns, dateColumnName, finalParentColumnTypes);

                            CompletableFuture<Map.Entry<String, List<String>>> future = CompletableFuture.supplyAsync(() -> {
                                List<String> exampleValues = new ArrayList<>();
                                try {
                                    if (sql != null) {
                                        SQLExecutor.getInstance().execute(connection, sql, rs -> {
                                            try {
                                                while (rs.next() && exampleValues.size() < 5) {
                                                    Object value = rs.getObject(1);
                                                    if (value != null) {
                                                        String strValue = value.toString();
                                                        if (strValue.length() > 100) {
                                                            strValue = strValue.substring(0, 100) + "...";
                                                        }
                                                        if (!exampleValues.contains(strValue)) {
                                                            exampleValues.add(strValue);
                                                        }
                                                    }
                                                }
                                            } catch (SQLException e) {
                                                log.debug("Error reading example values for column: {}", columnName);
                                            }
                                        });
                                    }
                                } catch (Exception e) {
                                    log.debug("Error collecting example values for column: {}", columnName);
                                }
                                return new AbstractMap.SimpleEntry<>(columnName, exampleValues);
                            });
                            exampleFutures.add(future);
                        }

                        // Wait for all and populate ColumnMetadata
                        CompletableFuture.allOf(exampleFutures.toArray(new CompletableFuture[0])).join();
                        Map<String, List<String>> exampleValuesMap = new HashMap<>();
                        for (CompletableFuture<Map.Entry<String, List<String>>> future : exampleFutures) {
                            try {
                                Map.Entry<String, List<String>> entry = future.get();
                                exampleValuesMap.put(entry.getKey(), entry.getValue());
                            } catch (Exception e) {
                                log.debug("Error getting example values future result");
                            }
                        }

                        // Add example values to ColumnMetadata
                        for (ColumnMetadata colMeta : tableMetadata.getColumns()) {
                            List<String> examples = exampleValuesMap.get(colMeta.getColumnName());
                            if (examples != null && !examples.isEmpty()) {
                                colMeta.setExampleValues(examples);
                            }
                        }
                        log.info("Collected example values for {} columns", exampleValuesMap.size());
                    }

                    // Partition-aware example sampling (uniform across DB types).
                    // Runs only when a low-cardinality event-like column was detected,
                    // and only on top-level scalar columns (skips nested BigQuery paths
                    // and the partition column itself). Adds richer per-event samples
                    // alongside the flat exampleValues already populated above.
                    if (partitionColumn != null) {
                        try {
                            Map<String, Map<String, List<String>>> partitioned = collectPartitionedSamples(
                                    connection, dbType, qualifiedTableName, dbColumns, partitionColumn);
                            if (!partitioned.isEmpty()) {
                                for (ColumnMetadata colMeta : tableMetadata.getColumns()) {
                                    Map<String, List<String>> grouped = partitioned.get(colMeta.getColumnName());
                                    if (grouped != null && !grouped.isEmpty()) {
                                        colMeta.setPartitionedExampleValues(grouped);
                                    }
                                }
                                tableMetadata.setPartitionColumn(partitionColumn);
                                log.info("Attached partitioned example values keyed by `{}` to {} columns",
                                        partitionColumn, partitioned.size());
                            }
                        } catch (Exception e) {
                            log.warn("Partition-aware sampling failed for {} ({}); continuing without grouped examples: {}",
                                    qualifiedTableName, partitionColumn, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to collect example values for AI metadata, continuing without them: {}", e.getMessage());
            }

            // Merge predefined column examples (event_params.key, event_name, etc.) into the
            // prompt context before calling the LLM. Prefer richer predefined lists over JSON samples.
            if (predefinedEvidence != null) {
                mergePredefinedEvidenceIntoTableMetadata(tableMetadata, predefinedEvidence);
            }
            tableMetadata.setDataProfileSummary(buildDataProfileSummary(tableMetadata, predefinedEvidence, param));

            // Create search services with user config
            ConfluenceService confluenceService = createConfluenceService(userConfig);
            JiraService jiraService = createJiraService(userConfig);
            SlackService slackService = createSlackService(userConfig);
            GitHubService githubService = createGitHubService(userConfig);
            OutlookService outlookService = createOutlookService(userConfig);
            GoogleDriveService googleDriveService = createGoogleDriveService(userConfig);

            // Parallel search (CompletableFuture)
            CompletableFuture<List<WikiSearchResult>> wikiFuture = CompletableFuture.supplyAsync(() -> {
                if (userConfig.getConfluenceBaseUrl() != null && !userConfig.getConfluenceBaseUrl().isEmpty() &&
                    userConfig.getConfluenceApiToken() != null && !userConfig.getConfluenceApiToken().isEmpty()) {
                    return confluenceService.searchPages(param.getTableName(), 5);
                }
                return new ArrayList<>();
            });
            
            CompletableFuture<List<JiraSearchResult>> jiraFuture = CompletableFuture.supplyAsync(() -> {
                if (userConfig.getJiraBaseUrl() != null && !userConfig.getJiraBaseUrl().isEmpty() &&
                    userConfig.getJiraApiToken() != null && !userConfig.getJiraApiToken().isEmpty()) {
                    return jiraService.searchIssues(param.getTableName(), 5);
                }
                return new ArrayList<>();
            });
            
            CompletableFuture<List<SlackSearchResult>> slackFuture = CompletableFuture.supplyAsync(() -> {
                if (userConfig.getSlackUserToken() != null && !userConfig.getSlackUserToken().isEmpty()) {
                    return slackService.searchMessages(param.getTableName(), 5);
                }
                return new ArrayList<>();
            });
            
            CompletableFuture<List<GitHubSearchResult>> githubFuture = CompletableFuture.supplyAsync(() -> {
                if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                    return githubService.searchCode(param.getTableName(), 5);
                }
                return new ArrayList<>();
            });
            
            CompletableFuture<List<OutlookSearchResult>> outlookFuture = CompletableFuture.supplyAsync(() -> {
                boolean canRefresh = userConfig.getOutlookRefreshToken() != null && !userConfig.getOutlookRefreshToken().isEmpty()
                    && userConfig.getOutlookTenantId() != null && !userConfig.getOutlookTenantId().isEmpty()
                    && userConfig.getOutlookClientId() != null && !userConfig.getOutlookClientId().isEmpty();
                if (canRefresh) {
                    return outlookService.searchEmails(param.getTableName(), 5);
                }
                return new ArrayList<>();
            });

            CompletableFuture<List<GoogleDriveSearchResult>> googleDriveFuture = CompletableFuture.supplyAsync(() -> {
                if (googleDriveService.isConfigured()) {
                    return searchGoogleDriveForCollection(googleDriveService, param.getTableName(), 5);
                }
                return new ArrayList<>();
            });

            Long collectUserId = ContextUtils.getUserId();
            String referenceDocQuery = buildReferenceDocumentSearchQuery(param);
            CompletableFuture<List<ReferenceDocumentChunkHit>> referenceDocFuture = CompletableFuture.supplyAsync(() -> {
                if (referenceDocumentSearchService == null || collectUserId == null) {
                    return new ArrayList<>();
                }
                try {
                    return referenceDocumentSearchService.search(collectUserId, referenceDocQuery, 5);
                } catch (Exception e) {
                    log.warn("Reference document search failed during catalog collect: {}", e.getMessage());
                    return new ArrayList<>();
                }
            });
            
            // Wait for all searches to complete
            CompletableFuture.allOf(wikiFuture, jiraFuture, slackFuture, githubFuture, outlookFuture,
                    googleDriveFuture, referenceDocFuture).join();
            
            // Get results
            List<WikiSearchResult> wikiResults = new ArrayList<>();
            List<JiraSearchResult> jiraResults = new ArrayList<>();
            List<SlackSearchResult> slackResults = new ArrayList<>();
            List<GitHubSearchResult> githubResults = new ArrayList<>();
            List<OutlookSearchResult> outlookResults = new ArrayList<>();
            List<GoogleDriveSearchResult> googleDriveResults = new ArrayList<>();
            List<ReferenceDocumentChunkHit> referenceDocResults = new ArrayList<>();
            
            try {
                wikiResults = wikiFuture.get();
            } catch (Exception e) {
                log.error("Wiki search error", e);
            }
            try {
                jiraResults = jiraFuture.get();
            } catch (Exception e) {
                log.error("JIRA search error", e);
            }
            try {
                slackResults = slackFuture.get();
            } catch (Exception e) {
                log.error("Slack search error", e);
            }
            try {
                githubResults = githubFuture.get();
            } catch (Exception e) {
                log.error("GitHub search error", e);
            }
            try {
                outlookResults = outlookFuture.get();
            } catch (Exception e) {
                log.error("Outlook search error", e);
            }
            try {
                googleDriveResults = googleDriveFuture.get();
            } catch (Exception e) {
                log.error("Google Drive search error", e);
            }
            try {
                referenceDocResults = referenceDocFuture.get();
            } catch (Exception e) {
                log.error("Reference document search error", e);
            }

            // Build lineage text (same format used for vector DB embedding)
            String lineageText = buildLineageText(param.getDataSourceId(), param.getDatabaseName(),
                    param.getSchemaName(), param.getTableName());

            // Extract metadata using LLM
            String catalogTableName = param.getCatalogTableName();
            geminiService.extractTableMetadata(tableMetadata, wikiResults, jiraResults, slackResults,
                githubResults, outlookResults, googleDriveResults, referenceDocResults, userConfig, dbType,
                catalogTableName, lineageText);

            // Table description comes from AI above. Column descriptions for known Google service
            // tables stay on the documented schema text; event_name gets per-event enrichment.
            if (predefinedEvidence != null) {
                applyPredefinedColumnDescriptionsAfterAi(tableMetadata, predefinedEvidence);
            } else if ("BIGQUERY".equalsIgnoreCase(dbType)) {
                String gaService = BigQueryPredefinedCatalog.detectService(param.getSchemaName(), param.getTableName());
                if ("GA4".equals(gaService)) {
                    enrichGa4EventNameColumnDescription(tableMetadata);
                }
            }

            // Combine tablePurpose and tableDescription
            String combinedDescription = "";
            if (tableMetadata.getTablePurpose() != null && !tableMetadata.getTablePurpose().isEmpty()) {
                combinedDescription = tableMetadata.getTablePurpose().trim();
                log.info("Table Purpose: {}", tableMetadata.getTablePurpose());
            }
            if (tableMetadata.getTableDescription() != null && !tableMetadata.getTableDescription().isEmpty()) {
                String description = tableMetadata.getTableDescription().trim();
                if (!combinedDescription.isEmpty()) {
                    // Join with line break
                    combinedDescription += "\n\n" + description;
                } else {
                    combinedDescription = description;
                }
                log.info("Table Description: {}", tableMetadata.getTableDescription());
            }
            log.info("Combined Description length: {}, content: {}", combinedDescription.length(), combinedDescription.substring(0, Math.min(100, combinedDescription.length())));

            // Don't save to database - just return the data for frontend to display
            // User must click Save button to persist changes
            Map<String, Object> result = new HashMap<>();
            result.put("tableDescription", combinedDescription);

            Map<String, String> columnDescriptions = new HashMap<>();
            Map<String, List<String>> columnExampleValues = new HashMap<>();
            for (ColumnMetadata col : tableMetadata.getColumns()) {
                columnDescriptions.put(col.getColumnName(), col.getDescription() != null ? col.getDescription() : "");
                // Include example values for bulk collection
                if (col.getExampleValues() != null && !col.getExampleValues().isEmpty()) {
                    columnExampleValues.put(col.getColumnName(), col.getExampleValues());
                }
            }
            // Prefer richer predefined custom-value lists (e.g. full event_name distinct set)
            if (predefinedEvidence != null) {
                for (Map.Entry<String, List<String>> entry : predefinedEvidence.columnExampleValues.entrySet()) {
                    List<String> predefined = entry.getValue();
                    List<String> existing = columnExampleValues.get(entry.getKey());
                    if (predefined != null && !predefined.isEmpty()
                        && (existing == null || predefined.size() >= existing.size())) {
                        columnExampleValues.put(entry.getKey(), predefined);
                    }
                }
            }

            result.put("columnDescriptions", columnDescriptions);
            result.put("columnExampleValues", columnExampleValues);

            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Error collecting AI metadata", e);
            return DataResult.error("Error while collecting AI metadata: " + e.getMessage(), "AI_COLLECTION_ERROR");
        }
    }

    /**
     * Collect predefined column schema + custom-value examples for well-known BigQuery tables.
     * Does not supply a table description — that is generated by AI like any other table.
     */
    private PredefinedColumnEvidence collectPredefinedColumnEvidence(TableQueryParam param) {
        String datasetName = param.getSchemaName();
        String tableName = param.getTableName();

        String service = BigQueryPredefinedCatalog.detectService(datasetName, tableName);
        if (service == null) {
            return null;
        }

        PredefinedTableMetadata predefined = BigQueryPredefinedCatalog.getMetadata(service, tableName);
        if (predefined == null) {
            return null;
        }

        log.info("Collecting predefined column evidence for BigQuery table: {}.{} (service: {})",
            datasetName, tableName, service);

        Map<String, String> columnDescriptions = new HashMap<>();
        for (PredefinedTableMetadata.PredefinedColumnMetadata col : predefined.getColumns()) {
            columnDescriptions.put(col.getColumnName(), col.getDescription());
        }

        Map<String, List<String>> columnExampleValues = new HashMap<>();
        String qualifiedTableName = buildQualifiedTableName("BIGQUERY", param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        Map<String, String> customQueries = BigQueryPredefinedCatalog.getCustomValueQueries(service, tableName, qualifiedTableName);

        if (!customQueries.isEmpty()) {
            try {
                Connection connection = InqueryContext.getConnection();
                if (connection != null) {
                    for (Map.Entry<String, String> entry : customQueries.entrySet()) {
                        String columnName = entry.getKey();
                        String sql = entry.getValue();
                        try (Statement stmt = connection.createStatement()) {
                            stmt.setQueryTimeout(30);
                            ResultSet rs = stmt.executeQuery(sql);
                            List<String> values = new ArrayList<>();
                            while (rs.next()) {
                                String val = rs.getString(1);
                                if (val != null && !val.isEmpty()) {
                                    values.add(val);
                                }
                            }
                            if (!values.isEmpty()) {
                                if ("event_name".equals(columnName)) {
                                    BigQueryPredefinedCatalog.filterValidGa4EventNames(values);
                                }
                                if (!values.isEmpty()) {
                                    columnExampleValues.put(columnName, values);
                                    log.info("Collected {} example values for custom column: {}", values.size(), columnName);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to collect example values for column {}: {}", columnName, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to collect custom column example values: {}", e.getMessage());
            }
        }

        if ("GA4".equals(service)) {
            List<String> presentEvents = columnExampleValues.get("event_name");
            if (presentEvents == null || presentEvents.isEmpty()) {
                try {
                    Connection connection = InqueryContext.getConnection();
                    if (connection != null) {
                        String fallbackSql = BigQueryPredefinedCatalog.buildGa4EventNameDistinctSqlSingleTable(qualifiedTableName);
                        presentEvents = executeDistinctValueQuery(connection, fallbackSql);
                        if (!presentEvents.isEmpty()) {
                            columnExampleValues.put("event_name", presentEvents);
                            log.info("Collected {} event_name values via single-table fallback", presentEvents.size());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to collect event_name via single-table fallback: {}", e.getMessage());
                }
            }
            if (presentEvents != null && !presentEvents.isEmpty()) {
                BigQueryPredefinedCatalog.filterValidGa4EventNames(presentEvents);
            }
            if (presentEvents != null && !presentEvents.isEmpty()) {
                columnDescriptions.put("event_name",
                    BigQueryPredefinedCatalog.buildGa4EventNameDescription(presentEvents));
                log.info("Prepared event_name description with {} event definitions", presentEvents.size());
            }
        }

        return new PredefinedColumnEvidence(service, columnDescriptions, columnExampleValues);
    }

    private void mergePredefinedEvidenceIntoTableMetadata(TableMetadata tableMetadata,
                                                          PredefinedColumnEvidence evidence) {
        for (ColumnMetadata col : tableMetadata.getColumns()) {
            List<String> predefinedExamples = evidence.columnExampleValues.get(col.getColumnName());
            if (predefinedExamples != null && !predefinedExamples.isEmpty()) {
                List<String> existing = col.getExampleValues();
                if (existing == null || existing.isEmpty() || predefinedExamples.size() >= existing.size()) {
                    col.setExampleValues(new ArrayList<>(predefinedExamples));
                }
            }
        }
    }

    private void applyPredefinedColumnDescriptionsAfterAi(TableMetadata tableMetadata,
                                                            PredefinedColumnEvidence evidence) {
        for (ColumnMetadata col : tableMetadata.getColumns()) {
            String predefinedDesc = evidence.columnDescriptions.get(col.getColumnName());
            if (predefinedDesc != null && !predefinedDesc.isEmpty()) {
                col.setDescription(predefinedDesc);
            }
        }
    }

    private void enrichGa4EventNameColumnDescription(TableMetadata tableMetadata) {
        for (ColumnMetadata col : tableMetadata.getColumns()) {
            if ("event_name".equalsIgnoreCase(col.getColumnName())
                && col.getExampleValues() != null && !col.getExampleValues().isEmpty()) {
                String eventDescription = BigQueryPredefinedCatalog.buildGa4EventNameDescription(col.getExampleValues());
                if (eventDescription != null && !eventDescription.isEmpty()) {
                    col.setDescription(eventDescription);
                }
                break;
            }
        }
    }

    /**
     * Build a structured evidence block from collected samples so the LLM can write a
     * dataset-specific table description (not generic GA4 / intraday boilerplate).
     */
    private String buildDataProfileSummary(TableMetadata tableMetadata,
                                           PredefinedColumnEvidence evidence,
                                           TableQueryParam param) {
        StringBuilder sb = new StringBuilder();
        String service = evidence != null ? evidence.service
            : BigQueryPredefinedCatalog.detectService(param.getSchemaName(), param.getTableName());

        if ("GA4".equals(service)) {
            sb.append("Source: Google Analytics 4 BigQuery export\n");
            String tableName = param.getTableName();
            if (tableName != null) {
                if (tableName.contains("_intraday_")) {
                    sb.append("Shard type: intraday (near-real-time; may lag final daily export)\n");
                } else if (tableName.contains("_fresh_")) {
                    sb.append("Shard type: fresh (recently updated shard)\n");
                } else if (tableName.matches("events_\\d{8}")) {
                    sb.append("Shard type: daily final export\n");
                }
            }
        } else if (service != null) {
            sb.append("Source: BigQuery ").append(service).append(" export\n");
        }

        List<String> eventNames = getColumnExamples(tableMetadata, evidence, "event_name");
        BigQueryPredefinedCatalog.filterValidGa4EventNames(eventNames);
        if (!eventNames.isEmpty()) {
            sb.append("Distinct event_name observed (").append(eventNames.size()).append("): ")
                .append(String.join(", ", eventNames.size() > 25 ? eventNames.subList(0, 25) : eventNames));
            if (eventNames.size() > 25) {
                sb.append(", ... (+").append(eventNames.size() - 25).append(" more)");
            }
            sb.append("\n");
            appendTrafficHint(sb, eventNames);
        }

        appendExampleList(sb, tableMetadata, evidence, "event_params.key", "event_params keys");
        appendExampleList(sb, tableMetadata, evidence, "event_params.value", "event_params sample (key = value)", 15);
        appendExampleList(sb, tableMetadata, evidence, "user_properties.key", "user_properties keys");
        appendExampleList(sb, tableMetadata, evidence, "stream_id", "stream_id values");
        appendExampleList(sb, tableMetadata, evidence, "items.item_id", "items.item_id samples", 10);
        appendExampleList(sb, tableMetadata, evidence, "items.item_name", "items.item_name samples", 10);

        if (tableMetadata.getPartitionColumn() != null) {
            sb.append("Primary row category column: ").append(tableMetadata.getPartitionColumn()).append("\n");
        }

        String profile = sb.toString().trim();
        return profile.isEmpty() ? null : profile;
    }

    private List<String> getColumnExamples(TableMetadata tableMetadata,
                                           PredefinedColumnEvidence evidence,
                                           String columnName) {
        if (evidence != null) {
            List<String> fromEvidence = evidence.columnExampleValues.get(columnName);
            if (fromEvidence != null && !fromEvidence.isEmpty()) {
                return fromEvidence;
            }
        }
        for (ColumnMetadata col : tableMetadata.getColumns()) {
            if (columnName.equals(col.getColumnName()) && col.getExampleValues() != null) {
                return col.getExampleValues();
            }
        }
        return List.of();
    }

    private void appendExampleList(StringBuilder sb, TableMetadata tableMetadata,
                                   PredefinedColumnEvidence evidence, String columnName,
                                   String label) {
        appendExampleList(sb, tableMetadata, evidence, columnName, label, 20);
    }

    private void appendExampleList(StringBuilder sb, TableMetadata tableMetadata,
                                   PredefinedColumnEvidence evidence, String columnName,
                                   String label, int limit) {
        List<String> values = getColumnExamples(tableMetadata, evidence, columnName);
        if (!values.isEmpty()) {
            List<String> shown = values.size() > limit ? values.subList(0, limit) : values;
            sb.append(label).append(" (").append(values.size()).append("): ")
                .append(String.join(", ", shown));
            if (values.size() > limit) {
                sb.append(", ... (+").append(values.size() - limit).append(" more)");
            }
            sb.append("\n");
        }
    }

    private void appendTrafficHint(StringBuilder sb, List<String> eventNames) {
        Set<String> normalized = eventNames.stream().map(String::toLowerCase).collect(Collectors.toSet());
        boolean hasWeb = normalized.contains("page_view") || normalized.contains("click")
            || normalized.contains("scroll") || normalized.contains("file_download");
        boolean hasApp = normalized.contains("screen_view") || normalized.contains("first_open")
            || normalized.contains("app_remove") || normalized.contains("notification_receive");
        boolean hasCommerce = normalized.contains("purchase") || normalized.contains("add_to_cart")
            || normalized.contains("begin_checkout") || normalized.contains("view_item");
        if (hasWeb || hasApp || hasCommerce) {
            sb.append("Inferred usage from events: ");
            List<String> hints = new ArrayList<>();
            if (hasWeb) hints.add("web traffic (page_view/click/scroll-class events present)");
            if (hasApp) hints.add("app traffic (screen_view/first_open-class events present)");
            if (hasCommerce) hints.add("e-commerce funnel (purchase/cart/checkout-class events present)");
            sb.append(String.join("; ", hints)).append("\n");
        }
    }

    /**
     * Auto-save predefined metadata for well-known BigQuery Google service tables.
     * Called during data source connection to populate catalog instantly without AI.
     *
     * @param dataSourceId data source ID
     * @param tablesMap    tables loaded from loadAllTablesAndViews()
     */
    @Override
    public void autoSaveBigQueryPredefinedMetadata(Long dataSourceId, Map<String, List<Table>> tablesMap) {
        if (tablesMap == null || tablesMap.isEmpty()) {
            return;
        }

        int savedCount = 0;
        // Track already-saved catalog names to avoid duplicate saves for sharded table groups
        Set<String> savedCatalogNames = new HashSet<>();

        for (Map.Entry<String, List<Table>> entry : tablesMap.entrySet()) {
            // Key format: "database.schema" (e.g., "my-project.analytics_123456")
            String key = entry.getKey();
            String[] parts = key.split("\\.", 2);
            if (parts.length < 2) continue;

            String databaseName = parts[0];
            String schemaName = parts[1];

            for (Table table : entry.getValue()) {
                String tableName = table.getName();
                String service = BigQueryPredefinedCatalog.detectService(schemaName, tableName);
                if (service == null) continue;

                PredefinedTableMetadata predefined = BigQueryPredefinedCatalog.getMetadata(service, tableName);
                if (predefined == null) continue;

                // For sharded tables (e.g., events_20260317), use group name (e.g., events_YYYYMMDD)
                // This matches the frontend catalogTableName convention
                String catalogTableName = toShardedGroupName(tableName);

                // Skip if already saved this group in this run
                String catalogKey = databaseName + "." + schemaName + "." + catalogTableName;
                if (savedCatalogNames.contains(catalogKey)) continue;

                // Check if catalog already exists in DB
                LambdaQueryWrapper<DataCatalogTableDO> existCheck = new LambdaQueryWrapper<>();
                existCheck.eq(DataCatalogTableDO::getDataSourceId, dataSourceId)
                        .eq(DataCatalogTableDO::getDatabaseName, databaseName)
                        .eq(DataCatalogTableDO::getSchemaName, schemaName)
                        .eq(DataCatalogTableDO::getTableName, catalogTableName);
                DataCatalogTableDO existing = getTableMapper().selectOne(existCheck);
                if (existing != null && StringUtils.isNotBlank(existing.getTableDescription())) {
                    savedCatalogNames.add(catalogKey);
                    continue;
                }

                // Build save param with group name
                DataCatalogSaveParam saveParam = new DataCatalogSaveParam();
                saveParam.setDataSourceId(dataSourceId);
                saveParam.setDatabaseName(databaseName);
                saveParam.setSchemaName(schemaName);
                saveParam.setTableName(catalogTableName);
                saveParam.setTableDescription("");

                List<DataCatalogColumnSaveParam> columnParams = new ArrayList<>();
                int ordinal = 1;
                for (PredefinedTableMetadata.PredefinedColumnMetadata col : predefined.getColumns()) {
                    DataCatalogColumnSaveParam colParam = new DataCatalogColumnSaveParam();
                    colParam.setColumnName(col.getColumnName());
                    colParam.setColumnDescription(col.getDescription());
                    colParam.setOrdinalPosition(ordinal++);
                    columnParams.add(colParam);
                }
                saveParam.setColumns(columnParams);

                try {
                    saveCatalog(saveParam);
                    savedCatalogNames.add(catalogKey);
                    savedCount++;
                    log.info("Auto-saved predefined metadata for BigQuery table group: {}.{}.{} (service: {})",
                            databaseName, schemaName, catalogTableName, service);
                } catch (Exception e) {
                    log.warn("Failed to auto-save predefined metadata for {}.{}.{}: {}",
                            databaseName, schemaName, catalogTableName, e.getMessage());
                }
            }
        }

        if (savedCount > 0) {
            log.info("Auto-saved predefined metadata for {} BigQuery Google service table groups (dataSourceId: {})",
                    savedCount, dataSourceId);
        }
    }

    /**
     * Convert a sharded table name to its group name.
     * e.g., "events_20260317" -> "events_YYYYMMDD"
     *       "events_intraday_20260317" -> "events_YYYYMMDD"
     *       "events_fresh_20260317" -> "events_YYYYMMDD"
     *       "pseudonymous_users_20260317" -> "pseudonymous_users_YYYYMMDD"
     *       "com_example_app_ANDROID" -> "com_example_app_ANDROID" (no change)
     *
     * GA4 variants (intraday/fresh) share the same schema as the daily table, so
     * they collapse into the base group. This must match the frontend grouping
     * (normalizeShardPrefix) so saved metadata is found by the same catalog key.
     */
    private String toShardedGroupName(String tableName) {
        // Match tables ending with _YYYYMMDD (8 digits)
        if (tableName != null && tableName.matches(".*_\\d{8}$")) {
            String grouped = tableName.replaceAll("_\\d{8}$", "_YYYYMMDD");
            // Collapse GA4 intraday/fresh variants into the base daily group.
            grouped = grouped.replaceAll("_(intraday|fresh)_YYYYMMDD$", "_YYYYMMDD");
            return grouped;
        }
        return tableName;
    }

    @Override
    public DataResult<List<Database>> queryDatabaseByDataSourceId(Long dataSourceId) {
        DatabaseQueryAllParam param = new DatabaseQueryAllParam();
        param.setDataSourceId(dataSourceId);
        ListResult<Database> listResult = databaseService.queryAll(param);
        return DataResult.of(listResult.getData());
    }

    @Override
    public DataResult<List<Schema>> querySchemaByDataSourceId(Long dataSourceId, String databaseName, String schemaName) {
        SchemaQueryParam param = new SchemaQueryParam();
        param.setDataSourceId(dataSourceId);
        param.setDataBaseName(databaseName);
        ListResult<Schema> listResult = databaseService.querySchema(param);
        return DataResult.of(listResult.getData());
    }

    @Override
    public DataResult<Map<String, List<Table>>> queryTableByDataSourceId(Long dataSourceId, String databaseName, String schemaName, List<String> tableNames, boolean refresh) {
        ai.inquery.server.domain.api.param.TablePageQueryParam param = new ai.inquery.server.domain.api.param.TablePageQueryParam();
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        param.setRefresh(refresh);
        param.setPageNo(1);
        param.setPageSize(10000);

        ListResult<ai.inquery.spi.model.SimpleTable> result = tableService.queryTables(param);
        if (!result.success()) {
            return DataResult.error(result.getErrorCode(), result.getErrorMessage());
        }

        Map<String, List<Table>> map = new HashMap<>();
        if (result.getData() != null) {
            for (ai.inquery.spi.model.SimpleTable simpleTable : result.getData()) {
                Table table = new Table();
                table.setName(simpleTable.getName());
                table.setDatabaseName(simpleTable.getDatabaseName());
                table.setSchemaName(simpleTable.getSchemaName());
                table.setComment(simpleTable.getComment());
                // table.setType(simpleTable.getType());

                String key = simpleTable.getDatabaseName();
                if (StringUtils.isNotBlank(simpleTable.getSchemaName())) {
                    key += "." + simpleTable.getSchemaName();
                }

                map.computeIfAbsent(key, k -> new ArrayList<>()).add(table);
            }
        }
        return DataResult.of(map);
    }

    public DataResult<Map<String, Object>> collectAIMetadataForAllTables(Long dataSourceId, String databaseName, String schemaName) {
        Map<String, Object> result = new HashMap<>();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);
        List<String> failedTables = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Set up connection context (required since this method is called with @RequestParam, not request objects)
        ConnectInfo connectInfo = buildConnectInfo(dataSourceId, databaseName, schemaName);
        if (connectInfo == null) {
            return DataResult.error("DATA_SOURCE_NOT_FOUND", "Data source not found: " + dataSourceId);
        }
        InqueryContext.putContext(connectInfo);

        try {
            // Get all tables
            ai.inquery.server.domain.api.param.TablePageQueryParam queryParam =
                ai.inquery.server.domain.api.param.TablePageQueryParam.builder()
                    .dataSourceId(dataSourceId)
                    .databaseName(databaseName)
                    .schemaName(schemaName)
                    .build();

            ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.SimpleTable> tablesResult =
                tableService.queryTables(queryParam);

            if (tablesResult == null || CollectionUtils.isEmpty(tablesResult.getData())) {
                result.put("successCount", 0);
                result.put("failCount", 0);
                result.put("totalCount", 0);
                return DataResult.of(result);
            }

            List<ai.inquery.spi.model.SimpleTable> tables = tablesResult.getData();
            int totalCount = tables.size();

            log.info("Starting parallel AI metadata collection for {} tables", totalCount);

            // Capture ThreadLocal context for worker threads
            Context userContext = ContextUtils.queryContext();

            // Process tables in parallel (limit concurrency to avoid API rate limits)
            int threadCount = Math.min(5, totalCount);
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();

            for (ai.inquery.spi.model.SimpleTable table : tables) {
                java.util.concurrent.CompletableFuture<Void> future = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    // Propagate ThreadLocal context to worker thread
                    ContextUtils.setContext(userContext);
                    ConnectInfo threadConnectInfo = buildConnectInfo(dataSourceId, databaseName, schemaName);
                    if (threadConnectInfo == null) {
                        failCount.incrementAndGet();
                        failedTables.add(table.getName());
                        return;
                    }
                    InqueryContext.putContext(threadConnectInfo);
                    try {
                        TableQueryParam tableParam = TableQueryParam.builder()
                            .dataSourceId(dataSourceId)
                            .databaseName(databaseName)
                            .schemaName(schemaName)
                            .tableName(table.getName())
                            .build();

                        DataResult<Map<String, Object>> aiResult = collectAIMetadata(tableParam);
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
                                        colParam.setExampleValues(examples != null && !examples.isEmpty() ? JSON.toJSONString(examples) : "");
                                    } else {
                                        colParam.setExampleValues("");
                                    }
                                    columnParams.add(colParam);
                                }
                                saveParam.setColumns(columnParams);
                            }

                            saveCatalog(saveParam);
                            successCount.incrementAndGet();
                            log.info("Successfully collected AI metadata for table: {}", table.getName());
                        } else {
                            failCount.incrementAndGet();
                            failedTables.add(table.getName());
                            log.warn("Failed to collect AI metadata for table: {} - {}", table.getName(), aiResult.getErrorMessage());
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        failedTables.add(table.getName());
                        log.error("Error collecting AI metadata for table: {}", table.getName(), e);
                    } finally {
                        InqueryContext.removeContext();
                        ContextUtils.removeContext();
                    }
                }, executor);
                futures.add(future);
            }

            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            executor.shutdown();

            result.put("successCount", successCount.get());
            result.put("failCount", failCount.get());
            result.put("totalCount", totalCount);
            result.put("failedTables", new ArrayList<>(failedTables));

            log.info("AI metadata collection completed: {}/{} succeeded, {} failed", successCount.get(), totalCount, failCount.get());
        } catch (Exception e) {
            log.error("Error collecting AI metadata for all tables", e);
            result.put("successCount", successCount.get());
            result.put("failCount", failCount.get());
            result.put("error", e.getMessage());
        } finally {
            // Clean up connection context
            InqueryContext.removeContext();
        }

        return DataResult.of(result);
    }

    /**
     * Build ConnectInfo from dataSourceId for setting up connection context.
     */
    private ConnectInfo buildConnectInfo(Long dataSourceId, String databaseName, String schemaName) {
        try {
            DataResult<DataSource> result = dataSourceService.queryById(dataSourceId);
            if (!result.success() || result.getData() == null) {
                log.error("Data source not found: {}", dataSourceId);
                return null;
            }
            DataSource dataSource = result.getData();

            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setAlias(dataSource.getAlias());
            connectInfo.setUser(dataSource.getUserName());
            connectInfo.setDataSourceId(dataSourceId);
            connectInfo.setPassword(dataSource.getPassword());
            connectInfo.setDbType(dataSource.getType());
            connectInfo.setUrl(dataSource.getUrl());
            connectInfo.setDatabase(databaseName);
            connectInfo.setSchemaName(schemaName);
            connectInfo.setConsoleOwn(false);
            // Set a fixed consoleId for AI collection to enable connection pool reuse
            // This avoids creating a new Snowflake connection for each AI collection request
            connectInfo.setConsoleId(0L);
            connectInfo.setDriver(dataSource.getDriver());
            connectInfo.setSsh(dataSource.getSsh());
            connectInfo.setSsl(dataSource.getSsl());
            connectInfo.setJdbc(dataSource.getJdbc());
            connectInfo.setExtendInfo(dataSource.getExtendInfo());
            connectInfo.setPort(StringUtils.isNotBlank(dataSource.getPort()) ? Integer.parseInt(dataSource.getPort()) : null);
            connectInfo.setHost(dataSource.getHost());
            connectInfo.setLoginUser(ContextUtils.getLoginUser().getId() + "");
            DriverConfig driverConfig = dataSource.getDriverConfig();
            if (driverConfig != null && driverConfig.notEmpty()) {
                connectInfo.setDriverConfig(driverConfig);
            }
            return connectInfo;
        } catch (Exception e) {
            log.error("Failed to build ConnectInfo for dataSourceId: {}", dataSourceId, e);
            return null;
        }
    }

    // Factory methods to create search services with user config
    private ConfluenceService createConfluenceService(UserAIConfigDO config) {
        return new ConfluenceService(config);
    }

    private JiraService createJiraService(UserAIConfigDO config) {
        return new JiraService(config);
    }

    private SlackService createSlackService(UserAIConfigDO config) {
        return new SlackService(config);
    }

    private GitHubService createGitHubService(UserAIConfigDO config) {
        return new GitHubService(config);
    }

    private OutlookService createOutlookService(UserAIConfigDO config) {
        return new OutlookService(config);
    }

    private GoogleDriveService createGoogleDriveService(UserAIConfigDO config) {
        return new GoogleDriveService(config);
    }

    /**
     * Search Google Drive for Docs/Sheets matching the table name and fetch content for top hits.
     */
    private List<GoogleDriveSearchResult> searchGoogleDriveForCollection(GoogleDriveService service,
            String keyword, int maxResults) {
        List<GoogleDriveSearchResult> results = service.searchFiles(keyword, maxResults);
        int fetchCount = Math.min(maxResults, results.size());
        for (int i = 0; i < fetchCount; i++) {
            GoogleDriveSearchResult result = results.get(i);
            if (result.getContent() == null || result.getContent().isEmpty()) {
                try {
                    result.setContent(service.fetchContent(result.getFileId(), result.getMimeType()));
                } catch (Exception e) {
                    log.warn("Google Drive content fetch failed for fileId={}: {}",
                            result.getFileId(), e.getMessage());
                }
            }
        }
        return results;
    }

    // Column cache: key = "dataSourceId:database.schema", value = cached columns map with timestamp
    private static class CachedColumnsResult {
        Map<String, List<TableColumn>> columnsMap;
        long timestamp;

        CachedColumnsResult(Map<String, List<TableColumn>> columnsMap, long timestamp) {
            this.columnsMap = columnsMap;
            this.timestamp = timestamp;
        }
    }

    /** Predefined column schema + custom-value samples for Google service tables (no table description). */
    private static class PredefinedColumnEvidence {
        final String service;
        final Map<String, String> columnDescriptions;
        final Map<String, List<String>> columnExampleValues;

        PredefinedColumnEvidence(String service,
                                 Map<String, String> columnDescriptions,
                                 Map<String, List<String>> columnExampleValues) {
            this.service = service;
            this.columnDescriptions = columnDescriptions;
            this.columnExampleValues = columnExampleValues;
        }
    }

    // Metadata cache: key = "dataSourceId:database.schema.table", value = cached catalog data with timestamp
    private static class CachedCatalogResult {
        String tableDescription;
        Map<String, DataCatalogColumnDO> columnMap;
        long timestamp;

        CachedCatalogResult(String tableDescription, Map<String, DataCatalogColumnDO> columnMap, long timestamp) {
            this.tableDescription = tableDescription;
            this.columnMap = columnMap;
            this.timestamp = timestamp;
        }
    }

    private static final Map<String, CachedColumnsResult> columnsCache = new ConcurrentHashMap<>();
    private static final Map<String, CachedCatalogResult> catalogCache = new ConcurrentHashMap<>();
    private static final long COLUMNS_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 1 day
    private static final long CATALOG_CACHE_EXPIRY_MS = 5 * 60 * 1000L; // 5 minutes (shorter because metadata can be updated)
    // Track in-progress batch column loading requests to prevent duplicate queries
    private static final Map<String, CompletableFuture<DataResult<Map<String, List<TableColumn>>>>> inProgressBatchLoads = new ConcurrentHashMap<>();

    @Override
    public DataResult<Map<String, List<TableColumn>>> batchLoadColumns(TableQueryParam param) {
        try {
            // Get dbType to check if BigQuery
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            boolean isBigQuery = connectInfo != null && "BIGQUERY".equalsIgnoreCase(connectInfo.getDbType());

            // databaseName is required for all databases to avoid CURRENT_DATABASE() issues
            // Snowflake: "Cannot perform SELECT. This session does not have a current database"
            if (StringUtils.isBlank(param.getDatabaseName())) {
                log.warn("Batch column load skipped - databaseName is required. Caller should use fallback.");
                return DataResult.of(new HashMap<>());
            }

            // BigQuery requires schemaName (dataset) for INFORMATION_SCHEMA queries
            // If not provided, skip batch loading and let the caller use fallback (direct query)
            if (isBigQuery && StringUtils.isBlank(param.getSchemaName())) {
                log.debug("BigQuery batch column load skipped - schemaName (dataset) is required. Caller should use fallback.");
                return DataResult.of(new HashMap<>());
            }

            // Build cache key: 
            // - BigQuery: "dataSourceId:database:schema" (schema-level, because INFORMATION_SCHEMA is at dataset level)
            // - Others: "dataSourceId:database" (database-level)
            String cacheKey;
            if (isBigQuery && StringUtils.isNotBlank(param.getSchemaName())) {
                cacheKey = String.format("%d:%s:%s", 
                    param.getDataSourceId(), 
                    param.getDatabaseName(),
                    param.getSchemaName());
            } else {
                cacheKey = String.format("%d:%s", 
                    param.getDataSourceId(), 
                    param.getDatabaseName());
            }

            // Check cache first (unless refresh is requested)
            if (!param.isRefresh()) {
                CachedColumnsResult cachedResult = columnsCache.get(cacheKey);
                if (cachedResult != null) {
                    long age = System.currentTimeMillis() - cachedResult.timestamp;
                    if (age < COLUMNS_CACHE_EXPIRY_MS) {
                        log.info("Returning cached columns for database: {} (age: {}ms, {} tables/views across all schemas)",
                            cacheKey, age, cachedResult.columnsMap.size());
                        if (param.getSchemaName() != null) {
                            Map<String, List<TableColumn>> filteredMap = new HashMap<>();
                            String schemaPrefix = param.getDatabaseName() + "." + param.getSchemaName() + ".";
                            cachedResult.columnsMap.forEach((key, columns) -> {
                                if (key.startsWith(schemaPrefix)) {
                                    filteredMap.put(key, columns);
                                }
                            });
                            log.info("Filtered cached columns for schema: {} ({} tables/views)",
                                param.getSchemaName(), filteredMap.size());
                            return DataResult.of(filteredMap);
                        }
                        return DataResult.of(cachedResult.columnsMap);
                    } else {
                        columnsCache.remove(cacheKey);
                        log.info("Cache expired for database: {} (age: {}ms), removing from cache", cacheKey, age);
                    }
                }
            } else {
                log.info("Refresh requested, ignoring cache for database: {}", cacheKey);
            }

            // Check if there's already an in-progress request for this database
            CompletableFuture<DataResult<Map<String, List<TableColumn>>>> inProgressFuture = inProgressBatchLoads.get(cacheKey);
            if (inProgressFuture != null && !inProgressFuture.isDone()) {
                log.info("Waiting for in-progress batch column load for database: {} (timeout: 60s)", cacheKey);
                try {
                    DataResult<Map<String, List<TableColumn>>> result = inProgressFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    log.info("Reusing result from in-progress batch column load for database: {} ({} tables/views)",
                        cacheKey, result.getData() != null ? result.getData().size() : 0);
                    return result;
                } catch (java.util.concurrent.TimeoutException e) {
                    log.warn("Timeout waiting for in-progress batch column load for database: {} (60s timeout), starting new request", cacheKey);
                    inProgressFuture.cancel(true);
                    inProgressBatchLoads.remove(cacheKey);
                } catch (Exception e) {
                    log.warn("Error waiting for in-progress batch column load for database: {}, starting new request", cacheKey, e);
                    inProgressBatchLoads.remove(cacheKey);
                }
            }

            // Reuse ConnectInfo from above for async task (already fetched at method start)
            // connectInfo was already retrieved at the beginning of this method
            if (connectInfo == null) {
                log.error("No ConnectInfo available for batch column loading database: {}", cacheKey);
                return DataResult.error("NO_CONNECTION", "No ConnectInfo available");
            }
            ConnectInfo connectInfoCopy = connectInfo.copy();
            // Set databaseName and schemaName to null to reuse the base connection pool
            // This avoids creating a new physical connection for each database
            connectInfoCopy.setDatabase(null);
            connectInfoCopy.setSchemaName(null);
            log.info("Starting batch column load for database: {} (dataSourceId: {}, database: {}) - loading ALL schemas (reusing base connection)", 
                cacheKey, param.getDataSourceId(), param.getDatabaseName());

            // Create a new CompletableFuture for this request
            CompletableFuture<DataResult<Map<String, List<TableColumn>>>> future = CompletableFuture.supplyAsync(() -> {
                Connection asyncConnection = null;
                long asyncStartTime = System.currentTimeMillis();
                try {
                    // Set ConnectInfo in ThreadLocal for this async task (required by ConnectionPool)
                    InqueryContext.putContext(connectInfoCopy);
                    try {
                        // Get a connection from the base connection pool (same key because databaseName is null)
                        // This reuses the existing connection instead of creating a new one
                        asyncConnection = ConnectionPool.getConnection(connectInfoCopy);
                        
                        if (asyncConnection == null) {
                            log.error("Failed to get connection for batch column loading database: {}", cacheKey);
                            return DataResult.error("NO_CONNECTION", "Failed to get connection");
                        }
                        
                        // Query INFORMATION_SCHEMA.COLUMNS for all tables and views in the database (all schemas)
                        // Use fully qualified name: DATABASE.INFORMATION_SCHEMA.COLUMNS
                        // No need for USE DATABASE - Snowflake supports DATABASE.INFORMATION_SCHEMA.COLUMNS syntax
                        // For BigQuery: schemaName is required because INFORMATION_SCHEMA is at dataset level
                        String sql = buildBatchColumnsQuery(param.getDatabaseName(), param.getSchemaName()); // Pass schemaName for BigQuery
                        log.info("Batch loading columns for database: {} (query: {})", cacheKey, sql);

                        Map<String, List<TableColumn>> columnsMap = SQLExecutor.getInstance().execute(asyncConnection, sql, resultSet -> {
                Map<String, List<TableColumn>> result = new HashMap<>();
                try {
                    while (resultSet.next()) {
                        String tableCatalog = resultSet.getString("TABLE_CATALOG");
                        String tableSchema = resultSet.getString("TABLE_SCHEMA");
                        String tableName = resultSet.getString("TABLE_NAME");
                        
                        // Build key: "database.schema.table"
                        String tableKey = String.format("%s.%s.%s", tableCatalog, tableSchema, tableName);
                        
                        // Get or create column list for this table
                        List<TableColumn> columns = result.computeIfAbsent(tableKey, k -> new ArrayList<>());
                        
                        // Create column object
                        TableColumn column = new TableColumn();
                        column.setName(resultSet.getString("COLUMN_NAME"));
                        column.setTableName(tableName);
                        column.setSchemaName(tableSchema);
                        column.setDatabaseName(tableCatalog);

                        // DATA_TYPE contains schema information (e.g., "VARCHAR(16777216)", "NUMBER(38,0)")
                        String dataType = resultSet.getString("DATA_TYPE");
                        column.setColumnType(dataType);

                        // Nullable information
                        String isNullable = resultSet.getString("IS_NULLABLE");
                        if ("YES".equalsIgnoreCase(isNullable)) {
                            column.setNullable(1); // 1 = nullable
                        } else if ("NO".equalsIgnoreCase(isNullable)) {
                            column.setNullable(0); // 0 = not nullable
                        }

                        columns.add(column);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Error reading column information from INFORMATION_SCHEMA.COLUMNS", e);
                }
                return result;
                    });

                        // Store in cache
                        columnsCache.put(cacheKey, new CachedColumnsResult(columnsMap, System.currentTimeMillis()));
                        log.info("Cached columns for database: {} ({} tables/views across all schemas, {}ms)",
                            cacheKey, columnsMap.size(), System.currentTimeMillis() - asyncStartTime);

                        long elapsed = System.currentTimeMillis() - asyncStartTime;
                        log.info("Batch loaded columns for database: {} ({} tables/views across all schemas, {}ms)", 
                            cacheKey, columnsMap.size(), elapsed);
                        
                        return DataResult.of(columnsMap);
                    } finally {
                        // Clear ThreadLocal after use (connection is still open, will be closed in outer finally)
                        InqueryContext.removeContext();
                    }
                } catch (Exception e) {
                    log.error("Error batch loading columns for schema: {}", cacheKey, e);
                    return DataResult.error("BATCH_LOAD_ERROR", "Failed to batch load columns: " + e.getMessage());
                } finally {
                    // Note: We don't close the connection here as it's managed by ConnectionPool
                    // The connection will be reused or closed by the pool's cleanup mechanism
                    // Remove from in-progress map when done
                    inProgressBatchLoads.remove(cacheKey);
                }
            });

            // Store the future in the in-progress map
            inProgressBatchLoads.put(cacheKey, future);

            // Wait for the future to complete and return the result (with timeout: 60 seconds)
            try {
                DataResult<Map<String, List<TableColumn>>> result = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                log.info("Batch column load completed for database: {} ({} tables/views across all schemas)", 
                    cacheKey, result.getData() != null ? result.getData().size() : 0);
                return result;
            } catch (java.util.concurrent.TimeoutException e) {
                log.error("Timeout waiting for batch column load to complete for database: {} (60s timeout)", cacheKey);
                // Cancel the future and remove from in-progress map
                future.cancel(true);
                inProgressBatchLoads.remove(cacheKey);
                return DataResult.error("BATCH_LOAD_TIMEOUT", "Batch column load timed out after 60 seconds");
            } catch (Exception e) {
                log.error("Error waiting for batch column load to complete for database: {}", cacheKey, e);
                inProgressBatchLoads.remove(cacheKey);
                return DataResult.error("BATCH_LOAD_ERROR", "Failed to batch load columns: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Error batch loading columns", e);
            return DataResult.error("BATCH_LOAD_ERROR", "Failed to batch load columns: " + e.getMessage());
        }
    }

    /**
     * Build SQL query to fetch all columns from INFORMATION_SCHEMA.COLUMNS for a schema
     * 
     * @param databaseName database name (TABLE_CATALOG)
     * @param schemaName schema name (TABLE_SCHEMA)
     * @return SQL query string
     */
    private String buildBatchColumnsQuery(String databaseName, String schemaName) {
        // Get dbType from context to determine correct identifier quoting
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        String dbType = connectInfo != null ? connectInfo.getDbType() : null;
        boolean isBigQuery = "BIGQUERY".equalsIgnoreCase(dbType);
        boolean isMySQL = "MYSQL".equalsIgnoreCase(dbType);
        boolean isDatabricks = "DATABRICKS".equalsIgnoreCase(dbType);
        
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("TABLE_CATALOG, ");
        sql.append("TABLE_SCHEMA, ");
        sql.append("TABLE_NAME, ");
        sql.append("COLUMN_NAME, ");
        sql.append("IS_NULLABLE, ");
        sql.append("DATA_TYPE, ");
        sql.append("ORDINAL_POSITION ");
        sql.append("FROM ");

        // Use fully qualified name: DATABASE.INFORMATION_SCHEMA.COLUMNS
        // Quote database name based on DB type:
        // - BigQuery/Databricks: backticks (`) - Spark SQL uses backticks
        // - MySQL: no quotes needed for INFORMATION_SCHEMA
        // - Snowflake/Others: double quotes (")
        if (StringUtils.isNotBlank(databaseName)) {
            if (isBigQuery || isDatabricks) {
                // BigQuery/Databricks use backticks for identifiers
                sql.append("`").append(databaseName).append("`.");
                if (StringUtils.isNotBlank(schemaName)) {
                    sql.append("`").append(schemaName).append("`.");
                }
            } else if (!isMySQL) {
                sql.append("\"").append(databaseName).append("\".");
            }
            // MySQL doesn't need database prefix for INFORMATION_SCHEMA
        }
        sql.append("INFORMATION_SCHEMA.COLUMNS ");
        sql.append("WHERE ");

        // Always filter by TABLE_CATALOG to ensure we only get columns from the specified database
        // When using fully qualified name DATABASE.INFORMATION_SCHEMA.COLUMNS, this filter is still needed
        // because INFORMATION_SCHEMA can show cross-database results
        if (StringUtils.isNotBlank(databaseName)) {
            if (isMySQL) {
                // MySQL uses TABLE_SCHEMA instead of TABLE_CATALOG
                sql.append("TABLE_SCHEMA = '").append(databaseName).append("' ");
            } else {
                sql.append("TABLE_CATALOG = '").append(databaseName).append("' ");
            }
        } else {
            // If databaseName is null, use CURRENT_DATABASE() as fallback
            // This should not happen in normal flow, but included for safety
            if (isBigQuery) {
                // BigQuery doesn't have CURRENT_DATABASE(), must specify database
                sql.append("1=1 ");
            } else {
                sql.append("TABLE_CATALOG = CURRENT_DATABASE() ");
            }
        }

        if (StringUtils.isNotBlank(schemaName)) {
            sql.append("AND ");
            sql.append("TABLE_SCHEMA = '").append(schemaName).append("' ");
        }

        sql.append("ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");

        return sql.toString();
    }

    // Cache for all tables and views: key = "dataSourceId", value = cached result with timestamp
    private static class CachedTablesAndViewsResult {
        Map<String, List<Table>> tablesMap;
        long timestamp;
        
        CachedTablesAndViewsResult(Map<String, List<Table>> tablesMap, long timestamp) {
            this.tablesMap = tablesMap;
            this.timestamp = timestamp;
        }
    }
    
    private static final Map<String, CachedTablesAndViewsResult> allTablesCache = new HashMap<>();
    private static final long ALL_TABLES_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 1 day
    private static final long L2_CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 1 day - same as L1


    public DataResult<Map<String, List<Table>>> loadAllTablesAndViews(Long dataSourceId, boolean refresh) {
        long startTime = System.currentTimeMillis();
        try {
            // Build cache key: "dataSourceId"
            String cacheKey = String.valueOf(dataSourceId);

            // === L1: In-memory cache ===
            if (!refresh) {
                synchronized (allTablesCache) {
                    CachedTablesAndViewsResult cached = allTablesCache.get(cacheKey);
                    if (cached != null) {
                        long age = System.currentTimeMillis() - cached.timestamp;
                        if (age < ALL_TABLES_CACHE_EXPIRY_MS) {
                            int cachedTotal = cached.tablesMap != null
                                ? cached.tablesMap.values().stream().mapToInt(List::size).sum()
                                : 0;
                            if (cached.tablesMap == null || cached.tablesMap.isEmpty() || cachedTotal == 0) {
                                log.warn("Ignoring empty cached tables/views for dataSourceId: {} (age: {}ms)", cacheKey, age);
                            } else {
                                log.info("L1 (in-memory) cache hit for dataSourceId: {} (age: {}ms, {} schemas)",
                                    cacheKey, age, cached.tablesMap.size());
                                return DataResult.of(cached.tablesMap);
                            }
                        } else {
                            allTablesCache.remove(cacheKey);
                            log.info("L1 cache expired for dataSourceId: {} (age: {}ms)", cacheKey, age);
                        }
                    }
                }
            } else {
                log.info("Refresh requested, ignoring all cache for dataSourceId: {}", cacheKey);
                // Clear L2 PostgreSQL cache on refresh to prevent stale/incomplete data
                clearPostgresCache(dataSourceId);
            }

            // === L2: PostgreSQL persistent cache ===
            if (!refresh) {
                Map<String, List<Table>> pgCached = loadTablesFromPostgresCache(dataSourceId);
                if (pgCached != null && !pgCached.isEmpty()) {
                    int pgTotal = pgCached.values().stream().mapToInt(List::size).sum();
                    if (pgTotal > 0) {
                        // Populate L1 from L2
                        synchronized (allTablesCache) {
                            allTablesCache.put(cacheKey, new CachedTablesAndViewsResult(pgCached, System.currentTimeMillis()));
                        }
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("L2 (PostgreSQL) cache hit for dataSourceId: {} in {}ms ({} schemas, {} tables/views)",
                            cacheKey, duration, pgCached.size(), pgTotal);
                        return DataResult.of(pgCached);
                    }
                }
            }

            // === L3: Live database query (cache miss) ===
            Connection connection = InqueryContext.getConnection();
            if (connection == null) {
                log.error("No database connection available for loading all tables and views");
                return DataResult.error("NO_CONNECTION", "No database connection available");
            }

            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            String dbType = connectInfo != null ? connectInfo.getDbType() : null;
            log.info("Loading all tables and views for dataSourceId: {}, dbType: {}", dataSourceId, dbType);

            // Query INFORMATION_SCHEMA.TABLES for all tables and views in all databases
            Map<String, List<Table>> resultMap = new HashMap<>();

            // Get database list using SHOW DATABASES (works even without a current database)
            List<String> databases = new ArrayList<>();
            boolean isMySQL = "MYSQL".equalsIgnoreCase(dbType);
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    // MySQL returns "Database" column, Snowflake returns "name"
                    String dbName = isMySQL ? rs.getString("Database") : rs.getString("name");
                    // Skip system databases
                    if (dbName != null 
                        && !dbName.equalsIgnoreCase("SNOWFLAKE")
                        && !dbName.equalsIgnoreCase("SNOWFLAKE_SAMPLE_DATA")
                        && !dbName.equalsIgnoreCase("information_schema")
                        && !dbName.equalsIgnoreCase("mysql")
                        && !dbName.equalsIgnoreCase("performance_schema")
                        && !dbName.equalsIgnoreCase("sys")) {
                        databases.add(dbName);
                    }
                }
            } catch (SQLException e) {
                log.warn("Cannot get database list using SHOW DATABASES, trying getCatalogs: {}", e.getMessage());
                // Fallback: use getCatalogs()
                try {
                    java.sql.DatabaseMetaData metaData = connection.getMetaData();
                    try (ResultSet catalogs = metaData.getCatalogs()) {
                        while (catalogs.next()) {
                            String dbName = catalogs.getString("TABLE_CAT");
                            if (dbName != null 
                                && !dbName.equalsIgnoreCase("SNOWFLAKE")
                                && !dbName.equalsIgnoreCase("SNOWFLAKE_SAMPLE_DATA")
                                && !dbName.equalsIgnoreCase("information_schema")
                                && !dbName.equalsIgnoreCase("mysql")
                                && !dbName.equalsIgnoreCase("performance_schema")
                                && !dbName.equalsIgnoreCase("sys")) {
                                databases.add(dbName);
                            }
                        }
                    }
                } catch (SQLException e2) {
                    log.error("Cannot get database list: {}", e2.getMessage());
                    return DataResult.error("NO_DATABASE", "Cannot get database list: " + e2.getMessage());
                }
            }

            if (databases.isEmpty()) {
                log.error("No databases found");
                return DataResult.error("NO_DATABASE", "No databases found");
            }

            // NOTE:
            // Snowflake JDBC connections are not safe to share across concurrent statements.
            // If we reuse a single connection across parallel tasks, intermittent failures can occur.
            // So we create a per-database ConnectInfo (different pool key) and also limit concurrency.
            log.info("Found {} databases, querying INFORMATION_SCHEMA.TABLES in parallel (limited concurrency, per-db connection)", databases.size());

            // Query each database's INFORMATION_SCHEMA.TABLES in parallel
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            ConnectInfo baseConnectInfo = InqueryContext.getConnectInfo();
            if (baseConnectInfo == null) {
                log.error("No ConnectInfo available for loading all tables and views");
                return DataResult.error("NO_CONNECTION", "No ConnectInfo available");
            }

            final boolean finalIsMySQL = isMySQL;
            final boolean finalIsBigQuery = "BIGQUERY".equalsIgnoreCase(dbType);
            int maxParallelism = Math.min(4, databases.size());
            ExecutorService executor = Executors.newFixedThreadPool(maxParallelism);
            try {
                for (String databaseName : databases) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        Connection dbConnection = null;
                        // Create per-db ConnectInfo so pool key differs by databaseName
                        ConnectInfo taskConnectInfo = baseConnectInfo.copy();
                        taskConnectInfo.setDatabase(databaseName);
                        taskConnectInfo.setSchemaName(null);
                        try {
                            // Set ConnectInfo in ThreadLocal for this async task (required by ConnectionPool/SQLExecutor reconnect)
                            InqueryContext.putContext(taskConnectInfo);
                            // Get a per-db connection from pool
                            dbConnection = ConnectionPool.getConnection(taskConnectInfo);

                            if (dbConnection == null) {
                                log.error("Failed to get connection for database: {}", databaseName);
                                return;
                            }

                            // BigQuery: Use plugin system (project.INFORMATION_SCHEMA.TABLES not supported)
                            if (finalIsBigQuery) {
                                try {
                                    // Get MetaData from plugin
                                    MetaData metaData = InqueryContext.getMetaData();
                                    
                                    // Get all datasets (schemas) in the project
                                    List<Schema> schemas = metaData.schemas(dbConnection, databaseName);
                                    log.info("BigQuery: Found {} datasets in project {}", schemas.size(), databaseName);
                                    
                                    // For each dataset, get tables
                                    for (Schema schema : schemas) {
                                        String schemaName = schema.getName();
                                        List<ai.inquery.spi.model.Table> schemaTables = metaData.tables(dbConnection, databaseName, schemaName, null);
                                        
                                        if (schemaTables != null && !schemaTables.isEmpty()) {
                                            String schemaKey = String.format("%s.%s", databaseName, schemaName);
                                            
                                            synchronized (resultMap) {
                                                List<Table> tables = resultMap.computeIfAbsent(schemaKey, k -> new ArrayList<>());
                                                for (ai.inquery.spi.model.Table t : schemaTables) {
                                                    Table table = Table.builder()
                                                        .name(t.getName())
                                                        .databaseName(databaseName)
                                                        .schemaName(schemaName)
                                                        .type(t.getType())
                                                        .comment(t.getComment())
                                                        .build();
                                                    tables.add(table);
                                                }
                                            }
                                            log.debug("BigQuery: Loaded {} tables from {}.{}", schemaTables.size(), databaseName, schemaName);
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("BigQuery: Failed to load tables for project {}: {}", databaseName, e.getMessage());
                                }
                                return;
                            }

                            // Build SQL based on database type
                            String sql;
                            
                            if (finalIsMySQL) {
                                // MySQL: Use backticks and query from INFORMATION_SCHEMA directly
                                // MySQL's INFORMATION_SCHEMA.TABLES uses TABLE_SCHEMA as database name
                                sql = String.format(
                                    "SELECT " +
                                    "  TABLE_SCHEMA AS TABLE_CATALOG, " +
                                    "  TABLE_SCHEMA, " +
                                    "  TABLE_NAME, " +
                                    "  TABLE_TYPE, " +
                                    "  TABLE_COMMENT AS COMMENT " +
                                    "FROM INFORMATION_SCHEMA.TABLES " +
                                    "WHERE TABLE_SCHEMA = '%s' " +
                                    "ORDER BY TABLE_SCHEMA, TABLE_NAME",
                                    databaseName
                                );
                            } else {
                                // Snowflake: Use double quotes for identifiers
                                // Quote database name with double quotes to handle special characters ($, @, . etc)
                                sql = String.format(
                                    "SELECT " +
                                    "  TABLE_CATALOG, " +
                                    "  TABLE_SCHEMA, " +
                                    "  TABLE_NAME, " +
                                    "  TABLE_TYPE, " +
                                    "  COMMENT " +
                                    "FROM \"%s\".INFORMATION_SCHEMA.TABLES " +
                                    "WHERE TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA') " +
                                    "ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME",
                                    databaseName
                                );
                            }

                            SQLExecutor.getInstance().execute(dbConnection, sql, resultSet -> {
                                try {
                                    synchronized (resultMap) {
                                        while (resultSet.next()) {
                                            String dbName = resultSet.getString("TABLE_CATALOG");
                                            String schemaName = resultSet.getString("TABLE_SCHEMA");
                                            String tableName = resultSet.getString("TABLE_NAME");
                                            String tableType = resultSet.getString("TABLE_TYPE");
                                            String comment = resultSet.getString("COMMENT");

                                            // Build key: "database.schema" (for MySQL, database = schema)
                                            String schemaKey = finalIsMySQL 
                                                ? databaseName  // MySQL: just use database name as key
                                                : String.format("%s.%s", dbName, schemaName);

                                            // Get or create table list for this schema
                                            List<Table> tables = resultMap.computeIfAbsent(schemaKey, k -> new ArrayList<>());

                                            // Create table object
                                            Table table = Table.builder()
                                                .name(tableName)
                                                .databaseName(finalIsMySQL ? databaseName : dbName)
                                                .schemaName(finalIsMySQL ? null : schemaName)
                                                .type(tableType)
                                                .comment(comment)
                                                .build();

                                            tables.add(table);
                                        }
                                    }
                                } catch (SQLException e) {
                                    throw new RuntimeException("Error reading table/view information from INFORMATION_SCHEMA.TABLES", e);
                                }
                                return null;
                            });

                            log.debug("Successfully queried tables for database: {}", databaseName);
                        } catch (Exception e) {
                            log.warn("Failed to query tables for database {}: {}", databaseName, e.getMessage());
                        } finally {
                            // Clear ThreadLocal after use
                            InqueryContext.removeContext();
                            // Note: We don't close the connection here as it's managed by ConnectionPool
                        }
                    }, executor);

                    futures.add(future);
                }

                // Wait for all parallel queries to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }

            log.info("Completed parallel queries for all {} databases", databases.size());

            int totalTables = resultMap.values().stream().mapToInt(List::size).sum();
            // Store in L1 (in-memory) cache ONLY if we got a non-empty result.
            if (totalTables > 0) {
                synchronized (allTablesCache) {
                    allTablesCache.put(cacheKey, new CachedTablesAndViewsResult(resultMap, System.currentTimeMillis()));
                }
                // Also persist to L2 (PostgreSQL) cache for fast recovery after server restart
                saveTablesViewsToPostgresCache(dataSourceId, resultMap);
            } else {
                log.warn("Skip caching empty tables/views result for dataSourceId: {}", cacheKey);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("L3 (live DB) loaded all tables/views for dataSourceId: {} in {}ms ({} schemas, {} total tables/views)",
                cacheKey, duration, resultMap.size(), totalTables);

            return DataResult.of(resultMap);
        } catch (Exception e) {
            log.error("Error loading all tables and views for dataSourceId: {}", dataSourceId, e);
            return DataResult.error("LOAD_ERROR", "Failed to load all tables and views: " + e.getMessage());
        }
    }

    /**
     * Read tables/views from PostgreSQL cache (L2 cache).
     * Reads from schema_meta_cache + table_meta_cache tables.
     * Returns null if cache is expired (older than L2_CACHE_TTL_MS) to trigger L3 refresh.
     */
    private Map<String, List<Table>> loadTablesFromPostgresCache(Long dataSourceId) {
        try {
            Long userId = ContextUtils.getUserId();
            SchemaMetaCacheMapper schemaMapper = Dbutils.getMapper(SchemaMetaCacheMapper.class);
            TableMetaCacheMapper tableMapper = Dbutils.getMapper(TableMetaCacheMapper.class);

            // Check L2 cache freshness using schema_meta_cache.gmt_modified
            List<SchemaMetaCacheDO> schemas = schemaMapper.findAllByDataSourceId(dataSourceId, userId);
            if (schemas == null || schemas.isEmpty()) {
                return null;
            }

            // Find the newest gmt_modified across all schemas
            Date newestModified = schemas.stream()
                .map(SchemaMetaCacheDO::getGmtModified)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);

            if (newestModified != null) {
                long age = System.currentTimeMillis() - newestModified.getTime();
                if (age > L2_CACHE_TTL_MS) {
                    log.info("L2 (PostgreSQL) cache expired for dataSourceId: {} (age: {}ms, TTL: {}ms)",
                        dataSourceId, age, L2_CACHE_TTL_MS);
                    return null;  // Skip stale L2 cache, fall through to L3
                }
            }

            // Cache is fresh, load table data
            List<ai.inquery.server.domain.repository.entity.TableSearchResultDO> rows =
                tableMapper.findAllByDataSourceId(dataSourceId, userId);

            if (rows == null || rows.isEmpty()) {
                return null;
            }

            Map<String, List<Table>> result = new HashMap<>();
            for (var row : rows) {
                String schemaKey = row.getSchemaName() != null
                    ? String.format("%s.%s", row.getDatabaseName(), row.getSchemaName())
                    : row.getDatabaseName();

                result.computeIfAbsent(schemaKey, k -> new ArrayList<>()).add(
                    Table.builder()
                        .name(row.getTableName())
                        .databaseName(row.getDatabaseName())
                        .schemaName(row.getSchemaName())
                        .type(row.getTableType())
                        .comment(row.getComment())
                        .build()
                );
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read from PostgreSQL cache for dataSourceId {}: {}", dataSourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Clear all PostgreSQL L2 cache entries for a data source.
     * Deletes column_meta_cache, table_meta_cache, and schema_meta_cache (in order to respect references).
     */
    private void clearPostgresCache(Long dataSourceId) {
        try {
            Long userId = ContextUtils.getUserId();
            SchemaMetaCacheMapper schemaMapper = Dbutils.getMapper(SchemaMetaCacheMapper.class);
            TableMetaCacheMapper tableMapper = Dbutils.getMapper(TableMetaCacheMapper.class);
            ColumnMetaCacheMapper columnMapper = Dbutils.getMapper(ColumnMetaCacheMapper.class);

            // Find all schema cache entries for this data source
            List<SchemaMetaCacheDO> schemas = schemaMapper.findAllByDataSourceId(dataSourceId, userId);
            if (schemas != null && !schemas.isEmpty()) {
                for (SchemaMetaCacheDO schema : schemas) {
                    // Delete columns first (deepest child records)
                    columnMapper.deleteBySchemaCacheId(schema.getId());
                    // Delete tables (child records)
                    tableMapper.deleteBySchemaCacheId(schema.getId());
                }
            }
            // Delete all schema entries
            schemaMapper.deleteByDataSourceId(dataSourceId, userId);
            log.info("Cleared PostgreSQL L2 cache for dataSourceId: {} ({} schema entries removed)",
                dataSourceId, schemas != null ? schemas.size() : 0);
        } catch (Exception e) {
            log.warn("Failed to clear PostgreSQL cache for dataSourceId {}: {}", dataSourceId, e.getMessage());
        }
    }

    /**
     * Save tables/views to PostgreSQL cache (L2 cache).
     * Delegates to saveSchemaTablesCache per schema entry.
     */
    private void saveTablesViewsToPostgresCache(Long dataSourceId, Map<String, List<Table>> resultMap) {
        try {
            for (Map.Entry<String, List<Table>> entry : resultMap.entrySet()) {
                String schemaKey = entry.getKey();
                List<Table> tables = entry.getValue();
                if (tables == null || tables.isEmpty()) continue;

                String databaseName;
                String schemaName;
                int dotIndex = schemaKey.indexOf('.');
                if (dotIndex > 0) {
                    databaseName = schemaKey.substring(0, dotIndex);
                    schemaName = schemaKey.substring(dotIndex + 1);
                } else {
                    databaseName = schemaKey;
                    schemaName = null;
                }

                saveSchemaTablesCache(dataSourceId, databaseName, schemaName, tables);
            }
            log.info("Saved tables/views to PostgreSQL L2 cache for dataSourceId: {}", dataSourceId);
        } catch (Exception e) {
            log.warn("Failed to save to PostgreSQL cache for dataSourceId {}: {}", dataSourceId, e.getMessage());
        }
    }

    public DataResult<List<ai.inquery.spi.model.Table>> loadTablesBySchema(TableQueryParam param) {
        String databaseName = param.getDatabaseName();
        String schemaName = param.getSchemaName();
        boolean refresh = param.isRefresh();
        
        if (databaseName == null) {
            return DataResult.error("INVALID_PARAM", "databaseName is required");
        }

        // L2: Check PostgreSQL cache first (skip on explicit refresh)
        if (!refresh) {
            List<Table> fromPg = loadTablesBySchemaFromPostgresCache(param.getDataSourceId(), databaseName, schemaName);
            if (fromPg != null && !fromPg.isEmpty()) {
                log.info("L2 (PostgreSQL) cache hit for tables: dataSourceId={}, database={}, schema={}, count={}",
                    param.getDataSourceId(), databaseName, schemaName, fromPg.size());
                return DataResult.of(fromPg);
            }
        }

        // L3: Live DB query
        try {
            Connection conn = InqueryContext.getConnection();
            if (conn == null) {
                log.error("No connection available for loadTablesBySchema");
                return DataResult.error("NO_CONNECTION", "No database connection available");
            }
            
            MetaData metaData = InqueryContext.getMetaData();
            if (metaData == null) {
                log.error("No MetaData plugin available");
                return DataResult.error("NO_METADATA", "No MetaData plugin available for this database type");
            }
            
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            String dbType = connectInfo != null ? connectInfo.getDbType() : "UNKNOWN";
            
            log.info("L3 (live DB) loadTablesBySchema (dbType={}): database={}, schema={}", 
                    dbType, databaseName, schemaName);
            
            List<Table> tables = metaData.tables(conn, databaseName, schemaName, null);
            
            log.info("L3 loaded {} tables/views for {}.{} (dbType={})", 
                    tables != null ? tables.size() : 0, databaseName, schemaName, dbType);
            
            // Save to L2 (PostgreSQL) cache for cross-page reuse
            if (tables != null && !tables.isEmpty()) {
                try {
                    saveSchemaTablesCache(param.getDataSourceId(), databaseName, schemaName, tables);
                } catch (Exception cacheEx) {
                    log.warn("Failed to save L2 cache for {}.{}: {}", databaseName, schemaName, cacheEx.getMessage());
                }
            }
            
            return DataResult.of(tables != null ? tables : new ArrayList<>());
            
        } catch (Exception e) {
            log.error("Failed to load tables by schema: {}.{}", databaseName, schemaName, e);
            return DataResult.error("LOAD_ERROR", "Failed to load tables: " + e.getMessage());
        }
    }

    /**
     * L2 (PostgreSQL) cache: Load tables for a specific schema from table_meta_cache.
     */
    private List<Table> loadTablesBySchemaFromPostgresCache(Long dataSourceId, String databaseName, String schemaName) {
        try {
            Long userId = ContextUtils.getUserId();
            TableMetaCacheMapper tableMapper = Dbutils.getMapper(TableMetaCacheMapper.class);
            List<ai.inquery.server.domain.repository.entity.TableSearchResultDO> rows =
                tableMapper.findByDataSourceAndSchema(dataSourceId, databaseName, schemaName, userId);

            if (rows == null || rows.isEmpty()) {
                return null;
            }

            List<Table> result = new ArrayList<>();
            for (var row : rows) {
                result.add(Table.builder()
                    .name(row.getTableName())
                    .databaseName(row.getDatabaseName())
                    .schemaName(row.getSchemaName())
                    .type(row.getTableType())
                    .comment(row.getComment())
                    .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("L2 PostgreSQL cache read failed for tables (dataSourceId={}, database={}, schema={}): {}",
                dataSourceId, databaseName, schemaName, e.getMessage());
            return null;
        }
    }

    /**
     * Save tables to L2 (PostgreSQL) cache for a single schema.
     * Shared by loadAllTablesAndViews and loadTablesBySchema.
     */
    private void saveSchemaTablesCache(Long dataSourceId, String databaseName, String schemaName, List<Table> tables) {
        try {
            Long userId = ContextUtils.getUserId();
            SchemaMetaCacheMapper schemaMapper = Dbutils.getMapper(SchemaMetaCacheMapper.class);
            TableMetaCacheMapper tableMapper = Dbutils.getMapper(TableMetaCacheMapper.class);

            SchemaMetaCacheDO existing = schemaMapper.findByKey(dataSourceId, databaseName, schemaName, userId);
            if (existing != null) {
                List<TableMetaCacheDO> existingTables = tableMapper.findBySchemaCacheId(existing.getId());
                if (existingTables != null && !existingTables.isEmpty() && existingTables.size() >= tables.size()) {
                    return;
                }
                if (existingTables != null && !existingTables.isEmpty()) {
                    ColumnMetaCacheMapper columnMapper = Dbutils.getMapper(ColumnMetaCacheMapper.class);
                    columnMapper.deleteBySchemaCacheId(existing.getId());
                    tableMapper.deleteBySchemaCacheId(existing.getId());
                }
            }

            Long schemaCacheId;
            if (existing != null) {
                schemaCacheId = existing.getId();
            } else {
                SchemaMetaCacheDO schemaDO = new SchemaMetaCacheDO();
                schemaDO.setDataSourceId(dataSourceId);
                schemaDO.setDatabaseName(databaseName);
                schemaDO.setSchemaName(schemaName);
                schemaDO.setUserId(userId);
                schemaDO.setGmtCreate(new Date());
                schemaDO.setGmtModified(new Date());
                schemaMapper.insert(schemaDO);
                schemaCacheId = schemaDO.getId();
            }

            List<TableMetaCacheDO> tableDOs = new ArrayList<>();
            for (Table t : tables) {
                TableMetaCacheDO tdo = new TableMetaCacheDO();
                tdo.setSchemaCacheId(schemaCacheId);
                tdo.setTableName(t.getName());
                tdo.setTableType(t.getType());
                tdo.setComment(t.getComment());
                tableDOs.add(tdo);
            }
            if (!tableDOs.isEmpty()) {
                tableMapper.batchInsert(tableDOs);
            }
            log.info("Saved L2 cache for {}.{} ({} tables)", databaseName, schemaName, tables.size());
        } catch (Exception e) {
            log.warn("Failed to save L2 cache for {}.{}: {}", databaseName, schemaName, e.getMessage());
        }
    }

    @Override
    public DataResult<List<ai.inquery.spi.model.TableColumn>> loadColumnsByTable(TableQueryParam param) {
        String databaseName = param.getDatabaseName();
        String schemaName = param.getSchemaName();
        String tableName = param.getTableName();
        
        if (databaseName == null || tableName == null) {
            return DataResult.error("INVALID_PARAM", "databaseName and tableName are required");
        }

        try {
            Connection conn = InqueryContext.getConnection();
            if (conn == null) {
                log.error("No connection available for loadColumnsByTable");
                return DataResult.error("NO_CONNECTION", "No database connection available");
            }
            
            // Use plugin system to get columns (handles DB-specific SQL automatically)
            MetaData metaData = InqueryContext.getMetaData();
            if (metaData == null) {
                log.error("No MetaData plugin available");
                return DataResult.error("NO_METADATA", "No MetaData plugin available for this database type");
            }
            
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            String dbType = connectInfo != null ? connectInfo.getDbType() : "UNKNOWN";
            
            log.info("loadColumnsByTable using plugin system (dbType={}): database={}, schema={}, table={}", 
                    dbType, databaseName, schemaName, tableName);
            
            // Call plugin's columns() method - each DB plugin handles its own SQL
            List<TableColumn> columns = metaData.columns(conn, databaseName, schemaName, tableName);
            
            log.info("Loaded {} columns for {}.{}.{} (dbType={})", 
                    columns != null ? columns.size() : 0, databaseName, schemaName, tableName, dbType);
            
            return DataResult.of(columns != null ? columns : new ArrayList<>());
            
        } catch (Exception e) {
            log.error("Failed to load columns by table: {}.{}.{}", databaseName, schemaName, tableName, e);
            return DataResult.error("LOAD_ERROR", "Failed to load columns: " + e.getMessage());
        }
    }

    @Override
    public DataResult<Map<String, Boolean>> getTableActiveStates(Long dataSourceId, String databaseName, String schemaName) {
        Map<String, Boolean> result = new HashMap<>();
        
        try {
            LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId);
            if (StringUtils.isNotBlank(databaseName)) {
                wrapper.eq(DataCatalogTableDO::getDatabaseName, databaseName);
            }
            if (StringUtils.isNotBlank(schemaName)) {
                wrapper.eq(DataCatalogTableDO::getSchemaName, schemaName);
            }
            
            List<DataCatalogTableDO> tables = getTableMapper().selectList(wrapper);
            
            for (DataCatalogTableDO table : tables) {
                // Build key: "database.schema.table"
                String key = String.format("%s.%s.%s",
                    table.getDatabaseName(),
                    table.getSchemaName() != null ? table.getSchemaName() : "PUBLIC",
                    table.getTableName());
                // Default to true if active is null, convert Short to Boolean
                result.put(key, table.getActive() == null || table.getActive() == 1);
            }
            
            log.info("Retrieved active states for {} tables (dataSourceId: {}, db: {}, schema: {})", 
                result.size(), dataSourceId, databaseName, schemaName);
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Error getting table active states", e);
            return DataResult.error("GET_ACTIVE_STATES_ERROR", "Failed to get active states: " + e.getMessage());
        }
    }

    @Override
    public ActionResult saveTableActiveState(Long dataSourceId, String databaseName, String schemaName, String tableName, Boolean active) {
        try {
            Long userId = ContextUtils.getUserId();
            
            // Find existing record
            LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId)
                    .eq(DataCatalogTableDO::getDatabaseName, databaseName)
                    .eq(StringUtils.isNotBlank(schemaName), DataCatalogTableDO::getSchemaName, schemaName)
                    .eq(DataCatalogTableDO::getTableName, tableName);
            
            DataCatalogTableDO tableDO = getTableMapper().selectOne(wrapper);
            
            if (tableDO == null) {
                // Create new record
                tableDO = new DataCatalogTableDO();
                tableDO.setDataSourceId(dataSourceId);
                tableDO.setDatabaseName(databaseName);
                tableDO.setSchemaName(schemaName);
                tableDO.setTableName(tableName);
                // Convert Boolean to Short for PostgreSQL SMALLINT compatibility
                tableDO.setActive(active != null && active ? (short) 1 : (short) 0);
                tableDO.setUserId(userId);
                tableDO.setGmtCreate(new Date());
                tableDO.setGmtModified(new Date());
                getTableMapper().insert(tableDO);
                log.info("Created new table catalog with active={} for {}.{}.{}", active, databaseName, schemaName, tableName);
            } else {
                // Update existing record - Convert Boolean to Short for PostgreSQL SMALLINT compatibility
                tableDO.setActive(active != null && active ? (short) 1 : (short) 0);
                tableDO.setGmtModified(new Date());
                getTableMapper().updateById(tableDO);
                log.info("Updated table active state to {} for {}.{}.{}", active, databaseName, schemaName, tableName);
            }
            
            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("Error saving table active state", e);
            return ActionResult.fail("SAVE_ACTIVE_STATE_ERROR", "Failed to save active state: " + e.getMessage(), e.toString());
        }
    }

    @Override
    public ActionResult batchSaveTableActiveStates(Long dataSourceId, Map<String, Boolean> activeStates) {
        if (activeStates == null || activeStates.isEmpty()) {
            return ActionResult.isSuccess();
        }
        
        try {
            Long userId = ContextUtils.getUserId();
            int updated = 0;
            int created = 0;
            
            for (Map.Entry<String, Boolean> entry : activeStates.entrySet()) {
                String tableKey = entry.getKey();
                Boolean active = entry.getValue();
                
                // Parse table key: "database.schema.table"
                String[] parts = tableKey.split("\\.");
                if (parts.length < 3) {
                    log.warn("Invalid table key format: {} (expected database.schema.table)", tableKey);
                    continue;
                }
                
                String databaseName = parts[0];
                String schemaName = parts[1];
                String tableName = parts[2];
                // Handle case where table name contains dots
                if (parts.length > 3) {
                    tableName = String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                }
                
                // Find existing record
                LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId)
                        .eq(DataCatalogTableDO::getDatabaseName, databaseName)
                        .eq(DataCatalogTableDO::getSchemaName, schemaName)
                        .eq(DataCatalogTableDO::getTableName, tableName);
                
                DataCatalogTableDO tableDO = getTableMapper().selectOne(wrapper);
                
                if (tableDO == null) {
                    // Create new record
                    tableDO = new DataCatalogTableDO();
                    tableDO.setDataSourceId(dataSourceId);
                    tableDO.setDatabaseName(databaseName);
                    tableDO.setSchemaName(schemaName);
                    tableDO.setTableName(tableName);
                    // Convert Boolean to Short for PostgreSQL SMALLINT compatibility
                    tableDO.setActive(active != null && active ? (short) 1 : (short) 0);
                    tableDO.setUserId(userId);
                    tableDO.setGmtCreate(new Date());
                    tableDO.setGmtModified(new Date());
                    getTableMapper().insert(tableDO);
                    created++;
                } else {
                    // Update existing record - Convert Boolean to Short for PostgreSQL SMALLINT compatibility
                    tableDO.setActive(active != null && active ? (short) 1 : (short) 0);
                    tableDO.setGmtModified(new Date());
                    getTableMapper().updateById(tableDO);
                    updated++;
                }
            }
            
            log.info("Batch saved table active states: {} created, {} updated (dataSourceId: {})", 
                created, updated, dataSourceId);
            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("Error batch saving table active states", e);
            return ActionResult.fail("BATCH_SAVE_ERROR", "Failed to batch save active states: " + e.getMessage(), e.toString());
        }
    }

    @Override
    public DataResult<List<String>> getActiveTables(Long dataSourceId, String databaseName, String schemaName) {
        List<String> result = new ArrayList<>();
        
        try {
            LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId);
            if (StringUtils.isNotBlank(databaseName)) {
                wrapper.eq(DataCatalogTableDO::getDatabaseName, databaseName);
            }
            if (StringUtils.isNotBlank(schemaName)) {
                wrapper.eq(DataCatalogTableDO::getSchemaName, schemaName);
            }
            // Only get tables where active is 1 (true) or null (default active)
            wrapper.and(w -> w.eq(DataCatalogTableDO::getActive, (short) 1).or().isNull(DataCatalogTableDO::getActive));
            
            List<DataCatalogTableDO> tables = getTableMapper().selectList(wrapper);
            
            for (DataCatalogTableDO table : tables) {
                result.add(table.getTableName());
            }
            
            log.info("Retrieved {} active tables (dataSourceId: {}, db: {}, schema: {})", 
                result.size(), dataSourceId, databaseName, schemaName);
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Error getting active tables", e);
            return DataResult.error("GET_ACTIVE_TABLES_ERROR", "Failed to get active tables: " + e.getMessage());
        }
    }

    @Override
    public DataResult<Map<String, String>> findTableByName(Long dataSourceId, String tableName) {
        if (dataSourceId == null || StringUtils.isBlank(tableName)) {
            return DataResult.error("INVALID_PARAM", "dataSourceId and tableName are required");
        }
        
        try {
            // Search by dataSourceId and tableName (case-insensitive)
            LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId)
                    .apply("UPPER(table_name) = {0}", tableName.toUpperCase())
                    .last("LIMIT 1");
            
            DataCatalogTableDO tableDO = getTableMapper().selectOne(wrapper);
            
            if (tableDO != null) {
                log.info("Found table by name: {} -> {}.{}.{} (dataSourceId: {})", 
                    tableName, tableDO.getDatabaseName(), tableDO.getSchemaName(), tableDO.getTableName(), dataSourceId);
                
                Map<String, String> result = new HashMap<>();
                result.put("databaseName", tableDO.getDatabaseName());
                result.put("schemaName", tableDO.getSchemaName());
                result.put("tableName", tableDO.getTableName());
                result.put("tableDescription", tableDO.getTableDescription());
                return DataResult.of(result);
            } else {
                log.info("Table not found in Data Catalog: {} (dataSourceId: {})", tableName, dataSourceId);
                return DataResult.empty();
            }
        } catch (Exception e) {
            log.error("Error finding table by name: {} (dataSourceId: {})", tableName, dataSourceId, e);
            return DataResult.error("FIND_TABLE_ERROR", "Failed to find table: " + e.getMessage());
        }
    }

    // collectAIMetadataWithProgress removed - frontend now uses existing APIs directly:
    // 1. collectAIMetadata() - get AI descriptions
    // 2. collectExampleValues() - get example values
    // 3. saveCatalog() - save to DB (this triggers vector embedding update automatically)
}

