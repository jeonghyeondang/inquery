package ai.inquery.server.web.api.controller.cache.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Schema metadata cache save request
 */
@Data
public class SchemaMetaCacheSaveRequest {

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

    /**
     * Tables to cache
     */
    private List<TableInfo> tables;

    /**
     * Table info with columns
     */
    @Data
    public static class TableInfo {
        private String tableName;
        private String tableType;
        private String comment;
        private List<ColumnInfo> columns;
    }

    /**
     * Column info
     */
    @Data
    public static class ColumnInfo {
        private String columnName;
        private String dataType;
        private Boolean isPrimaryKey;
        private Boolean isNullable;
        private String comment;
        private Integer ordinalPosition;
    }
}



