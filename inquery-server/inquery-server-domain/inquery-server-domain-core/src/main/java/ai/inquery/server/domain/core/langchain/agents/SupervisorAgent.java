package ai.inquery.server.domain.core.langchain.agents;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.AIService;
import ai.inquery.server.domain.api.service.AiFeedbackService;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Supervisor Agent (Deep Agent) - orchestrates multiple specialized agents
 * to handle complex data analysis tasks.
 *
 * Workflow:
 * 1. Find relevant schema via SchemaSearcher (vector DB search)
 * 2. Check if clarification is needed (ClarificationAgent)
 * 3. Generate SQL (SqlWriterAgent)
 * 4. Execute SQL with retry (SqlWriterAgent.fixSql on error)
 * 5. Analyze results (ResultAnalyzerAgent)
 * 6. Recommend chart visualization (ResultAnalyzerAgent)
 */
@Slf4j
public class SupervisorAgent {

    private final LangChainModelProvider modelProvider;
    private final DlTemplateService dlTemplateService;
    private final SchemaSearcher schemaSearcher;
    private final String modelName;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    private List<String> excludedTables;

    // Optional: Direct AI service (bypasses LangChain4j, uses GeminiAIClient — same path as AI-chat)
    private AIService aiService;

    // Optional: MCP ToolProvider for external service tools (Slack, Wiki, Jira, etc.)
    private dev.langchain4j.service.tool.ToolProvider mcpToolProvider;

    // Optional: Python execution tools for data analysis
    private ai.inquery.server.domain.core.langchain.tools.PythonTools pythonTools;

    // Optional: AI Feedback service for Few-shot learning
    private AiFeedbackService aiFeedbackService;

    // Optional: translated query for Vector DB search (improves search accuracy for non-English queries)
    private String searchQuery;

    // Optional: explicit prompt context injection
    private String conversationHistoryContext;
    private String businessContext;

    // Specialized agents (lazy initialized)
    private SqlWriterAgent sqlWriter;
    private ClarificationAgent clarificationAgent;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_FEWSHOT_EXAMPLES = 3;

    public SupervisorAgent(
            LangChainModelProvider modelProvider,
            DlTemplateService dlTemplateService,
            SchemaSearcher schemaSearcher,
            String modelName,
            Long dataSourceId,
            String databaseName,
            String schemaName
    ) {
        this.modelProvider = modelProvider;
        this.dlTemplateService = dlTemplateService;
        this.schemaSearcher = schemaSearcher;
        this.modelName = modelName;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.excludedTables = null;
        this.aiFeedbackService = null;
        this.conversationHistoryContext = null;
        this.businessContext = null;
    }

    /**
     * Set AI Feedback Service for Few-shot learning.
     * When set, successful query patterns will be used as examples for SQL generation.
     */
    public void setAiService(AIService aiService) {
        this.aiService = aiService;
    }

    public void setMcpToolProvider(dev.langchain4j.service.tool.ToolProvider mcpToolProvider) {
        this.mcpToolProvider = mcpToolProvider;
    }

    public void setPythonTools(ai.inquery.server.domain.core.langchain.tools.PythonTools pythonTools) {
        this.pythonTools = pythonTools;
    }

    public void setAiFeedbackService(AiFeedbackService aiFeedbackService) {
        this.aiFeedbackService = aiFeedbackService;
    }

    public void setConversationHistoryContext(String conversationHistoryContext) {
        this.conversationHistoryContext = conversationHistoryContext;
    }

    public void setBusinessContext(String businessContext) {
        this.businessContext = businessContext;
    }

    /**
     * Apply Data Catalog toggles (excluded tables) to schema search.
     */
    public void setExcludedTables(List<String> excludedTables) {
        this.excludedTables = excludedTables;
    }

