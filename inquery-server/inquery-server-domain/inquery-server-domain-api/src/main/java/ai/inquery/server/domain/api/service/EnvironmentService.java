package ai.inquery.server.domain.api.service;

import java.util.List;

import ai.inquery.server.domain.api.model.Environment;
import ai.inquery.server.domain.api.param.EnvironmentPageQueryParam;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;

/**
 * environment
 *
 */
public interface EnvironmentService {

    /**
     * List Query Data
     *
     * @param idList
     * @return
     */
    ListResult<Environment> listQuery(List<Long> idList);

    /**
     * Paging Query Data
     *
     * @param param
     * @return
     */
    PageResult<Environment> pageQuery(EnvironmentPageQueryParam param);

}
