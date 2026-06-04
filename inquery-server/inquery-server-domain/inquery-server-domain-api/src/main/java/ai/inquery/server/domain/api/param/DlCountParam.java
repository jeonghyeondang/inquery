package ai.inquery.server.domain.api.param;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * total number
 *
 */
@Data
public class DlCountParam {

    /**
     * sql statement
     */
    @NotNull
    private String sql;

    /**
     * console id
     */
    @NotNull
    private Long consoleId;

    /**
     * Data source id
     */
    @NotNull
    private Long dataSourceId;

    /**
     * databaseName
     */
    @NotNull
    private String databaseName;
}
