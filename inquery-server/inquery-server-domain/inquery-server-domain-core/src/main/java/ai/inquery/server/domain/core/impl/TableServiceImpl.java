package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.enums.TableVectorEnum;
import ai.inquery.server.domain.api.param.*;
import ai.inquery.server.domain.api.service.PinService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.domain.core.cache.CacheManage;
import ai.inquery.server.domain.core.converter.PinTableConverter;
import ai.inquery.server.domain.core.converter.TableConverter;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.TableCacheDO;
import ai.inquery.server.domain.repository.entity.TableCacheVersionDO;
import ai.inquery.server.domain.repository.entity.TableVectorMappingDO;
import ai.inquery.server.domain.repository.mapper.TableCacheMapper;
import ai.inquery.server.domain.repository.mapper.TableCacheVersionMapper;
import ai.inquery.server.domain.repository.mapper.TableVectorMappingMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.model.LoginUser;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.SqlBuilder;
import ai.inquery.spi.enums.EditStatus;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ai.inquery.server.domain.core.cache.CacheKey.getColumnKey;
import static ai.inquery.server.domain.core.cache.CacheKey.getTableKey;

/**
 * @version DataSourceCoreServiceImpl.java, v 0.1 September 23, 2022 15:51 moji Exp $
 */
@Service
@Slf4j
public class TableServiceImpl implements TableService {

    private static final ConcurrentHashMap<String, CompletableFuture<long[]>> CACHE_LOADING = new ConcurrentHashMap<>();

    @Autowired
    private PinService pinService;

    @Autowired
    private PinTableConverter pinTableConverter;


    private TableCacheMapper getTableCacheMapper() {
        return Dbutils.getMapper(TableCacheMapper.class);
    }

    @Autowired
    private TableConverter tableConverter;


    private TableCacheVersionMapper getVersionMapper() {
        return Dbutils.getMapper(TableCacheVersionMapper.class);
    }


    private TableVectorMappingMapper getTableVectorMapper() {
        return Dbutils.getMapper(TableVectorMappingMapper.class);
    }

