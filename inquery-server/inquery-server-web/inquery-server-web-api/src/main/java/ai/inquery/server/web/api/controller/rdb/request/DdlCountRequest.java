package ai.inquery.server.web.api.controller.rdb.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceConsoleRequestInfo;

import lombok.Data;

/**
 * total number
 *
 */
@Data
public class DdlCountRequest extends DataSourceBaseRequest implements DataSourceConsoleRequestInfo {

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
}
