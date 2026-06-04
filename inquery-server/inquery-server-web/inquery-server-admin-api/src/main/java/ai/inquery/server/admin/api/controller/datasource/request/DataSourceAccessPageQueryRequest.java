
package ai.inquery.server.admin.api.controller.datasource.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Common pagination query
 *
 */
@Data
public class DataSourceAccessPageQueryRequest extends PageQueryRequest {

    /**
     * Data source id
     */
    @NotNull
    private Long dataSourceId;

    /**
     * searchKey
     */
    private String searchKey;
}
