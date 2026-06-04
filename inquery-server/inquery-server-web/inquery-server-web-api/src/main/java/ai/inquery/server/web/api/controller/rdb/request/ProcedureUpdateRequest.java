package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @date: February 24, 2024 13:21
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProcedureUpdateRequest extends DataSourceBaseRequest {

    private String procedureName;
    private String procedureBody;

}
