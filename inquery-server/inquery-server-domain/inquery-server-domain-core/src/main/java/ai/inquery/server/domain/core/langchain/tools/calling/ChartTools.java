package ai.inquery.server.domain.core.langchain.tools.calling;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.chart.ChartRecommendationEngine;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

/**
 * Chart recommendation tool exposed to data-analysis agents.
 *
 * <p>The tool re-runs the SQL the LLM provides so that recommendation is anchored
 * to a concrete {@link ExecuteResult}. The cost of a second SELECT is small
 * compared to a fresh chart-engine call and keeps this tool self-contained.
 */
@Slf4j
public class ChartTools {

    private final ChartRecommendationEngine chartRecommendationEngine;
    private final DlTemplateService dlTemplateService;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    private final String modelName;

    public ChartTools(ChartRecommendationEngine chartRecommendationEngine,
                      DlTemplateService dlTemplateService,
                      Long dataSourceId,
                      String databaseName,
                      String schemaName,
                      String modelName) {
        this.chartRecommendationEngine = chartRecommendationEngine;
        this.dlTemplateService = dlTemplateService;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.modelName = modelName;
    }

    @Tool("Recommend a chart type (BAR, LINE, PIE, ...) for the result of a SELECT/WITH SQL.")
    public String recommendChart(
            @P("SELECT/WITH SQL to chart") String sql,
            @P("Original user question, used to bias axis/dimension choices") String userQuestion
    ) {
        log.info("[ChartTools] recommendChart for SQL: {}",
                sql.length() > 80 ? sql.substring(0, 80) + "..." : sql);
        try {
            DlExecuteParam param = new DlExecuteParam();
            param.setSql(sql);
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(databaseName);
            param.setSchemaName(schemaName);
            param.setConsoleId(0L);

            ListResult<ExecuteResult> result = dlTemplateService.execute(param);
            if (!result.success() || result.getData() == null || result.getData().isEmpty()) {
                return "Chart recommendation unavailable: SQL did not return data. Error: "
                        + result.getErrorMessage();
            }
            ExecuteResult executeResult = result.getData().get(0);
            ChartRecommendationEngine.ChartRecommendation rec =
                    chartRecommendationEngine.recommendChart(executeResult, userQuestion, modelName);
            return formatRecommendation(rec);
        } catch (Exception e) {
            log.warn("Chart recommendation failed: {}", e.getMessage());
            return "Chart recommendation failed: " + e.getMessage();
        }
    }

    private String formatRecommendation(ChartRecommendationEngine.ChartRecommendation rec) {
        if (rec == null) return "No chart recommendation produced.";
        StringBuilder sb = new StringBuilder();
        sb.append("Chart recommendation:\n");
        sb.append("- type: ").append(rec.getChartType()).append("\n");
        sb.append("- confidence: ").append(rec.getConfidence()).append("\n");
        if (rec.getXAxis() != null) sb.append("- xAxis: ").append(rec.getXAxis()).append("\n");
        if (rec.getYAxis() != null) sb.append("- yAxis: ").append(rec.getYAxis()).append("\n");
        if (rec.getReason() != null) sb.append("- reason: ").append(rec.getReason()).append("\n");
        return sb.toString();
    }
}