    @Override
    public DataResult<String> showCreateTable(ShowCreateTableParam param) {
        MetaData metaSchema = InqueryContext.getMetaData();
        String ddl = metaSchema.tableDDL(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        return DataResult.of(ddl);
    }

    @Override
    public ActionResult drop(DropParam param) {
        DBManage metaSchema = InqueryContext.getDBManage();
        metaSchema.dropTable(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchema(), param.getName());
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<String> createTableExample(String dbType) {
        String sql = InqueryContext.getDBConfig().getSimpleCreateTable();
        return DataResult.of(sql);
    }

    @Override
    public DataResult<String> alterTableExample(String dbType) {
        String sql = InqueryContext.getDBConfig().getSimpleAlterTable();
        return DataResult.of(sql);
    }

    @Override
    public DataResult<Table> query(TableQueryParam param, TableSelector selector) {
        MetaData metaSchema = InqueryContext.getMetaData();
        List<Table> tables = metaSchema.tables(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        if (!CollectionUtils.isEmpty(tables)) {
            Table table = tables.get(0);
            table.setIndexList(
                    metaSchema.indexes(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            table.setColumnList(
                    metaSchema.columns(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            setPrimaryKey(table);
            return DataResult.of(table);
        }
        return DataResult.of(null);
    }

    private void setPrimaryKey(Table table) {
        if (table == null) {
            return;
        }
        List<TableIndex> tableIndices = table.getIndexList();
        if (CollectionUtils.isEmpty(tableIndices)) {
            return;
        }
        List<TableColumn> columns = table.getColumnList();
        if (CollectionUtils.isEmpty(columns)) {
            return;
        }
        Map<String, TableColumn> columnMap = columns.stream()
                .collect(Collectors.toMap(TableColumn::getName, Function.identity()));
        List<TableIndex> indexes = new ArrayList<>();
        for (TableIndex tableIndex : tableIndices) {
            if ("Primary".equalsIgnoreCase(tableIndex.getType())) {
                List<TableIndexColumn> indexColumns = tableIndex.getColumnList();
                if (CollectionUtils.isNotEmpty(indexColumns)) {
                    for (TableIndexColumn indexColumn : indexColumns) {
                        TableColumn column = columnMap.get(indexColumn.getColumnName());
                        if (column != null) {
                            column.setPrimaryKey(true);
                            column.setPrimaryKeyOrder(indexColumn.getOrdinalPosition());
                            column.setPrimaryKeyName(tableIndex.getName());
                        }
                    }
                }
            } else {
                indexes.add(tableIndex);
            }
        }
        table.setIndexList(indexes);
    }

    @Override
    public ListResult<Sql> buildSql(Table oldTable, Table newTable) {
        initOldTable(oldTable, newTable);
        SqlBuilder sqlBuilder = InqueryContext.getSqlBuilder();
        List<Sql> sqls = new ArrayList<>();
        if (oldTable == null) {
            initPrimaryKey(newTable);
            sqls.add(Sql.builder().sql(sqlBuilder.buildCreateTableSql(newTable)).build());
        } else {
            initUpdatePrimaryKey(oldTable, newTable);
            sqls.add(Sql.builder().sql(sqlBuilder.buildModifyTaleSql(oldTable, newTable)).build());
        }
        return ListResult.of(sqls);
    }

    private void initUpdatePrimaryKey(Table oldTable, Table newTable) {
        if (newTable == null || oldTable == null) {
            return;
        }
        List<TableColumn> newColumns = getPrimaryKeyColumn(newTable);
        List<TableColumn> oldColumns = getPrimaryKeyColumn(oldTable);
        if (CollectionUtils.isEmpty(newColumns) && CollectionUtils.isEmpty(oldColumns)) {
            return;
        }
        if (!CollectionUtils.isEmpty(newColumns) && CollectionUtils.isEmpty(oldColumns)) {
            initPrimaryKey(newTable);
            return;
        }
        if (CollectionUtils.isEmpty(newColumns) && CollectionUtils.isNotEmpty(oldColumns)) {
            addPrimaryKey(newTable, oldColumns.get(0), EditStatus.DELETE.name());
            return;
        }
        if (newColumns.size() != oldColumns.size()) {
            for (TableColumn column : newColumns) {
                if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                    addPrimaryKey(newTable, column, EditStatus.MODIFY.name());
                }
            }
            return;
        }
        boolean flag = false;
        Map<String, TableColumn> oldColumnMap = oldColumns.stream().collect(Collectors.toMap(TableColumn::getName, Function.identity()));
        for (TableColumn column : newColumns) {
            TableColumn oldColumn = oldColumnMap.get(column.getName());
            if (oldColumn == null) {
                flag = true;
            }
        }
        if (flag) {
            for (TableColumn column : newColumns) {
                if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                    addPrimaryKey(newTable, column, EditStatus.MODIFY.name());
                }
            }
        }
    }

    private List<TableColumn> getPrimaryKeyColumn(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList())) {
            return null;
        }
        return table.getColumnList().stream().filter(tableColumn ->
                        tableColumn.getPrimaryKey() != null && tableColumn.getPrimaryKey())
                .collect(Collectors.toList());
    }

    private void initPrimaryKey(Table newTable) {
        if (newTable == null) {
            return;
        }
        List<TableColumn> columns = newTable.getColumnList();
        if (CollectionUtils.isEmpty(columns)) {
            return;
        }
        for (TableColumn column : columns) {
            if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                addPrimaryKey(newTable, column, EditStatus.ADD.name());
            }
        }
    }

    private void addPrimaryKey(Table newTable, TableColumn column, String status) {
        List<TableIndex> indexes = newTable.getIndexList();
        if (indexes == null) {
            indexes = new ArrayList<>();
        }
        TableIndex keyIndex = indexes.stream().filter(index -> "Primary".equalsIgnoreCase(index.getType())).findFirst().orElse(null);
        if (keyIndex == null) {
            keyIndex = new TableIndex();
            keyIndex.setType("Primary");
            keyIndex.setName(StringUtils.isBlank(column.getPrimaryKeyName()) ? "PRIMARY_KEY" : column.getPrimaryKeyName());
            keyIndex.setTableName(newTable.getName());
            keyIndex.setSchemaName(newTable.getSchemaName());
            keyIndex.setDatabaseName(newTable.getDatabaseName());
            keyIndex.setEditStatus(status);
            if (!EditStatus.ADD.name().equals(status)) {
                keyIndex.setOldName(keyIndex.getName());
            }
            indexes.add(keyIndex);
        }
        List<TableIndexColumn> tableIndexColumns = keyIndex.getColumnList();
        if (tableIndexColumns == null) {
            tableIndexColumns = new ArrayList<>();
        }
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName(column.getName());
        indexColumn.setTableName(newTable.getName());
        indexColumn.setSchemaName(newTable.getSchemaName());
        indexColumn.setDatabaseName(newTable.getDatabaseName());
        indexColumn.setOrdinalPosition(Short.valueOf(column.getPrimaryKeyOrder() + ""));
        indexColumn.setEditStatus(status);
        tableIndexColumns.add(indexColumn);
        List<TableIndexColumn> sortTableIndexColumns = tableIndexColumns.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition)).collect(Collectors.toList());
        Set<String> statusList = sortTableIndexColumns.stream().map(TableIndexColumn::getEditStatus).collect(Collectors.toSet());
        if (statusList.size() == 1) {
            //only one status ,set index status
            keyIndex.setEditStatus(statusList.iterator().next());
        } else {
            //more status ,set index status modify
            keyIndex.setEditStatus(EditStatus.MODIFY.name());
        }

        keyIndex.setColumnList(sortTableIndexColumns);
        newTable.setIndexList(indexes);

    }


