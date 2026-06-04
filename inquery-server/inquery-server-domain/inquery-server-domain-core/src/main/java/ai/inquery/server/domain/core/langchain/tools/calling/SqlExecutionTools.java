package ai.inquery.server.domain.core.langchain.tools.calling;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SQL execution tools for {@link ai.inquery.server.domain.core.langchain.agents.SqlExecutionAgent}.
 *
 * <p>Wraps {@code DlTemplateService} / {@code SchemaSearcher} with two extras
 * needed by the Auto-mode flow:
 * <ul>
 *   <li>Captures the most recent {@code executeSql} attempt (sql + result + error)
 *       so the controller can recover the final {@code ExecuteResult} once the
 *       LLM stops calling tools.</li>
 *   <li>Calls a {@code progress} consumer on each execute_sql attempt and a
 *       {@code sqlFix} consumer when the SQL changes between attempts, so the
 *       controller can re-emit the legacy {@code thinking} / {@code sql_fix}
 *       SSE events.</li>
 * </ul>
 *
 * <p>One instance per agent call (per HTTP request). Not thread-safe.
 */
@Slf4j
public class SqlExecutionTools {

    private static final List<String> ALLOWED_SQL_PREFIXES =
            List.of("SELECT", "WITH", "SHOW", "DESCRIBE", "EXPLAIN");
    private static final List<String> BLOCKED_SQL_KEYWORDS = List.of(
            "DROP", "DELETE", "TRUNCATE", "ALTER", "INSERT", "UPDATE",
            "CREATE", "GRANT", "REVOKE", "RENAME", "REPLACE", "MERGE",
            "CALL", "EXEC", "EXECUTE", "INTO"
    );

    private final DlTemplateService dlTemplateService;
    private final SchemaSearcher schemaSearcher;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;

    private final Consumer<String> progressCallback;
    private final SqlFixCallback sqlFixCallback;

    private int attempts = 0;
    private String previousSql;
    private LastRun lastRun;
    private LastRun lastSuccessfulRun;

    public SqlExecutionTools(DlTemplateService dlTemplateService,
                             SchemaSearcher schemaSearcher,
                             Long dataSourceId,
                             String databaseName,
                             String schemaName,
                             Consumer<String> progressCallback,
                             SqlFixCallback sqlFixCallback) {
        this.dlTemplateService = dlTemplateService;
        this.schemaSearcher = schemaSearcher;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.progressCallback = progressCallback;
        this.sqlFixCallback = sqlFixCallback;
    }

    @Tool("Execute a read-only SELECT/WITH SQL on the user's database. Returns row summary, or an 'Error' / 'BLOCKED' string you can analyze and retry.")
    public String executeSql(
            @P("A SELECT or WITH SQL query") String sql
    ) {
        attempts++;
        log.info("[SqlExecutionTools] attempt {} executeSql: {}", attempts,
                sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

        if (attempts == 1) {
            notifyProgress("Executing query...");
        } else {
            notifyProgress("Retrying with fixed SQL (attempt " + attempts + ")...");
            if (sqlFixCallback != null && previousSql != null && !previousSql.equals(sql)) {
                String prevError = lastRun != null ? lastRun.errorMessage() : null;
                sqlFixCallback.onSqlFix(sql, prevError, attempts);
            }
        }
        previousSql = sql;

        String rejectionReason = validateSqlSafety(sql);
        if (rejectionReason != null) {
            log.warn("SQL blocked: {} | SQL: {}", rejectionReason, sql);
            String msg = "BLOCKED: " + rejectionReason + ". Only SELECT/WITH (read-only) queries are allowed.";
            lastRun = new LastRun(sql, null, msg);
            return msg;
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
                LastRun run = new LastRun(sql, result, null);
                lastRun = run;
                lastSuccessfulRun = run;
                return formatResultForLLM(result.getData().get(0));
            }
            String err = result.getErrorMessage() != null ? result.getErrorMessage() : "(no error message)";
            lastRun = new LastRun(sql, result, err);
            return "Error: " + err;
        } catch (Exception e) {
            log.error("Failed to execute SQL: {}", sql, e);
            lastRun = new LastRun(sql, null, e.getMessage());
            return "Error executing SQL: " + e.getMessage();
        }
    }

    @Tool("Look up relevant database tables/columns by natural-language topic. Use only after execute_sql failed with a missing-table or missing-column error.")
    public String searchSchema(
            @P("Topic to look up in the schema (e.g. 'orders', 'user retention')") String query
    ) {
        log.info("[SqlExecutionTools] searchSchema: {}", query);
        try {
            List<String> schemas = schemaSearcher.searchSchema(query, null, dataSourceId, databaseName, schemaName);
            if (schemas == null || schemas.isEmpty()) {
                return "No relevant schemas found for: " + query;
            }
            return "Relevant schemas found:\n" + String.join("\n---\n", schemas);
        } catch (Exception e) {
            log.error("Failed to search schema", e);
            return "Error searching schema: " + e.getMessage();
        }
    }

    public int getAttempts() {
        return attempts;
    }

    public LastRun getLastRun() {
        return lastRun;
    }

    public LastRun getLastSuccessfulRun() {
        return lastSuccessfulRun;
    }

    private void notifyProgress(String message) {
        if (progressCallback != null) {
            try {
                progressCallback.accept(message);
            } catch (Exception e) {
                log.warn("Progress callback failed: {}", e.getMessage());
            }
        }
    }

    private String validateSqlSafety(String sql) {
        if (sql == null || sql.isBlank()) return "SQL is empty";
        String cleaned = sql.replaceAll("--[^\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ").trim();
        String upper = cleaned.toUpperCase();
        boolean allowed = ALLOWED_SQL_PREFIXES.stream().anyMatch(upper::startsWith);
        if (!allowed) return "Query must start with SELECT or WITH";
        String[] tokens = upper.split("\\s+|\\(|\\)|;|,");
        for (String t : tokens) {
            if (BLOCKED_SQL_KEYWORDS.contains(t)) return "Forbidden keyword detected: " + t;
        }
        String withoutTrailingSemi = cleaned.replaceAll(";\\s*$", "");
        if (withoutTrailingSemi.contains(";")) return "Multiple SQL statements are not allowed";
        return null;
    }

    private String formatResultForLLM(ExecuteResult result) {
        if (result == null || result.getDataList() == null) return "No data returned";
        List<List<String>> data = result.getDataList();
        List<ai.inquery.spi.model.Header> headers = result.getHeaderList();
        if (data.isEmpty()) return "Query returned 0 rows";

        StringBuilder sb = new StringBuilder();
        sb.append("Query returned ").append(data.size()).append(" rows.\n");
        if (headers != null && !headers.isEmpty()) {
            sb.append("Columns: ").append(headers.stream()
                    .map(ai.inquery.spi.model.Header::getName)
                    .collect(Collectors.joining(", "))).append("\n\n");
        }
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

    /** Snapshot of a single execute_sql attempt. */
    public record LastRun(String finalSql,
                          ListResult<ExecuteResult> result,
                          String errorMessage) {}

    /** Notified when the LLM produces a different SQL than the previous attempt. */
    @FunctionalInterface
    public interface SqlFixCallback {
        void onSqlFix(String fixedSql, String previousError, int attempt);
    }
}
