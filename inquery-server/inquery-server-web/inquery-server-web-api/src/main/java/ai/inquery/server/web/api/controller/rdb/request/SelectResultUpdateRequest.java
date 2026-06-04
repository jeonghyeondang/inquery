package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.domain.api.param.SelectResultOperation;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceConsoleRequestInfo;
import ai.inquery.spi.model.Header;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SelectResultUpdateRequest extends DataSourceBaseRequest implements DataSourceConsoleRequestInfo {

    /**
     * List of display headers
     */
    private List<Header> headerList;

    /**
     * List of modified data
     */
    @NotEmpty
    private List<SelectResultOperation> operations;

    /**
     * Table Name
     */
    private String tableName;

    /**
     * console id
     */
    @NotNull
    private Long consoleId;
    @Override
    public Long getConsoleId() {
        return consoleId;
    }

}
