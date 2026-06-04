package ai.inquery.server.web.api.controller.operation.log;

import java.util.List;

import ai.inquery.server.domain.api.model.OperationLog;
import ai.inquery.server.domain.api.param.operation.OperationLogCreateParam;
import ai.inquery.server.domain.api.param.operation.OperationLogPageQueryParam;
import ai.inquery.server.domain.api.service.OperationLogService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.base.wrapper.result.web.WebPageResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.web.api.controller.operation.log.converter.OperationLogWebConverter;
import ai.inquery.server.web.api.controller.operation.log.request.OperationLogCreateRequest;
import ai.inquery.server.web.api.controller.operation.log.request.OperationLogQueryRequest;
import ai.inquery.server.web.api.controller.operation.log.vo.OperationLogVO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * History service class
 *
 * @version HistoryManageController.java, v 0.1 September 18, 2022 10:55 moji Exp $
 */
@RequestMapping("/api/operation/log")
@RestController
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private OperationLogWebConverter operationLogWebConverter;

    /**
     * Query execution records
     *
     * @param request
     * @return
     */
    @GetMapping("/list")
    public WebPageResult<OperationLogVO> list(OperationLogQueryRequest request) {
        OperationLogPageQueryParam param = operationLogWebConverter.req2param(request);
        param.setUserId(ContextUtils.getUserId());
        PageResult<OperationLog> result = operationLogService.queryPage(param);
        List<OperationLogVO> operationLogVOList = operationLogWebConverter.dto2vo(result.getData());
        return WebPageResult.of(operationLogVOList, result.getTotal(), result.getPageNo(), result.getPageSize());
    }

    /**
     * Add history
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public DataResult<Long> create(@RequestBody OperationLogCreateRequest request) {
        OperationLogCreateParam param = operationLogWebConverter.createReq2param(request);
        return operationLogService.create(param);
    }

    /**
     * Delete a single history record
     *
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    public DataResult<Boolean> delete(@PathVariable Long id) {
        return operationLogService.delete(id);
    }

    /**
     * Clear all history for a data source
     *
     * @param dataSourceId
     * @return
     */
    @DeleteMapping("/clear")
    public DataResult<Boolean> clear(@RequestParam Long dataSourceId) {
        return operationLogService.clearByDataSource(dataSourceId);
    }

}
