
package ai.inquery.server.domain.api.service;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.param.SystemConfigParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;

/**
 */
public interface ConfigService {

    /**
     * Create configuration
     *
     * @param param
     * @return
     */
    ActionResult create(SystemConfigParam param);

    /**
     * Change setting
     *
     * @param param
     * @return
     */
    ActionResult update(SystemConfigParam param);

    /**
     * insert or update
     * @param param
     * @return
     */
    ActionResult createOrUpdate(SystemConfigParam param);

    /**
     * Query based on code
     *
     * @param code
     * @return
     */
    DataResult<Config> find(@NotNull String code);

    /**
     * delete
     *
     * @param code
     * @return
     */
    ActionResult delete(@NotNull String code);
}