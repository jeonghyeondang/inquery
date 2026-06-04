package ai.inquery.server.web.api.controller.dashboard;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.inquery.server.domain.api.model.Chart;
import ai.inquery.server.domain.api.model.Dashboard;
import ai.inquery.server.domain.api.param.dashboard.DashboardCreateParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardPageQueryParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardQueryParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardUpdateParam;
import ai.inquery.server.domain.api.service.ChartService;
import ai.inquery.server.domain.api.service.DashboardService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.base.wrapper.result.web.WebPageResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.web.api.controller.dashboard.converter.ChartWebConverter;
import ai.inquery.server.web.api.controller.dashboard.converter.DashboardWebConverter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import ai.inquery.server.web.api.controller.dashboard.request.DashboardCreateRequest;
import ai.inquery.server.web.api.controller.dashboard.request.DashboardUpdateRequest;
import ai.inquery.server.web.api.controller.dashboard.vo.ChartVO;
import ai.inquery.server.web.api.controller.dashboard.vo.DashboardVO;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Save dashboard class
 *
 * @version DashboardController.java, v 0.1 September 18, 2022 10:55 moji Exp $
 */
@RequestMapping("/api/dashboard")
@RestController
@Slf4j
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ChartService chartService;

    @Autowired
    private DashboardWebConverter dashboardWebConverter;

    @Autowired
    private ChartWebConverter chartWebConverter;

    @Autowired
    private LangChainModelProvider langChainModelProvider;

    /**
     * Query dashboard list
     *
     * @param request
     * @return
     */
    @GetMapping("/list")
    public WebPageResult<DashboardVO> list(DashboardPageQueryParam request) {
        request.setUserId(ContextUtils.getUserId());
        PageResult<Dashboard> result = dashboardService.queryPage(request);
        List<DashboardVO> dashboardVOS = dashboardWebConverter.model2vo(result.getData());
        return WebPageResult.of(dashboardVOS, result.getTotal(), result.getPageNo(), result.getPageSize());
    }

    /**
     * Query dashboard details based on id
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public DataResult<DashboardVO> get(@PathVariable("id") Long id) {
        DashboardQueryParam param = new DashboardQueryParam();
        param.setId(id);
        param.setUserId(ContextUtils.getUserId());
        return dashboardService.queryExistent(param)
            .map(dashboardWebConverter::model2vo);
    }

    /**
     * Save dashboard
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public DataResult<Long> create(@RequestBody DashboardCreateRequest request) {
        DashboardCreateParam param = dashboardWebConverter.req2param(request);
        return dashboardService.createWithPermission(param);
    }

    /**
     * Update dashboard
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/update", method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult update(@RequestBody DashboardUpdateRequest request) {
        DashboardUpdateParam param = dashboardWebConverter.req2updateParam(request);
        return dashboardService.updateWithPermission(param);
    }

    /**
     * Delete dashboard
     *
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    public ActionResult delete(@PathVariable("id") Long id) {
        return dashboardService.deleteWithPermission(id);
    }

    /**
     * Generate AI summary for a dashboard with streaming support.
     * Uses LangChain4j for unified model access.
     *
     * @param request Contains dashboardName, charts (with name, sql, data), and model
     * @return SSE stream of summary content
     */
    @PostMapping(value = "/summarize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CrossOrigin
    public SseEmitter summarizeDashboardStream(@RequestBody Map<String, Object> request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minute timeout

        String dashboardName = (String) request.get("dashboardName");
        String model = (String) request.get("model");
        String language = (String) request.get("language");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charts = (List<Map<String, Object>>) request.get("charts");

        // Build the prompt and apply repetition for fast models (arxiv 2512.14982)
        final String finalModel = model != null ? model : ModelMapper.getDefaultFastModel();
        final String prompt = ModelMapper.optimizePrompt(
                buildDashboardSummaryPrompt(dashboardName, charts, language), finalModel);

        log.info("Generating dashboard summary for: {} with {} charts using model: {}",
                 dashboardName, charts != null ? charts.size() : 0, finalModel);

        // Get streaming model from LangChain4j provider (cached)
        StreamingChatModel streamingModel;
        try {
            streamingModel = langChainModelProvider.getStreamingChatModel(finalModel);
        } catch (Exception e) {
            log.error("Failed to get streaming model: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data("Failed to initialize AI model: " + e.getMessage()));
                emitter.complete();
            } catch (IOException ex) {
                // Ignore
            }
            return emitter;
        }

        // Use LangChain4j streaming
        streamingModel.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(SseEmitter.event()
                        .name("content")
                        .data(partialResponse));
                } catch (IOException e) {
                    log.warn("Failed to send SSE event: {}", e.getMessage());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    emitter.send(SseEmitter.event().name("done").data(""));
                    emitter.complete();
                } catch (IOException e) {
                    log.warn("Failed to send done event: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("Error generating dashboard summary", error);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(error.getMessage()));
                } catch (IOException e) {
                    // Ignore
                }
                emitter.completeWithError(error);
            }
        });

        return emitter;
    }

    /**
     * Generate AI summary for a dashboard (non-streaming version).
     * Uses LangChain4j for unified model access.
     *
     * @param request Contains dashboardName, charts (with name, sql, data), and model
     * @return Summary as markdown text
     */
    @PostMapping("/summarize")
    @CrossOrigin
    public DataResult<Map<String, String>> summarizeDashboard(@RequestBody Map<String, Object> request) {
        try {
            String dashboardName = (String) request.get("dashboardName");
            String model = (String) request.get("model");
            String language = (String) request.get("language");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> charts = (List<Map<String, Object>>) request.get("charts");

            String finalModel = model != null ? model : ModelMapper.getDefaultFastModel();
            // Apply prompt repetition for fast models (arxiv 2512.14982)
            String prompt = ModelMapper.optimizePrompt(
                    buildDashboardSummaryPrompt(dashboardName, charts, language), finalModel);

            log.info("Generating dashboard summary for: {} with {} charts using model: {} in language: {}",
                     dashboardName, charts != null ? charts.size() : 0, finalModel, language);

            // Use LangChain4j ChatModel
            ChatModel chatModel = langChainModelProvider.getChatModel(finalModel);
            String summary = chatModel.chat(prompt);
            
            Map<String, String> result = new HashMap<>();
            result.put("summary", summary);
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to summarize dashboard", e);
            return DataResult.error("DASHBOARD_SUMMARY_ERROR", "Failed to generate dashboard summary: " + e.getMessage());
        }
    }

    /**
     * Build prompt for dashboard summary
     */
    private String buildDashboardSummaryPrompt(String dashboardName, List<Map<String, Object>> charts, String language) {
        StringBuilder sb = new StringBuilder();
        
        // Language instruction
        String langInstruction = "";
        if (language != null && !language.isEmpty() && !"English".equalsIgnoreCase(language)) {
            langInstruction = " Please write your entire response in " + language + ".";
        }
        
        sb.append("You are a data analyst assistant. Please analyze the following dashboard and provide a comprehensive summary in markdown format.").append(langInstruction).append("\n\n");
        sb.append("## Dashboard: ").append(dashboardName != null ? dashboardName : "Untitled Dashboard").append("\n\n");
        
        if (charts != null && !charts.isEmpty()) {
            sb.append("### Charts Overview\n\n");
            
            for (int i = 0; i < charts.size(); i++) {
                Map<String, Object> chart = charts.get(i);
                String chartName = (String) chart.get("name");
                String chartType = (String) chart.get("chartType");
                String sql = (String) chart.get("sql");
                Object data = chart.get("data");
                
                sb.append("#### Chart ").append(i + 1).append(": ").append(chartName != null ? chartName : "Untitled").append("\n");
                
                if (chartType != null) {
                    sb.append("- **Type**: ").append(chartType).append("\n");
                }
                
                if (sql != null && !sql.isEmpty()) {
                    sb.append("- **SQL Query**:\n```sql\n").append(sql).append("\n```\n");
                }
                
                if (data != null) {
                    String dataStr = data.toString();
                    // Limit data size to avoid token overflow
                    if (dataStr.length() > 2000) {
                        dataStr = dataStr.substring(0, 2000) + "... (truncated)";
                    }
                    sb.append("- **Data Preview**:\n```json\n").append(dataStr).append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("*No charts in this dashboard yet.*\n\n");
        }
        
        sb.append("### Instructions\n");
        sb.append("Please provide:\n");
        sb.append("1. **Executive Summary**: A brief overview of what this dashboard shows (2-3 sentences)\n");
        sb.append("2. **Key Insights**: Important findings from the data (bullet points)\n");
        sb.append("3. **Trends & Patterns**: Any notable trends or patterns observed\n");
        sb.append("4. **Recommendations**: Actionable suggestions based on the data (if applicable)\n\n");
        sb.append("Format your response in clean markdown. Be concise but comprehensive.");
        
        if (langInstruction.length() > 0) {
            sb.append(" Remember to write everything in ").append(language).append(".");
        }
        sb.append("\n");
        
        return sb.toString();
    }

    /**
     * Enable public sharing for a dashboard. Returns the share token.
     */
    @PostMapping("/{id}/share")
    public DataResult<Map<String, String>> enableShare(@PathVariable("id") Long id) {
        DataResult<String> result = dashboardService.enableShare(id);
        Map<String, String> data = new HashMap<>();
        data.put("shareToken", result.getData());
        return DataResult.of(data);
    }

    /**
     * Disable public sharing for a dashboard.
     */
    @DeleteMapping("/{id}/share")
    public ActionResult disableShare(@PathVariable("id") Long id) {
        return dashboardService.disableShare(id);
    }

    /**
     * Get a publicly shared dashboard by share token (no authentication required).
     * Path is added to SecurityConfig PUBLIC_PATHS.
     */
    @GetMapping("/public/{shareToken}")
    public DataResult<Map<String, Object>> getPublicDashboard(@PathVariable("shareToken") String shareToken) {
        DataResult<Dashboard> dashboardResult = dashboardService.findByShareToken(shareToken);
        Dashboard dashboard = dashboardResult.getData();
        DashboardVO dashboardVO = dashboardWebConverter.model2vo(dashboard);

        Map<String, Object> result = new HashMap<>();
        result.put("dashboard", dashboardVO);

        if (dashboard.getChartIds() != null && !dashboard.getChartIds().isEmpty()) {
            ListResult<Chart> chartsResult = chartService.queryByIds(dashboard.getChartIds());
            List<ChartVO> chartVOs = chartWebConverter.model2vo(chartsResult.getData());
            result.put("charts", chartVOs);
        } else {
            result.put("charts", List.of());
        }

        return DataResult.of(result);
    }
}
