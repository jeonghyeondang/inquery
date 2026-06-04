package ai.inquery.server.domain.core.langchain.tools;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Database-related tools for LangChain4j Agent.
 * These tools allow the AI agent to interact with databases.
 */
@Slf4j
public class DatabaseTools {

    private final DlTemplateService dlTemplateService;
    private final SchemaSearcher schemaSearcher;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    private final List<String> excludedTables; // List of tables to exclude from search

    public DatabaseTools(DlTemplateService dlTemplateService,
                         SchemaSearcher schemaSearcher,
                         Long dataSourceId,
                         String databaseName,
                         String schemaName) {
        this(dlTemplateService, schemaSearcher, dataSourceId, databaseName, schemaName, null);
    }

    public DatabaseTools(DlTemplateService dlTemplateService,
                         SchemaSearcher schemaSearcher,
                         Long dataSourceId,
                         String databaseName,
                         String schemaName,
                         List<String> excludedTables) {
        this.dlTemplateService = dlTemplateService;
        this.schemaSearcher = schemaSearcher;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.excludedTables = excludedTables;
    }

    // SQL commands that are allowed (read-only operations)
    private static final List<String> ALLOWED_SQL_PREFIXES = List.of("SELECT", "WITH", "SHOW", "DESCRIBE", "EXPLAIN");

    // SQL commands that are explicitly blocked (data modification / schema changes)
    private static final List<String> BLOCKED_SQL_KEYWORDS = List.of(
            "DROP", "DELETE", "TRUNCATE", "ALTER", "INSERT", "UPDATE",
            "CREATE", "GRANT", "REVOKE", "RENAME", "REPLACE", "MERGE",
            "CALL", "EXEC", "EXECUTE", "INTO"
    );

