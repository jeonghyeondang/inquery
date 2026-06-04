package ai.inquery.server.domain.api.service;

import java.util.List;

import ai.inquery.server.domain.api.model.Team;
import ai.inquery.server.domain.api.param.team.TeamCreateParam;
import ai.inquery.server.domain.api.param.team.TeamPageQueryParam;
import ai.inquery.server.domain.api.param.team.TeamSelector;
import ai.inquery.server.domain.api.param.team.TeamUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import jakarta.validation.constraints.NotNull;

/**
 * team
 *
 */
public interface TeamService {

    /**
     * Pagination query
     *
     * @param param
     * @param selector
     * @return
     */
    PageResult<Team> pageQuery(TeamPageQueryParam param, TeamSelector selector);

    /**
     * List Query Data
     *
     * @param idList
     * @return
     */
    ListResult<Team> listQuery(List<Long> idList);

    /**
     * Create
     *
     * @param param
     * @return
     */
    DataResult<Long> create(TeamCreateParam param);

    /**
     * update
     *
     * @param param
     * @return
     */
    DataResult<Long> update(TeamUpdateParam param);

    /**
     * delete
     *
     * @param id
     * @return
     */
    ActionResult delete(@NotNull Long id);

}
