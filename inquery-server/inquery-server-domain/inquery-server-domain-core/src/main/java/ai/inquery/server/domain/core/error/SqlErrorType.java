package ai.inquery.server.domain.core.error;

import ai.inquery.server.domain.api.service.AIService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 31 error types as defined in system_architecture.md
 * Used for intelligent error handling and retry decisions
 *
 * Now uses LLM-based classification with keyword fallback
 */
@Slf4j
@Getter
public enum SqlErrorType {

    // Structural Errors (4 types)
    MISSING_JOIN("Structural", true, "Required JOIN condition is missing"),
    INCORRECT_JOIN_CONDITION("Structural", true, "JOIN condition is incorrect"),
    AMBIGUOUS_COLUMN("Structural", true, "Column reference is ambiguous"),
    MISSING_TABLE_ALIAS("Structural", true, "Table alias is required but missing"),

    // Aggregation Errors (4 types)
    MISSING_GROUP_BY("Aggregation", true, "GROUP BY clause is missing"),
    INVALID_AGGREGATION("Aggregation", true, "Invalid aggregation function usage"),
    HAVING_WITHOUT_GROUP("Aggregation", true, "HAVING clause without GROUP BY"),
    WINDOW_FUNCTION_ERROR("Aggregation", true, "Window function syntax error"),

    // Data Type Errors (4 types)
    TYPE_MISMATCH("DataType", true, "Data type mismatch in operation"),
    DATE_FORMAT_ERROR("DataType", true, "Date format is incorrect"),
    NULL_HANDLING("DataType", true, "Incorrect NULL value handling"),
    CASTING_ERROR("DataType", true, "Type casting failed"),

    // Performance Errors (4 types)
    MISSING_INDEX("Performance", false, "Query may benefit from index"),
    CARTESIAN_JOIN("Performance", false, "Cartesian product detected"),
    INEFFICIENT_SUBQUERY("Performance", false, "Subquery is inefficient"),
    EXCESSIVE_DATA_SCAN("Performance", false, "Excessive data scanning"),

    // Business Logic Errors (4 types)
    WRONG_FILTER_VALUE("BusinessLogic", true, "Filter value is incorrect"),
    MISSING_FILTER("BusinessLogic", true, "Required filter is missing"),
    INCORRECT_CALCULATION("BusinessLogic", true, "Calculation logic is wrong"),
    PERMISSION_DENIED("BusinessLogic", false, "User lacks permission"),

    // Connection Errors (3 types)
    CONNECTION_TIMEOUT("Connection", true, "Database connection timeout"),
    CONNECTION_LOST("Connection", true, "Connection to database lost"),
    CONNECTION_POOL_EXHAUSTED("Connection", true, "Connection pool exhausted"),

    // Resource Errors (3 types)
    MEMORY_LIMIT_EXCEEDED("Resource", false, "Query exceeded memory limit"),
    QUERY_TIMEOUT("Resource", true, "Query execution timeout"),
    RATE_LIMIT_EXCEEDED("Resource", true, "Rate limit exceeded"),

    // Schema Errors (3 types)
    TABLE_NOT_FOUND("Schema", false, "Table does not exist"),
    COLUMN_NOT_FOUND("Schema", false, "Column does not exist"),
    SCHEMA_MISMATCH("Schema", false, "Schema version mismatch"),

    // Syntax Errors (2 types)
    SYNTAX_ERROR("Syntax", true, "SQL syntax error"),
    INVALID_STATEMENT("Syntax", false, "Statement type not allowed"),

    // Unknown (catch-all)
    UNKNOWN("Unknown", false, "Unknown error type");

    private final String category;
    private final boolean retryable;
    private final String description;

    SqlErrorType(String category, boolean retryable, String description) {
        this.category = category;
        this.retryable = retryable;
        this.description = description;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * LLM-based error classification with keyword fallback.
     * This method requires AIService to be passed in.
     */
    public static SqlErrorType classifyWithLLM(String errorMessage, AIService aiService, String model) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return UNKNOWN;
        }

