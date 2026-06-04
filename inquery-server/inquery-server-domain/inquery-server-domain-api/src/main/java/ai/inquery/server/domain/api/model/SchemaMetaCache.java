package ai.inquery.server.domain.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * Schema metadata cache model
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMetaCache {

    /**
     * Cache ID
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
     * Last modified time
     */
    private Date gmtModified;

    /**
     * Tables in this schema
     */
    private List<TableMetaCache> tables;

    /**
     * Table metadata cache
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableMetaCache {
        private Long id;
        private String tableName;
        private String tableType;
        private String comment;
        private List<ColumnMetaCache> columns;
    }

    /**
     * Column metadata cache
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMetaCache {
        private Long id;
        private String columnName;
        private String dataType;
        private Boolean isPrimaryKey;
        private Boolean isNullable;
        private String comment;
        private Integer ordinalPosition;
    }
}



