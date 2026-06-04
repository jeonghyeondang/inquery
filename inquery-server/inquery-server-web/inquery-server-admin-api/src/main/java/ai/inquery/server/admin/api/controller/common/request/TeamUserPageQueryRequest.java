
package ai.inquery.server.admin.api.controller.common.request;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import lombok.Data;

/**
 * Common pagination query
 *
 */
@Data
public class TeamUserPageQueryRequest extends PageQueryRequest {

    /**
     * Authorization type
     *
     * @see AccessObjectTypeEnum
     */
    private String type;

    /**
     * searchKey
     */
    private String searchKey;
}
