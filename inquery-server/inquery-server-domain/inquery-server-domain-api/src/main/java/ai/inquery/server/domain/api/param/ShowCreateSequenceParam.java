package ai.inquery.server.domain.api.param;


import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Query Sequence creation statement
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ShowCreateSequenceParam {
/**
     * Corresponding source id stored in the database
     */
    @NotNull
    private Long dataSourceId;

    /**
     * Corresponding connection database name
     */
    @NotNull
    private String databaseName;

    /**
     * Sequence Name
     */
    private String sequenceName;

    /**
     * The schema to which the sequence belongs
     */
    private String schemaName;
}
