package ai.inquery.server.domain.repository.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * Table search result with database/schema location
 */
@Getter
@Setter
public class TableSearchResultDO {

    /**
     * Table cache ID
     */
    private Long id;

    /**
     * Data source ID
     */
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
     * Table name
     */
    private String tableName;

    /**
     * Table type (BASE TABLE, VIEW, etc.)
     */
    private String tableType;

    /**
     * Table comment
     */
    private String comment;
}

