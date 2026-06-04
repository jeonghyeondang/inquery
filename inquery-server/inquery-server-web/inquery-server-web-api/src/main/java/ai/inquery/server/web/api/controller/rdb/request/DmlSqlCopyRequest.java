package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DmlSqlCopyRequest extends DataSourceBaseRequest {

    @NotNull
    private String tableName;

    private String type;
}
