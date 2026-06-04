
package ai.inquery.server.admin.api.controller.user.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class UserPageCommonQueryRequest extends PageQueryRequest {
    /**
     * user id
     */
    @NotNull
    private Long userId;

    /**
     * searchKey
     */
    private String searchKey;
}
