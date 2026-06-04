package ai.inquery.server.domain.core.catalog;

import java.util.List;

/**
 * Represents predefined metadata for a well-known BigQuery table.
 * Used for Google service tables (GA4, Firebase, Google Ads, etc.)
 * where schema and descriptions are publicly documented.
 */
public class PredefinedTableMetadata {

    private final String tableDescription;
    private final List<PredefinedColumnMetadata> columns;

    public PredefinedTableMetadata(String tableDescription, List<PredefinedColumnMetadata> columns) {
        this.tableDescription = tableDescription;
        this.columns = columns;
    }

    public String getTableDescription() {
        return tableDescription;
    }

    public List<PredefinedColumnMetadata> getColumns() {
        return columns;
    }

    /**
     * Represents predefined metadata for a single column.
     */
    public static class PredefinedColumnMetadata {
        private final String columnName;
        private final String description;

        public PredefinedColumnMetadata(String columnName, String description) {
            this.columnName = columnName;
            this.description = description;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getDescription() {
            return description;
        }
    }
}