        try {
            String prompt = buildClassificationPrompt(errorMessage);
            String response = aiService.generate(prompt, model);
            SqlErrorType result = parseClassificationResponse(response);
            log.debug("LLM error classification: {} -> {}",
                errorMessage.substring(0, Math.min(100, errorMessage.length())), result);
            return result;
        } catch (Exception e) {
            log.warn("LLM error classification failed, using keyword fallback: {}", e.getMessage());
            return classifyWithKeywords(errorMessage);
        }
    }

    private static String buildClassificationPrompt(String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the following database error message into ONE of these error types:\n\n");

        sb.append("Error Types:\n");
        for (SqlErrorType type : SqlErrorType.values()) {
            if (type != UNKNOWN) {
                sb.append("- ").append(type.name()).append(": ").append(type.description).append("\n");
            }
        }
        sb.append("\n");

        sb.append("Error Message: \"").append(errorMessage).append("\"\n\n");

        sb.append("Respond with ONLY the error type name (e.g., TABLE_NOT_FOUND, CONNECTION_TIMEOUT).\n");
        sb.append("If the error doesn't match any type, respond with: UNKNOWN\n");

        return sb.toString();
    }

    private static SqlErrorType parseClassificationResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return UNKNOWN;
        }

        String cleaned = response.trim().toUpperCase()
            .replaceAll("[^A-Z_]", "");  // Remove non-alphanumeric except underscore

        try {
            return SqlErrorType.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            log.warn("LLM returned invalid error type: '{}', defaulting to UNKNOWN", response);
            return UNKNOWN;
        }
    }

    /**
     * Original keyword-based classification (used as fallback).
     */
    public static SqlErrorType classifyWithKeywords(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return UNKNOWN;
        }

        String lower = errorMessage.toLowerCase();

        // Connection errors
        if (lower.contains("timeout") && lower.contains("connection")) {
            return CONNECTION_TIMEOUT;
        }
        if (lower.contains("connection") && (lower.contains("lost") || lower.contains("closed"))) {
            return CONNECTION_LOST;
        }
        if (lower.contains("pool") && lower.contains("exhausted")) {
            return CONNECTION_POOL_EXHAUSTED;
        }

        // Resource errors
        if (lower.contains("memory") && lower.contains("limit")) {
            return MEMORY_LIMIT_EXCEEDED;
        }
        if (lower.contains("timeout") && !lower.contains("connection")) {
            return QUERY_TIMEOUT;
        }
        if (lower.contains("rate") && lower.contains("limit")) {
            return RATE_LIMIT_EXCEEDED;
        }

        // Schema errors
        if (lower.contains("table") && (lower.contains("not found") || lower.contains("does not exist"))) {
            return TABLE_NOT_FOUND;
        }
        if (lower.contains("column") && (lower.contains("not found") || lower.contains("does not exist"))) {
            return COLUMN_NOT_FOUND;
        }

        // Permission
        if (lower.contains("permission") || lower.contains("denied") || lower.contains("unauthorized")) {
            return PERMISSION_DENIED;
        }

        // Structural
        if (lower.contains("join") && lower.contains("missing")) {
            return MISSING_JOIN;
        }
        if (lower.contains("ambiguous") && lower.contains("column")) {
            return AMBIGUOUS_COLUMN;
        }

        // Aggregation
        if (lower.contains("group by") && lower.contains("missing")) {
            return MISSING_GROUP_BY;
        }
        if (lower.contains("having") && lower.contains("group")) {
            return HAVING_WITHOUT_GROUP;
        }

        // Data type
        if (lower.contains("type") && lower.contains("mismatch")) {
            return TYPE_MISMATCH;
        }
        if (lower.contains("cast") && (lower.contains("error") || lower.contains("failed"))) {
            return CASTING_ERROR;
        }
        if (lower.contains("date") && lower.contains("format")) {
            return DATE_FORMAT_ERROR;
        }

        // Performance
        if (lower.contains("cartesian")) {
            return CARTESIAN_JOIN;
        }

        // Syntax
        if (lower.contains("syntax")) {
            return SYNTAX_ERROR;
        }
        if (lower.contains("invalid") && lower.contains("statement")) {
            return INVALID_STATEMENT;
        }

        return UNKNOWN;
    }

    /**
     * Deprecated: Use classifyWithLLM instead.
     * This method uses keyword-based classification only.
     */
    @Deprecated
    public static SqlErrorType classify(String errorMessage) {
        return classifyWithKeywords(errorMessage);
    }
}
