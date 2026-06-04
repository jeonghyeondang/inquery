
package ai.inquery.server.admin.api.controller.team.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class TeamPageCommonQueryRequest extends PageQueryRequest {
    /**
     * team id
     */
    @NotNull
    private Long teamId;

    /**
     * searchKey
     */
    private String searchKey;
}
