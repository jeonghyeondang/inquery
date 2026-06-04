package ai.inquery.plugin.bigquery;

import ai.inquery.spi.MetaData;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.inquery.spi.util.SortUtils.sortDatabase;

@Slf4j
public class BigQueryMetaData extends DefaultMetaService implements MetaData {

    // BigQuery internal datasets to exclude
    private List<String> systemDatabases = Arrays.asList();

    private List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA");

    @Override
    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    @Override
    public List<String> getSystemSchemas() {
        return systemSchemas;
    }

    /**
     * Get all projects (databases) accessible by the service account.
     * In BigQuery, "project" maps to "database" concept.
     */
    @Override
    public List<Database> databases(Connection connection) {
        // BigQuery doesn't have a direct way to list all projects via JDBC
        // The project is specified in the connection, so we return it from connection metadata
        List<Database> databases = new ArrayList<>();
        try {
            String catalog = connection.getCatalog();
            if (StringUtils.isNotBlank(catalog)) {
                Database database = new Database();
                database.setName(catalog);
                databases.add(database);
            }
        } catch (SQLException e) {
            log.error("Failed to get BigQuery project from connection", e);
        }
        return sortDatabase(databases, systemDatabases, connection);
    }

    /**
     * Get all datasets (schemas) in a project.
     * In BigQuery, "dataset" maps to "schema" concept.
     * Uses JDBC metadata API instead of SQL query to avoid SHOW statement issues.
     */
    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = new ArrayList<>();
        try {
            // Use JDBC metadata API to get schemas (datasets in BigQuery)
            // This avoids issues with BigQuery not supporting SHOW SCHEMAS statement
            java.sql.DatabaseMetaData metaData = connection.getMetaData();
            try (java.sql.ResultSet rs = metaData.getSchemas(databaseName, null)) {
                while (rs.next()) {
                    String schemaName = rs.getString("TABLE_SCHEM");
                    String catalogName = rs.getString("TABLE_CATALOG");
                    
                    // Skip system schemas
                    if (schemaName != null && !systemSchemas.contains(schemaName)) {
                        Schema schema = new Schema();
                        schema.setName(schemaName);
                        schema.setDatabaseName(catalogName != null ? catalogName : databaseName);
                        schemas.add(schema);
                    }
                }
            }
            
            // Sort schemas alphabetically
            schemas.sort((a, b) -> {
                if (a.getName() == null) return 1;
                if (b.getName() == null) return -1;
                return a.getName().compareTo(b.getName());
            });
            
            log.info("BigQuery schemas loaded: {} datasets for project: {}", schemas.size(), databaseName);
        } catch (SQLException e) {
            log.error("Failed to get BigQuery datasets using JDBC metadata, trying SQL query fallback", e);
            
            // Fallback: try direct SQL query if JDBC metadata fails
            try {
                String sql = "SELECT schema_name FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY schema_name";
                return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
                    List<Schema> fallbackSchemas = new ArrayList<>();
                    try {
                        while (resultSet.next()) {
                            String schemaName = resultSet.getString("schema_name");
                            if (schemaName != null && !systemSchemas.contains(schemaName)) {
                                Schema schema = new Schema();
                                schema.setName(schemaName);
                                schema.setDatabaseName(databaseName);
                                fallbackSchemas.add(schema);
                            }
                        }
                    } catch (SQLException ex) {
                        log.error("Failed to get BigQuery datasets via SQL fallback", ex);
                        throw new RuntimeException(ex);
                    }
                    return fallbackSchemas;
                });
            } catch (Exception fallbackEx) {
                log.error("Both JDBC metadata and SQL fallback failed for BigQuery schemas", fallbackEx);
                throw new RuntimeException("Failed to get BigQuery datasets", e);
            }
        }
        return schemas;
    }

    /**
     * Get all tables in a dataset.
     */
    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT table_name, table_type FROM `")
           .append(databaseName).append("`.`").append(schemaName).append("`.INFORMATION_SCHEMA.TABLES");

        if (StringUtils.isNotBlank(tableName)) {
            sql.append(" WHERE table_name = '").append(tableName).append("'");
        }

        sql.append(" ORDER BY table_name");

        return SQLExecutor.getInstance().execute(connection, sql.toString(), resultSet -> {
            List<Table> tables = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    Table table = new Table();
                    table.setName(resultSet.getString("table_name"));
                    table.setSchemaName(schemaName);
                    table.setDatabaseName(databaseName);

                    String tableType = resultSet.getString("table_type");
                    if ("VIEW".equalsIgnoreCase(tableType)) {
                        table.setType("VIEW");
                    } else {
                        table.setType("TABLE");
                    }
                    tables.add(table);
                }
            } catch (SQLException e) {
                log.error("Failed to get BigQuery tables", e);
                throw new RuntimeException(e);
            }
            return tables;
        });
    }

    /**
     * Get all columns for a table, including nested fields for STRUCT/RECORD types.
     * Uses COLUMN_FIELD_PATHS to get complete nested structure.
     */
    @Override
    public List<TableColumn> columns(Connection connection, @NotEmpty String databaseName, String schemaName, @NotEmpty String tableName) {
        log.info("BigQueryMetaData.columns() called - databaseName: {}, schemaName: {}, tableName: {}", 
            databaseName, schemaName, tableName);
        
        String effectiveDatabaseName = databaseName;
        String effectiveSchemaName = schemaName;
        String effectiveTableName = tableName;

        // Parse fully qualified table name if needed
        if (StringUtils.isNotBlank(tableName) && tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 3) {
                effectiveDatabaseName = parts[0];
                effectiveSchemaName = parts[1];
                effectiveTableName = parts[2];
            } else if (parts.length == 2) {
                effectiveSchemaName = parts[0];
                effectiveTableName = parts[1];
            }
        }

        // Get database from connection if not provided
        if (StringUtils.isBlank(effectiveDatabaseName)) {
            try {
                effectiveDatabaseName = connection.getCatalog();
            } catch (SQLException e) {
                log.warn("Could not get database from connection: {}", e.getMessage());
            }
        }

        if (StringUtils.isBlank(effectiveDatabaseName) || StringUtils.isBlank(effectiveSchemaName)) {
            log.warn("Database and schema are required for BigQuery column query - databaseName: {}, schemaName: {}", 
                effectiveDatabaseName, effectiveSchemaName);
            return java.util.Collections.emptyList();
        }

        if (effectiveDatabaseName.equals(effectiveSchemaName)) {
            log.warn("BigQuery schemaName equals databaseName (project ID used as dataset) - databaseName: {}, schemaName: {}, tableName: {}",
                effectiveDatabaseName, effectiveSchemaName, effectiveTableName);
            return java.util.Collections.emptyList();
        }

        // Final variables for lambda
        final String finalDatabaseName = effectiveDatabaseName;
        final String finalSchemaName = effectiveSchemaName;
        final String finalTableName = effectiveTableName;

        // Use COLUMN_FIELD_PATHS to get nested structure
        String sql = buildNestedColumnsQuery(finalDatabaseName, finalSchemaName, finalTableName);

        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            // Map to store columns by field_path for building hierarchy
            Map<String, TableColumn> columnsByPath = new HashMap<>();
            List<TableColumn> rootColumns = new ArrayList<>();
            // Recovered DDL ordinal per root column, used to enforce schema order in Java.
            Map<TableColumn, Integer> rootOrdinals = new HashMap<>();
            int ordinalPosition = 0;

            try {
                while (resultSet.next()) {
                    String fieldPath = resultSet.getString("field_path");
                    String columnName = resultSet.getString("column_name");
                    String dataType = resultSet.getString("data_type");
                    String tblName = resultSet.getString("table_name");
                    String description = null;
                    try {
                        description = resultSet.getString("description");
                    } catch (SQLException ignored) {
                        // description column might not exist in older BigQuery versions
                    }
                    Integer rootOrdinal = null;
                    try {
                        int ord = resultSet.getInt("root_ordinal");
                        if (!resultSet.wasNull()) {
                            rootOrdinal = ord;
                        }
                    } catch (SQLException ignored) {
                        // root_ordinal not present (older query path); fall back to scan order
                    }

                    TableColumn column = new TableColumn();
                    column.setFieldPath(fieldPath);
                    column.setTableName(tblName);
                    column.setSchemaName(finalSchemaName);
                    column.setDatabaseName(finalDatabaseName);
                    column.setColumnType(dataType);
                    column.setComment(description);
                    column.setOrdinalPosition(++ordinalPosition);

                    // Check if this is a nested field (contains '.')
                    if (fieldPath.contains(".")) {
                        // This is a nested field
                        String[] pathParts = fieldPath.split("\\.");
                        String leafName = pathParts[pathParts.length - 1];
                        String parentPath = fieldPath.substring(0, fieldPath.lastIndexOf('.'));

                        column.setName(leafName);
                        column.setParentColumnName(parentPath);

                        // Find parent and add as child
                        TableColumn parent = columnsByPath.get(parentPath);
                        if (parent != null) {
                            if (parent.getChildren() == null) {
                                parent.setChildren(new ArrayList<>());
                            }
                            parent.getChildren().add(column);
                        }
                    } else {
                        // This is a root-level column
                        column.setName(columnName);
                        rootColumns.add(column);
                        if (rootOrdinal != null) {
                            rootOrdinals.put(column, rootOrdinal);
                        }
                    }

                    columnsByPath.put(fieldPath, column);
                }
            } catch (SQLException e) {
                log.error("Failed to get BigQuery columns", e);
                throw new RuntimeException(e);
            }

            // Enforce real DDL declaration order on root columns using the recovered
            // ordinal_position. This is the single source of truth for column ordering and
            // keeps the data-catalog and workspace schema views consistent. Roots whose
            // ordinal could not be recovered are kept stably after the ordered ones (the
            // SQL ORDER BY already left them in field_path order). Children keep their
            // field_path order within each struct (COLUMN_FIELD_PATHS has no per-leaf ordinal).
            if (!rootOrdinals.isEmpty()) {
                List<TableColumn> ordered = new ArrayList<>(rootColumns);
                ordered.sort((a, b) -> {
                    int oa = rootOrdinals.getOrDefault(a, Integer.MAX_VALUE);
                    int ob = rootOrdinals.getOrDefault(b, Integer.MAX_VALUE);
                    return Integer.compare(oa, ob);
                });
                // Reassign a clean 1-based ordinalPosition reflecting the final root order.
                int pos = 1;
                for (TableColumn col : ordered) {
                    col.setOrdinalPosition(pos++);
                }
                return ordered;
            }
            return rootColumns;
        });
    }

    /**
     * Build query using COLUMN_FIELD_PATHS for nested structure support.
     */
    private String buildNestedColumnsQuery(String databaseName, String schemaName, String tableName) {
        // COLUMN_FIELD_PATHS exposes nested fields but has no ordinal_position, so on its
        // own it can only be ordered alphabetically by field_path. Join INFORMATION_SCHEMA.COLUMNS
        // to recover the real DDL declaration order of the root columns, then order roots by
        // ordinal_position and nested leaves by field_path within each root. This makes the
        // column list match the table's actual schema/DDL order instead of alphabetical.
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT fp.table_name AS table_name, fp.column_name AS column_name, ");
        sql.append("fp.field_path AS field_path, fp.data_type AS data_type, fp.description AS description, ");
        // Recover the root column's DDL declaration order so the result can be ordered by
        // it instead of alphabetically. Selected explicitly so we can also enforce ordering
        // in Java (not just rely on the JDBC driver preserving SQL ORDER BY).
        sql.append("c.ordinal_position AS root_ordinal ");
        sql.append("FROM `").append(databaseName).append("`.`").append(schemaName).append("`.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS fp ");
        sql.append("LEFT JOIN `").append(databaseName).append("`.`").append(schemaName).append("`.INFORMATION_SCHEMA.COLUMNS c ");
        sql.append("ON fp.table_name = c.table_name AND fp.column_name = c.column_name ");
        
        if (StringUtils.isNotBlank(tableName)) {
            sql.append("WHERE fp.table_name = '").append(tableName).append("' ");
        }
        
        sql.append("ORDER BY fp.table_name, COALESCE(c.ordinal_position, 999999), fp.field_path");
        return sql.toString();
    }

    /**
     * Fallback query using regular COLUMNS (for non-nested tables).
     */
    private String buildColumnsQuery(String databaseName, String schemaName, String tableName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT column_name, table_name, ordinal_position, data_type, is_nullable ");
        sql.append("FROM `").append(databaseName).append("`.`").append(schemaName).append("`.INFORMATION_SCHEMA.COLUMNS ");
        
        if (StringUtils.isNotBlank(tableName)) {
            sql.append("WHERE table_name = '").append(tableName).append("' ");
        }
        
        sql.append("ORDER BY table_name, ordinal_position");
        return sql.toString();
    }

    /**
     * Get table DDL (CREATE TABLE statement).
     */
    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(
            "SELECT ddl FROM `%s`.`%s`.INFORMATION_SCHEMA.TABLES WHERE table_name = '%s'",
            databaseName, schemaName, tableName
        );

        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString("ddl");
                }
            } catch (SQLException e) {
                log.error("Failed to get BigQuery table DDL", e);
            }
            return null;
        });
    }
}

