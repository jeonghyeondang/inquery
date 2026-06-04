package ai.inquery.server.web.api.controller.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QuickChart.io service for generating chart images via URL.
 * Parses LLM's chart recommendation JSON and converts to Chart.js format.
 * 
 * @see <a href="https://quickchart.io/documentation/">QuickChart Documentation</a>
 */
@Slf4j
@Service
public class QuickChartService {

    private static final String QUICKCHART_BASE_URL = "https://quickchart.io/chart";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Chart colors (similar to ECharts default palette)
    private static final String[] CHART_COLORS = {
            "rgba(91, 143, 249, 0.8)",   // Blue
            "rgba(90, 216, 166, 0.8)",   // Green
            "rgba(246, 189, 22, 0.8)",   // Yellow
            "rgba(232, 104, 74, 0.8)",   // Red
            "rgba(109, 120, 173, 0.8)",  // Purple
            "rgba(255, 157, 77, 0.8)",   // Orange
    };

    /**
     * Chart recommendation from LLM.
     */
    private record ChartRecommendation(
            String chartType,
            String xAxis,
            String yAxis,
            String dimension,
            String yAxisFormat,
            String lineVariant,
            String pieVariant,
            String barOrientation,
            double confidence,
            String reason
    ) {}

    /**
     * Generate QuickChart URL for the given data and LLM recommendation.
     *
     * @param chartRecommendationJson  JSON string from LLM (e.g., {"chartType":"BAR","xAxis":"date",...})
     * @param data                     Query result data
     * @param headers                  Column headers
     * @param title                    Chart title
     * @return QuickChart URL that renders as an image, or null if chart not supported
     */
    public String generateChartUrl(String chartRecommendationJson, List<Map<String, Object>> data,
                                   List<String> headers, String title) {
        if (data == null || data.isEmpty() || headers == null || headers.isEmpty()) {
            log.warn("[QuickChart] No data to generate chart");
            return null;
        }

        try {
            // Parse LLM recommendation
            ChartRecommendation recommendation = parseRecommendation(chartRecommendationJson);
            if (recommendation == null) {
                log.warn("[QuickChart] Failed to parse chart recommendation");
                return null;
            }

            // Check if chart type is supported
            String chartType = recommendation.chartType();
            if (chartType == null || "CARD".equals(chartType) || "TABLE".equals(chartType) || "FUNNEL".equals(chartType)) {
                log.info("[QuickChart] Chart type '{}' not supported for image generation", chartType);
                return null;
            }

            // Convert to Chart.js type
            String chartJsType = convertToChartJsType(chartType, recommendation.barOrientation());
            if (chartJsType == null) {
                log.warn("[QuickChart] Unknown chart type: {}", chartType);
                return null;
            }

            // Build Chart.js config
            Map<String, Object> chartConfig = buildChartConfig(chartJsType, recommendation, data, headers, title);
            
            // Convert to JSON
            String json = objectMapper.writeValueAsString(chartConfig);
            
            // QuickChart.io requires JavaScript functions WITHOUT quotes
            // JSON serializes them as strings like "(v) => v.toLocaleString()"
            // We need to remove the quotes to make them actual functions: (v) => v.toLocaleString()
            json = unquoteJavaScriptFunctions(json);
            
            String encodedChart = URLEncoder.encode(json, StandardCharsets.UTF_8);
            
            // Build URL with parameters
            String url = QUICKCHART_BASE_URL + "?c=" + encodedChart 
                    + "&backgroundColor=white"
                    + "&width=600"
                    + "&height=400";
            
            log.info("[QuickChart] Generated chart URL - type: {}, chartJsType: {}, xAxis: {}, yAxis: {}", 
                    chartType, chartJsType, recommendation.xAxis(), recommendation.yAxis());
            
            return url;
            
        } catch (Exception e) {
            log.error("[QuickChart] Failed to generate chart URL", e);
            return null;
        }
    }

