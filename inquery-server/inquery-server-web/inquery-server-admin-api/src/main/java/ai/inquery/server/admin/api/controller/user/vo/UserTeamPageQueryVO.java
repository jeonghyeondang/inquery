package ai.inquery.server.admin.api.controller.user.vo;

import ai.inquery.server.admin.api.controller.team.vo.SimpleTeamVO;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class UserTeamPageQueryVO {
    /**
     * primary key
     */
    private Long id;

    /**
     * user id
     */
    private Long userId;

    /**
     * team
     */
    private SimpleTeamVO team;
}
