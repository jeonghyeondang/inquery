
package ai.inquery.server.web.api.controller.rdb.request;

import java.io.Serial;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequestInfo;

import lombok.Data;

/**
 */
@Data
public class TableQueryRequest extends PageQueryRequest implements DataSourceBaseRequestInfo {

    @Serial
    private static final long serialVersionUID = 5794716286491282784L;

    /**
     * Data source id
     */
    @NotNull
    private Long dataSourceId;

    /**
     * DB name
     */
    @NotNull
    private String databaseName;

    /**
     * Schema name (optional, required for some databases like PostgreSQL, Oracle)
     */
    private String schemaName;

    /**
     * Table Name
     */
    private String tableName;
}