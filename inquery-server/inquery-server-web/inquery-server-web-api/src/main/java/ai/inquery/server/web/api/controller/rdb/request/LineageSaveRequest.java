package ai.inquery.server.web.api.controller.rdb.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request for saving table lineage information
 */
@Data
public class LineageSaveRequest {

    /**
     * Lineage ID (for update, null for create)
     */
    private Long id;

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

    /**
     * Source query - the full SQL that creates/populates the table
     */
    private String sourceQuery;

    /**
     * Source tables - comma-separated list of source table names
     */
    private String sourceTables;

    /**
     * Description
     */
    private String description;
}

