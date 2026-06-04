package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.Dashboard;
import ai.inquery.server.domain.api.param.dashboard.DashboardCreateParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardPageQueryParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardQueryParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @version DashboardService.java, v 0.1 June 9, 2023 15:28 moji Exp $
 */
public interface DashboardService {

    /**
     * Save report
     *
     * @param param
     * @return
     */
    DataResult<Long> createWithPermission(DashboardCreateParam param);

    /**
     * Update report
     *
     * @param param
     * @return
     */
    ActionResult updateWithPermission(DashboardUpdateParam param);

    /**
     * Query based on id
     *
     * @param id
     * @return
     */
    DataResult<Dashboard> find(@NotNull Long id);

    /**
     * Query a piece of data
     *
     * @param param
     * @param selector
     * @return
     */
    DataResult<Dashboard> queryExistent(@NotNull DashboardQueryParam param);

    /**
     * Query a piece of data
     *
     * @param id
     * @return
     */
    DataResult<Dashboard> queryExistent(@NotNull Long id);

    /**
     * delete
     *
     * @param id
     * @return
     */
    ActionResult deleteWithPermission(@NotNull Long id);

    /**
     * Query report list
     *
     * @param param
     * @return
     */
    PageResult<Dashboard> queryPage(DashboardPageQueryParam param);

    /**
     * Enable public sharing for a dashboard. Generates a share token if none exists.
     *
     * @param id dashboard ID
     * @return share token
     */
    DataResult<String> enableShare(@NotNull Long id);

    /**
     * Disable public sharing for a dashboard.
     *
     * @param id dashboard ID
     * @return action result
     */
    ActionResult disableShare(@NotNull Long id);

    /**
     * Find a publicly shared dashboard by its share token (no auth required).
     *
     * @param shareToken the share token
     * @return dashboard data
     */
    DataResult<Dashboard> findByShareToken(@NotBlank String shareToken);
}
