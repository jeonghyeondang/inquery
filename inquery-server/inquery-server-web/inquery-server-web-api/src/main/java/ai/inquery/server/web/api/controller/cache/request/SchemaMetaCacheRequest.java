package ai.inquery.server.web.api.controller.cache.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Schema metadata cache request
 */
@Data
public class SchemaMetaCacheRequest {

    /**
     * Data source ID
     */
    @NotNull
    private Long dataSourceId;

    /**
     * Database name
     */
    @NotNull
    private String databaseName;

    /**
     * Schema name (optional for some databases)
     */
    private String schemaName;
}



