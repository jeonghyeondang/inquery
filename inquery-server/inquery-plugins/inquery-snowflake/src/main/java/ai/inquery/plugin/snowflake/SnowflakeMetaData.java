package ai.inquery.plugin.snowflake;

import ai.inquery.spi.MetaData;
import ai.inquery.spi.jdbc.DefaultMetaService;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.SQLExecutor;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ai.inquery.spi.util.SortUtils.sortDatabase;

@Slf4j
public class SnowflakeMetaData extends DefaultMetaService implements MetaData {

    private List<String> systemDatabases = Arrays.asList("SNOWFLAKE", "SNOWFLAKE_SAMPLE_DATA");

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> list = SQLExecutor.getInstance().execute(connection, "SHOW DATABASES;", resultSet -> {
            List<Database> databases = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("name");
                    Database database = new Database();
                    database.setName(dbName);
                    databases.add(database);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return databases;
        });
        return sortDatabase(list, systemDatabases, connection);
    }

    private List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA");

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        // Build fully qualified table name for GET_DDL
        // Quote identifiers with double quotes to handle special characters ($, @, . etc)
        String qualifiedName = String.format("\"%s\".\"%s\".\"%s\"", databaseName, schemaName, tableName);
        String sql = String.format("SELECT GET_DDL('TABLE', '%s')", qualifiedName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Override
    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    @Override
    public List<String> getSystemSchemas() {
        return systemSchemas;
    }

    /**
     * Override columns() to use INFORMATION_SCHEMA.COLUMNS for faster querying
     * This is especially faster for VIEWs compared to JDBC DatabaseMetaData.getColumns()
     * 
     * @param connection database connection
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table/view name
     * @return list of table columns
     */
    @Override
    public List<TableColumn> columns(Connection connection, @NotEmpty String databaseName, String schemaName, @NotEmpty String tableName) {
        String effectiveDatabaseName = databaseName;
        String effectiveSchemaName = schemaName;
        String effectiveTableName = tableName;
        
        // Always try to parse fully qualified table name (DATABASE.SCHEMA.TABLE)
        // This handles cases where tableName contains the full path regardless of databaseName parameter
        if (StringUtils.isNotBlank(tableName) && tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 3) {
                // Format: DATABASE.SCHEMA.TABLE - always use parsed values
                effectiveDatabaseName = parts[0];
                effectiveSchemaName = parts[1];
                effectiveTableName = parts[2];
                log.debug("Parsed fully qualified name: db={}, schema={}, table={}", effectiveDatabaseName, effectiveSchemaName, effectiveTableName);
            } else if (parts.length == 2 && StringUtils.isBlank(effectiveDatabaseName)) {
                // Format: SCHEMA.TABLE - only parse if databaseName is missing
                effectiveSchemaName = parts[0];
                effectiveTableName = parts[1];
                log.debug("Parsed schema.table: schema={}, table={}", effectiveSchemaName, effectiveTableName);
            }
        }
        
        // If databaseName is still missing, try to get it from connection
        if (StringUtils.isBlank(effectiveDatabaseName)) {
            try {
                effectiveDatabaseName = connection.getCatalog();
                log.debug("databaseName was null, got from connection: {}", effectiveDatabaseName);
            } catch (SQLException e) {
                log.warn("Could not get database from connection, falling back to JDBC metadata: {}", e.getMessage());
                return super.columns(connection, databaseName, schemaName, tableName);
            }
        }
        
        // Still null? Fallback to JDBC
        if (StringUtils.isBlank(effectiveDatabaseName)) {
            log.warn("databaseName is still null, falling back to JDBC metadata for table: {}", tableName);
            return super.columns(connection, databaseName, schemaName, tableName);
        }
        
        // Use INFORMATION_SCHEMA.COLUMNS for faster querying (works for both TABLE and VIEW)
        String sql = buildInformationSchemaColumnsQuery(effectiveDatabaseName, effectiveSchemaName, effectiveTableName);
        
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<TableColumn> columns = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    TableColumn column = new TableColumn();
                    
                    // Basic column information
                    column.setName(resultSet.getString("COLUMN_NAME"));
                    column.setTableName(resultSet.getString("TABLE_NAME"));
                    column.setSchemaName(resultSet.getString("TABLE_SCHEMA"));
                    column.setDatabaseName(resultSet.getString("TABLE_CATALOG"));
                    column.setOrdinalPosition(resultSet.getInt("ORDINAL_POSITION"));
                    
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
                    
                    // Default value
                    String columnDefault = resultSet.getString("COLUMN_DEFAULT");
                    if (StringUtils.isNotBlank(columnDefault)) {
                        column.setDefaultValue(columnDefault);
                    }
                    
                    // Comment
                    String comment = resultSet.getString("COMMENT");
                    if (StringUtils.isNotBlank(comment)) {
                        column.setComment(comment);
                    }
                    
                    // Character maximum length (for string types)
                    String charMaxLength = resultSet.getString("CHARACTER_MAXIMUM_LENGTH");
                    if (StringUtils.isNotBlank(charMaxLength)) {
                        try {
                            column.setColumnSize(Integer.parseInt(charMaxLength));
                        } catch (NumberFormatException e) {
                            // Ignore if not a valid number
                        }
                    }
                    
                    // Numeric precision (for numeric types)
                    String numericPrecision = resultSet.getString("NUMERIC_PRECISION");
                    if (StringUtils.isNotBlank(numericPrecision)) {
                        try {
                            column.setColumnSize(Integer.parseInt(numericPrecision));
                        } catch (NumberFormatException e) {
                            // Ignore if not a valid number
                        }
                    }
                    
                    // Numeric scale (for numeric types)
                    String numericScale = resultSet.getString("NUMERIC_SCALE");
                    if (StringUtils.isNotBlank(numericScale)) {
                        try {
                            column.setDecimalDigits(Integer.parseInt(numericScale));
                        } catch (NumberFormatException e) {
                            // Ignore if not a valid number
                        }
                    }
                    
                    columns.add(column);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error reading column information from INFORMATION_SCHEMA.COLUMNS", e);
            }
            return columns;
        });
    }

    /**
     * Build SQL query to fetch columns from INFORMATION_SCHEMA.COLUMNS
     * 
     * IMPORTANT: For Snowflake, we MUST use a fully qualified name (DATABASE.INFORMATION_SCHEMA.COLUMNS)
     * because the session may not have a current database set.
     * 
     * @param databaseName database name (TABLE_CATALOG) - REQUIRED for Snowflake
     * @param schemaName schema name (TABLE_SCHEMA)
     * @param tableName table/view name (TABLE_NAME)
     * @return SQL query string
     */
    private String buildInformationSchemaColumnsQuery(String databaseName, String schemaName, String tableName) {
        // databaseName should already be validated by caller, but double-check
        if (StringUtils.isBlank(databaseName)) {
            log.error("databaseName is required for Snowflake INFORMATION_SCHEMA queries - this should not happen");
            throw new IllegalArgumentException("databaseName is required for Snowflake INFORMATION_SCHEMA queries");
        }
        
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("TABLE_CATALOG, ");
        sql.append("TABLE_SCHEMA, ");
        sql.append("TABLE_NAME, ");
        sql.append("COLUMN_NAME, ");
        sql.append("ORDINAL_POSITION, ");
        sql.append("DATA_TYPE, ");
        sql.append("CHARACTER_MAXIMUM_LENGTH, ");
        sql.append("NUMERIC_PRECISION, ");
        sql.append("NUMERIC_SCALE, ");
        sql.append("IS_NULLABLE, ");
        sql.append("COLUMN_DEFAULT, ");
        sql.append("COMMENT ");
        sql.append("FROM ");
        
        // Use fully qualified name: DATABASE.INFORMATION_SCHEMA.COLUMNS
        // Quote database name with double quotes to handle special characters ($, @, . etc)
        sql.append("\"").append(databaseName).append("\".INFORMATION_SCHEMA.COLUMNS ");
        sql.append("WHERE TABLE_CATALOG = '").append(databaseName).append("' ");
        
        if (StringUtils.isNotBlank(schemaName)) {
            sql.append("AND TABLE_SCHEMA = '").append(schemaName).append("' ");
        }
        
        if (StringUtils.isNotBlank(tableName)) {
            sql.append("AND TABLE_NAME = '").append(tableName).append("' ");
        }
        
        sql.append("ORDER BY ORDINAL_POSITION");

        log.debug("Snowflake columns query: {}", sql);
        return sql.toString();
    }

    /**
     * Override foreignKeys() to use SHOW IMPORTED KEYS for Snowflake.
     * Snowflake stores FK constraints but doesn't enforce them (informational only).
     * 
     * Note: Snowflake's INFORMATION_SCHEMA.KEY_COLUMN_USAGE doesn't contain
     * referenced table/column information, so we must use SHOW IMPORTED KEYS instead.
     * 
     * @param connection database connection
     * @param databaseName database name
     * @param schemaName schema name
     * @return list of foreign key relationships
     */
    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String databaseName, String schemaName) {
        return foreignKeys(connection, databaseName, schemaName, null);
    }

    /**
     * Override foreignKeys() to use SHOW IMPORTED KEYS for Snowflake.
     * 
     * SHOW IMPORTED KEYS output columns:
     * - pk_database_name, pk_schema_name, pk_table_name, pk_column_name
     * - fk_database_name, fk_schema_name, fk_table_name, fk_column_name
     * - key_sequence, update_rule, delete_rule, fk_name
     * 
     * @param connection database connection
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name (optional)
     * @return list of foreign key relationships
     */
    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = buildShowImportedKeysCommand(databaseName, schemaName, tableName);
        
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    ForeignKeyInfo fk = ForeignKeyInfo.builder()
                            .constraintName(resultSet.getString("fk_name"))
                            .fkDatabaseName(resultSet.getString("fk_database_name"))
                            .fkSchemaName(resultSet.getString("fk_schema_name"))
                            .fkTableName(resultSet.getString("fk_table_name"))
                            .fkColumnName(resultSet.getString("fk_column_name"))
                            .pkDatabaseName(resultSet.getString("pk_database_name"))
                            .pkSchemaName(resultSet.getString("pk_schema_name"))
                            .pkTableName(resultSet.getString("pk_table_name"))
                            .pkColumnName(resultSet.getString("pk_column_name"))
                            .keySequence(resultSet.getInt("key_sequence"))
                            .deleteRule(resultSet.getString("delete_rule"))
                            .updateRule(resultSet.getString("update_rule"))
                            .build();
                    foreignKeys.add(fk);
                }
            } catch (SQLException e) {
                log.error("Error reading foreign key information using SHOW IMPORTED KEYS", e);
            }
            return foreignKeys;
        });
    }

    /**
     * Build SHOW IMPORTED KEYS command for Snowflake.
     * 
     * Usage:
     * - SHOW IMPORTED KEYS IN DATABASE <database_name>;
     * - SHOW IMPORTED KEYS IN SCHEMA <database_name>.<schema_name>;
     * - SHOW IMPORTED KEYS IN TABLE <database_name>.<schema_name>.<table_name>;
     * 
     * Note: Identifiers are quoted with double quotes to handle special characters ($, @, . etc)
     */
    private String buildShowImportedKeysCommand(String databaseName, String schemaName, String tableName) {
        StringBuilder sql = new StringBuilder("SHOW IMPORTED KEYS IN ");
        
        if (StringUtils.isNotBlank(tableName) && StringUtils.isNotBlank(schemaName) && StringUtils.isNotBlank(databaseName)) {
            // Specific table - quote all identifiers
            sql.append("TABLE \"").append(databaseName).append("\".\"").append(schemaName).append("\".\"").append(tableName).append("\"");
        } else if (StringUtils.isNotBlank(schemaName) && StringUtils.isNotBlank(databaseName)) {
            // Entire schema - quote all identifiers
            sql.append("SCHEMA \"").append(databaseName).append("\".\"").append(schemaName).append("\"");
        } else if (StringUtils.isNotBlank(databaseName)) {
            // Entire database - quote identifier
            sql.append("DATABASE \"").append(databaseName).append("\"");
        } else {
            // Default to current database
            sql.append("DATABASE");
        }
        
        return sql.toString();
    }

    /**
     * Override functions() to use SHOW FUNCTIONS or INFORMATION_SCHEMA.FUNCTIONS
     * This provides better support for Snowflake-specific functions
     * 
     * @param connection database connection
     * @param databaseName database name
     * @param schemaName schema name
     * @return list of functions
     */
    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        // Try using INFORMATION_SCHEMA.FUNCTIONS first (more reliable for built-in functions)
        // Quote database name with double quotes to handle special characters ($, @, . etc)
        String sql;
        if (StringUtils.isNotBlank(databaseName) && StringUtils.isNotBlank(schemaName)) {
            sql = String.format(
                "SELECT FUNCTION_NAME, FUNCTION_SCHEMA, FUNCTION_CATALOG " +
                "FROM \"%s\".INFORMATION_SCHEMA.FUNCTIONS " +
                "WHERE FUNCTION_SCHEMA = '%s' " +
                "ORDER BY FUNCTION_NAME",
                databaseName, schemaName
            );
        } else if (StringUtils.isNotBlank(databaseName)) {
            sql = String.format(
                "SELECT FUNCTION_NAME, FUNCTION_SCHEMA, FUNCTION_CATALOG " +
                "FROM \"%s\".INFORMATION_SCHEMA.FUNCTIONS " +
                "ORDER BY FUNCTION_NAME",
                databaseName
            );
        } else {
            // Fallback to JDBC metadata if database/schema not specified
            return super.functions(connection, databaseName, schemaName);
        }

        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Function> functions = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    Function function = new Function();
                    function.setFunctionName(resultSet.getString("FUNCTION_NAME"));
                    function.setSchemaName(resultSet.getString("FUNCTION_SCHEMA"));
                    function.setDatabaseName(resultSet.getString("FUNCTION_CATALOG"));
                    functions.add(function);
                }
            } catch (SQLException e) {
                // If INFORMATION_SCHEMA query fails, fallback to JDBC metadata
                return super.functions(connection, databaseName, schemaName);
            }
            return functions;
        });
    }
}



