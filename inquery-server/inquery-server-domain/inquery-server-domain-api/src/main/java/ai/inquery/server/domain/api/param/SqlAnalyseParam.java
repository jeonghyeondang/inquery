package ai.inquery.server.domain.api.param;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Sql parsing parameters
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SqlAnalyseParam {

    /**
     * Corresponding source id stored in the database
     */
    @NotNull
    private Long dataSourceId;

    /**
     * The SQL that needs to be parsed may be a complex SQL
     */
    private String sql;
}
