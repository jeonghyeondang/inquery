package ai.inquery.server.domain.core.query;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Result of query processing.
 */
@Data
public class QueryProcessingResult {
    private String originalQuery;
    private String generatedSql;
    private Object resultData;
    private String thoughtProcess; // Chain-of-Thought logs
    private String recommendedChart; // Recommended visualization type
    private double chartConfidence; // Confidence score for recommendation
    private boolean needsExecution; // Flag indicating SQL needs to be executed by user
    private QueryType queryType; // Type of query (CHART, TABLE, or CHAT)
    private String schemaContext; // Schema metadata from vector DB for interpretation

    // Chart configuration recommendation (axis settings)
    private String chartXAxis;      // Recommended X-axis column name
    private String chartYAxis;      // Recommended Y-axis column name (single value from LLM)
    private String chartDimension;  // Recommended dimension/series grouping column (first, for backward compat)
    private List<String> chartDimensions; // All dimension columns for composite series (e.g. ["user_type", "day_diff"])
    private String chartXAxisFormat; // Recommended X-axis format: date_short, date_month_year, etc.
    private String chartYAxisFormat; // Recommended Y-axis format: comma, percent, percent1, k, etc.
    
    // Chart variant recommendations
    private String chartLineVariant;   // LINE chart variant: line, area, smooth, step
    private String chartPieVariant;   // PIE chart variant: pie, ring, rose
    private String chartBarOrientation; // BAR chart orientation: vertical, horizontal
    private String chartOrder;        // Recommended sort order: x_asc, x_desc, y_asc, y_desc

    // Chart recommendation LLM info (for monitoring)
    private String chartRecommendPrompt;
    private String chartRecommendResponse;
    private String chartRecommendReason;

    // Date range suggestion for DATA queries without specified time range
    private boolean needsDateRange; // Flag indicating query needs a date range selection

    // Clarification options for ambiguous queries (Deep Agent mode)
    private boolean needsClarification; // Flag indicating query needs clarification
    private List<Map<String, String>> clarificationOptions; // Options with "label" and "query" keys
    private String clarificationReason; // Why clarification is needed

    // Disambiguation options for AMBIGUOUS classification
    private boolean needsDisambiguation; // Flag indicating query intent is ambiguous
    private List<Map<String, String>> disambiguationOptions; // Options with "label", "queryType", "refinedQuery" keys

    // Schema query support (for "show table structure" queries)
    private boolean schemaQuery; // Flag indicating this is a schema/structure query
    private List<String> targetTables; // Table names for which to show schema

    // AI-generated natural language explanation (displayed as text in chat)
    private String aiMessage; // Brief explanation of the response in user's language (deprecated, use title + explanation)
    
    // New structured response fields (title -> SQL editor -> explanation layout)
    private String title; // Query title with emoji and ## markdown header (displayed above SQL editor)
    private String explanation; // Detailed explanation of query components (displayed below SQL editor)
    
    // Multiple query options (1-3 depending on what is useful for the question)
    private List<QueryItem> queries;
    
    // Overview text (displayed above all queries)
    private String overview;

    // Additional context from non-database tools (web/wiki/Slack/Jira/GitHub).
    // The frontend keeps this so Manual "Run Query" interpretation can compare
    // executed DB results against the previously gathered context.
    private String additionalInsightContext;

    // UI action emitted when the root agent decides a follow-up should only
    // change the visualization for an already-rendered result.
    private ChartUpdate chartUpdate;

    // Suggested next analysis actions rendered as clickable follow-up buttons.
    // These are generated only when a completed data/metadata result has
    // enough context for useful next steps; they do not imply automatic SQL.
    private List<SuggestedFollowUp> suggestedFollowUps;

    // Multi-aspect analysis fields (set only when the root agent invokes
    // runMultiAspectAnalysis). When true, queries[] holds 2-3 complementary
    // aspects executed in parallel, and synthesis holds the cross-aspect
    // narrative insight.
    private boolean multiAspect;
    private String synthesisGoal; // The cross-aspect goal the LLM was asked to answer
    private String synthesis; // Final synthesized narrative across all aspects
    
    /**
     * Single query item with title, sql, explanation, and (for multi-aspect)
     * per-aspect chart recommendation + short insight + execution status.
     */
    @Data
    public static class QueryItem {
        private String title; // Query title with emoji (## header)
        private String sql; // The SQL query
        private String explanation; // Detailed explanation
        private String suggestion; // Suggestion text for next query (displayed before this query)
        private Object result; // Backend execution result (ExecuteResult) - populated when backend executes SQL

        // Per-aspect fields (used only for multi-aspect analysis). These mirror
        // the chart recommendation fields on QueryProcessingResult but are
        // attached to each aspect since aspects have different shapes.
        private String aspectId;          // Stable id within the message (e.g. "a1", "a2")
        private String aspectReason;      // Why this aspect is required and complementary
        private String aspectInsight;     // 1-2 sentence insight returned by the synthesis LLM
        private String aspectErrorMessage; // Populated when this aspect's SQL execution fails
        private String recommendedChart;
        private String chartXAxis;
        private String chartYAxis;
        private String chartDimension;
        private List<String> chartDimensions;
        private String chartXAxisFormat;
        private String chartYAxisFormat;
        private String chartLineVariant;
        private String chartPieVariant;
        private String chartBarOrientation;
        private String chartOrder;
    }

    @Data
    public static class ChartUpdate {
        private String target; // latest_query
        private Integer queryIndex; // optional; frontend chooses latest result when null
        private String chartType; // BAR, LINE, PIE, SCATTER, TABLE, CARD
        private String chartTitle; // plain-text title for the chart; null => keep original title
        private String message; // short confirmation in the user's language, shown in the chat thread
    }

    @Data
    public static class SuggestedFollowUp {
        private String title;
        private String question;
        private String reason;
        private String type; // trend, driver, segment, quality, profile, etc.
    }
}
