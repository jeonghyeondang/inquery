package ai.inquery.server.web.api.controller.ai.response;

import lombok.Data;
import java.util.List;

/**
 * Response containing LLM-recommended chart configuration
 */
@Data
public class ChartConfigResponse {

    /**
     * Recommended chart type: LINE, BAR, PIE, AREA, SCATTER, TABLE
     */
    private String chartType;

    /**
     * Column name for X-axis (category/time dimension)
     */
    private String xAxis;

    /**
     * Column names for Y-axis (metrics/values)
     */
    private List<String> yAxis;

    /**
     * Column name for grouping/series dimension (optional)
     */
    private String dimension;

    /**
     * Format configuration
     */
    private FormatConfig format;

    /**
     * Confidence score 0.0-1.0
     */
    private Double confidence;

    /**
     * Brief explanation of why this configuration was chosen
     */
    private String reasoning;

    @Data
    public static class FormatConfig {
        /**
         * Y-axis number format: number, percent, currency
         */
        private String yAxisFormat;

        /**
         * Date format for X-axis if time-based (e.g., "YYYY-MM-DD", "MMM YYYY")
         */
        private String dateFormat;

        /**
         * Decimal places for numeric values
         */
        private Integer decimalPlaces;
    }
}
