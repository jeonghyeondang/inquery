package ai.inquery.server.web.api.controller.business;

import ai.inquery.server.domain.api.model.BusinessInsightDTO;
import ai.inquery.server.domain.core.business.BusinessInsightService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/business-insight")
public class BusinessInsightController {

    @Autowired
    private BusinessInsightService businessInsightService;

    @GetMapping
    public DataResult<BusinessInsightDTO> getInsight(@RequestParam Long dataSourceId, @RequestParam String databaseName) {
        return DataResult.of(businessInsightService.getInsight(dataSourceId, databaseName));
    }

    @PostMapping
    public DataResult<BusinessInsightDTO> saveInsight(@RequestBody BusinessInsightDTO dto) {
        return DataResult.of(businessInsightService.saveInsight(dto));
    }

    @PostMapping("/generate")
    public DataResult<BusinessInsightDTO> generateInsight(@RequestBody BusinessInsightDTO dto) {
        return DataResult.of(businessInsightService.generateInsight(dto));
    }
}
