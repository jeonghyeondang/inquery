package ai.inquery.spi.sql;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.enums.DataSourceTypeEnum;
import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import ai.inquery.server.tools.common.util.I18nUtils;
import ai.inquery.spi.CommandExecutor;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.ValueProcessor;
import ai.inquery.spi.enums.DataTypeEnum;
import ai.inquery.spi.enums.SqlTypeEnum;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.util.JdbcUtils;
import ai.inquery.spi.util.ResultSetUtils;
import ai.inquery.spi.util.SqlUtils;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.util.ArrayUtil;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * Unified database connection management
 *
 */
@Slf4j
public class SQLExecutor implements CommandExecutor {

    /**
     * Singleton instance of SQLExecutor.
     */
    private static final SQLExecutor INSTANCE = new SQLExecutor();

    public SQLExecutor() {
    }

    public static SQLExecutor getInstance() {
        return INSTANCE;
    }



    public <R> R execute(Connection connection, String sql, ResultSetFunction<R> function) {
        // Check if connection is closed before using it
        try {
            if (connection == null || connection.isClosed()) {
                log.warn("Connection is null or closed, attempting to reconnect");
                connection = reconnectIfNeeded();
                if (connection == null || connection.isClosed()) {
                    throw new RuntimeException("Failed to get a valid connection");
                }
            }
        } catch (SQLException e) {
            log.warn("Error checking connection status, attempting to reconnect: {}", e.getMessage());
            connection = reconnectIfNeeded();
            if (connection == null) {
                throw new RuntimeException("Failed to get a valid connection", e);
            }
        }
        
        try (Statement stmt = connection.createStatement();) {
            boolean query = stmt.execute(sql);
            // Represents the query
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    return function.apply(rs);
                }
            }
        } catch (Exception e) {
            // Check if it's a Snowflake connection error (closed or session expired)
            if (isSnowflakeConnectionError(e)) {
                log.warn("Snowflake connection error detected, attempting to reconnect: {}", e.getMessage());
                // Try to reconnect and retry
                Connection newConnection = reconnectIfNeeded();
                
                if (newConnection != null) {
                    try {
                        // Retry the query with new connection
                        try (Statement stmt = newConnection.createStatement();) {
                            boolean query = stmt.execute(sql);
                            if (query) {
                                try (ResultSet rs = stmt.getResultSet();) {
                                    return function.apply(rs);
                                }
                            }
                        }
                    } catch (Exception retryException) {
                        log.error("Failed to retry query after reconnect: {}", sql, retryException);
                        throw new RuntimeException("Snowflake connection error and retry failed", retryException);
                    }
                } else {
                    log.error("Failed to reconnect, cannot retry query");
                    throw new RuntimeException("Snowflake connection error and reconnection failed", e);
                }
            }
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
        return null;
    }
    
    /**
     * Check if the exception is a Snowflake connection error (closed or session expired)
     */
    private boolean isSnowflakeConnectionError(Exception e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        String className = e.getClass().getName();
        
        if (message != null) {
            // Check for connection closed errors
            if (message.contains("Connection has been closed") || 
                message.contains("Connection is closed")) {
                return true;
            }
            // Check for session expiration errors
            if (message.contains("Session no longer exists") || 
                message.contains("New login required")) {
                return true;
            }
            // Check for Snowflake SQL exceptions related to connection/session
            if (className.contains("SnowflakeSQLException")) {
                if (message.contains("session") || 
                    message.contains("connection") ||
                    message.contains("closed")) {
                    return true;
                }
            }
        }
        // Check cause recursively
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            return isSnowflakeConnectionError(cause instanceof Exception ? (Exception) cause : new RuntimeException(cause));
        }
        return false;
    }
    
    /**
     * Reconnect if needed and return a new connection
     */
    private Connection reconnectIfNeeded() {
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        if (connectInfo != null) {
            try {
                ConnectionPool.removeConnection(connectInfo.getDataSourceId());
                Connection newConnection = ConnectionPool.getConnection(connectInfo);
                if (newConnection != null && !newConnection.isClosed()) {
                    log.info("Reconnected successfully for dataSourceId: {}", connectInfo.getDataSourceId());
                    return newConnection;
                } else {
                    log.error("Failed to get a valid connection after reconnect");
                }
            } catch (Exception e) {
                log.error("Failed to reconnect", e);
            }
        } else {
            log.error("ConnectInfo is null, cannot reconnect");
        }
        return null;
    }

    public void execute(Connection connection, String sql, ResultSetConsumer consumer) {
        // Check if connection is closed before using it
        try {
            if (connection == null || connection.isClosed()) {
                log.warn("Connection is null or closed, attempting to reconnect");
                connection = reconnectIfNeeded();
                if (connection == null || connection.isClosed()) {
                    throw new RuntimeException("Failed to get a valid connection");
                }
            }
        } catch (SQLException e) {
            log.warn("Error checking connection status, attempting to reconnect: {}", e.getMessage());
            connection = reconnectIfNeeded();
            if (connection == null) {
                throw new RuntimeException("Failed to get a valid connection", e);
            }
        }
        
        try (Statement stmt = connection.createStatement()) {
            boolean query = stmt.execute(sql);
            // Represents the query
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    consumer.accept(rs);
                }
            }
        } catch (Exception e) {
            // Check if it's a Snowflake connection error (closed or session expired)
            if (isSnowflakeConnectionError(e)) {
                log.warn("Snowflake connection error detected, attempting to reconnect: {}", e.getMessage());
                // Try to reconnect and retry
                Connection newConnection = reconnectIfNeeded();
                if (newConnection != null) {
                    try {
                        // Retry the query with new connection
                        try (Statement stmt = newConnection.createStatement()) {
                            boolean query = stmt.execute(sql);
                            if (query) {
                                try (ResultSet rs = stmt.getResultSet();) {
                                    consumer.accept(rs);
                                    return;
                                }
                            }
                        }
                    } catch (Exception retryException) {
                        log.error("Failed to retry query after reconnect: {}", sql, retryException);
                        throw new RuntimeException("Snowflake connection error and retry failed", retryException);
                    }
                } else {
                    log.error("Failed to reconnect, cannot retry query");
                    throw new RuntimeException("Snowflake connection error and reconnection failed", e);
                }
            }
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
    }


    public void execute(
            Connection connection, String sql,
            Consumer<List<Header>> headerConsumer,
            Consumer<List<String>> rowConsumer,
            java.util.function.Function<JDBCDataValue,
                    String> valueFunction,
            boolean limitSize) {
        Assert.notNull(sql, "SQL must not be null");
        try (Statement stmt = connection.createStatement();) {
            boolean query = stmt.execute(sql);
            // Represents the query
            if (query) {
                ResultSet rs = null;
                try {
                    rs = stmt.getResultSet();
                    // Get how many columns
                    ResultSetMetaData resultSetMetaData = rs.getMetaData();
                    int col = resultSetMetaData.getColumnCount();

                    // Get header information
                    List<Header> headerList = generateHeaderList(resultSetMetaData);
                    headerConsumer.accept(headerList);

                    while (rs.next()) {
                        List<String> row = Lists.newArrayListWithExpectedSize(col);
                        for (int i = 1; i <= col; i++) {
                            JDBCDataValue jdbcDataValue = new JDBCDataValue(rs, resultSetMetaData, i, limitSize);
                            row.add(valueFunction.apply(jdbcDataValue));
                        }
                        rowConsumer.accept(row);
                    }
                } finally {
                    JdbcUtils.closeResultSet(rs);
                }
            }
        } catch (SQLException e) {
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
    }

    public <R> R preExecute(Connection connection, String sql, String[] args, ResultSetFunction<R> function) {
        log.info("preExecute:{}", sql);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            if (ArrayUtil.isNotEmpty(args)) {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
            }
            boolean query = preparedStatement.execute();
            // Represents the query
            if (query) {
                try (ResultSet rs = preparedStatement.getResultSet()) {
                    return function.apply(rs);
                }
            }
        } catch (Exception e) {
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
        return null;
    }

//    /**
//     * Execute SQL
//     *
//     * @param sql
//     * @return
//     * @throws SQLException
//     */
//    public ExecuteResult execute(final String sql, Connection connection, ValueHandler valueHandler)
//        throws SQLException {
//        return execute(sql, connection, true, null, null, valueHandler);
//    }

    @Override
    public ExecuteResult executeUpdate(String sql, Connection connection, int n)
            throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        ExecuteResult executeResult = ExecuteResult.builder().sql(sql).success(Boolean.TRUE).build();
        try (Statement stmt = connection.createStatement()) {
            int affectedRows = stmt.executeUpdate(sql);
            if (affectedRows != n) {
                log.info("Update error {} update affectedRows = {}", sql, affectedRows);
            }
        }
        return executeResult;
    }

    @Override
    public List<ExecuteResult> executeSelectTable(Command command) {
        MetaData metaData = InqueryContext.getMetaData();
        String tableName = metaData.getMetaDataName(command.getDatabaseName(), command.getSchemaName(),
                command.getTableName());
        String sql = "select * from " + tableName;
        command.setScript(sql);
        return execute(command);
    }


    /**
     * Executes the given SQL query using the provided connection.
     *
     * @param sql          The SQL query to be executed.
     * @param connection   The database connection to use for the query.
     * @param limitRowSize Flag to indicate if row size should be limited.
     * @param offset       The starting point of rows to fetch in the result set.
     * @param count        The number of rows to fetch from the result set.
     * @return ExecuteResult containing the result of the execution.
     * @throws SQLException If there is any SQL related error.
     */
    public ExecuteResult execute(final String sql, Connection connection, boolean limitRowSize, Integer offset, Integer count)
            throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        ExecuteResult executeResult = ExecuteResult.builder().sql(sql).success(Boolean.TRUE).build();
        try (Statement stmt = connection.createStatement()) {
            stmt.setFetchSize(EasyToolsConstant.MAX_PAGE_SIZE);
            if (offset != null && count != null) {
                stmt.setMaxRows(offset + count);
            }
            TimeInterval timeInterval = new TimeInterval();
            boolean query = stmt.execute(sql);
            executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
            // Represents the query
            if (query) {
                executeResult = generateQueryExecuteResult(stmt, limitRowSize, offset, count);
            } else {
                // Modification or other
                executeResult.setUpdateCount(stmt.getUpdateCount());
            }
            executeResult.setDuration(timeInterval.interval());
        }
        return executeResult;
    }

    private ExecuteResult generateQueryExecuteResult(Statement stmt, boolean limitRowSize, Integer offset,
                                                     Integer count) throws SQLException {
        ExecuteResult executeResult = ExecuteResult.builder().success(Boolean.TRUE).build();
        executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();
            // Get how many columns
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            // Get header information
            List<Header> headerList = generateHeaderList(resultSetMetaData);


            int inqueryAutoRowIdIndex = getAutoRowIdIndex(headerList);
            // Get data information
            List<List<String>> dataList = generateDataList(rs, col, inqueryAutoRowIdIndex, limitRowSize,
                    offset, count);

            executeResult.setHeaderList(headerList);
            executeResult.setDataList(dataList);
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
        return executeResult;
    }

    private List<List<String>> generateDataList(ResultSet rs, int col, int autoRowIdIndex,
                                                boolean limitRowSize, Integer offset, Integer count) throws SQLException {
        List<List<String>> dataList = Lists.newArrayList();

        if (offset == null || offset < 0) {
            offset = 0;
        }
        int rowNumber = 0;
        int rowCount = 1;
        while (rs.next()) {
            if (rowNumber++ < offset) {
                continue;
            }
            List<String> row = Lists.newArrayListWithExpectedSize(col);
            dataList.add(row);
            for (int i = 1; i <= col; i++) {
                if (autoRowIdIndex == i) {
                    continue;
                }
                ValueProcessor valueProcessor = InqueryContext.getMetaData().getValueProcessor();
                row.add(valueProcessor.getJdbcValue(new JDBCDataValue(rs, rs.getMetaData(), i, limitRowSize)));
            }
            if (count != null && count > 0 && rowCount++ >= count) {
                break;
            }
        }
        return dataList;
    }

    private int getAutoRowIdIndex(List<Header> headerList) {

        for (int i = 0; i < headerList.size(); i++) {
            Header header = headerList.get(i);
            if ("INQUERY_AUTO_ROW_ID".equals(header.getName())) {
                headerList.remove(i);
                return i + 1;
            }
        }
        return -1;
    }


    private List<Header> generateHeaderList(ResultSetMetaData resultSetMetaData) throws SQLException {
        int col = resultSetMetaData.getColumnCount();
        List<Header> headerList = Lists.newArrayListWithExpectedSize(col);
        for (int i = 1; i <= col; i++) {
            headerList.add(Header.builder()
                    .dataType(JdbcUtils.resolveDataType(
                            resultSetMetaData.getColumnTypeName(i), resultSetMetaData.getColumnType(i)).getCode())
                    .name(ResultSetUtils.getColumnName(resultSetMetaData, i))
                    .build());
        }
        return headerList;
    }


    public ExecuteResult execute(Connection connection, String sql) throws SQLException {
        return execute(sql, connection, true, null, null);
    }

    /**
     * Get all databases
     *
     * @param connection
     * @return
     */
    public List<Database> databases(Connection connection) {
        try (ResultSet resultSet = connection.getMetaData().getCatalogs();) {
            List<Database> databases = ResultSetUtils.toObjectList(resultSet, Database.class);
            if (CollectionUtils.isEmpty(databases)) {
                return databases;
            }
            return databases.stream().filter(database -> database.getName() != null).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the schema names available in this database. The results are ordered by TABLE_CATALOG and TABLE_SCHEM.
     * The schema columns are: TABLE_SCHEM String => schema name TABLE_CATALOG String => catalog name (may be null)
     * Params: catalog – a catalog name; must match the catalog name as it is stored in the database;"" retrieves those
     * without a catalog; null means catalog name should not be used to narrow down the search. schemaPattern – a schema
     * name; must match the schema name as it is stored in the database; null means schema name should not be used to
     * narrow down the search. Returns: a ResultSet object in which each row is a schema description Throws:
     * SQLException – if a database access error occurs Since: 1.6 See Also: getSearchStringEscape
     */
    public List<Schema> schemas(Connection connection, String databaseName, String schemaName) {
        if (StringUtils.isEmpty(databaseName) && StringUtils.isEmpty(schemaName)) {
            try (ResultSet resultSet = connection.getMetaData().getSchemas()) {
                return ResultSetUtils.toObjectList(resultSet, Schema.class);
            } catch (SQLException e) {
                throw new RuntimeException("Get schemas error", e);
            }
        }
        try (ResultSet resultSet = connection.getMetaData().getSchemas(databaseName, schemaName)) {
            return ResultSetUtils.toObjectList(resultSet, Schema.class);
        } catch (SQLException e) {
            throw new RuntimeException("Get schemas error", e);
        }
    }

    /**
     * Get all database tables
     *
     * @param connection
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @param types
     * @return
     */
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName,
                              String types[]) {
        try {
            // Check if connection is valid and not closed
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Connection is null or closed");
            }
            
            // getMetaData().getTables() creates an internal Statement that may be closed
            // if the connection was reused from pool. Try to get a fresh connection if needed.
            // However, since we can't easily detect if the internal Statement is closed,
            // we'll catch the exception and retry with a new connection if available.
            try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, schemaName, tableName, types)) {
                return ResultSetUtils.toObjectList(resultSet, Table.class);
            }
        } catch (SQLException e) {
            // If "Statement is closed" error, try to get a new connection from context
            if (e.getMessage() != null && (e.getMessage().contains("Statement is closed") || 
                e.getMessage().contains("closed"))) {
                log.warn("Statement is closed error, attempting to get new connection: {}", e.getMessage());
                try {
                    // Try to get a new connection from InqueryContext
                    Connection newConnection = InqueryContext.getConnection();
                    if (newConnection != null && !newConnection.isClosed()) {
                        log.info("Retrying with new connection");
                        try (ResultSet resultSet = newConnection.getMetaData().getTables(databaseName, schemaName, tableName, types)) {
                            return ResultSetUtils.toObjectList(resultSet, Table.class);
                        }
                    }
                } catch (Exception retryException) {
                    log.error("Failed to retry with new connection", retryException);
                }
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * query table names
     *
     * @param connection
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @param types
     * @return
     */
    public List<String> tableNames(Connection connection, String databaseName, String schemaName, String tableName,
                                   String[] types) {
        List<String> tableNames = new ArrayList<>();
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, schemaName, tableName, types)) {
            while (resultSet.next()) {
                tableNames.add(resultSet.getString("TABLE_NAME"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return tableNames;
    }

    /**
     * Get all database table columns
     *
     * @param connection
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @param columnName
     * @return
     */
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String
            tableName,
                                     String columnName) {
        try (ResultSet resultSet = connection.getMetaData().getColumns(databaseName, schemaName, tableName,
                columnName)) {
            return ResultSetUtils.toObjectList(resultSet, TableColumn.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * get all table index info
     *
     * @param connection   connection
     * @param databaseName databaseName of the index
     * @param schemaName   schemaName of the index
     * @param tableName    tableName of the index
     * @return List<TableIndex> table index list
     */
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableIndex> tableIndices = Lists.newArrayList();
        try (ResultSet resultSet = connection.getMetaData().getIndexInfo(databaseName, schemaName, tableName,
                false,
                false)) {
            List<TableIndexColumn> tableIndexColumns = ResultSetUtils.toObjectList(resultSet, TableIndexColumn.class);
            tableIndexColumns.stream().filter(c -> c.getIndexName() != null).collect(
                            Collectors.groupingBy(TableIndexColumn::getIndexName)).entrySet()
                    .stream().forEach(entry -> {
                        TableIndex tableIndex = new TableIndex();
                        TableIndexColumn column = entry.getValue().get(0);
                        tableIndex.setName(entry.getKey());
                        tableIndex.setTableName(column.getTableName());
                        tableIndex.setSchemaName(column.getSchemaName());
                        tableIndex.setDatabaseName(column.getDatabaseName());
                        tableIndex.setUnique(!column.getNonUnique());
                        tableIndex.setColumnList(entry.getValue());
                        tableIndices.add(tableIndex);
                    });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return tableIndices;
    }


    /**
     * Get all functions available in a catalog.
     *
     * @param connection   connection
     * @param databaseName databaseName of the function
     * @param schemaName   schemaName of the function
     * @return List<Function>
     */
    public List<ai.inquery.spi.model.Function> functions(Connection connection, String databaseName,
                                                         String schemaName) {
        try (ResultSet resultSet = connection.getMetaData().getFunctions(databaseName, schemaName, null);) {
            return ResultSetUtils.toObjectList(resultSet, ai.inquery.spi.model.Function.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves a description of all the data types supported by this database. They are ordered by DATA_TYPE and then
     * by how closely the data type maps to the corresponding JDBC SQL type. If the database supports SQL distinct
     * types, then getTypeInfo() will return a single row with a TYPE_NAME of DISTINCT and a DATA_TYPE of
     * Types.DISTINCT. If the database supports SQL structured types, then getTypeInfo() will return a single row with a
     * TYPE_NAME of STRUCT and a DATA_TYPE of Types.STRUCT. If SQL distinct or structured types are supported, then
     * information on the individual types may be obtained from the getUDTs() method.
     *
     * @param connection connection
     * @return List<Function>
     */
    public List<Type> types(Connection connection) {
        try (ResultSet resultSet = connection.getMetaData().getTypeInfo();) {
            return ResultSetUtils.toObjectList(resultSet, ai.inquery.spi.model.Type.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * procedure list
     *
     * @param connection   connection
     * @param databaseName databaseName
     * @param schemaName   schemaName
     * @return List<Procedure>
     */
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        try (ResultSet resultSet = connection.getMetaData().getProcedures(databaseName, schemaName, null)) {
            return ResultSetUtils.toObjectList(resultSet, Procedure.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getDbVersion(Connection connection) {
        try {
            String dbVersion = connection.getMetaData().getDatabaseProductVersion();
            return dbVersion;
        } catch (Exception e) {
            log.error("get db version error", e);
        }
        return "";
    }

    @Override
    public List<ExecuteResult> execute(Command command) {
        if (StringUtils.isBlank(command.getScript())) {
            return Collections.emptyList();
        }
        // parse sql
        String type = InqueryContext.getConnectInfo().getDbType();
        DbType dbType = JdbcUtils.parse2DruidDbType(type);
        List<String> sqlList = Lists.newArrayList(command.getScript());
        if(!command.isSingle()) {
            sqlList = SqlUtils.parse(command.getScript(), dbType, true);
        }
        if (CollectionUtils.isEmpty(sqlList)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        List<ExecuteResult> result = new ArrayList<>();
        // Execute SQL
        for (String originalSql : sqlList) {
            ExecuteResult executeResult = executeSQL(originalSql, dbType, command);
            result.add(executeResult);
        }
        return result;
    }

    private ExecuteResult executeSQL(String originalSql, DbType dbType, Command param) {
        int pageNo = Optional.ofNullable(param.getPageNo()).orElse(1);
        int pageSize = Optional.ofNullable(param.getPageSize()).orElse(EasyToolsConstant.MAX_PAGE_SIZE);
        Integer offset = (pageNo - 1) * pageSize;
        Integer count = pageSize;
        SqlTypeEnum sqlType = getSqlType(dbType, originalSql);
        ExecuteResult executeResult = null;

        if (SqlTypeEnum.SELECT.equals(sqlType) && !SqlUtils.hasPageLimit(originalSql, dbType)) {
            String pageLimit = InqueryContext.getSqlBuilder().pageLimit(originalSql, offset, pageNo, pageSize);
            if (StringUtils.isNotBlank(pageLimit)) {
                executeResult = execute(pageLimit, 0, count);
            }
        }
        if (executeResult == null || !executeResult.getSuccess()) {
            executeResult = execute(originalSql, offset, count);
        }

        executeResult.setSqlType(sqlType.getCode());
        executeResult.setOriginalSql(originalSql);

        SqlUtils.buildCanEditResult(originalSql, dbType, executeResult);
        // Add row number
        addRowNumber(executeResult, pageNo, pageSize);
        //  Total number of fuzzy rows
        setPageInfo(executeResult, sqlType, pageNo, pageSize);
        return executeResult;
    }

    private SqlTypeEnum getSqlType(DbType dbType, String originalSql) {
        SqlTypeEnum sqlType = SqlTypeEnum.UNKNOWN;

        // Handle EXPLAIN queries - they return results like SELECT but use DB-specific syntax
        // that may not be parseable by Druid (e.g., Snowflake's "EXPLAIN USING JSON")
        String trimmedSql = originalSql.trim().toUpperCase();
        if (trimmedSql.startsWith("EXPLAIN")) {
            return SqlTypeEnum.SELECT;  // EXPLAIN returns result set
        }

        // parse sql
        String type = InqueryContext.getConnectInfo().getDbType();
        boolean supportDruid = !DataSourceTypeEnum.MONGODB.getCode().equals(type);
        SQLStatement sqlStatement = null;
        if (supportDruid) {
            try {
                sqlStatement = SQLUtils.parseSingleStatement(originalSql, dbType);
            } catch (Exception e) {
                log.warn("Failed to parse sql: {}", originalSql, e);
            }
        }

        // Mongodb is currently unable to recognize it, so every time a page is transmitted
        if (!supportDruid || (sqlStatement instanceof SQLSelectStatement)) {
            sqlType = SqlTypeEnum.SELECT;
        }
        return sqlType;
    }

    private void setPageInfo(ExecuteResult executeResult, SqlTypeEnum sqlType, int pageNo, int pageSize) {
        if (SqlTypeEnum.SELECT.equals(sqlType)) {
            executeResult.setPageNo(pageNo);
            executeResult.setPageSize(pageSize);
            executeResult.setHasNextPage(
                    CollectionUtils.size(executeResult.getDataList()) >= executeResult.getPageSize());
        } else {
            executeResult.setPageNo(pageNo);
            executeResult.setPageSize(CollectionUtils.size(executeResult.getDataList()));
            executeResult.setHasNextPage(Boolean.FALSE);
        }
        executeResult.setFuzzyTotal(calculateFuzzyTotal(pageNo, pageSize, executeResult));
    }


    private void addRowNumber(ExecuteResult executeResult, int pageNo, int pageSize) {
        List<Header> headers = executeResult.getHeaderList();
        Header rowNumberHeader = Header.builder()
                .name(I18nUtils.getMessage("sqlResult.rowNumber"))
                .dataType(DataTypeEnum.INQUERY_ROW_NUMBER
                        .getCode()).build();
        executeResult.setHeaderList(EasyCollectionUtils.union(Arrays.asList(rowNumberHeader), headers));

        // Add row number
        if (executeResult.getDataList() != null) {
            int rowNumberIncrement = 1 + Math.max(pageNo - 1, 0) * pageSize;
            for (int i = 0; i < executeResult.getDataList().size(); i++) {
                List<String> row = executeResult.getDataList().get(i);
                List<String> newRow = Lists.newArrayListWithExpectedSize(row.size() + 1);
                newRow.add(Integer.toString(i + rowNumberIncrement));
                newRow.addAll(row);
                executeResult.getDataList().set(i, newRow);
            }
        }
    }


    private String calculateFuzzyTotal(int pageNo, int pageSize, ExecuteResult executeResult) {
        int dataSize = CollectionUtils.size(executeResult.getDataList());
        if (pageSize <= 0) {
            return Integer.toString(dataSize);
        }
        int fuzzyTotal = Math.max(pageNo - 1, 0) * pageSize + dataSize;
        if (dataSize < pageSize) {
            return Integer.toString(fuzzyTotal);
        }
        return fuzzyTotal + "+";
    }

    private ExecuteResult execute(String sql, Integer offset, Integer count) {
        ExecuteResult executeResult;
        try {
            executeResult = SQLExecutor.getInstance().execute(sql, InqueryContext.getConnection(), true, offset, count);
        } catch (SQLException e) {
            log.error("Execute sql: {} exception", sql, e);
            executeResult = ExecuteResult.builder()
                    .sql(sql)
                    .success(Boolean.FALSE)
                    .message(e.getMessage())
                    .build();
        }
        return executeResult;
    }

    /**
     * Formats the given table name by stripping off any schema or catalog prefixes.
     * If the table name contains a dot ('.'), it splits the string by the dot
     * and returns the last part, which is generally the actual table name.
     * If the table name is blank (null, empty, or only whitespace), it returns the original table name.
     *
     * @param tableName the original table name, potentially including schema or catalog prefixes.
     * @return the formatted table name, or the original table name if it's blank or contains no dot.
     */
    public static String formatTableName(String tableName) {
        // Check if the table name is blank (null, empty, or only whitespace)
        if (StringUtils.isBlank(tableName)) {
            return tableName;
        }

        // Check if the table name contains a dot ('.')
        if (tableName.contains(".")) {
            // Split the table name by the dot and return the last part
            String[] split = tableName.split("\\.");
            return split[split.length - 1];
        }

        // Return the original table name if it contains no dot
        return tableName;
    }

    public void execute(Connection connection, String sql, int batchSize, ResultSetConsumer consumer) {
        try (Statement stmt = connection.createStatement()) {
            stmt.setFetchSize(batchSize);
            boolean query = stmt.execute(sql);
            // Represents the query
            if (query) {
                try (ResultSet rs = stmt.getResultSet()) {
                    consumer.accept(rs);
                }
            }
        } catch (Exception e) {
            log.error("execute error:{}", sql, e);
            throw new RuntimeException(e);
        }
    }

    public void executeBatchInsert(Connection connection, List<String> sqlCacheList) {
        try (Statement stmt = connection.createStatement()) {
            for (String sql : sqlCacheList) {
                stmt.addBatch(sql);
            }
            stmt.executeBatch();
            stmt.clearBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get foreign key information using JDBC DatabaseMetaData.
     * This is the default implementation that works with most databases.
     *
     * @param connection database connection
     * @param databaseName database/catalog name
     * @param schemaName schema name
     * @param tableName table name (if null, returns all FKs in schema)
     * @return list of foreign key relationships
     */
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            
            if (tableName != null) {
                // Get foreign keys for a specific table
                try (ResultSet rs = metaData.getImportedKeys(databaseName, schemaName, tableName)) {
                    while (rs.next()) {
                        ForeignKeyInfo fk = buildForeignKeyFromResultSet(rs);
                        foreignKeys.add(fk);
                    }
                }
            } else {
                // Get foreign keys for all tables in the schema
                // First, get all table names
                List<String> tableNames = tableNames(connection, databaseName, schemaName, null, new String[]{"TABLE"});
                for (String tblName : tableNames) {
                    try (ResultSet rs = metaData.getImportedKeys(databaseName, schemaName, tblName)) {
                        while (rs.next()) {
                            ForeignKeyInfo fk = buildForeignKeyFromResultSet(rs);
                            foreignKeys.add(fk);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error getting foreign keys for database: {}, schema: {}, table: {}", 
                    databaseName, schemaName, tableName, e);
        }
        return foreignKeys;
    }

    /**
     * Build ForeignKeyInfo from JDBC ResultSet.
     */
    private ForeignKeyInfo buildForeignKeyFromResultSet(ResultSet rs) throws SQLException {
        return ForeignKeyInfo.builder()
                .constraintName(rs.getString("FK_NAME"))
                .fkDatabaseName(rs.getString("FKTABLE_CAT"))
                .fkSchemaName(rs.getString("FKTABLE_SCHEM"))
                .fkTableName(rs.getString("FKTABLE_NAME"))
                .fkColumnName(rs.getString("FKCOLUMN_NAME"))
                .pkDatabaseName(rs.getString("PKTABLE_CAT"))
                .pkSchemaName(rs.getString("PKTABLE_SCHEM"))
                .pkTableName(rs.getString("PKTABLE_NAME"))
                .pkColumnName(rs.getString("PKCOLUMN_NAME"))
                .keySequence(rs.getInt("KEY_SEQ"))
                .updateRule(getUpdateDeleteRule(rs.getShort("UPDATE_RULE")))
                .deleteRule(getUpdateDeleteRule(rs.getShort("DELETE_RULE")))
                .build();
    }

    /**
     * Convert JDBC update/delete rule code to string.
     */
    private String getUpdateDeleteRule(short rule) {
        switch (rule) {
            case DatabaseMetaData.importedKeyCascade:
                return "CASCADE";
            case DatabaseMetaData.importedKeySetNull:
                return "SET NULL";
            case DatabaseMetaData.importedKeySetDefault:
                return "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict:
                return "RESTRICT";
            case DatabaseMetaData.importedKeyNoAction:
            default:
                return "NO ACTION";
        }
    }
}
