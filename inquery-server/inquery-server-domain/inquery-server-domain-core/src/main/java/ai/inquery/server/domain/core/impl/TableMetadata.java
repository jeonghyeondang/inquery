package ai.inquery.server.domain.core.impl;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class TableMetadata {
    private String tableName;
    private List<ColumnMetadata> columns;
    private String tableDescription;
    private String tablePurpose;
    
    /**
     * JSON sample data for BigQuery tables.
     * Contains full row samples with all nested structures preserved.
     * Used instead of per-column example values for complex BigQuery tables.
     */
    private String sampleJson;

    /**
     * Optional categorical column whose values segment row semantics
     * (e.g. {@code event_name}, {@code event_type}, {@code type}). When set,
     * {@link ColumnMetadata#getPartitionedExampleValues()} is populated for
     * relevant columns and the AI metadata prompt renders examples grouped
     * by this column. Detected automatically by name pattern + bounded
     * cardinality probe; null if the table has no event-like column.
     */
    private String partitionColumn;

    /**
     * Structured summary of values observed during Collect (event names, parameter keys,
     * stream IDs, etc.). Injected into the LLM prompt so tablePurpose/tableDescription
     * reflect this dataset — not generic schema boilerplate.
     */
    private String dataProfileSummary;
    
    public TableMetadata() {
        this.columns = new ArrayList<>();
    }
    
    public TableMetadata(String tableName) {
        this.tableName = tableName;
        this.columns = new ArrayList<>();
    }
}







