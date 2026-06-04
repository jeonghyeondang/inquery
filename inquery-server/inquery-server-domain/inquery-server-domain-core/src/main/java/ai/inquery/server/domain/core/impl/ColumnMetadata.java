package ai.inquery.server.domain.core.impl;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ColumnMetadata {
    private String columnName;
    private String dataType;
    private String description;
    private boolean nullable;
    private List<String> exampleValues = new ArrayList<>();

    /**
     * Optional per-partition sample values, populated when the source table has
     * an "event-like" categorical column (event_name, event_type, type, ...).
     * Keyed by the partition value -> the column's representative samples for
     * rows where the partition column equals that value.
     *
     * <p>This stays empty for tables without a detected partition column. It is
     * intentionally not persisted (transient to the API surface) — the goal is
     * to feed the AI metadata prompt with richer, event-segmented examples so
     * the generated descriptions can disambiguate columns whose meaning shifts
     * across event types (e.g. {@code value} is a price for {@code purchase}
     * but a dwell time for {@code page_view}).
     */
    private Map<String, List<String>> partitionedExampleValues = new LinkedHashMap<>();

    public ColumnMetadata() {}

    public ColumnMetadata(String columnName, String dataType) {
        this.columnName = columnName;
        this.dataType = dataType;
    }
}







