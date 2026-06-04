package ai.inquery.server.common.api.controller;

import ai.inquery.server.common.api.controller.converter.EnvironmentCommonConverter;
import ai.inquery.server.common.api.controller.vo.SimpleEnvironmentVO;
import ai.inquery.server.domain.api.param.EnvironmentPageQueryParam;
import ai.inquery.server.domain.api.service.EnvironmentService;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Basic interface
 *
 */
@RequestMapping("/api/common")
@RestController
public class CommonCommonController {

    @Resource
    private EnvironmentService environmentService;
    @Resource
    private EnvironmentCommonConverter environmentCommonConverter;

    /**
     * Query all environments
     *
     * @return
     * @version 2.1.0
     */
    @GetMapping("/environment/list_all")
    public ListResult<SimpleEnvironmentVO> environmentList() {
        EnvironmentPageQueryParam environmentPageQueryParam = new EnvironmentPageQueryParam();
        environmentPageQueryParam.setPageSize(10000);  // Use reasonable max value
        return ListResult.of(
            environmentCommonConverter.dto2vo(environmentService.pageQuery(environmentPageQueryParam).getData()));
    }

}
