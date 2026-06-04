package ai.inquery.server.web.api.controller.cache;

import ai.inquery.server.domain.api.model.SchemaMetaCache;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheParam;
import ai.inquery.server.domain.api.param.schemacache.SchemaMetaCacheSaveParam;
import ai.inquery.server.domain.api.service.SchemaMetaCacheService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.controller.cache.request.SchemaMetaCacheRequest;
import ai.inquery.server.web.api.controller.cache.request.SchemaMetaCacheSaveRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Schema metadata cache controller
 * Provides API for caching database schema metadata
 */
@Slf4j
@RequestMapping("/api/schema-cache")
@RestController
public class SchemaMetaCacheController {

    @Autowired
    private SchemaMetaCacheService schemaMetaCacheService;

    /**
     * Get cached schema metadata
     *
     * @param request query parameters
     * @return cached schema data or empty if not found
     */
    @GetMapping
    public DataResult<SchemaMetaCache> getCache(@Valid SchemaMetaCacheRequest request) {
        SchemaMetaCacheParam param = SchemaMetaCacheParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .build();
        return schemaMetaCacheService.getCache(param);
    }

    /**
     * Save schema metadata to cache
     *
     * @param request save parameters
     * @return action result
     */
    @PostMapping
    public ActionResult saveCache(@RequestBody @Valid SchemaMetaCacheSaveRequest request) {
        SchemaMetaCacheSaveParam param = SchemaMetaCacheSaveParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .tables(request.getTables() != null ? request.getTables().stream()
                .map(t -> SchemaMetaCacheSaveParam.TableInfo.builder()
                    .tableName(t.getTableName())
                    .tableType(t.getTableType())
                    .comment(t.getComment())
                    .columns(t.getColumns() != null ? t.getColumns().stream()
                        .map(c -> SchemaMetaCacheSaveParam.ColumnInfo.builder()
                            .columnName(c.getColumnName())
                            .dataType(c.getDataType())
                            .isPrimaryKey(c.getIsPrimaryKey())
                            .isNullable(c.getIsNullable())
                            .comment(c.getComment())
                            .ordinalPosition(c.getOrdinalPosition())
                            .build())
                        .toList() : null)
                    .build())
                .toList() : null)
            .build();
        return schemaMetaCacheService.saveCache(param);
    }

    /**
     * Invalidate cache for a specific schema
     *
     * @param request query parameters
     * @return action result
     */
    @DeleteMapping
    public ActionResult invalidateCache(@Valid SchemaMetaCacheRequest request) {
        SchemaMetaCacheParam param = SchemaMetaCacheParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .build();
        return schemaMetaCacheService.invalidateCache(param);
    }

    /**
     * Invalidate all caches for a data source
     *
     * @param dataSourceId data source ID
     * @return action result
     */
    @DeleteMapping("/datasource/{dataSourceId}")
    public ActionResult invalidateByDataSource(@PathVariable Long dataSourceId) {
        return schemaMetaCacheService.invalidateByDataSource(dataSourceId);
    }

    /**
     * Check if cache exists
     *
     * @param request query parameters
     * @return true if valid cache exists
     */
    @GetMapping("/exists")
    public DataResult<Boolean> hasValidCache(@Valid SchemaMetaCacheRequest request) {
        SchemaMetaCacheParam param = SchemaMetaCacheParam.builder()
            .dataSourceId(request.getDataSourceId())
            .databaseName(request.getDatabaseName())
            .schemaName(request.getSchemaName())
            .build();
        return schemaMetaCacheService.hasValidCache(param);
    }

    /**
     * Search for a table by name across all cached schemas
     * Returns the database/schema location of the table
     *
     * @param dataSourceId data source ID
     * @param tableName table name to search
     * @return list of matching tables with their locations
     */
    @GetMapping("/search-table")
    public DataResult<java.util.List<SchemaMetaCacheService.TableLocation>> searchTable(
            @RequestParam Long dataSourceId,
            @RequestParam String tableName) {
        return schemaMetaCacheService.searchTableByName(dataSourceId, tableName);
    }
}



