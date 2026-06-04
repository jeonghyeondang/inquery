package ai.inquery.server.domain.api.param.team.user;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Team User
 *
 */
@Data
public class TeamUserPageQueryParam extends PageQueryParam {

    /**
     * team id
     */
    @NotNull
    private Long teamId;

    /**
     * user id
     */
    @NotNull
    private Long userId;

}
