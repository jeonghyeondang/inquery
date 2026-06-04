package ai.inquery.server.domain.core.chart;

import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.core.langchain.agents.ResultAnalyzerAgent;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.model.Header;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM-based chart recommendation engine using LangChain4j AI Service pattern.
 * Analyzes query results and recommends the most suitable visualization
 * including chart type and axis configuration (xAxis, yAxis, dimension).
 */
@Slf4j
@Component
public class ChartRecommendationEngine {

    @Autowired
    private LangChainModelProvider modelProvider;

    public ChartRecommendation recommendChart(ExecuteResult executeResult) {
        return recommendChart(executeResult, null, ModelMapper.getDefaultFastModel());
    }

    public ChartRecommendation recommendChart(ExecuteResult executeResult, String userQuestion) {
        return recommendChart(executeResult, userQuestion, ModelMapper.getDefaultFastModel());
    }

    public ChartRecommendation recommendChart(ExecuteResult executeResult, String userQuestion, String model) {
        if (executeResult == null || !executeResult.getSuccess()) {
            log.warn("Cannot recommend chart for failed result");
            return new ChartRecommendation(ChartType.BAR, 0.0, "Execution failed");
        }

        List<Header> headers = executeResult.getHeaderList();
        List<List<String>> dataList = executeResult.getDataList();

        if (headers == null || headers.isEmpty()) {
            return new ChartRecommendation(ChartType.BAR, 1.0, "No columns found");
        }

        // Build query results string for the agent
        String queryResults = buildQueryResultsString(headers, dataList);
        String question = userQuestion != null ? userQuestion : "Analyze this data";

        try {
            // Use LangChain4j AI Service pattern
            ChatModel chatModel = modelProvider.getChatModel(model);
            ResultAnalyzerAgent agent = AiServices.builder(ResultAnalyzerAgent.class)
                .chatModel(chatModel)
                .chatRequestTransformer(ModelMapper.promptRepetitionTransformer(model))
                .build();

            String response = agent.recommendChart(question, queryResults);
            ChartRecommendation recommendation = parseResponse(response, headers);
            recommendation.setRawResponse(response);
            recommendation = preferChartOverTable(recommendation, headers, dataList, question);

            log.info("LLM chart recommendation: type={}, xAxis={}, yAxis={}, dimensions={}, xAxisFormat={}, yAxisFormat={}, lineVariant={}, pieVariant={}, barOrientation={}, order={}, confidence={}, reason='{}'",
                recommendation.getChartType(), recommendation.getXAxis(), recommendation.getYAxis(),
                recommendation.getDimensions(), recommendation.getXAxisFormat(), recommendation.getYAxisFormat(),
                recommendation.getLineVariant(), recommendation.getPieVariant(), recommendation.getBarOrientation(),
                recommendation.getOrder(), recommendation.getConfidence(), recommendation.getReason());
            
            return recommendation;
        } catch (Exception e) {
            log.warn("LLM chart recommendation failed, using fallback: {}", e.getMessage());
            ChartRecommendation fallback = fallbackRecommendation(headers, dataList);
            fallback.setRawResponse("ERROR: " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Build a string representation of query results for the agent
     */
    private String buildQueryResultsString(List<Header> headers, List<List<String>> dataList) {
        StringBuilder sb = new StringBuilder();
        
        // Column information (exclude system Row Number column)
        sb.append("COLUMNS:\n");
        int colIndex = 0;
        for (Header header : headers) {
            // Skip INQUERY_ROW_NUMBER - it's a system-generated column, not actual data
            if (isRowNumberColumn(header)) {
                continue;
            }
            sb.append(colIndex++).append(". ").append(header.getName())
                .append(" (").append(header.getDataType()).append(")\n");
        }
        sb.append("\n");

        // Data sample (first 5 rows)
        int rowCount = dataList != null ? dataList.size() : 0;
        sb.append("Total rows: ").append(rowCount).append("\n");
        if (dataList != null && !dataList.isEmpty()) {
            sb.append("Sample data (first 5 rows):\n");
            int sampleSize = Math.min(5, dataList.size());
            for (int i = 0; i < sampleSize; i++) {
                sb.append(dataList.get(i)).append("\n");
            }
        }
        
        return sb.toString();
    }

    private ChartRecommendation parseResponse(String response, List<Header> headers) {
        if (response == null || response.trim().isEmpty()) {
            throw new RuntimeException("Empty LLM response");
        }

        // Clean response - extract JSON if wrapped in markdown
        String jsonStr = response.trim();
        if (jsonStr.startsWith("```json")) {
            jsonStr = jsonStr.substring(7);
        }
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.substring(3);
        }
        if (jsonStr.endsWith("```")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
        }
        jsonStr = jsonStr.trim();

        try {
            JSONObject json = JSON.parseObject(jsonStr);

            String chartTypeStr = json.getString("chartType");
            ChartType chartType = parseChartType(chartTypeStr);

            Double confidence = json.getDouble("confidence");
            if (confidence == null) confidence = 0.8;

            String reason = json.getString("reason");
            if (reason == null) reason = "LLM recommendation";

            // Parse axis configuration
            String xAxis = json.getString("xAxis");
            String yAxis = json.getString("yAxis");
            String xAxisFormat = json.getString("xAxisFormat");
            String yAxisFormat = json.getString("yAxisFormat");

            // Parse dimension — supports both string and array from LLM
            List<String> dimensions = parseDimensions(json, headers);

            // Parse variant configuration
            String lineVariant = json.getString("lineVariant");
            String pieVariant = json.getString("pieVariant");
            String barOrientation = json.getString("barOrientation");
            String order = json.getString("order");

            // Validate column names exist in headers
            xAxis = validateColumnName(xAxis, headers);
            yAxis = validateColumnName(yAxis, headers);

            // Validate formats
            xAxisFormat = validateXAxisFormat(xAxisFormat);
            yAxisFormat = validateYAxisFormat(yAxisFormat);

            // Validate variants
            lineVariant = validateLineVariant(lineVariant);
            pieVariant = validatePieVariant(pieVariant);
            barOrientation = validateBarOrientation(barOrientation);
            order = validateOrder(order);

            // CARD and TABLE do not require axis configuration
            if (chartType == ChartType.CARD || chartType == ChartType.TABLE) {
                return new ChartRecommendation(chartType, confidence, reason, null, null, (List<String>) null, null, yAxisFormat, null, null, null, null);
            }

            // If xAxis or yAxis not set, use defaults
            if (xAxis == null && !headers.isEmpty()) {
                xAxis = headers.get(0).getName();
            }
            if (yAxis == null && headers.size() > 1) {
                // Find first numeric column for yAxis
                for (Header h : headers) {
                    if (isRowNumberColumn(h)) continue;
                    if (isNumericType(h.getDataType() != null ? h.getDataType().toLowerCase() : "")) {
                        yAxis = h.getName();
                        break;
                    }
                }
                if (yAxis == null) {
                    // Find last valid column (not Row Number)
                    for (int i = headers.size() - 1; i >= 0; i--) {
                        if (!isRowNumberColumn(headers.get(i))) {
                            yAxis = headers.get(i).getName();
                            break;
                        }
                    }
                }
            }

            return new ChartRecommendation(chartType, confidence, reason, xAxis, yAxis, dimensions, xAxisFormat, yAxisFormat, lineVariant, pieVariant, barOrientation, order);

        } catch (Exception e) {
            log.warn("Failed to parse LLM chart response: {}, raw: {}", e.getMessage(), jsonStr);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }

    /**
     * Parse dimension field from LLM JSON — handles both string and array.
     * e.g. "dimension": "user_type" or "dimension": ["user_type", "day_diff"]
     */
    private List<String> parseDimensions(JSONObject json, List<Header> headers) {
        Object rawDimension = json.get("dimension");
        if (rawDimension == null) return null;

        List<String> result = new ArrayList<>();
        if (rawDimension instanceof com.alibaba.fastjson2.JSONArray) {
            com.alibaba.fastjson2.JSONArray arr = (com.alibaba.fastjson2.JSONArray) rawDimension;
            for (int i = 0; i < arr.size(); i++) {
                String validated = validateColumnName(arr.getString(i), headers);
                if (validated != null) result.add(validated);
            }
        } else {
            String validated = validateColumnName(String.valueOf(rawDimension), headers);
            if (validated != null) result.add(validated);
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Validate that column name exists in headers, return null if not found.
     * Also rejects system-generated Row Number column.
     */
    private String validateColumnName(String columnName, List<Header> headers) {
        if (columnName == null || columnName.trim().isEmpty() || "null".equalsIgnoreCase(columnName)) {
            return null;
        }
        for (Header h : headers) {
            if (h.getName().equalsIgnoreCase(columnName.trim())) {
                // Reject Row Number column - it's not real data
                if (isRowNumberColumn(h)) {
                    log.warn("Column '{}' is a system Row Number column, ignoring", columnName);
                    return null;
                }
                return h.getName(); // Return actual case from header
            }
        }
        log.warn("Column '{}' not found in headers, ignoring", columnName);
        return null;
    }

    /**
     * Check if the header is a system-generated Row Number column
     */
    private boolean isRowNumberColumn(Header header) {
        if (header == null) return false;
        String dataType = header.getDataType();
        String name = header.getName();
        // Check by dataType (primary) or name (fallback)
        return "INQUERY_ROW_NUMBER".equalsIgnoreCase(dataType) ||
               "Row Number".equalsIgnoreCase(name);
    }

    /**
     * Validate xAxisFormat, return null (original) if invalid
     */
    private String validateXAxisFormat(String format) {
        if (format == null || format.trim().isEmpty() || "null".equalsIgnoreCase(format)) {
            return null;
        }
        String normalized = format.trim().toLowerCase();
        switch (normalized) {
            case "original":
            case "date_iso":
            case "date_us":
            case "date_eu":
            case "date_short":
            case "date_month_year":
            case "date_year":
            case "date_month_day":
            case "date_quarter":
            case "date_time":
            case "number_comma":
            case "number_compact":
                return "original".equals(normalized) ? null : normalized;
            default:
                log.warn("Unknown xAxisFormat '{}', using original", format);
                return null;
        }
    }

    /**
     * Validate yAxisFormat, return 'comma' as default if invalid
     */
    private String validateYAxisFormat(String format) {
        if (format == null || format.trim().isEmpty()) {
            return "comma";
        }
        String normalized = format.trim().toLowerCase();
        switch (normalized) {
            case "comma":
            case "percent":
            case "percent0":
            case "percent1":
            case "percent2":
            case "decimal1":
            case "decimal2":
            case "k":
            case "original":
                return normalized;
            default:
                log.warn("Unknown yAxisFormat '{}', defaulting to 'comma'", format);
                return "comma";
        }
    }

    /**
     * Validate lineVariant, return null if invalid (use default)
     */
    private String validateLineVariant(String variant) {
        if (variant == null || variant.trim().isEmpty() || "null".equalsIgnoreCase(variant)) {
            return null;
        }
        String normalized = variant.trim().toLowerCase();
        switch (normalized) {
            case "line":
            case "area":
            case "smooth":
            case "step":
                return normalized;
            default:
                log.warn("Unknown lineVariant '{}', using default", variant);
                return null;
        }
    }

    /**
     * Validate pieVariant, return null if invalid (use default)
     */
    private String validatePieVariant(String variant) {
        if (variant == null || variant.trim().isEmpty() || "null".equalsIgnoreCase(variant)) {
            return null;
        }
        String normalized = variant.trim().toLowerCase();
        switch (normalized) {
            case "pie":
            case "ring":
            case "rose":
                return normalized;
            default:
                log.warn("Unknown pieVariant '{}', using default", variant);
                return null;
        }
    }

    /**
     * Validate barOrientation, return null if invalid (use default)
     */
    private String validateBarOrientation(String orientation) {
        if (orientation == null || orientation.trim().isEmpty() || "null".equalsIgnoreCase(orientation)) {
            return null;
        }
        String normalized = orientation.trim().toLowerCase();
        switch (normalized) {
            case "vertical":
            case "horizontal":
                return normalized;
            default:
                log.warn("Unknown barOrientation '{}', using default", orientation);
                return null;
        }
    }

    /**
     * Validate order, return null if invalid (use default)
     */
    private String validateOrder(String order) {
        if (order == null || order.trim().isEmpty() || "null".equalsIgnoreCase(order)) {
            return null;
        }
        String normalized = order.trim().toLowerCase();
        switch (normalized) {
            case "x_asc":
            case "x_desc":
            case "y_asc":
            case "y_desc":
                return normalized;
            default:
                log.warn("Unknown order '{}', using default", order);
                return null;
        }
    }

    private ChartType parseChartType(String value) {
        if (value == null) return ChartType.BAR;
        try {
            ChartType type = ChartType.valueOf(value.toUpperCase().trim());
            // All types are supported in frontend: LINE, BAR, PIE, SCATTER, CARD, FUNNEL, TABLE
            return type;
        } catch (Exception e) {
            log.warn("Unknown chart type '{}', defaulting to BAR", value);
            return ChartType.BAR;
        }
    }

    /**
     * The chart LLM sometimes returns TABLE when multiple numeric columns share
     * different scales. For categorical breakdowns the UI should still show a
     * chart (table is always rendered separately). Upgrade TABLE → rule-based
     * BAR/PIE/LINE when the result shape is clearly chartable.
     */
    private ChartRecommendation preferChartOverTable(ChartRecommendation recommendation,
                                                     List<Header> headers,
                                                     List<List<String>> dataList,
                                                     String userQuestion) {
        if (recommendation == null || recommendation.getChartType() != ChartType.TABLE) {
            return recommendation;
        }
        if (!isChartableTabularData(headers, dataList)) {
            return recommendation;
        }

        ChartRecommendation fallback = fallbackRecommendation(headers, dataList);
        if (fallback.getChartType() == ChartType.TABLE || fallback.getChartType() == ChartType.CARD) {
            return recommendation;
        }

        String yAxis = pickPrimaryMetricColumn(headers, userQuestion);
        if (yAxis != null && !yAxis.equals(fallback.getYAxis())) {
            fallback = new ChartRecommendation(
                    fallback.getChartType(),
                    fallback.getConfidence(),
                    fallback.getReason() + " (primary metric aligned to question)",
                    fallback.getXAxis(),
                    yAxis,
                    fallback.getDimensions(),
                    fallback.getXAxisFormat(),
                    fallback.getYAxisFormat(),
                    fallback.getLineVariant(),
                    fallback.getPieVariant(),
                    fallback.getBarOrientation(),
                    fallback.getOrder());
        }

        log.info("Upgraded TABLE chart recommendation to {} (xAxis='{}', yAxis='{}')",
                fallback.getChartType(), fallback.getXAxis(), fallback.getYAxis());
        fallback.setRawResponse(recommendation.getRawResponse());
        return fallback;
    }

    private boolean isChartableTabularData(List<Header> headers, List<List<String>> dataList) {
        int dataCols = 0;
        boolean hasCategorical = false;
        boolean hasNumeric = false;
        for (Header header : headers) {
            if (isRowNumberColumn(header)) continue;
            dataCols++;
            String dataType = header.getDataType() != null ? header.getDataType().toLowerCase() : "";
            if (isNumericType(dataType)) {
                hasNumeric = true;
            } else if (!isDateType(dataType)) {
                hasCategorical = true;
            }
        }
        int rowCount = dataList != null ? dataList.size() : 0;
        return hasCategorical && hasNumeric && dataCols <= 6 && rowCount >= 2 && rowCount <= 30;
    }

    private String pickPrimaryMetricColumn(List<Header> headers, String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) return null;
        String question = userQuestion.toLowerCase(Locale.ROOT);
        String bestMatch = null;
        int bestScore = 0;
        for (Header header : headers) {
            if (isRowNumberColumn(header)) continue;
            String dataType = header.getDataType() != null ? header.getDataType().toLowerCase() : "";
            if (!isNumericType(dataType)) continue;
            String name = header.getName();
            if (name == null || name.isBlank()) continue;
            String normalized = name.toLowerCase(Locale.ROOT).replace('_', ' ');
            int score = 0;
            for (String token : normalized.split("[\\s_]+")) {
                if (token.length() < 3) continue;
                if (question.contains(token)) score += token.length();
            }
            if (score > bestScore) {
                bestScore = score;
                bestMatch = name;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    /**
     * Fallback rule-based recommendation if LLM fails.
     */
    private ChartRecommendation fallbackRecommendation(List<Header> headers, List<List<String>> dataList) {
        int rowCount = dataList != null ? dataList.size() : 0;

        // Find columns by type (skip Row Number column)
        String dateColumn = null;
        String numericColumn = null;
        String categoricalColumn = null;
        String yAxisFormat = "comma"; // Default format
        int numericCount = 0;
        int dateCount = 0;
        int otherCount = 0;

        for (Header header : headers) {
            // Skip system Row Number column
            if (isRowNumberColumn(header)) {
                continue;
            }
            String dataType = header.getDataType() != null ? header.getDataType().toLowerCase() : "";
            String columnName = header.getName().toLowerCase();
            if (isDateType(dataType) && dateColumn == null) {
                dateColumn = header.getName();
                dateCount++;
            } else if (isNumericType(dataType) && numericColumn == null) {
                numericColumn = header.getName();
                numericCount++;
                // Try to detect format from column name
                if (columnName.contains("rate") || columnName.contains("ratio") || 
                    columnName.contains("retention") || columnName.contains("percent") ||
                    columnName.contains("%")) {
                    // Most ratio-style metrics in fact outputs are 0-1 (prefer ×100 formats by default)
                    yAxisFormat = "percent1";
                }
            } else if (isNumericType(dataType)) {
                numericCount++;
            } else if (categoricalColumn == null) {
                categoricalColumn = header.getName();
                otherCount++;
            } else {
                otherCount++;
            }
        }

        // Get first valid column (not Row Number)
        String firstValidColumn = null;
        String lastValidColumn = null;
        for (Header h : headers) {
            if (!isRowNumberColumn(h)) {
                if (firstValidColumn == null) {
                    firstValidColumn = h.getName();
                }
                lastValidColumn = h.getName();
            }
        }

        // Single row metrics-only → CARD
        if (rowCount == 1) {
            boolean metricsOnly = dateCount == 0 && otherCount == 0 && numericCount >= 1;
            if (metricsOnly) {
                return new ChartRecommendation(ChartType.CARD, 0.96, "Single row metrics-only (fallback)",
                    null, null, null, yAxisFormat);
            }
            // Otherwise → BAR (frontend may auto-transpose)
            String col = firstValidColumn != null ? firstValidColumn : (headers.size() > 0 ? headers.get(0).getName() : null);
            return new ChartRecommendation(ChartType.BAR, 0.95, "Single row - BAR chart (fallback)", col, col, null, yAxisFormat, null, null, null);
        }

        // Time series → LINE (only if date column exists)
        if (dateColumn != null && numericColumn != null) {
            return new ChartRecommendation(ChartType.LINE, 0.90,
                "Time series data detected (fallback)", dateColumn, numericColumn, null, "date_short", yAxisFormat, null, null, null, null);
        }

        // Part-to-whole with limited categories → PIE
        if (categoricalColumn != null && numericColumn != null && rowCount > 1 && rowCount <= 7) {
            return new ChartRecommendation(ChartType.PIE, 0.82,
                "Part-to-whole comparison (fallback)", categoricalColumn, numericColumn, null, yAxisFormat, null, null, null);
        }

        // Sequential/conversion data → FUNNEL (if categorical order suggests funnel)
        if (categoricalColumn != null && numericColumn != null && rowCount > 1 && rowCount <= 10) {
            // Check if column name suggests funnel/conversion
            String catName = categoricalColumn.toLowerCase();
            if (catName.contains("stage") || catName.contains("step") || catName.contains("funnel") ||
                catName.contains("conversion") || catName.contains("level")) {
                return new ChartRecommendation(ChartType.FUNNEL, 0.80,
                    "Conversion funnel detected (fallback)", categoricalColumn, numericColumn, null, yAxisFormat, null, null, null);
            }
        }

        // Categorical + numeric → BAR (prefer BAR over SCATTER when categorical exists)
        if (categoricalColumn != null && numericColumn != null) {
            return new ChartRecommendation(ChartType.BAR, 0.85,
                "Categorical comparison (fallback)", categoricalColumn, numericColumn, null, yAxisFormat, null, null, null);
        }

        // Two numeric columns ONLY (no categorical) → SCATTER (correlation analysis)
        // Only recommend SCATTER when there are NO categorical columns and exactly 2+ numeric columns
        if (numericCount >= 2 && otherCount == 0 && rowCount > 1) {
            // Find second numeric column
            String secondNumericColumn = null;
            for (Header h : headers) {
                if (!isRowNumberColumn(h) && isNumericType(h.getDataType() != null ? h.getDataType().toLowerCase() : "")) {
                    if (!h.getName().equals(numericColumn)) {
                        secondNumericColumn = h.getName();
                        break;
                    }
                }
            }
            if (secondNumericColumn != null) {
                return new ChartRecommendation(ChartType.SCATTER, 0.88,
                    "Two numeric variables for correlation (fallback)", numericColumn, secondNumericColumn, null, yAxisFormat, null, null, null);
            }
        }

        // Default → BAR with first/last valid columns (excluding Row Number)
        String xAxis = firstValidColumn;
        String yAxis = lastValidColumn != null && !lastValidColumn.equals(firstValidColumn) ? lastValidColumn : firstValidColumn;
        return new ChartRecommendation(ChartType.BAR, 0.70,
            "Default chart (fallback)", xAxis, yAxis, null, yAxisFormat, null, null, null);
    }

    private boolean isNumericType(String dataType) {
        return dataType.contains("int") ||
               dataType.contains("float") ||
               dataType.contains("double") ||
               dataType.contains("decimal") ||
               dataType.contains("number") ||
               dataType.contains("numeric");
    }

    private boolean isDateType(String dataType) {
        return dataType.contains("date") ||
               dataType.contains("time") ||
               dataType.contains("timestamp");
    }

    public enum ChartType {
        LINE,       // Time series (requires date column)
        BAR,        // Categorical comparison (default)
        PIE,        // Part-to-whole
        SCATTER,    // Correlation between two numeric variables
        CARD,       // Metric-only single-row summary
        FUNNEL,     // Conversion funnel / process flow
        TABLE       // Tabular data display
    }

    public static class ChartRecommendation {
        private final ChartType chartType;
        private final double confidence;
        private final String reason;
        private final String xAxis;
        private final String yAxis;
        private final List<String> dimensions;
        private final String xAxisFormat;
        private final String yAxisFormat;
        private final String lineVariant;
        private final String pieVariant;
        private final String barOrientation;
        private final String order;
        private String rawResponse;

        public ChartRecommendation(ChartType chartType, double confidence, String reason) {
            this(chartType, confidence, reason, null, null, (List<String>) null, null, "comma", null, null, null, null);
        }

        public ChartRecommendation(ChartType chartType, double confidence, String reason,
                                   String xAxis, String yAxis, String dimension, String yAxisFormat) {
            this(chartType, confidence, reason, xAxis, yAxis,
                dimension != null ? List.of(dimension) : null,
                null, yAxisFormat, null, null, null, null);
        }

        public ChartRecommendation(ChartType chartType, double confidence, String reason,
                                   String xAxis, String yAxis, String dimension, String yAxisFormat,
                                   String lineVariant, String pieVariant, String barOrientation) {
            this(chartType, confidence, reason, xAxis, yAxis,
                dimension != null ? List.of(dimension) : null,
                null, yAxisFormat, lineVariant, pieVariant, barOrientation, null);
        }

        public ChartRecommendation(ChartType chartType, double confidence, String reason,
                                   String xAxis, String yAxis, List<String> dimensions,
                                   String xAxisFormat, String yAxisFormat,
                                   String lineVariant, String pieVariant, String barOrientation, String order) {
            this.chartType = chartType;
            this.confidence = confidence;
            this.reason = reason;
            this.xAxis = xAxis;
            this.yAxis = yAxis;
            this.dimensions = dimensions;
            this.xAxisFormat = xAxisFormat;
            this.yAxisFormat = yAxisFormat != null ? yAxisFormat : "comma";
            this.lineVariant = lineVariant;
            this.pieVariant = pieVariant;
            this.barOrientation = barOrientation;
            this.order = order;
        }

        public ChartType getChartType() { return chartType; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }
        public String getXAxis() { return xAxis; }
        public String getYAxis() { return yAxis; }
        /** First dimension for backward compatibility */
        public String getDimension() {
            return dimensions != null && !dimensions.isEmpty() ? dimensions.get(0) : null;
        }
        /** All dimensions (for composite series) */
        public List<String> getDimensions() { return dimensions; }
        public String getXAxisFormat() { return xAxisFormat; }
        public String getYAxisFormat() { return yAxisFormat; }
        public String getLineVariant() { return lineVariant; }
        public String getPieVariant() { return pieVariant; }
        public String getBarOrientation() { return barOrientation; }
        public String getOrder() { return order; }
        public String getRawResponse() { return rawResponse; }
        public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

        @Override
        public String toString() {
            return String.format("ChartRecommendation{type=%s, xAxis='%s', yAxis='%s', dimensions=%s, xAxisFormat='%s', yAxisFormat='%s', lineVariant='%s', pieVariant='%s', barOrientation='%s', order='%s', confidence=%.0f%%, reason='%s'}",
                chartType, xAxis, yAxis, dimensions, xAxisFormat, yAxisFormat, lineVariant, pieVariant, barOrientation, order, confidence * 100, reason);
        }
    }
}