    /**
     * Set translated query for Vector DB search.
     * If set, this query (typically English translation + keywords) will be used for schema search
     * instead of the original user query. This improves search accuracy for non-English queries.
     */
    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
    }

    /**
     * Process a user query with full agent orchestration.
     * If skipClarification is false, checks if query needs clarification first.
     */
    public SupervisorResult process(String userQuery, Consumer<String> progressCallback) {
        return process(userQuery, progressCallback, false);
    }

    /**
     * Process a user query with full agent orchestration.
     *
     * @param userQuery User's question
     * @param progressCallback Callback for progress updates
     * @param skipClarification If true, skip clarification check (used when user already selected an option)
     */
    public SupervisorResult process(String userQuery, Consumer<String> progressCallback, boolean skipClarification) {
        log.info("Supervisor processing query: {}, skipClarification: {}", userQuery, skipClarification);
        SupervisorResult.SupervisorResultBuilder resultBuilder = SupervisorResult.builder()
                .originalQuery(userQuery);

        try {
            // Step -1: If MCP tools available and query is about external services, route to tool agent
            if (isExternalServiceQuery(userQuery)) {
                reportProgress(progressCallback, "Connecting to external services...");
                String toolResult = processWithExternalTools(userQuery);
                if (toolResult != null && !toolResult.isBlank()) {
                    log.info("Query handled by external tool agent");
                    return resultBuilder
                            .success(true)
                            .analysis(toolResult)
                            .build();
                }
                log.info("External tool agent returned no result, falling through to SQL pipeline");
            }

            // Step 0: Find relevant schema first (needed for clarification)
            reportProgress(progressCallback, "Searching for relevant tables...");
            String schemaContext = findRelevantSchema(userQuery);
            String combinedSchemaContext = buildCombinedSchemaContext(schemaContext);
            resultBuilder.schemaContext(combinedSchemaContext);

            if (combinedSchemaContext == null || combinedSchemaContext.isEmpty()) {
                // No matching tables — treat as small-talk / general question
                // (the old QueryClassifierTranslator used to short-circuit these).
                // Fall back to a single LLM chat reply so users get a usable
                // response instead of the bare "no tables found" error.
                String chatReply = answerWithoutSchema(userQuery);
                return resultBuilder
                        .success(true)
                        .analysis(chatReply)
                        .build();
            }

            // Send found tables info via callback (special prefix for parsing)
            String foundTables = extractTableNames(schemaContext);
            if (foundTables != null && !foundTables.isEmpty()) {
                reportProgress(progressCallback, "TABLES:" + foundTables);
            }

            // Step 1: Check if clarification is needed (unless skipped)
            if (!skipClarification) {
                reportProgress(progressCallback, "Analyzing query intent...");
                ClarificationResult clarificationResult = checkClarification(userQuery, combinedSchemaContext);

                if (clarificationResult.needsClarification) {
                    log.info("Query needs clarification, returning options");
                    return resultBuilder
                            .success(true)
                            .needsClarification(true)
                            .clarificationOptions(clarificationResult.options)
                            .analysis(clarificationResult.reason)
                            .build();
                }
            }

            // Step 2: Generate SQL
            reportProgress(progressCallback, "Generating SQL query...");
            String sql = generateSql(userQuery, combinedSchemaContext);
            resultBuilder.generatedSql(sql);

            if (sql == null || sql.isEmpty()) {
                return resultBuilder
                        .success(false)
                        .errorMessage("Failed to generate SQL query")
                        .build();
            }

            // Send generated SQL via callback (special prefix for parsing)
            reportProgress(progressCallback, "SQL:" + sql);

            // Step 3: Execute SQL with retry
            reportProgress(progressCallback, "Executing query...");
            ExecutionResult execResult = executeSqlWithRetry(sql, combinedSchemaContext, userQuery);
            resultBuilder.executedSql(execResult.finalSql);

            if (!execResult.success) {
                return resultBuilder
                        .success(false)
                        .errorMessage("Query execution failed: " + execResult.errorMessage)
                        .build();
            }

            resultBuilder.resultData(execResult.resultData);
            resultBuilder.rowCount(execResult.rowCount);

            // Step 3.5: If many rows and Python available, run Python analysis
            if (pythonTools != null && execResult.rowCount >= 100) {
                reportProgress(progressCallback, "Running Python data analysis...");
                String pythonAnalysis = runPythonAnalysis(userQuery, execResult);
                if (pythonAnalysis != null && !pythonAnalysis.isBlank()) {
                    resultBuilder.pythonAnalysis(pythonAnalysis);
                    log.info("Python analysis completed for {} rows", execResult.rowCount);
                }
            }

            // Step 4 & 5: Analyze results + Recommend chart (parallel)
            reportProgress(progressCallback, "Analyzing results...");
            long parallelStart = System.currentTimeMillis();

            CompletableFuture<String> analysisFuture = CompletableFuture.supplyAsync(() ->
                    analyzeResults(userQuery, execResult.finalSql, execResult.formattedResults));

            CompletableFuture<String> chartFuture = CompletableFuture.supplyAsync(() ->
                    recommendChart(userQuery, execResult.formattedResults));

            String analysis = analysisFuture.join();
            String chartRecommendation = chartFuture.join();

            log.info("[Parallel] Analysis + Chart completed in {}ms", System.currentTimeMillis() - parallelStart);

            resultBuilder.analysis(analysis);
            resultBuilder.chartRecommendation(chartRecommendation);

            return resultBuilder.success(true).build();

        } catch (Exception e) {
            log.error("Supervisor processing failed", e);
            return resultBuilder
                    .success(false)
                    .errorMessage("Processing failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Fallback used when {@link #findRelevantSchema} returns nothing. The query
     * is likely small-talk, general knowledge, or a non-data question; respond
     * directly with a single LLM call instead of failing with "no tables found".
     */
    private String answerWithoutSchema(String userQuery) {
        try {
            String historyBlock = (conversationHistoryContext != null && !conversationHistoryContext.isBlank())
                    ? "\n\n[CONVERSATION HISTORY]\n" + conversationHistoryContext
                    : "";
            String businessBlock = (businessContext != null && !businessContext.isBlank())
                    ? "\n\n[ADDITIONAL CONTEXT]\n" + businessContext
                    : "";
            String prompt = """
                    You are Inquery, an AI data assistant. The user's question
                    did not match any tables in their database, so answer in
                    plain language using the same language the user wrote in.

                    - If the question is a greeting, small talk, or general
                      knowledge, give a short helpful answer.
                    - If the question clearly needs the user's data but no
                      tables matched, briefly say so and ask the user to be
                      more specific or check that the relevant data source is
                      connected.
                    - Never make up data or SQL.

                    Question:
                    %s%s%s
                    """.formatted(userQuery, historyBlock, businessBlock);

            if (aiService != null) {
                String reply = aiService.generateWithStreaming(prompt, modelName, t -> {});
                if (reply != null && !reply.isBlank()) {
                    return reply.trim();
                }
            }
            return "I couldn't find any tables matching your question. "
                    + "Could you rephrase it or specify the data source you'd like to query?";
        } catch (Exception e) {
            log.warn("answerWithoutSchema fallback failed: {}", e.getMessage());
            return "I couldn't process your question right now. Please try again.";
        }
    }

    private String findRelevantSchema(String query) {
        try {
            // Use searchQuery (English translation + keywords) for better Vector DB search accuracy
            // Fall back to original query if searchQuery not set
            String queryForSearch = (searchQuery != null && !searchQuery.isEmpty()) ? searchQuery : query;
            log.debug("[SupervisorAgent] Using query for schema search: {}", queryForSearch);
            
            List<String> schemas = (excludedTables != null && !excludedTables.isEmpty())
                ? schemaSearcher.searchSchema(queryForSearch, excludedTables, dataSourceId, databaseName, schemaName)
                : schemaSearcher.searchSchema(queryForSearch, null, dataSourceId, databaseName, schemaName);
            if (schemas == null || schemas.isEmpty()) {
                return null;
            }
            return String.join("\n---\n", schemas);
        } catch (Exception e) {
            log.error("Schema search failed", e);
            return null;
        }
    }

    /**
     * Build combined context with caching-friendly order.
     * Order: Business Context (static) -> Schema Context (dynamic) -> History (dynamic)
     * 
     * This order optimizes for LLM prompt caching:
     * - Business Context is per-datasource static, so placing it first enables cache hits
     * - OpenAI/Claude cache based on prefix matching, so static content should come first
     */
    private String buildCombinedSchemaContext(String schemaContext) {
        if (schemaContext == null || schemaContext.isEmpty()) {
            return schemaContext;
        }

        boolean hasBusiness = businessContext != null && !businessContext.isEmpty();
        boolean hasHistory = conversationHistoryContext != null && !conversationHistoryContext.isEmpty();
        if (!hasBusiness && !hasHistory) {
            return schemaContext;
        }

        StringBuilder sb = new StringBuilder();
        
        // 1. Business Context first (STATIC - per datasource, cache-friendly)
        if (hasBusiness) {
            sb.append("=== BUSINESS CONTEXT (STATIC) ===\n");
            sb.append(businessContext).append("\n");
            sb.append("=== END BUSINESS CONTEXT ===\n\n");
        }
        
        // 2. Schema Context (DYNAMIC - varies per query from Vector DB)
        sb.append("=== SCHEMA CONTEXT (DYNAMIC) ===\n");
        sb.append(schemaContext).append("\n");
        sb.append("=== END SCHEMA CONTEXT ===\n");
        
        // 3. Conversation History last (DYNAMIC - varies per message)
        if (hasHistory) {
            sb.append("\n").append(conversationHistoryContext);
        }
        
        return sb.toString();
    }

    /**
     * Extract table names from schema context.
     * Looks for patterns like "Table: TABLE_NAME" or "[Table Path: DB.SCHEMA.TABLE]"
     */
    private String extractTableNames(String schemaContext) {
        if (schemaContext == null || schemaContext.isEmpty()) {
            return null;
        }

        java.util.Set<String> tableNames = new java.util.LinkedHashSet<>();
        
        // Pattern 1: [Table Path: DB.SCHEMA.TABLE]
        java.util.regex.Pattern pathPattern = java.util.regex.Pattern.compile("\\[Table Path:\\s*([^\\]]+)\\]");
        java.util.regex.Matcher pathMatcher = pathPattern.matcher(schemaContext);
        while (pathMatcher.find()) {
            String fullPath = pathMatcher.group(1).trim();
            // Extract just the table name (last part)
            String[] parts = fullPath.split("\\.");
            if (parts.length > 0) {
                tableNames.add(parts[parts.length - 1]);
            }
        }
        
        // Pattern 2: Table: TABLE_NAME
        java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile("Table:\\s*(\\S+)");
        java.util.regex.Matcher tableMatcher = tablePattern.matcher(schemaContext);
        while (tableMatcher.find()) {
            tableNames.add(tableMatcher.group(1).trim());
        }

        if (tableNames.isEmpty()) {
            return null;
        }
        
        return String.join(", ", tableNames);
    }

    private String generateSql(String query, String schemaContext) {
        try {
            long sqlStart = System.currentTimeMillis();
            String rawResponse;
            
            String fewShotExamples = getFewShotExamples();

            if (aiService != null) {
                // Direct path: AIService → GeminiAIClient (same as AI-chat)
                String prompt = buildDirectSqlPrompt(query, schemaContext, fewShotExamples);
                log.info("[SQL Generation] Using direct AIService streaming path, prompt length: {}", prompt.length());
                rawResponse = aiService.generateWithStreaming(prompt, modelName, t -> {});
            } else {
                // Fallback: LangChain4j AiServices path
                SqlWriterAgent writer = getSqlWriterAgent();
                if (fewShotExamples != null && !fewShotExamples.isEmpty()) {
                    log.info("[SQL Generation] Using LangChain4j with Few-shot learning");
                    rawResponse = writer.writeSqlWithExamples(query, schemaContext, fewShotExamples);
                } else {
                    log.info("[SQL Generation] Using LangChain4j standard generation");
                    rawResponse = writer.writeSql(query, schemaContext);
                }
            }
            
            long sqlElapsed = System.currentTimeMillis() - sqlStart;
            log.info("[SQL Generation] Completed in {}ms, response length: {}, first 200 chars: {}", 
                    sqlElapsed,
                    rawResponse != null ? rawResponse.length() : 0,
                    rawResponse != null ? rawResponse.substring(0, Math.min(200, rawResponse.length())) : "null");
            
            String sql = stripCodeBlock(rawResponse);
            
            if (sql != null && !sql.trim().isEmpty()) {
                String upperSql = sql.trim().toUpperCase();
                if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH") && 
                    !upperSql.startsWith("INSERT") && !upperSql.startsWith("UPDATE") && 
                    !upperSql.startsWith("DELETE") && !upperSql.startsWith("CREATE")) {
                    log.warn("[SQL Generation] WARNING: Result does not start with SQL keyword! First 100 chars: {}", 
                            sql.substring(0, Math.min(100, sql.length())));
                }
            }
            
            return sql;
        } catch (Exception e) {
            log.error("SQL generation failed", e);
            return null;
        }
    }

    /**
     * Build SQL prompt for direct AIService call (same prompt content as SqlWriterAgent).
     */
    private String buildDirectSqlPrompt(String query, String schemaContext, String fewShotExamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("Imagine you are a senior data engineer at a Fortune 500 company with 10+ years of experience.\n");
        sb.append("You've written thousands of production SQL queries and mentored junior engineers.\n\n");

        if (fewShotExamples != null && !fewShotExamples.isEmpty()) {
            sb.append("IMPORTANT: Learn from the successful examples provided below.\n\n");
        }

        sb.append("When this engineer writes SQL, they ALWAYS follow this exact format:\n");
        sb.append("Format: Annotated SQL (```sql``` block, header comment)\n\n");
        sb.append("ENGINEERING BEST PRACTICES this engineer follows:\n");
        sb.append("- ALWAYS wrap SQL in ```sql code block (REQUIRED for parsing)\n");
        sb.append("- ALWAYS end SQL with semicolon (;)\n");
        sb.append("- NO text before or after the code block\n");
        sb.append("- Use FULLY QUALIFIED table names ONLY in FROM/JOIN clauses: {database}.{schema}.{table}\n");
        sb.append("- For columns, use simple column names or table alias (e.g., column_name or t.column_name)\n");
        sb.append("- STRING COMPARISON: LOWER(column_name) = 'lowercase_value'\n");
        sb.append("- Use proper JOINs when multiple tables are needed\n\n");
        sb.append("AGGREGATION RULES (CRITICAL - ALWAYS FOLLOW):\n");
        sb.append("- Fact tables contain MULTIPLE ROWS per date (split by OS, COUNTRY, USER_TYPE, etc.)\n");
        sb.append("- ALWAYS use SUM(), COUNT() for numeric metrics if there are dimension columns\n");
        sb.append("- ALWAYS GROUP BY the date/dimension columns you SELECT\n");
        sb.append("- For ratios: SUM(numerator) / NULLIF(SUM(denominator), 0) - aggregate BEFORE dividing\n\n");
        sb.append("COLUMN ALIAS NAMING RULES (CRITICAL):\n");
        sb.append("- Use SHORT, CONCISE aliases (max 2-3 words)\n");
        sb.append("- NO inline comments after column definitions\n\n");
        sb.append("Write the SQL as this engineer would.\n\n");

        if (fewShotExamples != null && !fewShotExamples.isEmpty()) {
            sb.append("=== SUCCESSFUL EXAMPLES (Learn from these patterns) ===\n");
            sb.append(fewShotExamples).append("\n");
            sb.append("=== END EXAMPLES ===\n\n");
        }

        sb.append("Question: ").append(query).append("\n\n");
        sb.append("Available Schema:\n").append(schemaContext);
        return sb.toString();
    }

    /**
     * Get Few-shot examples from successful query patterns.
     * Returns formatted examples string or null if no patterns available.
     */
    private String getFewShotExamples() {
        if (aiFeedbackService == null || dataSourceId == null) {
            return null;
        }

        try {
            ListResult<AiFeedbackService.QueryPattern> result = 
                aiFeedbackService.getSuccessfulPatterns(dataSourceId, MAX_FEWSHOT_EXAMPLES);
            
            if (result == null || result.getData() == null || result.getData().isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            int index = 1;
            for (AiFeedbackService.QueryPattern pattern : result.getData()) {
                sb.append("Example ").append(index).append(":\n");
                sb.append("Question: ").append(pattern.getQuestion()).append("\n");
                sb.append("SQL:\n```sql\n").append(pattern.getSql()).append("\n```\n\n");
                index++;
            }
            
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to get Few-shot examples: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Strips markdown code block fences (```sql\n...\n```) and leading SQL comments from a string.
     */
    private String stripCodeBlock(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String result = text.trim();
        
        // Pattern to match ```sql or ``` followed by content and then ```
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "```(?:sql)?\\s*\\n?(.*?)\\n?\\s*```", 
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            result = matcher.group(1).trim();
        }
        
        // Remove leading SQL block comments (/* ... */) - LLM sometimes adds comments in user's language
        result = result.replaceAll("^\\s*/\\*.*?\\*/\\s*", "");
        
        // Remove any remaining partial comment artifacts at the start (e.g., trailing "*/")
        // This handles cases where markdown stripping left partial comments
        java.util.regex.Pattern partialComment = java.util.regex.Pattern.compile("^[^A-Za-z]*\\*/\\s*");
        result = partialComment.matcher(result).replaceFirst("");
        
        // Ensure SQL starts with a valid keyword (SELECT, WITH, INSERT, UPDATE, DELETE, CREATE, etc.)
        java.util.regex.Pattern sqlStartPattern = java.util.regex.Pattern.compile(
                "^.*?((?:SELECT|WITH|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|MERGE)\\b.*)",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher sqlMatcher = sqlStartPattern.matcher(result);
        if (sqlMatcher.find()) {
            result = sqlMatcher.group(1);
        }
        
        return result.trim();
    }

    /**
     * Legacy hand-rolled SQL execution + LLM fix retry loop, restored from
     * {@code git HEAD}. The Slack Deep Agent UX expects each failed attempt
     * to be patched by a single LLM "fix this SQL" call (cheap fast model)
     * and re-executed — NOT to be replaced by a tool-calling self-correction
     * loop, which has different latency/cost characteristics and changes
     * the streamed thought-trace shape the Slack client renders.
     */
    private ExecutionResult executeSqlWithRetry(String sql, String schemaContext, String originalQuery) {
        String currentSql = sql;
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("Executing SQL (attempt {}/{}): {}", attempt, MAX_RETRY_ATTEMPTS,
                        currentSql.length() > 100 ? currentSql.substring(0, 100) + "..." : currentSql);

                DlExecuteParam param = new DlExecuteParam();
                param.setSql(currentSql);
                param.setDataSourceId(dataSourceId);
                param.setDatabaseName(databaseName);
                param.setSchemaName(schemaName);
                param.setConsoleId(0L);

                ListResult<ExecuteResult> result = dlTemplateService.execute(param);

                if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                    ExecuteResult execResult = result.getData().get(0);
                    return ExecutionResult.success(
                            currentSql,
                            execResult,
                            formatResultsForAnalysis(execResult)
                    );
                } else {
                    lastError = result.getErrorMessage();
                    log.warn("SQL execution failed: {}", lastError);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("SQL execution exception: {}", lastError);
            }

            if (attempt < MAX_RETRY_ATTEMPTS && lastError != null) {
                log.info("Attempting to fix SQL based on error...");
                try {
                    String fixedRaw;
                    if (aiService != null) {
                        String fastModel = ModelMapper.getFastModel(modelName);
                        String fixPrompt = buildFixSqlPrompt(currentSql, lastError, schemaContext);
                        log.info("[fixSql] Using direct AIService streaming path with fast model: {}", fastModel);
                        fixedRaw = aiService.generateWithStreaming(fixPrompt, fastModel, t -> {});
                    } else {
                        SqlWriterAgent writer = getSqlWriterAgent();
                        fixedRaw = writer.fixSql(currentSql, lastError, schemaContext);
                    }
                    currentSql = stripCodeBlock(fixedRaw);
                    log.info("Fixed SQL: {}", currentSql.length() > 100 ? currentSql.substring(0, 100) + "..." : currentSql);
                } catch (Exception e) {
                    log.warn("Failed to fix SQL", e);
                }
            }
        }

        return ExecutionResult.failure(currentSql, lastError);
    }

    private String buildFixSqlPrompt(String originalSql, String error, String schemaContext) {
        return "Imagine you are a senior data engineer debugging a failed SQL query.\n"
                + "You've fixed thousands of SQL errors in production systems.\n\n"
                + "When this engineer sees an error, they:\n"
                + "- Analyze the error message carefully\n"
                + "- Check the schema to find correct table/column names\n"
                + "- Apply the minimal fix needed\n\n"
                + "Common fixes:\n"
                + "- TABLE_NOT_FOUND: Find correct table name from schema\n"
                + "- COLUMN_NOT_FOUND: Find correct column name from schema\n"
                + "- SYNTAX_ERROR: Fix SQL syntax\n"
                + "- TYPE_MISMATCH: Add proper type casting\n\n"
                + "Return ONLY the fixed SQL query. No explanations.\n\n"
                + "Original SQL:\n" + originalSql + "\n\n"
                + "Error:\n" + error + "\n\n"
                + "Schema:\n" + schemaContext;
    }

    private String analyzeResults(String query, String sql, String results) {
        try {
            if (aiService != null) {
                String prompt = buildAnalysisPrompt(query, sql, results);
                return aiService.generateWithStreaming(prompt, modelName, t -> {});
            }
            ResultAnalyzerAgent analyzer = createFreshAnalyzerAgent(modelName);
            StringBuilder context = new StringBuilder();
            if (this.conversationHistoryContext != null && !this.conversationHistoryContext.isEmpty()) {
                context.append(this.conversationHistoryContext).append("\n");
            }
            if (this.businessContext != null && !this.businessContext.isEmpty()) {
                context.append("[BUSINESS CONTEXT]\n").append(this.businessContext);
            }
            return analyzer.analyzeResults(query, sql, results, context.toString());
        } catch (Exception e) {
            log.error("Result analysis failed", e);
            return "Analysis not available";
        }
    }

    private String recommendChart(String query, String results) {
        try {
            String fastModel = ModelMapper.getFastModel(modelName);
            if (aiService != null) {
                String prompt = buildChartPrompt(query, results);
                return aiService.generateWithStreaming(prompt, fastModel, t -> {});
            }
            ResultAnalyzerAgent analyzer = createFreshAnalyzerAgent(fastModel);
            return analyzer.recommendChart(query, results);
        } catch (Exception e) {
            log.error("Chart recommendation failed", e);
            return "{\"chartType\":\"BAR\",\"confidence\":0.5,\"reason\":\"Default recommendation\"}";
        }
    }

    private String buildAnalysisPrompt(String query, String sql, String results) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior business analyst with expertise in data insights.\n\n");
        sb.append("CRITICAL RULE - LANGUAGE:\n");
        sb.append("- You MUST respond in the SAME LANGUAGE as the user's original question.\n\n");
        sb.append("ANALYSIS GUIDELINES:\n");
        sb.append("- Keep your analysis concise: maximum 200 words\n");
        sb.append("- Be data-driven: reference specific numbers from the results\n");
        sb.append("- Focus on what matters most for the user's question\n");
        sb.append("- Write in clear, non-technical language\n");
        sb.append("- DO NOT suggest or recommend chart types\n\n");
        if (this.conversationHistoryContext != null && !this.conversationHistoryContext.isEmpty()) {
            sb.append(this.conversationHistoryContext).append("\n");
        }
        if (this.businessContext != null && !this.businessContext.isEmpty()) {
            sb.append("[BUSINESS CONTEXT]\n").append(this.businessContext).append("\n\n");
        }
        sb.append("Original Question: ").append(query).append("\n\n");
        sb.append("Executed SQL:\n").append(sql).append("\n\n");
        sb.append("Query Results:\n").append(results);
        return sb.toString();
    }

    private String buildChartPrompt(String query, String results) {
        return "You are a data visualization expert. Respond ONLY with JSON:\n" +
                "{\"chartType\": \"LINE|BAR|PIE|SCATTER|CARD|FUNNEL|TABLE\",\n" +
                " \"xAxis\": \"column_name or null\", \"yAxis\": \"column_name or null\",\n" +
                " \"dimension\": \"grouping column or null\",\n" +
                " \"yAxisFormat\": \"original|comma|decimal1|decimal2|percent|percent1|percent2|k\",\n" +
                " \"lineVariant\": \"line|area|smooth|step or null\",\n" +
                " \"pieVariant\": \"pie|ring|rose or null\",\n" +
                " \"barOrientation\": \"vertical|horizontal or null\",\n" +
                " \"order\": \"x_asc|x_desc|y_asc|y_desc or null\",\n" +
                " \"confidence\": 0.0-1.0, \"reason\": \"Brief explanation\"}\n\n" +
                "CHART SELECTION (priority order):\n" +
                "1. Single row + metrics only → CARD\n" +
                "2. Single row + 5+ columns → TABLE\n" +
                "3. Single row otherwise → BAR\n" +
                "4. Date column exists → LINE\n" +
                "5. <7 categories + part-to-whole → PIE\n" +
                "6. Sequential stages → FUNNEL\n" +
                "7. 2 numeric columns + NO categorical → SCATTER\n" +
                "8. Default → BAR\n\n" +
                "Y-AXIS FORMAT: check ACTUAL values, not column names.\n" +
                "CRITICAL: Never use 'Row Number' as axis.\n\n" +
                "Original Question: " + query + "\n\n" +
                "Query Results:\n" + results;
    }

    private String formatResultsForAnalysis(ExecuteResult result) {
        if (result == null || result.getDataList() == null) {
            return "No data";
        }

        List<List<String>> data = result.getDataList();
        List<ai.inquery.spi.model.Header> headers = result.getHeaderList();

        StringBuilder sb = new StringBuilder();
        sb.append("Rows: ").append(data.size()).append("\n");

        if (headers != null && !headers.isEmpty()) {
            sb.append("Columns: ").append(headers.stream()
                    .map(ai.inquery.spi.model.Header::getName)
                    .collect(Collectors.joining(", "))).append("\n\n");
        }

        // Include sample data (first 10 rows)
        int limit = Math.min(data.size(), 10);
        for (int i = 0; i < limit; i++) {
            List<String> row = data.get(i);
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

        if (data.size() > limit) {
            sb.append("... and ").append(data.size() - limit).append(" more rows");
        }

        return sb.toString();
    }

    private void reportProgress(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    // Lazy initialization of specialized agents. SQL retry now happens in the
    // DataAnalysisAgent tool-calling loop, so this writer only needs the
    // initial write turn — no chat memory shared with a separate fix call.
    //
    // Hybrid reasoning: this is the Slack Deep-Agent NL→SQL hot path,
    // mirror of InqueryRootAgentRunner.generateMarkdown. We bump effort
    // to MEDIUM so the writer spends more time on schema/predicate
    // selection — the cost of a wrong SQL (DB round-trip + retry) far
    // outweighs a few extra seconds of model thinking.
    private SqlWriterAgent getSqlWriterAgent() {
        if (sqlWriter == null) {
            StreamingChatModel model = modelProvider.getStreamingChatModel(
                    modelName, LangChainModelProvider.ReasoningEffort.MEDIUM);
            sqlWriter = AiServices.builder(SqlWriterAgent.class)
                    .streamingChatModel(model)
                    .build();
        }
        return sqlWriter;
    }

    private ResultAnalyzerAgent createFreshAnalyzerAgent(String model) {
        StreamingChatModel chatModel = modelProvider.getStreamingChatModel(model);
        return AiServices.builder(ResultAnalyzerAgent.class)
                .streamingChatModel(chatModel)
                .chatRequestTransformer(ModelMapper.promptRepetitionTransformer(model))
                .build();
    }

    private ClarificationAgent getClarificationAgent() {
        if (clarificationAgent == null) {
            String fastModel = ModelMapper.getFastModel(modelName);
            StreamingChatModel model = modelProvider.getStreamingChatModel(fastModel);
            clarificationAgent = AiServices.builder(ClarificationAgent.class)
                    .streamingChatModel(model)
                    .chatRequestTransformer(ModelMapper.promptRepetitionTransformer(fastModel))
                    .build();
        }
        return clarificationAgent;
    }

    /**
     * Check if the query needs clarification.
     */
    private ClarificationResult checkClarification(String query, String schemaContext) {
        ClarificationResult result = new ClarificationResult();

        try {
            ClarificationAgent agent = getClarificationAgent();
            String response = agent.analyzeQuery(query, schemaContext);
            log.info("Clarification analysis response: {}", response);

            String jsonStr = extractJsonObject(response);
            if (jsonStr == null) {
                log.warn("No JSON object found in clarification response");
                result.needsClarification = false;
                return result;
            }

            JSONObject json = JSON.parseObject(jsonStr);
            result.needsClarification = json.getBooleanValue("needsClarification");
            result.reason = json.getString("reason");

            if (result.needsClarification) {
                JSONArray optionsArray = json.getJSONArray("options");
                result.options = new ArrayList<>();

                if (optionsArray != null) {
                    for (int i = 0; i < optionsArray.size(); i++) {
                        JSONObject opt = optionsArray.getJSONObject(i);
                        ClarificationOption option = ClarificationOption.builder()
                                .label(opt.getString("label"))
                                .query(opt.getString("query"))
                                .build();
                        result.options.add(option);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to check clarification, proceeding without: {}", e.getMessage());
            result.needsClarification = false;
        }

        return result;
    }

    /**
     * Extracts the outermost balanced JSON object from LLM response text.
     * Handles ```json code blocks and extra surrounding text with braces.
     */
    private String extractJsonObject(String text) {
        if (text == null || text.isEmpty()) return null;

        String cleaned = text.trim();

        // Strip any markdown code block (```json, ```, etc.)
        java.util.regex.Pattern codeBlock = java.util.regex.Pattern.compile(
                "```\\w*\\s*\\n?(.*?)\\n?\\s*```",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher cbMatcher = codeBlock.matcher(cleaned);
        if (cbMatcher.find()) {
            cleaned = cbMatcher.group(1).trim();
        }

        // Find outermost balanced { ... } using brace depth tracking
        int start = cleaned.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return cleaned.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ===== Tool-augmented agent for MCP and Python =====

    /**
     * AI Service interface for executing queries with external tools (MCP, Python).
     * LangChain4j auto-invokes registered tools based on the user's query.
     */
    interface ToolAgent {
        @dev.langchain4j.service.SystemMessage(
                "You are a helpful assistant that can use external tools to complete tasks. "
                + "When the user asks to interact with Slack, Jira, Confluence, GitHub, or other services, "
                + "use the appropriate tool. When data analysis or chart generation is requested, use Python. "
                + "Always respond in the same language as the user's question."
        )
        String execute(@dev.langchain4j.service.UserMessage String query);
    }

    private static final java.util.Set<String> EXTERNAL_SERVICE_KEYWORDS = java.util.Set.of(
            "slack", "jira", "confluence", "wiki", "github", "git",
            "message", "send", "search slack", "create issue", "find issue",
            "pull request", "pr"
    );

    /**
     * Check if the query is about external services (Slack, Jira, etc.)
     * rather than database/SQL queries.
     */
    private boolean isExternalServiceQuery(String query) {
        if (mcpToolProvider == null) return false;
        String lower = query.toLowerCase();
        return EXTERNAL_SERVICE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Process query using external tool agent (MCP tools).
     * Returns null if tools couldn't handle the query.
     */
    private String processWithExternalTools(String userQuery) {
        try {
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getToolCallingChatModel(modelName);
            var builder = AiServices.builder(ToolAgent.class)
                    .chatModel(chatModel);

            if (mcpToolProvider != null) {
                builder.toolProvider(mcpToolProvider);
            }
            if (pythonTools != null) {
                builder.tools(pythonTools);
            }

            ToolAgent agent = builder.build();
            String result = agent.execute(userQuery);
            log.info("External tool agent result length: {}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("External tool execution failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Run Python analysis on large result sets.
     * Writes full CSV to a temp file, then lets LLM generate Python code only.
     * LLM sees a preview (header + 10 rows) for context; Python reads the full file.
     */
    private String runPythonAnalysis(String userQuery, ExecutionResult execResult) {
        if (pythonTools == null) return null;

        try {
            // Write full CSV to temp file so Python can read ALL rows
            String fullCsv = buildFullCsv(execResult);
            Path csvFile = Files.createTempFile("inquery-python-", ".csv");
            Files.writeString(csvFile, fullCsv);

            // Build preview (header + 10 rows) for LLM context
            String[] csvLines = fullCsv.split("\n");
            int previewLines = Math.min(csvLines.length, 11);
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < previewLines; i++) {
                preview.append(csvLines[i]).append("\n");
            }
            if (csvLines.length > previewLines) {
                preview.append("... (" + (csvLines.length - 1) + " total rows)\n");
            }

            // LLM generates code; Python reads full data from the pre-written file
            String prompt = "The user asked: \"" + userQuery + "\"\n\n"
                    + "SQL query returned " + execResult.rowCount + " rows.\n"
                    + "Data preview (first 10 rows):\n" + preview + "\n"
                    + "The FULL dataset is already loaded as DataFrame 'df' (" + execResult.rowCount + " rows). "
                    + "You do NOT need to pass CSV data — just pass empty string for inputData.\n\n"
                    + "Produce a statistical summary relevant to the user's question. "
                    + "Print results to stdout. Do NOT create charts.";

            // Override the data file path so PythonTools reads from the pre-written full CSV
            String result = pythonTools.executePythonWithDataFile(prompt, csvFile.toString(),
                    modelProvider.getChatModel(modelName));

            // Clean up temp file
            try { Files.deleteIfExists(csvFile); } catch (Exception ignored) {}

            return result;
        } catch (Exception e) {
            log.warn("Python analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build full CSV string from ExecutionResult for Python analysis.
     */
    private String buildFullCsv(ExecutionResult execResult) {
        if (!(execResult.resultData instanceof ExecuteResult result)) {
            return execResult.formattedResults;
        }
        List<List<String>> data = result.getDataList();
        List<ai.inquery.spi.model.Header> headers = result.getHeaderList();
        if (data == null || data.isEmpty()) {
            return "No data";
        }

        StringBuilder csv = new StringBuilder();
        // Header row
        if (headers != null && !headers.isEmpty()) {
            csv.append(headers.stream()
                    .map(h -> escapeCsvField(h.getName()))
                    .collect(Collectors.joining(","))).append("\n");
        }
        // All data rows
        for (List<String> row : data) {
            csv.append(row.stream()
                    .map(this::escapeCsvField)
                    .collect(Collectors.joining(","))).append("\n");
        }
        return csv.toString();
    }

    private String escapeCsvField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    // Inner classes for results
    @Data
    @Builder
    public static class SupervisorResult {
        private String originalQuery;
        private String schemaContext;
        private String generatedSql;
        private String executedSql;
        private Object resultData;
        private int rowCount;
        private String analysis;
        private String chartRecommendation;
        private boolean success;
        private String errorMessage;
        private String pythonAnalysis;
        // Clarification fields
        private boolean needsClarification;
        private List<ClarificationOption> clarificationOptions;
    }

    @Data
    @Builder
    public static class ClarificationOption {
        private String label;
        private String query;
    }

    @Data
    private static class ClarificationResult {
        private boolean needsClarification;
        private String reason;
        private List<ClarificationOption> options;
    }

    @Data
    private static class ExecutionResult {
        private String finalSql;
        private boolean success;
        private String errorMessage;
        private Object resultData;
        private int rowCount;
        private String formattedResults;

        static ExecutionResult success(String sql, ExecuteResult result, String formatted) {
            ExecutionResult r = new ExecutionResult();
            r.finalSql = sql;
            r.success = true;
            r.resultData = result;
            r.rowCount = result.getDataList() != null ? result.getDataList().size() : 0;
            r.formattedResults = formatted;
            return r;
        }

        static ExecutionResult failure(String sql, String error) {
            ExecutionResult r = new ExecutionResult();
            r.finalSql = sql;
            r.success = false;
            r.errorMessage = error;
            return r;
        }
    }
}
