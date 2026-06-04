package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.DataCatalogSaveParam;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;

import java.util.List;
import java.util.Map;

/**
 * Data catalog service
 *
 * @since 2025-01-27
 */
public interface DataCatalogService {

    /**
     * Query catalog information for a table
     *
     * @param param table query param
     * @return catalog data map containing table description and columns
     */
    DataResult<Map<String, Object>> queryCatalog(TableQueryParam param);

    /**
     * Save or update catalog information
     *
     * @param param catalog save param
     * @return action result
     */
    ActionResult saveCatalog(DataCatalogSaveParam param);

    /**
     * Collect example values for columns
     *
     * @param param table query param
     * @return map of column names to example values
     */
    DataResult<Map<String, Object>> collectExampleValues(TableQueryParam param);

    /**
     * Collect AI metadata for a table
     *
     * @param param table query param
     * @return data result containing table description and column descriptions
     */
    DataResult<Map<String, Object>> collectAIMetadata(TableQueryParam param);

    /**
     * Collect AI metadata for all tables in a data source
     * Query schemas for a data source
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name (optional)
     * @return list of Schema objects
     */
    DataResult<List<ai.inquery.spi.model.Schema>> querySchemaByDataSourceId(Long dataSourceId, String databaseName, String schemaName);


    /**
     * Batch load all column information for tables and views in a schema
     * This is optimized for initial data-catalog page load
     *
     * @param param table query param (databaseName and schemaName are required)
     * @return map of table/view names to their column lists
     *         Key format: "database.schema.table" (e.g., "PROD.APP.FCT_STUDIO_TS")
     *         Value: list of TableColumn objects
     */
    DataResult<Map<String, List<ai.inquery.spi.model.TableColumn>>> batchLoadColumns(TableQueryParam param);

    /**
     * Load tables and views for a data source
     * This is optimized for data-catalog page initial load (cached for 1 day)
     * 
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableNames list of table names to filter by (optional)
     * @param refresh whether to refresh cache
     * @return map of database names to their table/view lists grouped by schema
     *         Key format: "database.schema" (e.g., "mydb.public")
     *         Value: list of Table objects (both BASE TABLE and VIEW)
     */
    DataResult<Map<String, List<ai.inquery.spi.model.Table>>> queryTableByDataSourceId(Long dataSourceId, String databaseName, String schemaName, List<String> tableNames, boolean refresh);

    /**
     * Load databases for a data source
     * 
     * @param dataSourceId data source ID
     * @return list of Database objects
     */
    DataResult<List<ai.inquery.spi.model.Database>> queryDatabaseByDataSourceId(Long dataSourceId);

    /**
     * Load columns for a specific table
     * 
     * @param param table query param (databaseName, schemaName, and tableName are required)
     * @return list of TableColumn objects
     */
    DataResult<List<ai.inquery.spi.model.TableColumn>> loadColumnsByTable(TableQueryParam param);

    /**
     * Collect AI metadata for all tables in a data source
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @return map of table names to metadata
     */
    DataResult<Map<String, Object>> collectAIMetadataForAllTables(Long dataSourceId, String databaseName, String schemaName);

    /**
     * Load all tables and views for a data source
     *
     * @param dataSourceId data source ID
     * @param refresh whether to refresh cache
     * @return map of database names to their table/view lists
     */
    DataResult<Map<String, List<ai.inquery.spi.model.Table>>> loadAllTablesAndViews(Long dataSourceId, boolean refresh);

    /**
     * Load tables by schema
     *
     * @param param table query param
     * @return list of tables
     */
    DataResult<List<ai.inquery.spi.model.Table>> loadTablesBySchema(TableQueryParam param);

    /**
     * Get active states for all tables in a schema
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name (optional)
     * @return map of table keys to active states (key format: "database.schema.table")
     */
    DataResult<Map<String, Boolean>> getTableActiveStates(Long dataSourceId, String databaseName, String schemaName);

    /**
     * Save active state for a single table
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param active whether the table is active for AI queries
     * @return action result
     */
    ActionResult saveTableActiveState(Long dataSourceId, String databaseName, String schemaName, String tableName, Boolean active);

    /**
     * Batch save active states for multiple tables
     *
     * @param dataSourceId data source ID
     * @param activeStates map of table keys to active states (key format: "database.schema.table")
     * @return action result
     */
    ActionResult batchSaveTableActiveStates(Long dataSourceId, Map<String, Boolean> activeStates);

    /**
     * Get list of active tables for AI queries
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name (optional)
     * @return list of active table names
     */
    DataResult<List<String>> getActiveTables(Long dataSourceId, String databaseName, String schemaName);

    /**
     * Find table info by table name only (without databaseName/schemaName)
     * Searches Data Catalog by dataSourceId and tableName to find the full table info
     * including databaseName and schemaName.
     *
     * @param dataSourceId data source ID
     * @param tableName table name (case-insensitive)
     * @return map with keys: databaseName, schemaName, tableName, tableDescription
     */
    DataResult<Map<String, String>> findTableByName(Long dataSourceId, String tableName);

    /**
     * Auto-save predefined metadata for well-known BigQuery Google service tables.
     * Called during data source connection to populate catalog instantly without AI.
     *
     * @param dataSourceId data source ID
     * @param tablesMap    tables loaded from loadAllTablesAndViews(), key format: "database.schema"
     */
    void autoSaveBigQueryPredefinedMetadata(Long dataSourceId, Map<String, List<ai.inquery.spi.model.Table>> tablesMap);

    // collectAIMetadataWithProgress removed - frontend now uses existing APIs directly:
    // 1. collectAIMetadata() - get AI descriptions
    // 2. collectExampleValues() - get example values
    // 3. saveCatalog() - save to DB (this triggers vector embedding update automatically)
}

