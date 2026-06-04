package ai.inquery.server.domain.api.param.schemacache;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Schema metadata cache query parameters
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMetaCacheParam {

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
}



