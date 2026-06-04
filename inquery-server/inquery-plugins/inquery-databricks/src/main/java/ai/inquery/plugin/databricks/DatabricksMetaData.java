package ai.inquery.plugin.databricks;

import ai.inquery.spi.MetaData;
import ai.inquery.spi.ValueProcessor;
import ai.inquery.spi.jdbc.DefaultMetaService;
import ai.inquery.spi.model.Database;
import ai.inquery.spi.model.Schema;
import ai.inquery.spi.model.Table;
import ai.inquery.spi.model.TableColumn;
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

/**
 * Databricks metadata service implementation.
 * Supports Unity Catalog with catalog.schema.table hierarchy.
 * 
 * In Databricks:
 * - Catalog maps to "Database" concept in Inquery
 * - Schema maps to "Schema" concept in Inquery
 */
@Slf4j
public class DatabricksMetaData extends DefaultMetaService implements MetaData {

    // System catalogs to exclude
    private List<String> systemDatabases = Arrays.asList(
        "system",
        "__databricks_internal"
    );

    // System schemas to exclude
    private List<String> systemSchemas = Arrays.asList(
        "information_schema",
        "__databricks_internal"
    );

    @Override
    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    @Override
    public List<String> getSystemSchemas() {
        return systemSchemas;
    }

    /**
     * Return Databricks-specific value processor for proper data type handling.
     */
    @Override
    public ValueProcessor getValueProcessor() {
        return new DatabricksValueProcessor();
    }

