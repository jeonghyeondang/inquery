package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.DlCountParam;
import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.param.OrderByParam;
import ai.inquery.server.domain.api.param.UpdateSelectResultParam;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.domain.api.param.GroupByParam;

import ai.inquery.server.tools.base.wrapper.result.ListResult;

/**
 * Data source management services
 *
 * @version DataSourceCoreService.java, v 0.1 September 23, 2022 15:22 moji Exp $
 */
public interface DlTemplateService {

    /**
     * data source execution dl
     *
     * @param param
     * @return
     */
    ListResult<ExecuteResult> execute(DlExecuteParam param);


    /**
     *
     * @param param
     * @return
     */
    ListResult<ExecuteResult> executeSelectTable(DlExecuteParam param);


    /**
     * Data source execution update
     *
     * @param param
     * @return
     */
    DataResult<ExecuteResult> executeUpdate(DlExecuteParam param);

    /**
     * Execute statistics sql
     *
     * @param param
     * @return
     */
    DataResult<Long> count(DlCountParam param);

    /**
     * Update query results
     * @param param
     * @return
     */
    DataResult<String> updateSelectResult(UpdateSelectResultParam param);

    /**
     *
     * @param param
     * @return
     */
    DataResult<String> getGroupBySql(GroupByParam param);

    /**
     *
     * @param param
     * @return
     */
    DataResult<String> getOrderBySql(OrderByParam param);

}