    private void initOldTable(Table oldTable, Table newTable) {
        if (oldTable == null || newTable == null) {
            return;
        }
        Map<String, TableColumn> columnMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(oldTable.getColumnList())) {
            for (TableColumn column : oldTable.getColumnList()) {
                columnMap.put(column.getName(), column);
            }
        }
        if (CollectionUtils.isNotEmpty(newTable.getColumnList())) {
            for (TableColumn newColumn : newTable.getColumnList()) {
                if (EditStatus.ADD.name().equals(newColumn.getEditStatus())) {
                    continue;
                }
                String name = newColumn.getOldName() == null ? newColumn.getName() : newColumn.getOldName();
                TableColumn oldColumn = columnMap.get(name);
                if (oldColumn != null) {
                    if (oldColumn.equals(newColumn) && EditStatus.MODIFY.name().equals(newColumn.getEditStatus())) {
                        newColumn.setEditStatus(null);
                    } else {
                        newColumn.setOldColumn(oldColumn);
                    }
                }
            }
        }
    }

    @Override
    public PageResult<Table> pageQuery(TablePageQueryParam param, TableSelector selector) {
        LambdaQueryWrapper<TableCacheVersionDO> queryWrapper = new LambdaQueryWrapper<>();
        String key = getTableKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        queryWrapper.eq(TableCacheVersionDO::getKey, key);
        TableCacheVersionDO versionDO = getVersionMapper().selectOne(queryWrapper);
        long total = 0;
        long version = 0L;
        if (param.isRefresh() || versionDO == null) {
            total = addCache(param, versionDO);
            // After addCache, refresh the versionDO to get the new version
            versionDO = getVersionMapper().selectOne(queryWrapper);
            if (versionDO != null) {
                version = versionDO.getVersion();
            }
        } else {
            if ("2".equals(versionDO.getStatus())) {
                version = versionDO.getVersion() - 1;
            } else {
                version = versionDO.getVersion();
            }
            total = versionDO.getTableCount();
        }
        Page<TableCacheDO> page = new Page<>(param.getPageNo(), param.getPageSize());
        // page.setSearchCount(param.getEnableReturnCount());
        // Pass version to filter only current version records (ensures table_type is populated)
        IPage<TableCacheDO> iPage = getTableCacheMapper().pageQuery(page, param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), param.getSearchKey(), version > 0 ? version : null);
        List<Table> tables = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(iPage.getRecords())) {
            for (TableCacheDO tableCacheDO : iPage.getRecords()) {
                Table t = new Table();
                t.setName(tableCacheDO.getTableName());
                t.setType(tableCacheDO.getTableType());
                t.setComment(tableCacheDO.getExtendInfo());
                t.setSchemaName(tableCacheDO.getSchemaName());
                t.setDatabaseName(tableCacheDO.getDatabaseName());
                tables.add(t);
            }
        }
        if (param.getPageNo() <= 1) {
            tables = pinTable(tables, param);
        }
        return PageResult.of(tables, total, param);
    }

    private long addCache(TablePageQueryParam param, TableCacheVersionDO versionDO) {
        LambdaQueryWrapper<TableCacheVersionDO> queryWrapper = new LambdaQueryWrapper<>();
        String key = getTableKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        queryWrapper.eq(TableCacheVersionDO::getKey, key);
        long version = getLock(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), versionDO);

        if (version != -1) {
            // We got the lock - load cache and notify waiting threads
            CompletableFuture<long[]> future = new CompletableFuture<>();
            CACHE_LOADING.put(key, future);
            try {
                long total = addDBCache(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), version);
                TableCacheVersionDO update = new TableCacheVersionDO();
                update.setStatus("1");
                update.setTableCount(total);
                getVersionMapper().update(update, queryWrapper);
                future.complete(new long[]{version, total});
                return total;
            } catch (Exception e) {
                future.completeExceptionally(e);
                log.error("addCache error for key: {}", key, e);
                return 0;
            } finally {
                CACHE_LOADING.remove(key);
            }
        }

        // Another thread is loading - try in-JVM future first (no DB polling needed)
        CompletableFuture<long[]> loadingFuture = CACHE_LOADING.get(key);
        if (loadingFuture != null) {
            try {
                long[] result = loadingFuture.get(30, TimeUnit.SECONDS);
                return result[1];
            } catch (Exception e) {
                log.warn("Waiting for in-JVM cache loading failed for key: {}", key, e);
            }
        }

        // Fallback: cross-JVM case - poll DB with reduced frequency
        for (int n = 0; n < 30; n++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            versionDO = getVersionMapper().selectOne(queryWrapper);
            if (versionDO != null && "1".equals(versionDO.getStatus())) {
                return versionDO.getTableCount();
            }
        }
        log.warn("Timed out waiting for cache loading for key: {}", key);
        return 0;
    }

    @Override
    public ListResult<SimpleTable> queryTables(TablePageQueryParam param) {
        LambdaQueryWrapper<TableCacheVersionDO> queryWrapper = new LambdaQueryWrapper<>();
        String key = getTableKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        queryWrapper.eq(TableCacheVersionDO::getKey, key);
        TableCacheVersionDO versionDO = getVersionMapper().selectOne(queryWrapper);
        if (versionDO == null) {
            addCache(param, versionDO);
            versionDO = getVersionMapper().selectOne(queryWrapper);
        }
        long version = "2".equals(versionDO.getStatus()) ? versionDO.getVersion() - 1 : versionDO.getVersion();

        LambdaQueryWrapper<TableCacheDO> query = new LambdaQueryWrapper<>();
        query.eq(TableCacheDO::getVersion, version);
        query.eq(TableCacheDO::getDataSourceId, param.getDataSourceId());
        if (StringUtils.isNotBlank(param.getDatabaseName())) {
            query.eq(TableCacheDO::getDatabaseName, param.getDatabaseName());
        }
        if (StringUtils.isNotBlank(param.getSchemaName())) {
            query.eq(TableCacheDO::getSchemaName, param.getSchemaName());
        }
        List<SimpleTable> tables = new ArrayList<>();

        for (int i = 0; i < versionDO.getTableCount() / 500 + 1; i++) {
            Page<TableCacheDO> page = new Page<>(i + 1, 500);
            IPage<TableCacheDO> iPage = getTableCacheMapper().selectPage(page, query);
            if (CollectionUtils.isNotEmpty(iPage.getRecords())) {
                for (TableCacheDO tableCacheDO : iPage.getRecords()) {
                    SimpleTable t = new SimpleTable();
                    t.setName(tableCacheDO.getTableName());
                    t.setComment(tableCacheDO.getExtendInfo());
                    tables.add(t);
                }
            }
        }
        return ListResult.of(tables);
    }

    private long addDBCache(Long dataSourceId, String databaseName, String schemaName, long version) {
        String key = getTableKey(dataSourceId, databaseName, schemaName);

        Connection connection = InqueryContext.getConnection();
        long n = 0;
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, schemaName, null,
                new String[]{"TABLE", "SYSTEM TABLE", "VIEW"})) {
            List<TableCacheDO> cacheDOS = new ArrayList<>();
            while (resultSet.next()) {
                TableCacheDO tableCacheDO = new TableCacheDO();
                tableCacheDO.setDatabaseName(databaseName);
                tableCacheDO.setSchemaName(schemaName);
                tableCacheDO.setTableName(resultSet.getString("TABLE_NAME"));
                tableCacheDO.setTableType(resultSet.getString("TABLE_TYPE"));
                tableCacheDO.setExtendInfo(resultSet.getString("REMARKS"));
                tableCacheDO.setDataSourceId(dataSourceId);
                tableCacheDO.setVersion(version);
                tableCacheDO.setKey(key);
                cacheDOS.add(tableCacheDO);
                if (cacheDOS.size() >= 500) {
                    getTableCacheMapper().batchInsert(cacheDOS);
                    cacheDOS = new ArrayList<>();
                }
                n++;
            }
            if (!CollectionUtils.isEmpty(cacheDOS)) {
                getTableCacheMapper().batchInsert(cacheDOS);
            }
            LambdaQueryWrapper<TableCacheDO> q = new LambdaQueryWrapper();
            q.eq(TableCacheDO::getDataSourceId, dataSourceId);
            q.lt(TableCacheDO::getVersion, version);
            if (StringUtils.isNotBlank(databaseName)) {
                q.eq(TableCacheDO::getDatabaseName, databaseName);
            }
            if (StringUtils.isNotBlank(schemaName)) {
                q.eq(TableCacheDO::getSchemaName, schemaName);
            }
            getTableCacheMapper().delete(q);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return n;
    }

    private Long getLock(Long dataSourceId, String databaseName, String schemaName, TableCacheVersionDO versionDO) {
        String key = getTableKey(dataSourceId, databaseName, schemaName);
        if (versionDO == null) {
            versionDO = new TableCacheVersionDO();
            versionDO.setDatabaseName(databaseName);
            versionDO.setSchemaName(schemaName);
            versionDO.setDataSourceId(dataSourceId);
            versionDO.setStatus("2");
            versionDO.setKey(key);
            versionDO.setVersion(0L);
            versionDO.setTableCount(0L);
            try {
                getVersionMapper().insert(versionDO);
                return 0L;
            } catch (Exception e) {
                log.error("getLock error", e);
                return -1L;
            }
        } else {
            long version = versionDO.getVersion() + 1;
            LambdaQueryWrapper<TableCacheVersionDO> queryWrapper = new LambdaQueryWrapper();
            queryWrapper.eq(TableCacheVersionDO::getId, versionDO.getId());
            queryWrapper.eq(TableCacheVersionDO::getVersion, versionDO.getVersion());
            versionDO.setVersion(version);
            versionDO.setStatus("2");
            int n = getVersionMapper().update(versionDO, queryWrapper);
            if (n == 1) {
                return version;
            } else {
                return -1L;
            }
        }
    }


