package ai.inquery.server.web.api.controller.ai.request;

import java.util.List;

import ai.inquery.server.web.api.controller.ai.enums.PromptType;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * Chat query input parameters
 *
 * @version ChatQueryRequest.java, v 0.1 April 2, 2023 13:28 moji Exp $
 */
@Data
public class ChatQueryRequest extends DataSourceBaseRequest {

    /**
     * Enter message
     */
    private String message;

    /**
     * SQL function type
     * @see PromptType
     */
    private String promptType;

    /**
     * table name list
     */
    private List<String> tableNames;

    /**
     * Target SQL data type
     * @see ai.inquery.server.domain.support.enums.DbTypeEnum
     */
    private String destSqlType;

    /**
     * More remarks: such as requirements or restrictions, etc.
     */
    private String ext;

    /**
     * AI model to use for this request (overrides global setting)
     * Examples: gpt-5.4-mini, claude-sonnet-4-6, gemini-3.5-flash
     */
    private String model;

    /**
     * Conversation ID for maintaining chat context
     * Used to track conversation history for follow-up questions
     */
    private String conversationId;

    /**
     * Whether to automatically execute the generated SQL.
     * If true, SQL is executed immediately after generation.
     * If false (default), user must click "Run Query" to execute.
     */
    private Boolean executeQuery = false;

    /**
     * List of tables to exclude from AI context (Vector DB search).
     * Format: "schema.table" or just "table" depending on DB type.
     * These tables are explicitly disabled via data catalog toggles.
     */
    private String excludedTables; // JSON string from frontend

    /**
     * Agent mode for processing queries.
     * - "basic": Single agent with tool calling (faster, simpler queries)
     * - "deep": Multi-agent supervisor pattern (complex queries, automatic retry)
     * Default is "basic".
     */
    private String agentMode = "basic";

    /**
     * Skip clarification step.
     * Set to true when user has already selected a clarification option.
     * Default is false.
     */
    private Boolean skipClarification = false;

    /**
     * Query type hint to skip classification.
     * Set to "sql" to bypass the LLM classifier and go directly to SQL generation.
     * Used by workspace console (generate/drag+AI buttons) which are always SQL operations.
     */
    private String queryType;

    /**
     * Conversation history sent by the frontend for context-aware responses.
     * Replaces unreliable server-side LocalCache approach (5-min TTL, lost on restart).
     * Frontend holds the full message list per chat room and sends the last N messages.
     */
    private List<ChatConversationMessage> conversationHistory;

    /**
     * Optional chat-attachment ids (image / pdf / text-family) to ride
     * along with this turn. Uploaded separately via
     * {@code POST /api/ai/attachments} and referenced here by id.
     * Capped at {@code AiChatAttachmentService.MAX_ATTACHMENTS_PER_MESSAGE}.
     */
    private List<Long> attachmentIds;

    @Data
    public static class ChatConversationMessage {
        private String role;
        private String content;
        private String generatedSql;
    }
}
