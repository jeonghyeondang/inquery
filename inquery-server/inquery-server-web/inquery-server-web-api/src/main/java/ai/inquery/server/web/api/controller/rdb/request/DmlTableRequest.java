package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceConsoleRequestInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @version TableManageRequest.java, v 0.1 September 16, 2022 17:55 moji Exp $
 */
@Data
public class DmlTableRequest extends DataSourceBaseRequest implements DataSourceConsoleRequestInfo {

    /**
     * table noun
     */
    @NotNull
    private String tableName;

    /**
     * console id
     */
    @NotNull
    private Long consoleId;

    /**
     *Page coding
     * Only available for select statements
     */
    private Integer pageNo;

    /**
     * Paging Size
     * Only available for select statements
     */
    private Integer pageSize;

    /**
     * Return all data
     * Only available for select statements
     */
    private Boolean pageSizeAll;
}
