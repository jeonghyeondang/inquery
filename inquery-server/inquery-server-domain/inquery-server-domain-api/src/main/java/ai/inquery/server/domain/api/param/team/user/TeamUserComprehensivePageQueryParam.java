package ai.inquery.server.domain.api.param.team.user;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import lombok.Data;

/**
 * Team User
 *
 */
@Data
public class TeamUserComprehensivePageQueryParam extends PageQueryParam {

    /**
     * team id
     */
    private Long teamId;

    /**
     * user id
     */
    private Long userId;
    

    /**
     * Query keywords for team
     */
    private String teamSearchKey;

    /**
     * Query keywords for user
     */
    private String userSearchKey;
}