    /**
     * Get all catalogs (databases) accessible by the user.
     * In Databricks Unity Catalog, "catalog" maps to "database" concept.
     */
    @Override
    public List<Database> databases(Connection connection) {
        List<Database> databases = new ArrayList<>();
        
        String sql = "SHOW CATALOGS";
        
        try {
            return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
                List<Database> catalogs = new ArrayList<>();
                try {
                    while (resultSet.next()) {
                        String catalogName = resultSet.getString("catalog");
                        if (catalogName != null && !systemDatabases.contains(catalogName.toLowerCase())) {
                            Database database = new Database();
                            database.setName(catalogName);
                            catalogs.add(database);
                        }
                    }
                } catch (SQLException e) {
                    log.error("Failed to get Databricks catalogs", e);
                    throw new RuntimeException(e);
                }
                return catalogs;
            });
        } catch (Exception e) {
            log.error("Failed to execute SHOW CATALOGS, trying JDBC metadata fallback", e);
            
            // Fallback: try JDBC metadata API
            try {
                java.sql.DatabaseMetaData metaData = connection.getMetaData();
                try (java.sql.ResultSet rs = metaData.getCatalogs()) {
                    while (rs.next()) {
                        String catalogName = rs.getString("TABLE_CAT");
                        if (catalogName != null && !systemDatabases.contains(catalogName.toLowerCase())) {
                            Database database = new Database();
                            database.setName(catalogName);
                            databases.add(database);
                        }
                    }
                }
            } catch (SQLException ex) {
                log.error("JDBC metadata fallback also failed", ex);
            }
        }
        
        return sortDatabase(databases, systemDatabases, connection);
    }

    /**
     * Get all schemas in a catalog.
     * In Databricks Unity Catalog, schemas are within catalogs.
     */
    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = new ArrayList<>();
        
        String sql = StringUtils.isNotBlank(databaseName) 
            ? "SHOW SCHEMAS IN " + quoteIdentifier(databaseName)
            : "SHOW SCHEMAS";
        
        try {
            return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
                List<Schema> schemaList = new ArrayList<>();
                try {
                    while (resultSet.next()) {
                        String schemaName = resultSet.getString("databaseName");
                        if (schemaName == null) {
                            // Try alternative column name
                            schemaName = resultSet.getString("namespace");
                        }
                        if (schemaName == null) {
                            schemaName = resultSet.getString(1);
                        }
                        
                        if (schemaName != null && !systemSchemas.contains(schemaName.toLowerCase())) {
                            Schema schema = new Schema();
                            schema.setName(schemaName);
                            schema.setDatabaseName(databaseName);
                            schemaList.add(schema);
                        }
                    }
                } catch (SQLException e) {
                    log.error("Failed to get Databricks schemas", e);
                    throw new RuntimeException(e);
                }
                return schemaList;
            });
        } catch (Exception e) {
            log.error("Failed to execute SHOW SCHEMAS, trying JDBC metadata fallback", e);
            
            // Fallback: try JDBC metadata API
            try {
                java.sql.DatabaseMetaData metaData = connection.getMetaData();
                try (java.sql.ResultSet rs = metaData.getSchemas(databaseName, null)) {
                    while (rs.next()) {
                        String schemaName = rs.getString("TABLE_SCHEM");
                        if (schemaName != null && !systemSchemas.contains(schemaName.toLowerCase())) {
                            Schema schema = new Schema();
                            schema.setName(schemaName);
                            schema.setDatabaseName(databaseName);
                            schemas.add(schema);
                        }
                    }
                }
            } catch (SQLException ex) {
                log.error("JDBC metadata fallback also failed", ex);
            }
        }
        
        // Sort schemas alphabetically
        schemas.sort((a, b) -> {
            if (a.getName() == null) return 1;
            if (b.getName() == null) return -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        log.info("Databricks schemas loaded: {} schemas for catalog: {}", schemas.size(), databaseName);
        return schemas;
    }

    /**
     * Get all tables in a schema.
     */
    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder sql = new StringBuilder();
        
        if (StringUtils.isNotBlank(databaseName) && StringUtils.isNotBlank(schemaName)) {
            sql.append("SHOW TABLES IN ").append(quoteIdentifier(databaseName))
               .append(".").append(quoteIdentifier(schemaName));
        } else if (StringUtils.isNotBlank(schemaName)) {
            sql.append("SHOW TABLES IN ").append(quoteIdentifier(schemaName));
        } else {
            sql.append("SHOW TABLES");
        }

        return SQLExecutor.getInstance().execute(connection, sql.toString(), resultSet -> {
            List<Table> tables = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String tblName = resultSet.getString("tableName");
                    if (tblName == null) {
                        tblName = resultSet.getString(2); // Second column usually contains table name
                    }
                    
                    // Filter by table name if provided
                    if (StringUtils.isNotBlank(tableName) && !tableName.equalsIgnoreCase(tblName)) {
                        continue;
                    }
                    
                    Table table = new Table();
                    table.setName(tblName);
                    table.setSchemaName(schemaName);
                    table.setDatabaseName(databaseName);
                    
                    // Try to get table type
                    try {
                        boolean isTemporary = resultSet.getBoolean("isTemporary");
                        table.setType(isTemporary ? "TEMPORARY" : "TABLE");
                    } catch (SQLException ignored) {
                        table.setType("TABLE");
                    }
                    
                    tables.add(table);
                }
            } catch (SQLException e) {
                log.error("Failed to get Databricks tables", e);
                throw new RuntimeException(e);
            }
            return tables;
        });
    }

    /**
     * Get all columns for a table.
     */
    @Override
    public List<TableColumn> columns(Connection connection, @NotEmpty String databaseName, String schemaName, @NotEmpty String tableName) {
        log.info("DatabricksMetaData.columns() called - databaseName: {}, schemaName: {}, tableName: {}", 
            databaseName, schemaName, tableName);
        
        // Handle null or empty tableName - return empty list
        if (StringUtils.isBlank(tableName)) {
            log.warn("tableName is null or empty, returning empty column list");
            return new ArrayList<>();
        }
        
        String fullTableName = buildFullTableName(databaseName, schemaName, tableName);
        String sql = "DESCRIBE TABLE " + fullTableName;

        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<TableColumn> columns = new ArrayList<>();
            int ordinalPosition = 0;
            
            try {
                while (resultSet.next()) {
                    String colName = resultSet.getString("col_name");
                    
                    // Skip partition info and empty rows
                    if (StringUtils.isBlank(colName) || colName.startsWith("#") || colName.contains("Partition")) {
                        continue;
                    }
                    
                    String dataType = resultSet.getString("data_type");
                    String comment = null;
                    try {
                        comment = resultSet.getString("comment");
                    } catch (SQLException ignored) {
                        // comment column might not exist
                    }

                    TableColumn column = new TableColumn();
                    column.setName(colName);
                    column.setTableName(tableName);
                    column.setSchemaName(schemaName);
                    column.setDatabaseName(databaseName);
                    column.setColumnType(dataType);
                    column.setComment(comment);
                    column.setOrdinalPosition(++ordinalPosition);
                    
                    columns.add(column);
                }
            } catch (SQLException e) {
                log.error("Failed to get Databricks columns", e);
                throw new RuntimeException(e);
            }
            return columns;
        });
    }

    /**
     * Get table DDL (CREATE TABLE statement).
     */
    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String fullTableName = buildFullTableName(databaseName, schemaName, tableName);
        String sql = "SHOW CREATE TABLE " + fullTableName;

        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString("createtab_stmt");
                }
            } catch (SQLException e) {
                log.error("Failed to get Databricks table DDL", e);
            }
            return null;
        });
    }

    /**
     * Build fully qualified table name.
     */
    private String buildFullTableName(String catalog, String schema, String table) {
        StringBuilder sb = new StringBuilder();
        
        if (StringUtils.isNotBlank(catalog)) {
            sb.append(quoteIdentifier(catalog)).append(".");
        }
        if (StringUtils.isNotBlank(schema)) {
            sb.append(quoteIdentifier(schema)).append(".");
        }
        sb.append(quoteIdentifier(table));
        
        return sb.toString();
    }

    /**
     * Quote identifier using backticks (Databricks style).
     */
    private String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        // Use backticks for Databricks
        return "`" + identifier.replace("`", "``") + "`";
    }
}
