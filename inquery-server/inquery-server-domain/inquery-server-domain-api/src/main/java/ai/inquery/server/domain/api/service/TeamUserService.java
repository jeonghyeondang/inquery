package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.TeamUser;
import ai.inquery.server.domain.api.param.team.user.TeamUserComprehensivePageQueryParam;
import ai.inquery.server.domain.api.param.team.user.TeamUserCreatParam;
import ai.inquery.server.domain.api.param.team.user.TeamUserPageQueryParam;
import ai.inquery.server.domain.api.param.team.user.TeamUserSelector;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import jakarta.validation.constraints.NotNull;

/**
 * team user
 *
 */
public interface TeamUserService {

    /**
     * Comprehensive Paging Query Data
     *
     * @param param
     * @param selector
     * @return
     */
    PageResult<TeamUser> pageQuery(TeamUserPageQueryParam param, TeamUserSelector selector);

    /**
     * Comprehensive Paging Query Data
     *
     * @param param
     * @param selector
     * @return
     */
    PageResult<TeamUser> comprehensivePageQuery(TeamUserComprehensivePageQueryParam param, TeamUserSelector selector);

    /**
     * Create
     *
     * @param param
     * @return
     */
    DataResult<Long> create(TeamUserCreatParam param);

    /**
     * delete
     *
     * @param id
     * @return
     */
    ActionResult delete(@NotNull Long id);
}
