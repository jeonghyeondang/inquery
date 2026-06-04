package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.*;
import ai.inquery.server.domain.api.param.operation.OperationLogCreateParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.api.service.OperationLogService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.domain.core.converter.CommandConverter;
import ai.inquery.server.domain.core.util.MetaNameUtils;
import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import ai.inquery.spi.CommandExecutor;
import ai.inquery.spi.SqlBuilder;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.util.JdbcUtils;
import ai.inquery.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.PagerUtils;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @version DataSourceCoreServiceImpl.java, v 0.1 September 23, 2022 15:51 moji Exp $
 */
@Slf4j
@Service
public class DlTemplateServiceImpl implements DlTemplateService {

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private TableService tableService;

    @Autowired
    private CommandConverter commandConverter;

    @Override
    public ListResult<ExecuteResult> execute(DlExecuteParam param) {
        CommandExecutor executor = InqueryContext.getMetaData().getCommandExecutor();
        Command command = commandConverter.param2model(param);
        List<ExecuteResult> results = executor.execute(command);
        return reBuildHeader(results, param.getSchemaName(), param.getDatabaseName(), param.getSql());
    }

    private ListResult<ExecuteResult> reBuildHeader(List<ExecuteResult> results, String schemaName, String databaseName, String originalSql){
        ListResult<ExecuteResult> listResult = ListResult.of(results);
        for (ExecuteResult executeResult : results) {
            List<Header> headers = executeResult.getHeaderList();
            if (executeResult.getSuccess() && executeResult.isCanEdit() && CollectionUtils.isNotEmpty(headers)) {
                headers = setColumnInfo(headers, executeResult.getTableName(), schemaName, databaseName);
                executeResult.setHeaderList(headers);
            }
            if (!executeResult.getSuccess()) {
                listResult.setSuccess(false);
                listResult.errorCode(executeResult.getDescription());
                listResult.setErrorMessage(executeResult.getMessage());
            }
            // Use original SQL if executeResult.getSql() is null
            String sqlForLog = executeResult.getSql();
            if (StringUtils.isBlank(sqlForLog)) {
                sqlForLog = originalSql;
            }
            addOperationLog(executeResult, sqlForLog);
        }
        return listResult;
    }

    @Override
    public ListResult<ExecuteResult> executeSelectTable(DlExecuteParam param) {
        Command command = commandConverter.param2model(param);
        List<ExecuteResult> results = InqueryContext.getMetaData().getCommandExecutor().executeSelectTable(command);
        return reBuildHeader(results, param.getSchemaName(), param.getDatabaseName(), param.getSql());
    }

    @Override
    public DataResult<ExecuteResult> executeUpdate(DlExecuteParam param) {
        CommandExecutor executor = InqueryContext.getMetaData().getCommandExecutor();
        DataResult<ExecuteResult> dataResult = new DataResult<>();
        dataResult.setSuccess(true);
        //RemoveSpecialGO(param);
        DbType dbType =
                JdbcUtils.parse2DruidDbType(InqueryContext.getConnectInfo().getDbType());
        List<String> sqlList = SqlUtils.parse(param.getSql(), dbType,true);
        Connection connection = InqueryContext.getConnection();
        try {
//            connection.setAutoCommit(false);
            for (String sql : sqlList) {
                ExecuteResult executeResult = executor.executeUpdate(sql, connection, 1);
                dataResult.setData(executeResult);
                addOperationLog(executeResult, sql);
            }
//            connection.commit();
        } catch (Exception e) {
            log.error("executeUpdate error", e);
            dataResult.setSuccess(false);
            dataResult.setErrorCode("connection error");
            dataResult.setErrorMessage(e.getMessage());
        }
        return dataResult;
    }




    @Override
    public DataResult<Long> count(DlCountParam param) {
        if (StringUtils.isBlank(param.getSql())) {
            return DataResult.of(0L);
        }
        DbType dbType =
                JdbcUtils.parse2DruidDbType(InqueryContext.getConnectInfo().getDbType());
        String sql = param.getSql();
        // Parse sql pagination
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        sql = PagerUtils.count(sql, dbType);
        ExecuteResult executeResult;
        try {
            executeResult = InqueryContext.getMetaData().getCommandExecutor().execute(sql, InqueryContext.getConnection(), true, null, null);
        } catch (SQLException e) {
            log.warn("Execute sql: {} exception", sql, e);
            executeResult = ExecuteResult.builder()
                    .sql(sql)
                    .success(Boolean.FALSE)
                    .message(e.getMessage())
                    .build();
        }

        List<List<String>> dataList = executeResult.getDataList();
        if (CollectionUtils.isEmpty(dataList)) {
            return DataResult.of(0L);
        }
        String count = EasyCollectionUtils.stream(executeResult.getDataList())
                .findFirst()
                .orElse(Collections.emptyList())
                .stream()
                .findFirst()
                .orElse("0");
        return DataResult.of(Long.valueOf(count));
    }

    @Override
    public DataResult<String> updateSelectResult(UpdateSelectResultParam param) {
        SqlBuilder sqlBuilder = InqueryContext.getSqlBuilder();
        QueryResult queryResult = new QueryResult();
        BeanUtils.copyProperties(param, queryResult);
        String sql = sqlBuilder.buildSqlByQuery(queryResult);
        return DataResult.of(sql);
    }