    /**
     * Execute a read-only SQL query and return results as JSON.
     * Only SELECT/WITH queries are allowed — all data modification commands are blocked for safety.
     */
    @Tool("Execute a read-only SQL query against the database and return results. Use this after generating SQL to get actual data. Only SELECT and WITH queries are allowed.")
    public String executeSql(
            @P("The SQL query to execute (must be a SELECT or WITH query)") String sql
    ) {
        log.info("Tool executeSql called with SQL: {}", sql);

        // Safety check: only allow read-only queries
        String rejectionReason = validateSqlSafety(sql);
        if (rejectionReason != null) {
            log.warn("SQL blocked by safety check: {} | SQL: {}", rejectionReason, sql);
            return "BLOCKED: " + rejectionReason + ". Only SELECT/WITH (read-only) queries are allowed.";
        }

        try {
            DlExecuteParam param = new DlExecuteParam();
            param.setSql(sql);
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(databaseName);
            param.setSchemaName(schemaName);
            param.setConsoleId(0L);

            ListResult<ExecuteResult> result = dlTemplateService.execute(param);

            if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                ExecuteResult executeResult = result.getData().get(0);
                // Convert to simplified JSON for LLM consumption
                return formatResultForLLM(executeResult);
            } else {
                return "Query executed but returned no results. Error: " + result.getErrorMessage();
            }
        } catch (Exception e) {
            log.error("Failed to execute SQL: {}", sql, e);
            return "Error executing SQL: " + e.getMessage();
        }
    }

    /**
     * Check if the SQL is safe to execute (read-only).
     * Returns null if safe, or a rejection reason string if blocked.
     */
    private String validateSqlSafety(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL is empty";
        }

        // Remove comments (-- and /* */) and normalize whitespace
        String cleaned = sql.replaceAll("--[^\n]*", " ")
                            .replaceAll("/\\*.*?\\*/", " ")
                            .trim();
        String upper = cleaned.toUpperCase();

        // Check that the query starts with an allowed prefix
        boolean startsWithAllowed = ALLOWED_SQL_PREFIXES.stream()
                .anyMatch(prefix -> upper.startsWith(prefix));
        if (!startsWithAllowed) {
            return "Query must start with SELECT or WITH";
        }

        // Check for blocked keywords that could appear in subqueries or CTEs
        // Split into tokens and check each one to avoid false positives in column/table names
        String[] tokens = upper.split("\\s+|\\(|\\)|;|,");
        for (String token : tokens) {
            if (BLOCKED_SQL_KEYWORDS.contains(token)) {
                return "Forbidden keyword detected: " + token;
            }
        }

        // Block multiple statements (SQL injection via semicolons)
        // Allow semicolons only at the very end
        String withoutTrailingSemicolon = cleaned.replaceAll(";\\s*$", "");
        if (withoutTrailingSemicolon.contains(";")) {
            return "Multiple SQL statements are not allowed";
        }

        return null; // Safe
    }

    /**
     * Search for relevant database schemas based on a query.
     */
    @Tool("Search for relevant database tables and columns based on a natural language query. Use this to find what tables/columns are available for a given topic.")
    public String searchSchema(
            @P("Natural language description of what data you're looking for") String query
    ) {
        log.info("Tool searchSchema called with query: {}", query);

        try {
            // Pass the excludedTables and connection info for keyword fallback when vector DB is empty
            List<String> schemas = schemaSearcher.searchSchema(query, excludedTables, dataSourceId, databaseName, schemaName);
            if (schemas == null || schemas.isEmpty()) {
                return "No relevant schemas found for: " + query;
            }

            return "Relevant schemas found:\n" + String.join("\n---\n", schemas);
        } catch (Exception e) {
            log.error("Failed to search schema", e);
            return "Error searching schema: " + e.getMessage();
        }
    }

    /**
     * Get the schema/structure of a specific table.
     */
    @Tool("Get the detailed schema (columns, data types) of a specific table. Use this when you need to understand a table's structure.")
    public String getTableSchema(
            @P("The fully qualified table name (e.g., database.schema.table or just table_name)") String tableName
    ) {
        log.info("Tool getTableSchema called for table: {}", tableName);

        try {
            // Use vector search to find the specific table, with keyword fallback
            List<String> schemas = schemaSearcher.searchSchema(tableName, null, dataSourceId, databaseName, schemaName);
            if (schemas == null || schemas.isEmpty()) {
                return "Table not found: " + tableName;
            }

            // Find the most relevant schema (exact match preferred)
            for (String schema : schemas) {
                if (schema.toLowerCase().contains(tableName.toLowerCase())) {
                    return "Table schema:\n" + schema;
                }
            }

            // Return first match if no exact match
            return "Closest matching table schema:\n" + schemas.get(0);
        } catch (Exception e) {
            log.error("Failed to get table schema", e);
            return "Error getting table schema: " + e.getMessage();
        }
    }

    /**
     * Validate SQL syntax without executing.
     */
    @Tool("Validate SQL syntax without executing it. Returns 'valid' if SQL is correct, or error message if there are syntax issues.")
    public String validateSql(
            @P("The SQL query to validate") String sql
    ) {
        log.info("Tool validateSql called");

        // Basic validation - check for required keywords
        String upperSql = sql.toUpperCase().trim();

        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH") &&
            !upperSql.startsWith("INSERT") && !upperSql.startsWith("UPDATE") &&
            !upperSql.startsWith("DELETE") && !upperSql.startsWith("CREATE")) {
            return "Invalid: SQL must start with SELECT, WITH, INSERT, UPDATE, DELETE, or CREATE";
        }

        if (upperSql.startsWith("SELECT") && !upperSql.contains("FROM")) {
            return "Invalid: SELECT query must contain FROM clause";
        }

        // Check for unclosed quotes
        int singleQuotes = sql.length() - sql.replace("'", "").length();
        if (singleQuotes % 2 != 0) {
            return "Invalid: Unclosed single quote detected";
        }

        int doubleQuotes = sql.length() - sql.replace("\"", "").length();
        if (doubleQuotes % 2 != 0) {
            return "Invalid: Unclosed double quote detected";
        }

        // Check for balanced parentheses
        int openParens = sql.length() - sql.replace("(", "").length();
        int closeParens = sql.length() - sql.replace(")", "").length();
        if (openParens != closeParens) {
            return "Invalid: Unbalanced parentheses";
        }

        return "Valid: SQL syntax appears correct";
    }

    private String formatResultForLLM(ExecuteResult result) {
        if (result == null || result.getDataList() == null) {
            return "No data returned";
        }

        List<List<String>> data = result.getDataList();
        List<ai.inquery.spi.model.Header> headers = result.getHeaderList();

        if (data.isEmpty()) {
            return "Query returned 0 rows";
        }

        // Build a simple JSON-like structure
        StringBuilder sb = new StringBuilder();
        sb.append("Query returned ").append(data.size()).append(" rows.\n");

        // Column names
        if (headers != null && !headers.isEmpty()) {
            sb.append("Columns: ");
            sb.append(headers.stream()
                    .map(ai.inquery.spi.model.Header::getName)
                    .collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }

        // Limit to first 20 rows for LLM context
        int rowLimit = Math.min(data.size(), 20);
        sb.append("Data (first ").append(rowLimit).append(" rows):\n");

        for (int i = 0; i < rowLimit; i++) {
            List<String> row = data.get(i);
            sb.append("Row ").append(i + 1).append(": ");
            if (headers != null && headers.size() == row.size()) {
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(headers.get(j).getName()).append("=").append(row.get(j));
                }
            } else {
                sb.append(String.join(", ", row));
            }
            sb.append("\n");
        }

        if (data.size() > rowLimit) {
            sb.append("... and ").append(data.size() - rowLimit).append(" more rows");
        }

        return sb.toString();
    }
}
