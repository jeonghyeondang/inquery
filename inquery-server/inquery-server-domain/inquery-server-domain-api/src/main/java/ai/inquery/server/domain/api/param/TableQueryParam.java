package ai.inquery.server.domain.api.param;

import java.io.Serial;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.tools.base.wrapper.param.QueryParam;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Query table information
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableQueryParam extends QueryParam {
    @Serial
    private static final long serialVersionUID = -8918610899872508804L;
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
     * Table Name
     */
    private String tableName;

    /**
     * Space name
     */
    private String schemaName;

    /**
     * Catalog table name for BigQuery sharded tables (pattern name like "events_YYYYMMDD")
     * If provided, catalog data will be stored/retrieved using this name instead of tableName.
     * tableName is still used for schema/column lookup from actual database.
     */
    private String catalogTableName;

    private boolean refresh;
}
