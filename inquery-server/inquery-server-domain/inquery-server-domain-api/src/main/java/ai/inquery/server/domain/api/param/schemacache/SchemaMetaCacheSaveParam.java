package ai.inquery.server.domain.api.param.schemacache;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema metadata cache save parameters
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMetaCacheSaveParam {

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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnInfo {
        private String columnName;
        private String dataType;
        private Boolean isPrimaryKey;
        private Boolean isNullable;
        private String comment;
        private Integer ordinalPosition;
    }
}



