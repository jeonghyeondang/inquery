package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.operation.OperationLogPageQueryParam;
import ai.inquery.server.domain.api.model.OperationLog;
import ai.inquery.server.domain.api.param.operation.OperationLogCreateParam;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;

/**
 * User executes ddl
 *
 * @version UserExecutedDdlCoreService.java, v 0.1 September 23, 2022 17:35 moji Exp $
 */
public interface OperationLogService {

    /**
     * Create ddl record executed by user
     *
     * @param param
     * @return
     */
    DataResult<Long> create(OperationLogCreateParam param);

    /**
     * Query the ddl records executed by the user
     *
     * @param param
     * @return
     */
    PageResult<OperationLog> queryPage(OperationLogPageQueryParam param);

    /**
     * Delete a single operation log by id
     *
     * @param id
     * @return
     */
    DataResult<Boolean> delete(Long id);

    /**
     * Clear all operation logs for a data source
     *
     * @param dataSourceId
     * @return
     */
    DataResult<Boolean> clearByDataSource(Long dataSourceId);
}
