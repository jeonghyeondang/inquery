package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.model.SchemaMetaCache;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheParam;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheSaveParam;
import ai.inquery.server.domain.api.service.SchemaMetaCacheService;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.ColumnMetaCacheDO;
import ai.inquery.server.domain.repository.entity.SchemaMetaCacheDO;
import ai.inquery.server.domain.repository.entity.TableMetaCacheDO;
import ai.inquery.server.domain.repository.mapper.ColumnMetaCacheMapper;
import ai.inquery.server.domain.repository.mapper.SchemaMetaCacheMapper;
import ai.inquery.server.domain.repository.mapper.TableMetaCacheMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Schema metadata cache service implementation
 */
@Slf4j
@Service
public class SchemaMetaCacheServiceImpl implements SchemaMetaCacheService {

    // Track in-progress saves to prevent duplicate concurrent requests
    private static final Set<String> savingInProgress = ConcurrentHashMap.newKeySet();

    private static String buildCacheKey(Long dataSourceId, String databaseName, String schemaName, Long userId) {
        return String.format("%d:%s:%s:%d", dataSourceId, databaseName, schemaName, userId);
    }

    private SchemaMetaCacheMapper getSchemaMapper() {
        return Dbutils.getMapper(SchemaMetaCacheMapper.class);
    }

    private TableMetaCacheMapper getTableMapper() {
        return Dbutils.getMapper(TableMetaCacheMapper.class);
    }

    private ColumnMetaCacheMapper getColumnMapper() {
        return Dbutils.getMapper(ColumnMetaCacheMapper.class);
    }

    @Override
    public DataResult<SchemaMetaCache> getCache(SchemaMetaCacheParam param) {
        Long userId = ContextUtils.getUserId();
        SchemaMetaCacheDO schemaCacheDO = getSchemaMapper().findByKey(
            param.getDataSourceId(),
            param.getDatabaseName(),
            param.getSchemaName(),
            userId
        );

        if (schemaCacheDO == null) {
            return DataResult.empty();
        }

        // Load tables
        List<TableMetaCacheDO> tableDOs = getTableMapper().findBySchemaCacheId(schemaCacheDO.getId());
        
        // Load all columns for this schema
        List<ColumnMetaCacheDO> allColumns = getColumnMapper().findBySchemaCacheId(schemaCacheDO.getId());
        
        // Group columns by tableId
        Map<Long, List<ColumnMetaCacheDO>> columnsByTableId = allColumns.stream()
            .collect(Collectors.groupingBy(ColumnMetaCacheDO::getTableCacheId));

        // Build result
        List<SchemaMetaCache.TableMetaCache> tables = tableDOs.stream()
            .map(tableDO -> {
                List<ColumnMetaCacheDO> tableColumns = columnsByTableId.getOrDefault(tableDO.getId(), Collections.emptyList());
                return SchemaMetaCache.TableMetaCache.builder()
                    .id(tableDO.getId())
                    .tableName(tableDO.getTableName())
                    .tableType(tableDO.getTableType())
                    .comment(tableDO.getComment())
                    .columns(tableColumns.stream()
                        .map(col -> SchemaMetaCache.ColumnMetaCache.builder()
                            .id(col.getId())
                            .columnName(col.getColumnName())
                            .dataType(col.getDataType())
                            // Convert Short to Boolean for API response
                            .isPrimaryKey(col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1)
                            .isNullable(col.getIsNullable() != null && col.getIsNullable() == 1)
                            .comment(col.getComment())
                            .ordinalPosition(col.getOrdinalPosition())
                            .build())
                        .collect(Collectors.toList()))
                    .build();
            })
            .collect(Collectors.toList());

        SchemaMetaCache result = SchemaMetaCache.builder()
            .id(schemaCacheDO.getId())
            .dataSourceId(schemaCacheDO.getDataSourceId())
            .databaseName(schemaCacheDO.getDatabaseName())
            .schemaName(schemaCacheDO.getSchemaName())
            .gmtModified(schemaCacheDO.getGmtModified())
            .tables(tables)
            .build();

        return DataResult.of(result);
    }

