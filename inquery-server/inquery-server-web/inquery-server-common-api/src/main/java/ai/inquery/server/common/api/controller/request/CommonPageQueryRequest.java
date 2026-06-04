package ai.inquery.server.common.api.controller.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import lombok.Data;

/**
 * Common pagination query
 *
 */
@Data
public class CommonPageQueryRequest extends PageQueryRequest {


    /**
     * searchKey
     */
    private String searchKey;
}
