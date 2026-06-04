package ai.inquery.server.domain.core.query;

import ai.inquery.server.domain.api.param.QueryRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates SQL from natural language queries using LLMs.
 */
@Component
@Slf4j
public class SqlGenerator {

    /**
     * Single query item with title, sql, and explanation.
     */
    @Data
    public static class QueryItem {
        private String title; // Query title with emoji (displayed as ## header)
        private String sql; // The SQL query
        private String explanation; // Detailed explanation of query components
    }

    @Data
    public static class SqlGenerationResult {
        private String prompt;
        private String rawResponse;
        private String cleanedSql; // First query's SQL (for backward compatibility)
        private String aiMessage; // AI-generated explanation in user's language (deprecated)
        private String title; // First query's title (for backward compatibility)
        private String explanation; // First query's explanation (for backward compatibility)
        private List<QueryItem> queries; // Multiple query options (1-3)
    }

    /**
     * Builds a SQL-only prompt for workspace generate mode.
     * Returns only a SQL code block with no explanation, title, or overview.
     */
    public String buildSqlOnlyPrompt(String query, List<String> schemaContext,
            List<QueryRequest.ConversationMessage> conversationHistory, String businessContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior data engineer. Generate a single SQL query to answer the user's question.\n\n");

        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("Output ONLY a single SQL code block. No explanation, no title, no overview.\n");
        sb.append("Example:\n");
        sb.append("```sql\n");
        sb.append("SELECT column1, column2\n");
        sb.append("FROM database.schema.table\n");
        sb.append("WHERE condition;\n");
        sb.append("```\n\n");

        sb.append("=== SQL RULES ===\n");
        sb.append("- ONLY use table names and column names that EXACTLY appear in the AVAILABLE TABLES section below. NEVER invent, guess, or infer column names from descriptions.\n");
        sb.append("- ALWAYS end SQL with semicolon (;)\n");
        sb.append("- Use FULLY QUALIFIED table names in the form database.schema.table (no curly braces, no placeholder syntax).\n");
        sb.append("- STRING COMPARISON: LOWER(column_name) = 'lowercase_value'\n");
        sb.append("- FCT_* tables: ALWAYS use SUM() and GROUP BY for metrics\n\n");

        if (businessContext != null && !businessContext.isEmpty()) {
            sb.append("=== BUSINESS CONTEXT ===\n");
            sb.append(businessContext).append("\n");
            sb.append("=== END BUSINESS CONTEXT ===\n\n");
        }

        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (QueryRequest.ConversationMessage msg : conversationHistory) {
                if ("user".equals(msg.getRole())) {
                    sb.append("User: ").append(msg.getContent()).append("\n");
                } else if ("assistant".equals(msg.getRole())) {
                    if (msg.getGeneratedSql() != null) {
                        sb.append("Previous SQL: ").append(msg.getGeneratedSql()).append("\n");
                    }
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        sb.append("Assistant: ").append(msg.getContent()).append("\n");
                    }
                }
            }
            sb.append("=== END HISTORY ===\n\n");
        }

        if (!schemaContext.isEmpty()) {
            sb.append("=== AVAILABLE TABLES ===\n");
            for (String schema : schemaContext) {
                sb.append(schema).append("\n\n");
            }
        }

        sb.append("User Query: ").append(query).append("\n\n");
        sb.append("SQL (code block only):");
        return sb.toString();
    }

    /**
     * Builds a streaming-friendly prompt with explicit delimiters for easy real-time parsing.
     * Uses [TAG] format for unambiguous section boundaries during streaming.
     */
    public String buildStreamingPrompt(String query, List<String> schemaContext,
            List<QueryRequest.ConversationMessage> conversationHistory, String businessContext) {
        return buildStreamingPrompt(query, schemaContext, conversationHistory, businessContext, null);
    }

    public String buildStreamingPrompt(String query, List<String> schemaContext,
            List<QueryRequest.ConversationMessage> conversationHistory, String businessContext,
            String conversationContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior data engineer at a Fortune 500 company with 10+ years of experience.\n\n");

        sb.append("=== OUTPUT FORMAT (MARKDOWN) ===\n");
        sb.append("Output in standard markdown format. This will be rendered directly in the UI.\n\n");

        sb.append("FORMAT TEMPLATE (produce EXACTLY this structure — one query only):\n\n");
        sb.append("Brief overview explaining what the single query below will answer. If you mention a table name in prose, wrap the fully qualified name in single backticks, e.g. `database.schema.table` — NEVER wrap it in curly braces like {database.schema.table}. Keep it concise.\n\n");
        sb.append("---\n\n");
        sb.append("## 📊 Title\n\n");
        sb.append("```sql\n");
        sb.append("SELECT column1, column2\n");
        sb.append("FROM schema.table\n");
        sb.append("WHERE condition;\n");
        sb.append("```\n\n");
        sb.append("**Explanation:**\n");
        sb.append("- `column_name`: description\n");
        sb.append("- `FUNCTION()`: what it does\n\n");

        sb.append("=== CRITICAL RULES ===\n");
        sb.append("1. Use standard markdown: ## for headers, ```sql for code blocks, > for suggestions\n");
        sb.append("2. Start with a brief overview (no header), then use --- separator before first query\n");
        sb.append("3. Write EVERYTHING in the SAME LANGUAGE as the user's question. If the question mixes languages, use the main natural language of the question, not short SQL/date phrases such as 'for all available dates'.\n");
        sb.append("4. SCRIPT RESTRICTION: the entire reply must use only the script(s) the user wrote in (Korean → Hangul + ASCII; Japanese → Kana + Kanji + ASCII; etc.). Do NOT include Devanagari, Arabic, Thai, Cyrillic, Hebrew, or any other script the user did not use.\n");
        sb.append("5. Use DIFFERENT emojis for each ## header: 📊 📈 📉 💰 👥 📅 🔍 📋 🎯\n");
        sb.append("6. Use backticks for column names, functions, and table identifiers in prose: `column_name`, `database.schema.table`. NEVER wrap identifiers in curly braces (e.g. `{table}` is WRONG).\n\n");

        sb.append("=== SINGLE-QUERY POLICY ===\n");
        sb.append("- Output EXACTLY ONE SQL query that best answers the user's current question.\n");
        sb.append("- Do NOT propose multiple alternative perspectives in this response. Alternative analyses are surfaced separately as clickable follow-up suggestions after the result is rendered — those will start a fresh chat turn with their own SQL.\n");
        sb.append("- Pick the single most useful query. If two perspectives are equally valuable, pick the one most directly answering the user's wording and leave the rest for the follow-up suggestion list.\n");
        sb.append("- Never emit '## 📈 Title 2', a second ```sql block, or a 'You may also want to ...' query block. The response must contain exactly one ## header and one fenced sql block.\n\n");

        sb.append("=== SQL RULES ===\n");
        sb.append("- CRITICAL: ONLY use table names and column names that EXACTLY appear in the AVAILABLE TABLES section below. NEVER invent, guess, or infer column names from descriptions.\n");
        sb.append("- ALWAYS end SQL with semicolon (;)\n");
        sb.append("- Use FULLY QUALIFIED table names in FROM/JOIN in the form database.schema.table (no curly braces, no placeholder syntax).\n");
        sb.append("- STRING COMPARISON: LOWER(column_name) = 'lowercase_value'\n");
        sb.append("- FCT_* tables: ALWAYS use SUM() and GROUP BY for metrics\n\n");

        // Add business context if available
        if (businessContext != null && !businessContext.isEmpty()) {
            sb.append("=== BUSINESS CONTEXT ===\n");
            sb.append(businessContext).append("\n");
            sb.append("=== END BUSINESS CONTEXT ===\n\n");
        }

        if (conversationContext != null && !conversationContext.isEmpty()) {
            sb.append("=== RELEVANT PRIOR DATA CONTEXT ===\n");
            sb.append("Use this only to resolve references in the current question, such as previous table names, SQL, filters, grain, date range, or stated data limitations.\n");
            sb.append(conversationContext).append("\n");
            sb.append("=== END PRIOR DATA CONTEXT ===\n\n");
        }

        // Add conversation history
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (QueryRequest.ConversationMessage msg : conversationHistory) {
                if ("user".equals(msg.getRole())) {
                    sb.append("User: ").append(msg.getContent()).append("\n");
                } else if ("assistant".equals(msg.getRole())) {
                    if (msg.getGeneratedSql() != null) {
                        sb.append("Previous SQL: ").append(msg.getGeneratedSql()).append("\n");
                    }
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        sb.append("Assistant: ").append(msg.getContent()).append("\n");
                    }
                }
            }
            sb.append("=== END HISTORY ===\n\n");
        }

        if (!schemaContext.isEmpty()) {
            sb.append("=== AVAILABLE TABLES ===\n");
            for (String schema : schemaContext) {
                sb.append(schema).append("\n\n");
            }
        }

        sb.append("User Query: ").append(query).append("\n\n");
        sb.append("Response (markdown format, start with overview):");
        return sb.toString();
    }
}
