package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import jakarta.validation.constraints.NotNull;

/**
 * Data Source Access
 *
 */
public interface DataSourceAccessBusinessService {
    /**
     * delete
     *
     * @param dataSource
     * @return
     */
    ActionResult checkPermission(@NotNull DataSource dataSource);
}