    /**
     * Extract chart type and reason from LLM's chart recommendation JSON.
     * Returns a string array: [chartType, reason] or null if parsing fails.
     */
    public String[] getChartTypeAndReason(String jsonString) {
        ChartRecommendation rec = parseRecommendation(jsonString);
        if (rec == null) {
            return null;
        }
        return new String[] { rec.chartType(), rec.reason() };
    }

    /**
     * Parse LLM's chart recommendation JSON.
     */
    private ChartRecommendation parseRecommendation(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return null;
        }

        try {
            // Handle case where LLM returns JSON wrapped in markdown code block
            String cleanJson = jsonString.trim();
            if (cleanJson.startsWith("```")) {
                int start = cleanJson.indexOf('{');
                int end = cleanJson.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    cleanJson = cleanJson.substring(start, end + 1);
                }
            }

            JsonNode node = objectMapper.readTree(cleanJson);
            
            return new ChartRecommendation(
                    getTextOrNull(node, "chartType"),
                    getTextOrNull(node, "xAxis"),
                    getTextOrNull(node, "yAxis"),
                    getTextOrNull(node, "dimension"),
                    getTextOrNull(node, "yAxisFormat"),
                    getTextOrNull(node, "lineVariant"),
                    getTextOrNull(node, "pieVariant"),
                    getTextOrNull(node, "barOrientation"),
                    node.has("confidence") ? node.get("confidence").asDouble() : 0.5,
                    getTextOrNull(node, "reason")
            );
        } catch (Exception e) {
            log.warn("[QuickChart] Failed to parse recommendation JSON: {}", e.getMessage());
            // Fallback: try to extract chart type from string
            if (jsonString.toUpperCase().contains("BAR")) {
                return new ChartRecommendation("BAR", null, null, null, null, null, null, null, 0.5, null);
            } else if (jsonString.toUpperCase().contains("LINE")) {
                return new ChartRecommendation("LINE", null, null, null, null, null, null, null, 0.5, null);
            } else if (jsonString.toUpperCase().contains("PIE")) {
                return new ChartRecommendation("PIE", null, null, null, null, null, null, null, 0.5, null);
            }
            return null;
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    /**
     * Convert LLM chart type to Chart.js type.
     */
    private String convertToChartJsType(String llmType, String barOrientation) {
        if (llmType == null) return "bar";
        
        return switch (llmType.toUpperCase()) {
            case "LINE" -> "line";
            case "BAR" -> "horizontal".equalsIgnoreCase(barOrientation) ? "horizontalBar" : "bar";
            case "PIE" -> "pie";
            case "SCATTER" -> "scatter";
            default -> null;
        };
    }

    /**
     * Build Chart.js configuration object using LLM recommendation.
     */
    private Map<String, Object> buildChartConfig(String chartJsType, ChartRecommendation rec,
                                                  List<Map<String, Object>> data,
                                                  List<String> headers, String title) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", chartJsType);
        
        // Build data section
        Map<String, Object> dataSection = new HashMap<>();
        
        // Determine xAxis field (use recommendation or default to first column)
        String xAxisField = rec.xAxis() != null ? findMatchingHeader(rec.xAxis(), headers) : headers.get(0);
        
        // Determine yAxis field (use recommendation or default to second column)
        String yAxisField = rec.yAxis() != null ? findMatchingHeader(rec.yAxis(), headers) : 
                (headers.size() > 1 ? headers.get(1) : headers.get(0));
        
        // Sort data by xAxis if it looks like a date field (for chronological order)
        final String finalXAxisField = xAxisField;
        List<Map<String, Object>> sortedData = new ArrayList<>(data);
        if (isDateLikeField(xAxisField)) {
            sortedData.sort((a, b) -> {
                String valA = String.valueOf(a.getOrDefault(finalXAxisField, ""));
                String valB = String.valueOf(b.getOrDefault(finalXAxisField, ""));
                return valA.compareTo(valB); // String comparison works for ISO dates (YYYY-MM-DD)
            });
            log.debug("[QuickChart] Sorted data by date field: {}", xAxisField);
        }
        
        // Labels (X-axis values)
        List<String> labels = sortedData.stream()
                .map(row -> String.valueOf(row.getOrDefault(finalXAxisField, "")))
                .toList();
        dataSection.put("labels", labels);
        
        // Build datasets
        List<Map<String, Object>> datasets = new ArrayList<>();
        
        if ("pie".equals(chartJsType)) {
            // Pie chart: single dataset with multiple colors
            Map<String, Object> dataset = new HashMap<>();
            dataset.put("label", yAxisField);
            
            List<Object> values = sortedData.stream()
                    .map(row -> parseNumericValue(row.get(yAxisField)))
                    .toList();
            dataset.put("data", values);
            
            // Colors for each slice
            List<String> colors = new ArrayList<>();
            for (int i = 0; i < sortedData.size(); i++) {
                colors.add(CHART_COLORS[i % CHART_COLORS.length]);
            }
            dataset.put("backgroundColor", colors);
            
            datasets.add(dataset);
        } else if (rec.dimension() != null && !rec.dimension().equals(xAxisField)) {
            // Grouped data: create multiple datasets based on dimension
            String dimensionField = findMatchingHeader(rec.dimension(), headers);
            if (dimensionField != null) {
                // Get unique dimension values
                List<String> dimensionValues = sortedData.stream()
                        .map(row -> String.valueOf(row.getOrDefault(dimensionField, "")))
                        .distinct()
                        .toList();
                
                int colorIndex = 0;
                for (String dimValue : dimensionValues) {
                    Map<String, Object> dataset = new HashMap<>();
                    dataset.put("label", dimValue);
                    
                    // Filter data for this dimension value
                    List<Object> values = sortedData.stream()
                            .filter(row -> dimValue.equals(String.valueOf(row.getOrDefault(dimensionField, ""))))
                            .map(row -> parseNumericValue(row.get(yAxisField)))
                            .toList();
                    dataset.put("data", values);
                    
                    String color = CHART_COLORS[colorIndex % CHART_COLORS.length];
                    dataset.put("backgroundColor", color);
                    dataset.put("borderColor", color.replace("0.8", "1"));
                    
                    if ("line".equals(chartJsType)) {
                        dataset.put("fill", "area".equalsIgnoreCase(rec.lineVariant()));
                        dataset.put("tension", "smooth".equalsIgnoreCase(rec.lineVariant()) ? 0.4 : 0);
                        if ("step".equalsIgnoreCase(rec.lineVariant())) {
                            dataset.put("steppedLine", true);
                        }
                    }
                    
                    datasets.add(dataset);
                    colorIndex++;
                }
            }
        }
        
        // Default: single dataset if no grouping or grouping failed
        if (datasets.isEmpty()) {
            Map<String, Object> dataset = new HashMap<>();
            dataset.put("label", yAxisField);
            
            List<Object> values = sortedData.stream()
                    .map(row -> parseNumericValue(row.get(yAxisField)))
                    .toList();
            dataset.put("data", values);
            
            String color = CHART_COLORS[0];
            dataset.put("backgroundColor", color);
            dataset.put("borderColor", color.replace("0.8", "1"));
            
            if ("line".equals(chartJsType)) {
                dataset.put("fill", "area".equalsIgnoreCase(rec.lineVariant()));
                dataset.put("tension", "smooth".equalsIgnoreCase(rec.lineVariant()) ? 0.4 : 0);
            }
            
            datasets.add(dataset);
        }
        
        dataSection.put("datasets", datasets);
        config.put("data", dataSection);
        
        // Build options section
        Map<String, Object> options = new HashMap<>();
        
        // Title
        if (title != null && !title.isEmpty()) {
            Map<String, Object> titleConfig = new HashMap<>();
            titleConfig.put("display", true);
            titleConfig.put("text", truncateTitle(title));
            options.put("title", titleConfig);
        }
        
        // Legend
        Map<String, Object> legend = new HashMap<>();
        legend.put("display", datasets.size() <= 5);
        legend.put("position", "bottom");
        options.put("legend", legend);
        
        // Plugins for data labels (only for small datasets)
        Map<String, Object> plugins = new HashMap<>();
        Map<String, Object> datalabels = new HashMap<>();
        datalabels.put("display", data.size() <= 10);
        
        // Adjust anchor/align based on chart type
        // horizontalBar: labels should be at end (right side of bar)
        // Other charts: labels should be on top
        if ("horizontalBar".equals(chartJsType)) {
            datalabels.put("anchor", "end");
            datalabels.put("align", "right");
        } else {
            datalabels.put("anchor", "end");
            datalabels.put("align", "top");
        }
        
        // Apply format to data labels using JavaScript functions
        String formatterJs = getFormatterFunction(rec.yAxisFormat());
        if (formatterJs != null) {
            datalabels.put("formatter", formatterJs);
        }
        
        plugins.put("datalabels", datalabels);
        options.put("plugins", plugins);
        
        // Apply format to value axis ticks (for bar/line charts)
        // Note: horizontalBar has values on X-axis, regular bar/line has values on Y-axis
        if (!"pie".equals(chartJsType) && rec.yAxisFormat() != null) {
            Map<String, Object> scales = new HashMap<>();
            Map<String, Object> valueAxis = new HashMap<>();
            Map<String, Object> ticks = new HashMap<>();
            
            String tickCallback = getTickCallbackFunction(rec.yAxisFormat());
            if (tickCallback != null) {
                ticks.put("callback", tickCallback);
            }
            
            valueAxis.put("ticks", ticks);
            
            // For horizontalBar, values are on X-axis; for others, values are on Y-axis
            if ("horizontalBar".equals(chartJsType)) {
                scales.put("xAxes", List.of(valueAxis));
            } else {
                scales.put("yAxes", List.of(valueAxis));
            }
            options.put("scales", scales);
        }
        
        config.put("options", options);
        
        return config;
    }

    /**
     * Find matching header (case-insensitive, partial match).
     */
    private String findMatchingHeader(String target, List<String> headers) {
        if (target == null || headers == null) return null;
        
        String lowerTarget = target.toLowerCase();
        
        // Exact match first
        for (String header : headers) {
            if (header.equalsIgnoreCase(target)) {
                return header;
            }
        }
        
        // Partial match
        for (String header : headers) {
            if (header.toLowerCase().contains(lowerTarget) || lowerTarget.contains(header.toLowerCase())) {
                return header;
            }
        }
        
        return null;
    }

    /**
     * Parse value to numeric, handling strings.
     */
    private Object parseNumericValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return value;
        
        try {
            String str = String.valueOf(value).trim();
            // Remove commas and currency symbols
            str = str.replaceAll("[,$€¥£]", "");
            if (str.contains(".")) {
                return Double.parseDouble(str);
            } else {
                return Long.parseLong(str);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if field name looks like a date field.
     */
    private boolean isDateLikeField(String fieldName) {
        if (fieldName == null) return false;
        String lower = fieldName.toLowerCase();
        return lower.contains("date") || lower.contains("dt") || lower.contains("day") 
                || lower.contains("month") || lower.contains("year") || lower.contains("time")
                || lower.equals("created") || lower.equals("updated");
    }

    /**
     * Truncate title if too long.
     */
    private String truncateTitle(String title) {
        if (title == null) return "";
        if (title.length() <= 50) return title;
        return title.substring(0, 47) + "...";
    }

    /**
     * Get JavaScript formatter function for data labels based on format type.
     * QuickChart.io accepts JavaScript function strings.
     * 
     * Supported formats (from LLM chart recommendation - aligned with ResultAnalyzerAgent):
     * - comma/number: 1000 → 1,000 (thousand separator)
     * - percent: Values ALREADY 0-100 range (45.5 → 45.5%)
     * - percent1: Values 0-1 RATIO, needs ×100 (0.416 → 41.6%)
     * - k/compact: 1000000 → 1M (compact notation)
     * - decimal1: 1.234 → 1.2 (1 decimal place)
     * - decimal2: 1.234 → 1.23 (2 decimal places)
     * - currency/usd: 1000 → $1,000
     * - krw: 1000 → ₩1,000
     */
    private String getFormatterFunction(String format) {
        if (format == null) return null;
        
        return switch (format.toLowerCase()) {
            case "comma", "number" -> "(v) => v.toLocaleString()";
            case "percent", "percent100", "percentage" -> "(v) => v.toFixed(1) + '%'";  // Already 0-100
            case "percent1" -> "(v) => (v * 100).toFixed(1) + '%'";  // 0-1 ratio, multiply by 100
            case "k", "compact" -> "(v) => { if (v >= 1000000) return (v/1000000).toFixed(1) + 'M'; if (v >= 1000) return (v/1000).toFixed(1) + 'K'; return v; }";
            case "decimal1" -> "(v) => v.toFixed(1)";
            case "decimal2" -> "(v) => v.toFixed(2)";
            case "currency", "usd" -> "(v) => '$' + v.toLocaleString()";
            case "krw" -> "(v) => '₩' + v.toLocaleString()";
            case "original" -> null; // No formatting
            default -> null;
        };
    }

    /**
     * Get JavaScript callback function for Y-axis ticks based on format type.
     */
    private String getTickCallbackFunction(String format) {
        if (format == null) return null;
        
        return switch (format.toLowerCase()) {
            case "comma", "number" -> "(v) => v.toLocaleString()";
            case "percent", "percent100", "percentage" -> "(v) => v.toFixed(0) + '%'";  // Already 0-100
            case "percent1" -> "(v) => (v * 100).toFixed(0) + '%'";  // 0-1 ratio, multiply by 100
            case "k", "compact" -> "(v) => { if (v >= 1000000) return (v/1000000).toFixed(0) + 'M'; if (v >= 1000) return (v/1000).toFixed(0) + 'K'; return v; }";
            case "decimal1" -> "(v) => v.toFixed(1)";
            case "decimal2" -> "(v) => v.toFixed(2)";
            case "currency", "usd" -> "(v) => '$' + v.toLocaleString()";
            case "krw" -> "(v) => '₩' + v.toLocaleString()";
            case "original" -> null; // No formatting
            default -> null;
        };
    }

    /**
     * Remove quotes around JavaScript function strings in JSON.
     * QuickChart.io requires actual JS functions, not string representations.
     * 
     * Transforms: {"formatter": "(v) => v.toLocaleString()"}
     * To:         {"formatter": (v) => v.toLocaleString()}
     */
    private String unquoteJavaScriptFunctions(String json) {
        if (json == null) return null;
        
        // Pattern to find quoted arrow functions: "(v) => ..."
        // Match: "formatter":"(v) => something"
        // Replace with: "formatter":(v) => something
        
        // Handle arrow functions with simple expressions
        json = json.replaceAll("\"\\(v\\) => ([^\"]+)\"", "(v) => $1");
        
        // Handle arrow functions with block bodies { ... }
        // This is trickier because the content might have nested quotes
        java.util.regex.Pattern blockPattern = java.util.regex.Pattern.compile(
                "\"\\(v\\) => \\{ ([^}]+) \\}\"");
        java.util.regex.Matcher matcher = blockPattern.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String body = matcher.group(1);
            matcher.appendReplacement(sb, "(v) => { " + body + " }");
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
}
