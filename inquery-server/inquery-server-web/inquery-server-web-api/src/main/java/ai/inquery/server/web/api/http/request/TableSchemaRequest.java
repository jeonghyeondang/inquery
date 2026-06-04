package ai.inquery.server.web.api.http.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableSchemaRequest {

    private Long dataSourceId;

    private String databaseName;

    private String apiKey;

    private String dataSourceSchema;

    private List<java.util.List<BigDecimal>> schemaVector;

    private List<String> schemaList;

    @Builder.Default
    private Boolean deleteBeforeInsert = false;

    /**
     * Table name (optional, for single table operations)
     */
    private String tableName;

    /**
     * Database type (e.g., SNOWFLAKE, MYSQL, POSTGRESQL) for metadata
     */
    private String dbType;
}