//    private String buildKey(Long dataSourceId, String databaseName, String schemaName) {
//        StringBuilder stringBuilder = new StringBuilder(dataSourceId.toString());
//        if (StringUtils.isNotBlank(databaseName)) {
//            stringBuilder.append("_").append(databaseName);
//        }
//        if (StringUtils.isNotBlank(schemaName)) {
//            stringBuilder.append("_").append(schemaName);
//        }
//        return stringBuilder.toString();
//    }

    private List<Table> pinTable(List<Table> list, TablePageQueryParam param) {
        if (CollectionUtils.isEmpty(list)) {
            return Lists.newArrayList();
        }
        // Skip pinTable if user context is not available (e.g., background thread)
        LoginUser loginUser = ContextUtils.queryLoginUser();
        if (loginUser == null) {
            return list;
        }
        PinTableParam pinTableParam = pinTableConverter.toPinTableParam(param);
        pinTableParam.setUserId(loginUser.getId());
        ListResult<String> listResult = pinService.queryPinTables(pinTableParam);
        if (!listResult.success() || CollectionUtils.isEmpty(listResult.getData())) {
            return list;
        }
        List<Table> tables = new ArrayList<>();
        Map<String, Table> tableMap = list.stream().collect(Collectors.toMap(Table::getName, Function.identity()));
        for (String tableName : listResult.getData()) {
            Table table = tableMap.get(tableName);
            if (table != null) {
                table.setPinned(true);
                tables.add(table);
            }
        }

        for (Table table : list) {
            if (table != null && !tables.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    @Override
    public List<TableColumn> queryColumns(TableQueryParam param) {
        String tableColumnKey = getColumnKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());
        MetaData metaSchema = InqueryContext.getMetaData();
        return CacheManage.getList(tableColumnKey, TableColumn.class,
                (key) -> param.isRefresh(), (key) ->
                        metaSchema.columns(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
    }

    @Override
    public List<TableIndex> queryIndexes(TableQueryParam param) {
        MetaData metaSchema = InqueryContext.getMetaData();
        return metaSchema.indexes(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getTableName());

    }

    @Override
    public List<Type> queryTypes(TypeQueryParam param) {
        MetaData metaSchema = InqueryContext.getMetaData();
        return metaSchema.types(InqueryContext.getConnection());
    }

    @Override
    public TableMeta queryTableMeta(TypeQueryParam param) {
        MetaData metaSchema = InqueryContext.getMetaData();
        TableMeta tableMeta = metaSchema.getTableMeta(null, null, null);
        if (tableMeta != null) {
            //filter primary key
            List<IndexType> indexTypes = tableMeta.getIndexTypes();
            if (CollectionUtils.isNotEmpty(indexTypes)) {
                List<IndexType> types = indexTypes.stream().filter(indexType -> !"Primary".equalsIgnoreCase(indexType.getTypeName())).collect(Collectors.toList());
                tableMeta.setIndexTypes(types);
            }
        }
        return tableMeta;

    }

    @Override
    public ActionResult saveTableVector(TableVectorParam param) {
        if (checkTableVector(param).getData()) {
            return ActionResult.isSuccess();
        }
        TableVectorMappingDO mappingDO = tableConverter.toTableVectorMappingDO(param);
        mappingDO.setStatus(TableVectorEnum.SAVED.getCode());
        getTableVectorMapper().insert(mappingDO);
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Boolean> checkTableVector(TableVectorParam param) {
        LambdaQueryWrapper<TableVectorMappingDO> queryWrapper = new LambdaQueryWrapper();
        queryWrapper.eq(TableVectorMappingDO::getApiKey, param.getApiKey());
        queryWrapper.eq(TableVectorMappingDO::getDataSourceId, param.getDataSourceId());
        queryWrapper.eq(TableVectorMappingDO::getDatabase, param.getDatabase());
        queryWrapper.eq(TableVectorMappingDO::getSchema, param.getSchema());
        TableVectorMappingDO mappingDO = getTableVectorMapper().selectOne(queryWrapper);
        if (Objects.nonNull(mappingDO) && TableVectorEnum.SAVED.getCode().equals(mappingDO.getStatus())) {
            return DataResult.of(true);
        }
        return DataResult.of(false);
    }

    @Override
    public DataResult<String> copyDmlSql(DmlSqlCopyParam param) {
        List<TableColumn> columns = queryColumns(param);
        SqlBuilder sqlBuilder = InqueryContext.getSqlBuilder();
        Table table = Table.builder().name(param.getTableName()).columnList(columns).build();
        String sql = sqlBuilder.getTableDmlSql(table, param.getType());
        return DataResult.of(sql);
    }

    @Override
    public ListResult<SimpleTable> queryAllTablesFromAllDatabases(TablePageQueryParam param) {
        List<SimpleTable> allTables = new ArrayList<>();
        
        // Get connection info first - this is critical!
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        if (connectInfo == null) {
            log.warn("No connection info available in InqueryContext");
            return ListResult.of(Collections.emptyList());
        }
        
        Connection connection = InqueryContext.getConnection();
        if (connection == null) {
            log.warn("No connection available in InqueryContext");
            return ListResult.of(Collections.emptyList());
        }
        
        try {
            // Get database metadata
            java.sql.DatabaseMetaData metaData = connection.getMetaData();
            String dbType = connectInfo.getDbType().toUpperCase();
            
            // Use INFORMATION_SCHEMA for supported databases
            if ("MYSQL".equals(dbType) || "POSTGRESQL".equals(dbType)) {
                String sql = buildInformationSchemaSql(dbType);
                try (java.sql.Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        SimpleTable table = new SimpleTable();
                        table.setDatabaseName(rs.getString("database_name"));
                        table.setSchemaName(rs.getString("schema_name"));
                        table.setName(rs.getString("table_name"));
                        try {
                            table.setComment(rs.getString("table_comment"));
                        } catch (SQLException e) {
                            // Comment column might not exist in some databases
                            table.setComment(null);
                        }
                        allTables.add(table);
                    }
                }
            } else if ("SNOWFLAKE".equals(dbType)) {
                // Snowflake optimization: USE DATABASE + INFORMATION_SCHEMA query
                log.info("Using Snowflake optimized query for all tables");
                
                // 1. Load database list
                List<String> databases = new ArrayList<>();
                try (ResultSet catalogs = metaData.getCatalogs()) {
                    while (catalogs.next()) {
                        databases.add(catalogs.getString("TABLE_CAT"));
                    }
                }
                
                log.info("Found {} databases in Snowflake", databases.size());
                
                // 2. Switch to each database and query INFORMATION_SCHEMA
                for (String dbName : databases) {
                    try {
                        // Run USE DATABASE
                        try (java.sql.Statement useStmt = connection.createStatement()) {
                            useStmt.execute("USE DATABASE " + dbName);
                        }
                        
                        // Query tables from INFORMATION_SCHEMA
                        String sql = 
                            "SELECT " +
                            "  TABLE_CATALOG as database_name, " +
                            "  TABLE_SCHEMA as schema_name, " +
                            "  TABLE_NAME as table_name, " +
                            "  COMMENT as table_comment " +
                            "FROM INFORMATION_SCHEMA.TABLES " +
                            "WHERE TABLE_TYPE = 'BASE TABLE'";
                        
                        try (java.sql.Statement stmt = connection.createStatement();
                             ResultSet rs = stmt.executeQuery(sql)) {
                            int count = 0;
                            while (rs.next()) {
                                SimpleTable table = new SimpleTable();
                                table.setDatabaseName(rs.getString("database_name"));
                                table.setSchemaName(rs.getString("schema_name"));
                                table.setName(rs.getString("table_name"));
                                table.setComment(rs.getString("table_comment"));
                                allTables.add(table);
                                count++;
                            }
                            log.info("Retrieved {} tables from database: {}", count, dbName);
                        }
                    } catch (SQLException e) {
                        log.warn("Cannot get tables for database {} using INFORMATION_SCHEMA: {}", dbName, e.getMessage());
                    }
                }
            } else {
                // Fallback to JDBC metadata for other databases (Oracle, SQL Server, etc.)
                try (ResultSet catalogs = metaData.getCatalogs()) {
                    while (catalogs.next()) {
                        String dbName = catalogs.getString("TABLE_CAT");
                        try (ResultSet schemas = metaData.getSchemas(dbName, null)) {
                            while (schemas.next()) {
                                String schemaName = schemas.getString("TABLE_SCHEM");
                                try (ResultSet tables = metaData.getTables(dbName, schemaName, null, 
                                        new String[]{"TABLE", "SYSTEM TABLE", "VIEW"})) {
                                    while (tables.next()) {
                                        SimpleTable table = new SimpleTable();
                                        table.setDatabaseName(dbName);
                                        table.setSchemaName(schemaName);
                                        table.setName(tables.getString("TABLE_NAME"));
                                        table.setComment(tables.getString("REMARKS"));
                                        allTables.add(table);
                                    }
                                }
                            }
                        } catch (SQLException e) {
                            log.warn("Cannot get schemas for database {}: {}", dbName, e.getMessage());
                        }
                    }
                }
            }
            
            log.info("Successfully retrieved {} tables from all databases", allTables.size());
            return ListResult.of(allTables);
            
        } catch (SQLException e) {
            log.error("Failed to query all tables from all databases", e);
            return ListResult.of(Collections.emptyList());
        }
    }

    private String buildInformationSchemaSql(String dbType) {
        switch (dbType) {
            case "MYSQL":
                return "SELECT " +
                       "TABLE_SCHEMA as database_name, " +
                       "NULL as schema_name, " +
                       "TABLE_NAME as table_name, " +
                       "TABLE_COMMENT as table_comment " +
                       "FROM INFORMATION_SCHEMA.TABLES " +
                       "WHERE TABLE_TYPE = 'BASE TABLE' " +
                       "AND TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')";
            case "POSTGRESQL":
                return "SELECT " +
                       "table_catalog as database_name, " +
                       "table_schema as schema_name, " +
                       "table_name, " +
                       "obj_description((table_schema||'.'||table_name)::regclass, 'pg_class') as table_comment " +
                       "FROM information_schema.tables " +
                       "WHERE table_type = 'BASE TABLE' " +
                       "AND table_schema NOT IN ('information_schema', 'pg_catalog')";
            case "SNOWFLAKE":
                return "SELECT " +
                       "TABLE_CATALOG as database_name, " +
                       "TABLE_SCHEMA as schema_name, " +
                       "TABLE_NAME as table_name, " +
                       "COMMENT as table_comment " +
                       "FROM INFORMATION_SCHEMA.TABLES " +
                       "WHERE TABLE_TYPE = 'BASE TABLE' " +
                       "AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA')";
            default:
                throw new UnsupportedOperationException("INFORMATION_SCHEMA not supported for database type: " + dbType);
        }
    }
}
