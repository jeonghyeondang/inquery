package ai.inquery.server.web.api.controller.rdb.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request for querying table lineage information
 */
@Data
public class LineageQueryRequest {

    /**
     * Data source connection ID
     */
    @NotNull
    private Long dataSourceId;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * Target table name
     */
    @NotNull
    private String tableName;
}

