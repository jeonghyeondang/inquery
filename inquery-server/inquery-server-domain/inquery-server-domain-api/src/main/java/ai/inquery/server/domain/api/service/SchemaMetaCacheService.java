package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.SchemaMetaCache;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheParam;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheSaveParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;

/**
 * Schema metadata cache service
 */
public interface SchemaMetaCacheService {

    /**
     * Get cached schema metadata
     *
     * @param param query parameters
     * @return cached schema data or null if not found
     */
    DataResult<SchemaMetaCache> getCache(SchemaMetaCacheParam param);

    /**
     * Save schema metadata to cache (full replace)
     *
     * @param param save parameters
     * @return action result
     */
    ActionResult saveCache(SchemaMetaCacheSaveParam param);

    /**
     * Invalidate cache for a specific schema
     *
     * @param param query parameters
     * @return action result
     */
    ActionResult invalidateCache(SchemaMetaCacheParam param);

    /**
     * Invalidate all caches for a data source
     *
     * @param dataSourceId data source ID
     * @return action result
     */
    ActionResult invalidateByDataSource(Long dataSourceId);

    /**
     * Check if cache exists and is valid
     *
     * @param param query parameters
     * @return true if valid cache exists
     */
    DataResult<Boolean> hasValidCache(SchemaMetaCacheParam param);

    /**
     * Search for a table by name across all cached schemas
     * Returns the database/schema location of the table
     *
     * @param dataSourceId data source ID
     * @param tableName table name to search
     * @return list of matching tables with their locations
     */
    DataResult<java.util.List<TableLocation>> searchTableByName(Long dataSourceId, String tableName);

    /**
     * Table location result
     */
    record TableLocation(
        Long dataSourceId,
        String databaseName,
        String schemaName,
        String tableName,
        String tableType
    ) {}
}