    @Override
    public DataResult<String> getOrderBySql(OrderByParam param) {
        SqlBuilder sqlBuilder = InqueryContext.getSqlBuilder();
        String orderSql = sqlBuilder.buildOrderBySql(param.getOriginSql(), param.getOrderByList());
        return DataResult.of(orderSql);
    }

    
    /**
     * The method getGroupBySql constructs a GROUP BY SQL query string from the provided parameters.
     *
     * @param param - a GroupByParam object containing the original SQL query and the list of columns to group by
     * @return DataResult<String> - a DataResult object containing the constructed GROUP BY SQL query string
    */
   @Override
    public DataResult<String> getGroupBySql(GroupByParam param) {
        // - Retrieve the SqlBuilder instance from InqueryContext
        SqlBuilder sqlBuilder = InqueryContext.getSqlBuilder();
        // Build the GROUP BY SQL using the provided parameters
        String groupSql = sqlBuilder.buildGroupBySql(param.getOriginSql(), param.getGroupByList());
        // Return the built SQL as a DataResult
        return DataResult.of(groupSql);
    }
    private List<Header> setColumnInfo(List<Header> headers, String tableName, String schemaName, String databaseName) {
        // Skip if tableName is null or empty (can't look up column info without it)
        if (StringUtils.isBlank(tableName)) {
            log.debug("setColumnInfo skipped: tableName is blank");
            return headers;
        }
        
        try {
            TableQueryParam tableQueryParam = new TableQueryParam();
            tableQueryParam.setTableName(MetaNameUtils.getMetaName(tableName));
            tableQueryParam.setSchemaName(schemaName);
            tableQueryParam.setDatabaseName(databaseName);
            // Use cached data instead of always refreshing (major performance improvement)
            tableQueryParam.setRefresh(false);
            
            List<TableColumn> columns = tableService.queryColumns(tableQueryParam);
            if (CollectionUtils.isEmpty(columns)) {
                return headers;
            }
            Map<String, TableColumn> columnMap = columns.stream().collect(
                    Collectors.toMap(TableColumn::getName, tableColumn -> tableColumn, (a, b) -> a));
            
            // Only query indexes if we have columns (avoid unnecessary DB call)
            List<TableIndex> tableIndices = tableService.queryIndexes(tableQueryParam);
            if (!CollectionUtils.isEmpty(tableIndices)) {
                for (TableIndex tableIndex : tableIndices) {
                    if ("PRIMARY".equalsIgnoreCase(tableIndex.getType())) {
                        List<TableIndexColumn> columnList = tableIndex.getColumnList();
                        if (!CollectionUtils.isEmpty(columnList)) {
                            for (TableIndexColumn tableIndexColumn : columnList) {
                                TableColumn tableColumn = columnMap.get(tableIndexColumn.getColumnName());
                                if (tableColumn != null) {
                                    tableColumn.setPrimaryKey(true);
                                }
                            }
                        }
                    }
                }
            }
            for (Header header : headers) {
                TableColumn tableColumn = columnMap.get(header.getName());
                if (tableColumn != null) {
                    header.setPrimaryKey(tableColumn.getPrimaryKey());
                    header.setComment(tableColumn.getComment());
                    header.setDefaultValue(tableColumn.getDefaultValue());
                    header.setNullable(tableColumn.getNullable());
                    header.setColumnSize(tableColumn.getColumnSize());
                    header.setDecimalDigits(tableColumn.getDecimalDigits());
                }
            }

        } catch (Exception e) {
            log.error("setColumnInfo error:", e);
        }
        return headers;
    }


    private void addOperationLog(ExecuteResult executeResult, String originalSql) {
        if (executeResult == null) {
            return;
        }
        try {
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            OperationLogCreateParam createParam = new OperationLogCreateParam();
            // Use originalSql if provided, fallback to executeResult.getSql()
            String sql = StringUtils.isNotBlank(originalSql) ? originalSql : executeResult.getSql();

            // Skip empty SQL - don't save history without SQL content
            if (StringUtils.isBlank(sql)) {
                return;
            }

            // Skip EXPLAIN queries from history
            if (sql.trim().toUpperCase().startsWith("EXPLAIN")) {
                return;
            }

            createParam.setDdl(sql);
            createParam.setStatus(executeResult.getSuccess() ? "success" : "fail");
            createParam.setDatabaseName(connectInfo.getDatabaseName());
            createParam.setDataSourceId(connectInfo.getDataSourceId());
            createParam.setSchemaName(connectInfo.getSchemaName());
            createParam.setUseTime(executeResult.getDuration());
            createParam.setType(connectInfo.getDbType());
            // Set query source (WORKSPACE or AI_CHAT)
            createParam.setSource(InqueryContext.getQuerySource());
            // For SELECT queries, use row count from dataList; for UPDATE/INSERT/DELETE use updateCount
            Long rowCount = null;
            if (executeResult.getUpdateCount() != null) {
                rowCount = Long.valueOf(executeResult.getUpdateCount());
            } else if (executeResult.getDataList() != null) {
                rowCount = Long.valueOf(executeResult.getDataList().size());
            }
            createParam.setOperationRows(rowCount);
            operationLogService.create(createParam);
        } catch (ai.inquery.server.tools.common.exception.NeedLoggedInBusinessException e) {
            // Skip operation log for non-authenticated requests (e.g., Slack bot)
            log.debug("Skipping operation log - no authenticated user (Slack/API request)");
        } catch (Exception e) {
            log.error("addOperationLog error:", e);
        }
    }
}
