package ai.inquery.server.web.api.controller.ai.request;

import lombok.Data;

/**
 * Request for executing generated SQL from AI chat.
 */
@Data
public class SqlExecuteRequest {
    /**
     * The generated SQL query to execute.
     */
    private String sql;

    /**
     * Original natural language query.
     */
    private String originalQuery;

    /**
     * Data source ID.
     */
    private Long dataSourceId;

    /**
     * Database name.
     */
    private String databaseName;

    /**
     * Schema name.
     */
    private String schemaName;
}
