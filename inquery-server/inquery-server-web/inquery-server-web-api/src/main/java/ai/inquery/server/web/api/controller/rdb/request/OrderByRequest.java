package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequestInfo;
import ai.inquery.spi.model.OrderBy;
import lombok.Data;

import java.util.List;

@Data
public class OrderByRequest extends DataSourceBaseRequest implements DataSourceBaseRequestInfo {

    /**
     * origin sql
     */
    private String originSql;

    /**
     * sort field
     */
    private List<OrderBy> orderByList;

}
