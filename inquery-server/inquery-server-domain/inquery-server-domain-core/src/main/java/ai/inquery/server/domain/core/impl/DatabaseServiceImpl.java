package ai.inquery.server.domain.core.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import ai.inquery.server.domain.api.param.datasource.DatabaseCreateParam;
import ai.inquery.server.domain.api.param.datasource.DatabaseExportParam;
import ai.inquery.server.domain.api.param.datasource.DatabaseQueryAllParam;
import ai.inquery.server.domain.api.param.MetaDataQueryParam;
import ai.inquery.server.domain.api.param.SchemaOperationParam;
import ai.inquery.server.domain.api.param.SchemaQueryParam;
import ai.inquery.server.domain.api.service.DatabaseService;
import ai.inquery.server.domain.core.cache.CacheManage;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import static ai.inquery.server.domain.core.cache.CacheKey.getDataBasesKey;
import static ai.inquery.server.domain.core.cache.CacheKey.getDataSourceKey;
import static ai.inquery.server.domain.core.cache.CacheKey.getSchemasKey;

/**
 * @version DataSourceCoreServiceImpl.java, v 0.1 September 23, 2022 15:51 moji Exp $
 */
@Slf4j
@Service
public class DatabaseServiceImpl implements DatabaseService {

    @Override
    public ListResult<Database> queryAll(DatabaseQueryAllParam param) {
        List<Database> databases = CacheManage.getList(getDataBasesKey(param.getDataSourceId()), Database.class,
                (key) -> param.isRefresh(),
                (key) -> getDatabases(param.getDbType(), param.getConnection() == null ? InqueryContext.getConnection()
                        : param.getConnection())
        );
        return ListResult.of(databases);
    }

    private List<Database> getDatabases(String dbType, Connection connection) {
        return InqueryContext.getMetaData(dbType).databases(connection);
    }

    @Override
    public ListResult<Schema> querySchema(SchemaQueryParam param) {
        List<Schema> schemas = CacheManage.getList(getSchemasKey(param.getDataSourceId(), param.getDataBaseName()),
                Schema.class,
                (key) -> param.isRefresh(), (key) -> {
                    Connection connection = param.getConnection() == null ? InqueryContext.getConnection()
                            : param.getConnection();
                    return getSchemaList(param.getDataBaseName(), connection);
                });
        return ListResult.of(schemas);
    }


    private List<Schema> getSchemaList(String databaseName, Connection connection) {
        MetaData metaData = InqueryContext.getMetaData();
        List<Schema> schemas = metaData.schemas(connection, databaseName);
        sortSchema(schemas, connection);
        return schemas;
    }

    private void sortSchema(List<Schema> schemas, Connection connection) {
        if (CollectionUtils.isEmpty(schemas)) {
            return;
        }
        String ulr = null;
        try {
            ulr = connection.getMetaData().getURL();
        } catch (SQLException e) {
            log.error("get url error", e);
        }
        // If the database name contains the name of the current database, the current database is placed in the first place
        int targetIndex = -1;
        for (int i = 0; i < schemas.size(); i++) {
            String schema = schemas.get(i).getName();
            if (StringUtils.isNotBlank(ulr) && schema!=null && ulr.contains(schema)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex != -1 && targetIndex != 0) {
            Collections.swap(schemas, targetIndex, 0);
        }
    }

    @Override
    public DataResult<MetaSchema> queryDatabaseSchema(MetaDataQueryParam param) {
        MetaSchema metaSchema = new MetaSchema();
        MetaData metaData = InqueryContext.getMetaData();
        MetaSchema ms = CacheManage.get(getDataSourceKey(param.getDataSourceId()), MetaSchema.class,
                (key) -> param.isRefresh(), (key) -> {
                    Connection connection = InqueryContext.getConnection();
                    List<Database> databases = metaData.databases(connection);
                    if (!CollectionUtils.isEmpty(databases)) {
                        for (Database database : databases) {
                            try {
                                database.setSchemas(metaData.schemas(connection, database.getName()));
                            } catch (Exception e) {
                                log.error("queryDatabaseSchema error for database: {}", database.getName(), e);
                            }
                        }
                        metaSchema.setDatabases(databases);

                    } else {
                        List<Schema> schemas = metaData.schemas(connection, null);
                        metaSchema.setSchemas(schemas);
                    }
                    return metaSchema;
                });

        return DataResult.of(ms);
    }

    @Override
    public ActionResult deleteDatabase(DatabaseCreateParam param) {
        InqueryContext.getDBManage().dropDatabase(InqueryContext.getConnection(), param.getName());
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Sql> createDatabase(Database database) {
        String sql = InqueryContext.getSqlBuilder().buildCreateDatabaseSql(database);
        return DataResult.of(Sql.builder().sql(sql).build());
    }

    @Override
    public ActionResult modifyDatabase(DatabaseCreateParam param) {
        InqueryContext.getDBManage().modifyDatabase(InqueryContext.getConnection(), param.getName(),
                param.getNewName());
        return ActionResult.isSuccess();
    }

    @Override
    public ActionResult deleteSchema(SchemaOperationParam param) {
        InqueryContext.getDBManage().dropSchema(InqueryContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName());
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Sql> createSchema(Schema schema) {
        String sql = InqueryContext.getSqlBuilder().buildCreateSchemaSql(schema);
        return DataResult.of(Sql.builder().sql(sql).build());
    }

    @Override
    public ActionResult modifySchema(SchemaOperationParam param) {
        InqueryContext.getDBManage().modifySchema(InqueryContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName(),
                param.getNewSchemaName());
        return ActionResult.isSuccess();
    }

    @Override
    public String exportDatabase(DatabaseExportParam param) throws SQLException {
        AsyncCall call = new AsyncCall() {

            @Override
            public void update(Map<String, Object> map) {

            }
        };

        AsyncContext asyncContext = new AsyncContext(call, ContextUtils.queryContext(), null, param.getContainData());
        InqueryContext.getDBManage().exportDatabase(InqueryContext.getConnection(),
                                                          param.getDatabaseName(),
                                                          param.getSchemaName(), asyncContext);

        return "exportDatabase success";
    }

    @Override
    public ListResult<String> getUsernameList(){
        MetaData metaSchema = InqueryContext.getMetaData();
        List<String> usernames = metaSchema.usernames(InqueryContext.getConnection());
        return ListResult.of(usernames);
    }
}