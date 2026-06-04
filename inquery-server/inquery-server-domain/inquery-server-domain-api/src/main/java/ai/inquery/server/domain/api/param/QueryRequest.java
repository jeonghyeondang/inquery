package ai.inquery.server.domain.api.param;

import lombok.Data;
import java.util.List;

/**
 * Request parameter for natural language query processing.
 */
@Data
public class QueryRequest {
    /**
     * The natural language query message.
     */
    private String message;

    /**
     * The AI model to use.
     */
    private String model;

    /**
     * Data source ID.
     */
    private Long dataSourceId;

    /**
     * Database name.
     */
    private String databaseName;

    /**
     * Schema name.
     */
    private String schemaName;

    /**
     * Whether to execute the generated SQL query.
     * If false, only returns the generated SQL without executing it.
     * Default is false (SQL generation only).
     */
    private Boolean executeQuery = false;

    /**
     * Conversation history for context-aware responses.
     * Each entry contains: role (user/assistant), content, and optionally generatedSql.
     */
    private List<ConversationMessage> conversationHistory;

    /**
     * Whether to skip clarification check for ambiguous queries.
     * Used when user has already selected a clarification option.
     * Default is false (perform clarification check).
     */
    private Boolean skipClarification = false;

    /**
     * Explicit table names parsed from selected SQL.
     * When provided, skip semantic vector search and fetch these tables' schema directly.
     */
    private List<String> tableNames;

    /**
     * Tables excluded from vector search via Data Catalog toggles.
     * Format: table names (e.g., "FCT_AVATURN_GENERATED_DAILY_RETENTION").
     */
    private List<String> excludedTables;

    /**
     * Query type hint to skip classification.
     * When set to "sql", the LLM classification step is bypassed and the request
     * is treated directly as a SQL generation task.
     */
    private String queryType;

    /**
     * Represents a message in the conversation history.
     */
    @Data
    public static class ConversationMessage {
        private String role; // "user" or "assistant"
        private String content;
        private String generatedSql; // SQL generated for this message (if any)
    }
}