    @Override
    @Transactional
    public ActionResult saveCache(SchemaMetaCacheSaveParam param) {
        Long userId = ContextUtils.getUserId();
        String cacheKey = buildCacheKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), userId);
        
        // Prevent concurrent saves for the same schema
        if (!savingInProgress.add(cacheKey)) {
            return ActionResult.isSuccess();
        }
        
        try {
            // Find or create schema cache entry
            SchemaMetaCacheDO schemaCacheDO = getSchemaMapper().findByKey(
                param.getDataSourceId(),
                param.getDatabaseName(),
                param.getSchemaName(),
                userId
            );

            if (schemaCacheDO != null) {
                // MERGE strategy: update/add incoming tables, PRESERVE existing tables not in the incoming data.
                // This prevents ERD prefetch from accidentally deleting tables that loadAllTablesAndViews cached.
                schemaCacheDO.setGmtModified(new Date());
                getSchemaMapper().updateById(schemaCacheDO);

                if (CollectionUtils.isNotEmpty(param.getTables())) {
                    // Build a lookup map of existing tables by name
                    List<TableMetaCacheDO> existingTables = getTableMapper().findBySchemaCacheId(schemaCacheDO.getId());
                    Map<String, TableMetaCacheDO> existingByName = new HashMap<>();
                    if (existingTables != null) {
                        for (TableMetaCacheDO t : existingTables) {
                            existingByName.put(t.getTableName(), t);
                        }
                    }

                    for (SchemaMetaCacheSaveParam.TableInfo tableInfo : param.getTables()) {
                        TableMetaCacheDO existingTable = existingByName.get(tableInfo.getTableName());

                        if (existingTable != null) {
                            // Table already exists: update its columns (delete old columns, insert new ones)
                            getColumnMapper().deleteByTableCacheId(existingTable.getId());

                            // Update table metadata
                            existingTable.setTableType(tableInfo.getTableType());
                            existingTable.setComment(tableInfo.getComment());
                            existingTable.setGmtModified(new Date());
                            getTableMapper().updateById(existingTable);

                            // Insert new columns
                            insertColumns(existingTable.getId(), tableInfo.getColumns());
                        } else {
                            // New table: insert table + columns
                            TableMetaCacheDO tableDO = createTableDO(schemaCacheDO.getId(), tableInfo);
                            getTableMapper().insert(tableDO);
                            insertColumns(tableDO.getId(), tableInfo.getColumns());
                        }
                    }
                }
            } else {
                // No existing cache — create fresh schema entry + insert all tables
                schemaCacheDO = new SchemaMetaCacheDO();
                schemaCacheDO.setDataSourceId(param.getDataSourceId());
                schemaCacheDO.setDatabaseName(param.getDatabaseName());
                schemaCacheDO.setSchemaName(param.getSchemaName());
                schemaCacheDO.setUserId(userId);
                schemaCacheDO.setGmtCreate(new Date());
                schemaCacheDO.setGmtModified(new Date());
                getSchemaMapper().insert(schemaCacheDO);

                if (CollectionUtils.isNotEmpty(param.getTables())) {
                    for (SchemaMetaCacheSaveParam.TableInfo tableInfo : param.getTables()) {
                        TableMetaCacheDO tableDO = createTableDO(schemaCacheDO.getId(), tableInfo);
                        getTableMapper().insert(tableDO);
                        insertColumns(tableDO.getId(), tableInfo.getColumns());
                    }
                }
            }

            return ActionResult.isSuccess();
        } catch (DataIntegrityViolationException e) {
            // Handle race condition gracefully - another request already saved this cache
            // This can happen when multiple prefetch requests run concurrently
            return ActionResult.isSuccess();
        } catch (Exception e) {
            // Check if it's a constraint violation wrapped in another exception
            if (e.getCause() != null && e.getCause().getMessage() != null 
                && e.getCause().getMessage().contains("Unique index or primary key violation")) {
                return ActionResult.isSuccess();
            }
            log.error("[Schema Cache] Failed to save cache for {}: {}", cacheKey, e.getMessage());
            throw e;
        } finally {
            savingInProgress.remove(cacheKey);
        }
    }

    /**
     * Helper: create a TableMetaCacheDO from param
     */
    private TableMetaCacheDO createTableDO(Long schemaCacheId, SchemaMetaCacheSaveParam.TableInfo tableInfo) {
        TableMetaCacheDO tableDO = new TableMetaCacheDO();
        tableDO.setSchemaCacheId(schemaCacheId);
        tableDO.setTableName(tableInfo.getTableName());
        tableDO.setTableType(tableInfo.getTableType());
        tableDO.setComment(tableInfo.getComment());
        tableDO.setGmtCreate(new Date());
        tableDO.setGmtModified(new Date());
        return tableDO;
    }

    /**
     * Helper: insert columns for a table cache entry
     */
    private void insertColumns(Long tableCacheId, List<SchemaMetaCacheSaveParam.ColumnInfo> columns) {
        if (CollectionUtils.isEmpty(columns)) {
            return;
        }
        for (SchemaMetaCacheSaveParam.ColumnInfo col : columns) {
            ColumnMetaCacheDO columnDO = new ColumnMetaCacheDO();
            columnDO.setTableCacheId(tableCacheId);
            columnDO.setColumnName(col.getColumnName());
            columnDO.setDataType(col.getDataType());
            // Convert Boolean to Short for PostgreSQL SMALLINT compatibility
            columnDO.setIsPrimaryKey(col.getIsPrimaryKey() != null && col.getIsPrimaryKey() ? (short) 1 : (short) 0);
            columnDO.setIsNullable(col.getIsNullable() != null && col.getIsNullable() ? (short) 1 : (short) 0);
            columnDO.setComment(col.getComment());
            columnDO.setOrdinalPosition(col.getOrdinalPosition());
            columnDO.setGmtCreate(new Date());
            columnDO.setGmtModified(new Date());
            getColumnMapper().insert(columnDO);
        }
    }

    @Override
    @Transactional
    public ActionResult invalidateCache(SchemaMetaCacheParam param) {
        Long userId = ContextUtils.getUserId();
        SchemaMetaCacheDO schemaCacheDO = getSchemaMapper().findByKey(
            param.getDataSourceId(),
            param.getDatabaseName(),
            param.getSchemaName(),
            userId
        );

        if (schemaCacheDO != null) {
            getColumnMapper().deleteBySchemaCacheId(schemaCacheDO.getId());
            getTableMapper().deleteBySchemaCacheId(schemaCacheDO.getId());
            getSchemaMapper().deleteById(schemaCacheDO.getId());
        }

        return ActionResult.isSuccess();
    }

    @Override
    @Transactional
    public ActionResult invalidateByDataSource(Long dataSourceId) {
        Long userId = ContextUtils.getUserId();
        
        // Explicitly delete child records first (columns → tables → schemas)
        // to avoid orphaned records since there are no DB-level CASCADE constraints
        List<SchemaMetaCacheDO> schemas = getSchemaMapper().findAllByDataSourceId(dataSourceId, userId);
        if (schemas != null) {
            for (SchemaMetaCacheDO schema : schemas) {
                getColumnMapper().deleteBySchemaCacheId(schema.getId());
                getTableMapper().deleteBySchemaCacheId(schema.getId());
            }
        }
        getSchemaMapper().deleteByDataSourceId(dataSourceId, userId);

        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Boolean> hasValidCache(SchemaMetaCacheParam param) {
        Long userId = ContextUtils.getUserId();
        SchemaMetaCacheDO schemaCacheDO = getSchemaMapper().findByKey(
            param.getDataSourceId(),
            param.getDatabaseName(),
            param.getSchemaName(),
            userId
        );

        return DataResult.of(schemaCacheDO != null);
    }

    @Override
    public DataResult<List<TableLocation>> searchTableByName(Long dataSourceId, String tableName) {
        Long userId = ContextUtils.getUserId();
        
        var results = getTableMapper().searchByTableName(dataSourceId, tableName, userId);
        
        if (CollectionUtils.isEmpty(results)) {
            return DataResult.of(Collections.emptyList());
        }

        List<TableLocation> locations = results.stream()
            .map(r -> new TableLocation(
                r.getDataSourceId(),
                r.getDatabaseName(),
                r.getSchemaName(),
                r.getTableName(),
                r.getTableType()
            ))
            .collect(Collectors.toList());

        return DataResult.of(locations);
    }
}


