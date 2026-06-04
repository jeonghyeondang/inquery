
package ai.inquery.server.admin.api.controller.team.vo;

import ai.inquery.server.admin.api.controller.user.vo.SimpleUserVO;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class TeamUserPageQueryVO {
    /**
     * primary key
     */
    private Long id;

    /**
     * team id
     */
    private Long teamId;

    /**
     * user
     */
    private SimpleUserVO user;
}
