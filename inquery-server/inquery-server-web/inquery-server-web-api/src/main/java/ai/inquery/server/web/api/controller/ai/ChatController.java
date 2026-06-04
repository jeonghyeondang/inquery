package ai.inquery.server.web.api.controller.ai;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.ShowCreateTableParam;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.tools.base.enums.WhiteListTypeEnum;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.util.EasyEnumUtils;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient;
import ai.inquery.server.web.api.controller.ai.claude.listener.ClaudeAIEventSourceListener;
import ai.inquery.server.web.api.controller.ai.config.LocalCache;
import ai.inquery.server.web.api.controller.ai.converter.ChatConverter;
import ai.inquery.server.web.api.controller.ai.enums.PromptType;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatItem;
import ai.inquery.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.inquery.server.web.api.controller.ai.fastchat.model.FastChatRole;
import ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient;
import ai.inquery.server.web.api.controller.ai.openai.client.OfficialOpenAIClient;
import ai.inquery.server.web.api.controller.ai.openai.client.DirectOpenAIClient;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient;
import ai.inquery.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.inquery.server.web.api.controller.ai.request.ChatRequest;
import ai.inquery.server.web.api.http.GatewayClientService;
import ai.inquery.server.web.api.http.model.EsTableSchema;
import ai.inquery.server.web.api.http.model.TableSchema;
import ai.inquery.server.web.api.http.request.EsTableSchemaRequest;
import ai.inquery.server.web.api.http.request.TableSchemaRequest;
import ai.inquery.server.web.api.http.request.WhiteListRequest;
import ai.inquery.server.web.api.http.response.EsTableSchemaResponse;
import ai.inquery.server.web.api.http.response.TableSchemaResponse;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import jakarta.servlet.http.HttpServletResponse;
import com.unfbx.chatgpt.entity.chat.Message;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * description: 
 *
 * @date 2023-03-01
 */
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/ai")
@Slf4j
public class ChatController {
    // ===== Preferred provider/model selection (based on configured API keys) =====
    // Defaults below are used when a provider is auto-selected by
    //   pickPreferredProvider() and the request did not pin a specific
    //   model. Keep these in sync with the recommendations shown in the
    //   settings UI / README so the UI promise matches the runtime pick.
    //
    // OpenAI default is gpt-5.4-mini rather than the gpt-5.5 flagship
    //   on purpose: gpt-5.5 rejects reasoning_effort with function tools
    //   on /v1/chat/completions (see LangChainModelProvider#isOpenAi55Family),
    //   which forces the OpenAI default `medium` effort and pushes
    //   single-turn chat latency to 30-60s. gpt-5.4-mini is half the
    //   price, "Fast" tier, and honors our hybrid LOW(chat)/MEDIUM(SQL)
    //   policy — a strict UX upgrade for the auto-selected default.
    //   Users who want gpt-5.5 quality can still pin it explicitly.
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.4-mini";
    private static final String DEFAULT_GEMINI_MODEL = ModelMapper.getDefaultPrimaryModel();
    private static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6";


    private Integer parsePortOrNull(String rawPort, String sourceTag) {
        if (StringUtils.isBlank(rawPort)) {
            return null;
        }
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            log.warn("Invalid port format from {}: {}", sourceTag, rawPort);
            return null;
        }
    }

    private boolean hasConfiguredKey(ConfigService configService, String configCode) {
        try {
            DataResult<Config> r = configService.find(configCode);
            Config cfg = r != null ? r.getData() : null;
            return cfg != null && StringUtils.isNotBlank(cfg.getContent());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read the persisted "enabled" flag for a provider. Treat a missing or
     * blank row as enabled so providers configured before the flag existed
     * stay selectable. Mirrors ConfigController.readEnabledFlag.
     */
    private boolean isProviderEnabled(ConfigService configService, String enabledConfigCode) {
        try {
            DataResult<Config> r = configService.find(enabledConfigCode);
            Config cfg = r != null ? r.getData() : null;
            if (cfg == null || StringUtils.isBlank(cfg.getContent())) {
                return true;
            }
            return !"false".equalsIgnoreCase(cfg.getContent().trim());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Priority when multiple keys are present: Gemini > Claude > OpenAI.
     * A provider is only picked when BOTH conditions hold:
     *   1. an apiKey is persisted
     *   2. the per-provider "enabled" flag is not "false"
     * If no provider qualifies, returns null (fallback to legacy routing).
     */
    private AiSqlSourceEnum pickPreferredProvider(ConfigService configService) {
        boolean hasGemini = hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.GEMINI_API_KEY);
        boolean hasClaude = hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.CLAUDE_API_KEY);
        boolean hasOpenAi = hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient.OPENAI_KEY);

        boolean geminiEnabled = isProviderEnabled(configService, ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.GEMINI_ENABLED);
        boolean claudeEnabled = isProviderEnabled(configService, ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.CLAUDE_ENABLED);
        boolean openAiEnabled = isProviderEnabled(configService, ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient.OPENAI_ENABLED);

        if (hasGemini && geminiEnabled) return AiSqlSourceEnum.GEMINI;
        if (hasClaude && claudeEnabled) return AiSqlSourceEnum.CLAUDEAI;
        if (hasOpenAi && openAiEnabled) return AiSqlSourceEnum.OPENAI;
        return null;
    }

    private String defaultModelFor(AiSqlSourceEnum provider) {
        if (provider == null) return null;
        switch (provider) {
            case GEMINI:
                return DEFAULT_GEMINI_MODEL;
            case CLAUDEAI:
                return DEFAULT_CLAUDE_MODEL;
            case OPENAI:
                return DEFAULT_OPENAI_MODEL;
            default:
                return null;
        }
    }

    @Autowired
    protected TableService tableService;

    @Autowired
    private ChatConverter chatConverter;

    @Autowired
    private DataSourceService dataSourceService;

    @Value("${chatgpt.context.length}")
    private Integer contextLength;

    @Value("${chatgpt.version}")
    private String gptVersion;

    @Resource
    private GatewayClientService gatewayClientService;

    @Autowired
    private ai.inquery.server.domain.api.service.DlTemplateService dlTemplateService;

    @Autowired
    private ai.inquery.server.domain.api.service.AIService aiService;

    @Autowired
    private ai.inquery.server.domain.core.query.SchemaSearcher schemaSearcher;

    @Autowired
    private ai.inquery.server.domain.core.langchain.InqueryAgentService inqueryAgentService;

    @Autowired
    private ai.inquery.server.domain.core.langchain.LangChainModelProvider langChainModelProvider;

    @Autowired
    private ai.inquery.server.domain.core.business.BusinessInsightService businessInsightService;

    @Autowired
    private ai.inquery.server.domain.api.service.AiFeedbackService aiFeedbackService;

    @Autowired
    private ai.inquery.server.domain.core.query.SqlGenerator sqlGenerator;

    @Autowired
    private ai.inquery.server.domain.core.security.AstValidator astValidator;

    @Autowired
    private ai.inquery.server.domain.core.chart.ChartRecommendationEngine chartRecommendationEngine;

    @Autowired
    private ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager toolApprovalManager;

    @Autowired
    private ai.inquery.server.domain.api.service.UserAIConfigService userAIConfigService;

    @Autowired
    private ai.inquery.server.domain.core.python.PythonEnvironmentSetup pythonEnvironmentSetup;

    @Autowired
    private ai.inquery.server.domain.core.langchain.tools.WebSearchService webSearchService;

    @Autowired(required = false)
    private ai.inquery.server.domain.api.service.ReferenceDocumentSearchService referenceDocumentSearchService;

    @Autowired
    private ai.inquery.server.domain.core.attachment.AiChatAttachmentService chatAttachmentService;

    @Autowired
    private java.util.List<ai.inquery.server.domain.core.search.ExternalSearchHandler> externalSearchHandlers;

    @Autowired
    private ai.inquery.server.domain.core.langchain.mcp.McpConnectionManager mcpConnectionManager;

    /**
     * Warmup Vector DB (Pinecone) connection.
     * Called by frontend when user enters any page to reduce latency for first query.
     * Safe to call multiple times - connection is cached.
     */
    @PostMapping("/warmup")
    @CrossOrigin
    public DataResult<Boolean> warmupVectorDb() {
        try {
            log.info("Vector DB warmup requested");
            schemaSearcher.warmUp();
            log.info("Vector DB warmup completed");
            return DataResult.of(true);
        } catch (Exception e) {
            log.warn("Vector DB warmup failed (non-fatal): {}", e.getMessage());
            return DataResult.of(false);
        }
    }

    /**
     * Interpret SQL result endpoint - LLM generates natural language answer from query result
     * Returns interpretation along with prompt and markdown table for monitoring
     */
    @PostMapping("/interpret")
    @CrossOrigin
    public DataResult<java.util.Map<String, String>> interpretResult(@RequestBody ai.inquery.server.web.api.controller.ai.request.InterpretRequest request) {
        try {
            String originalQuery = request.getOriginalQuery();
            Object sqlResult = request.getSqlResult();
            String model = request.getModel();
            String generatedSql = request.getGeneratedSql();
            String schemaContext = request.getSchemaContext();
            String businessContext = request.getBusinessContext();
            String additionalInsightContext = request.getAdditionalInsightContext();

            if (model == null || model.isEmpty() || "inquery-agent".equals(model)) {
                ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
                AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
                String primaryModel = defaultModelFor(preferredProvider);
                // Use fast model for lightweight interpret task
                model = ModelMapper.getFastModel(primaryModel);
            }

            // Convert to markdown table first
            String markdownTable = convertToMarkdownTable(sqlResult);

            Integer totalRowCount = request.getTotalRowCount();

            String pythonAnalysis = request.getPythonAnalysis();

            // Build prompt for interpretation with SQL, schema context, and business context
            String prompt = buildInterpretPrompt(originalQuery, sqlResult, generatedSql, schemaContext,
                    businessContext, additionalInsightContext, totalRowCount, pythonAnalysis);
            log.info("Interpreting result for query: {}", originalQuery);

            // Call LLM
            String interpretation = aiService.generate(prompt, model);

            // Return interpretation with prompt and table for monitoring
            java.util.Map<String, String> result = new java.util.HashMap<>();
            result.put("interpretation", interpretation);
            result.put("prompt", prompt);
            result.put("markdownTable", markdownTable);
            result.put("model", model);

            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to interpret result", e);
            return DataResult.error("INTERPRET_ERROR", "Failed to interpret result: " + e.getMessage());
        }
    }

    /**
     * Analyze execution plan with AI - provides performance insights, index recommendations,
     * and optimization suggestions based on EXPLAIN output.
     */
    @PostMapping("/analyze-plan")
    @CrossOrigin
    public DataResult<java.util.Map<String, String>> analyzeQueryPlan(@RequestBody java.util.Map<String, String> request) {
        try {
            String sql = request.get("sql");
            String databaseType = request.get("databaseType");
            String executionPlan = request.get("executionPlan");
            String metricsJson = request.get("metrics");
            String warningsJson = request.get("warnings");
            String schemaContext = request.get("schemaContext");
            String language = request.get("language");
            String model = request.get("model");

            if (model == null || model.isEmpty()) {
                ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
                AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
                String primaryModel = defaultModelFor(preferredProvider);
                model = ModelMapper.getFastModel(primaryModel);
            }

            if (sql == null || sql.trim().isEmpty()) {
                return DataResult.error("ANALYZE_PLAN_ERROR", "SQL query is required");
            }
            if (executionPlan == null || executionPlan.trim().isEmpty()) {
                return DataResult.error("ANALYZE_PLAN_ERROR", "Execution plan is required");
            }

            String prompt = buildAnalyzePlanPrompt(sql, databaseType, executionPlan, metricsJson, warningsJson, schemaContext, language);
            log.info("Analyzing query plan for database type: {}", databaseType);

            String analysis = aiService.generate(prompt, model);

            java.util.Map<String, String> result = new java.util.HashMap<>();
            result.put("analysis", analysis);
            result.put("model", model);
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to analyze query plan", e);
            return DataResult.error("ANALYZE_PLAN_ERROR", "Failed to analyze query plan: " + e.getMessage());
        }
    }

    private String buildAnalyzePlanPrompt(String sql, String databaseType, String executionPlan,
                                           String metricsJson, String warningsJson, String schemaContext, String language) {
        StringBuilder sb = new StringBuilder();
        String dbLabel = (databaseType != null && !databaseType.isEmpty()) ? databaseType : "SQL";
        sb.append("You are a ").append(dbLabel).append(" performance tuning expert.\n\n");

        sb.append("Analyze the following SQL query and its execution plan. Provide actionable insights.\n\n");

        sb.append("## SQL Query\n```sql\n").append(sql).append("\n```\n\n");

        sb.append("## Execution Plan\n```\n").append(executionPlan).append("\n```\n\n");

        if (metricsJson != null && !metricsJson.isEmpty()) {
            sb.append("## Metrics\n").append(metricsJson).append("\n\n");
        }

        if (warningsJson != null && !warningsJson.isEmpty()) {
            sb.append("## Detected Warnings\n").append(warningsJson).append("\n\n");
        }

        if (schemaContext != null && !schemaContext.isEmpty()) {
            sb.append("## Table Definitions\n```sql\n").append(schemaContext).append("\n```\n\n");
        }

        String langName = resolveLanguageName(language);
        sb.append("## Requirements\n");
        sb.append("- CRITICAL: You MUST respond entirely in ").append(langName).append(".\n");
        sb.append("- Format your response in well-structured markdown.\n");
        sb.append("- Be concise and practical — focus on actionable advice.\n");
        sb.append("- Keep SQL code, table names, and column names in their original form (do not translate them).\n\n");

        sb.append("## Response Format\n");
        sb.append("### Performance Summary\n");
        sb.append("A brief 2-3 sentence summary of the query's performance characteristics.\n\n");

        sb.append("### Issues Found\n");
        sb.append("List each issue with severity (Critical/Warning/Info) and a clear explanation.\n");
        sb.append("For each issue, explain WHY it's a problem and its IMPACT on performance.\n\n");

        sb.append("### Optimization Suggestions\n");
        sb.append("Concrete, actionable suggestions ranked by expected impact:\n");
        sb.append("1. Index recommendations (with exact CREATE INDEX statements)\n");
        sb.append("2. Query rewrite suggestions (with example SQL)\n");
        sb.append("3. Schema or configuration changes if applicable\n\n");

        sb.append("### Estimated Improvement\n");
        sb.append("Brief estimate of expected performance gain if suggestions are applied (e.g., '~10x faster', '~80% cost reduction').\n");

        return sb.toString();
    }

    private String resolveLanguageName(String langCode) {
        if (langCode == null || langCode.isEmpty()) return "English";
        switch (langCode.toLowerCase()) {
            case "ko-kr": return "Korean";
            case "ja-jp": return "Japanese (日本語)";
            case "tr-tr": return "Turkish (Türkçe)";
            case "en-us": default: return "English";
        }
    }

    /**
     * Optimize SQL query endpoint - LLM returns optimized SQL only
     */
    @PostMapping("/optimize")
    @CrossOrigin
    public DataResult<java.util.Map<String, String>> optimizeSQL(@RequestBody java.util.Map<String, String> request) {
        try {
            String sql = request.get("sql");
            String databaseType = request.get("databaseType");
            String model = request.get("model");
            if (model == null || model.isEmpty()) {
                ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
                AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
                String primaryModel = defaultModelFor(preferredProvider);
                // Use fast model for lightweight optimize task
                model = ModelMapper.getFastModel(primaryModel);
            }

            if (sql == null || sql.trim().isEmpty()) {
                return DataResult.error("OPTIMIZE_ERROR", "SQL query is required");
            }

            String schemaContext = request.get("schemaContext");
            String prompt = buildOptimizePrompt(sql, databaseType, schemaContext);
            log.info("Optimizing SQL for database type: {}, hasSchema: {}", databaseType, schemaContext != null);

            String optimizedSql = aiService.generate(prompt, model);

            java.util.Map<String, String> result = new java.util.HashMap<>();
            result.put("optimizedSql", optimizedSql);
            result.put("model", model);
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to optimize SQL", e);
            return DataResult.error("OPTIMIZE_ERROR", "Failed to optimize SQL: " + e.getMessage());
        }
    }

    private String buildOptimizePrompt(String sql, String databaseType, String schemaContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a ").append(databaseType != null && !databaseType.isEmpty() ? databaseType : "SQL");
        sb.append(" query optimization expert.\n\n");
        if (schemaContext != null && !schemaContext.isEmpty()) {
            sb.append("Table definitions:\n").append(schemaContext).append("\n\n");
        }
        sb.append("Optimize the following SQL query for better performance.\n");
        if (schemaContext != null && !schemaContext.isEmpty()) {
            sb.append("Use the table definitions above to make informed optimizations (e.g., select only needed columns, leverage indexes).\n");
        }
        sb.append("Return ONLY the optimized SQL query without any explanation, comments, or markdown formatting.\n");
        sb.append("Format the SQL with proper indentation and line breaks for readability.\n\n");
        sb.append("SQL:\n").append(sql);
        return sb.toString();
    }

    /**
     * Schema summarize endpoint - LLM generates a summary of the table schema in response to user's question
     * Uses fast model for quick response (auto-detects configured provider)
     */
    @PostMapping("/schema/summarize")
    @CrossOrigin
    public DataResult<java.util.Map<String, String>> summarizeSchema(@RequestBody java.util.Map<String, String> request) {
        try {
            String originalQuery = request.get("originalQuery");
            String schemaContext = request.get("schemaContext");
            String model = request.get("model");

            // Auto-detect model if not specified - use fast model based on configured provider
            if (model == null || model.isEmpty()) {
                ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
                AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
                String primaryModel = defaultModelFor(preferredProvider);
                model = ModelMapper.getFastModel(primaryModel);
            }

            // Build prompt for schema summary
            String prompt = buildSchemaSummaryPrompt(originalQuery, schemaContext);
            log.info("Generating schema summary for query: {} with model: {}", originalQuery, model);

            // Call LLM
            String summary = aiService.generate(prompt, model);

            // Return summary
            java.util.Map<String, String> result = new java.util.HashMap<>();
            result.put("summary", summary);
            result.put("model", model);

            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to summarize schema", e);
            return DataResult.error("SCHEMA_SUMMARY_ERROR", "Failed to summarize schema: " + e.getMessage());
        }
    }

    private String buildSchemaSummaryPrompt(String originalQuery, String schemaContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a data analyst helping a user understand a database table schema.\n\n");
        sb.append("Requirements:\n");
        sb.append("- CRITICAL: You MUST respond in the SAME LANGUAGE as the user's question, regardless of app UI language.\n");
        sb.append("- Format your response in markdown\n");
        sb.append("- Be concise but informative\n");
        sb.append("- Explain the table purpose and key columns\n");
        sb.append("- Highlight any primary keys, foreign keys, and important relationships\n");
        sb.append("- If there are column descriptions or examples, use them to provide context\n");
        sb.append("- Answer the user's specific question about the schema if applicable\n\n");

        sb.append("User's Question: ").append(originalQuery).append("\n\n");
        sb.append("Table Schema:\n").append(schemaContext).append("\n\n");
        sb.append("Summary:");
        return sb.toString();
    }

    private String buildInterpretPrompt(String originalQuery, Object sqlResult, String generatedSql, String schemaContext,
                                        String businessContext, String additionalInsightContext,
                                        Integer totalRowCount, String pythonAnalysis) {
        StringBuilder sb = new StringBuilder();

        // Detect SQL explanation mode (no result data provided — just explain the query)
        boolean isExplainMode = sqlResult == null && totalRowCount == null && pythonAnalysis == null;

        boolean hasPythonStats = pythonAnalysis != null && !pythonAnalysis.isEmpty();
        boolean hasData = hasPythonStats
                || (sqlResult != null && !(sqlResult instanceof java.util.List && ((java.util.List<?>) sqlResult).isEmpty()));

        int shownRows = 0;
        if (sqlResult instanceof java.util.List) {
            shownRows = ((java.util.List<?>) sqlResult).size();
        }
        boolean isTruncated = totalRowCount != null && totalRowCount > shownRows;

        if (isExplainMode) {
            sb.append("You are a SQL expert. Explain the given SQL query clearly and concisely.\n");
            sb.append("This is a query EXPLANATION task — do NOT mention query results, row counts, or data absence.\n\n");
        } else if (hasData) {
            sb.append("You are a data analyst. Answer the user's question based on the query result below.\n\n");
        } else {
            sb.append("You are a data analyst. The SQL query executed successfully but returned **zero rows**.\n\n");
        }

        sb.append("Requirements:\n");
        sb.append("- MUST respond in the same language as the user's question\n");
        sb.append("- Format your response in well-structured markdown\n");
        sb.append("- Use ## for main section headings, ### for sub-section headings\n");
        sb.append("- Use bullet lists (- item) for enumerated points or observations\n");
        sb.append("- Use **bold** only for emphasis within text, NOT as section headers\n");
        sb.append("- Separate sections clearly with blank lines\n");

        if (isExplainMode) {
            sb.append("- Describe step by step what the query does: tables, joins, filters, aggregations, expected output\n");
            sb.append("- Do NOT mention query results, row counts, or whether data exists\n");
        } else if (hasData) {
            sb.append("- Provide meaningful analysis, not just data description\n");
            sb.append("- Use your understanding of the data schema to provide accurate insights\n");
            if (isTruncated && (pythonAnalysis == null || pythonAnalysis.isEmpty())) {
                sb.append("- NOTE: The query result is truncated. Showing ").append(shownRows).append(" of ").append(totalRowCount).append(" total rows. Mention that additional rows exist but only analyze visible data.\n");
            } else {
                sb.append("- NOTE: The query result below contains ALL rows returned by the query (").append(totalRowCount != null ? totalRowCount : shownRows).append(" rows total). This is the complete dataset — do NOT speculate about missing or additional data.\n");
            }
            sb.append("- Do NOT say 'data not available' if data is present in the result - check carefully before stating absence.\n");
            sb.append("\n");
            sb.append("STRICT OUTPUT RESTRICTIONS (the UI already renders the result as an interactive table AND a chart above your answer — do NOT duplicate the data):\n");
            sb.append("- NEVER include ASCII / text-art charts, bar diagrams, sparklines, or any visualization built from characters like `●`, `█`, `|`, `─`, `+`, `-`, `*`, `·`. The chart component above your answer is the only visualization. If you would normally draw a chart, write a 1–2 sentence insight about the pattern instead.\n");
            sb.append("- NEVER re-print the raw result as a markdown table, CSV, JSON, or row-by-row list (`| col | col |`, `Electronics | 7424.53`, etc.). The table component already shows every row.\n");
            sb.append("- You MAY cite at most a handful of specific numbers inline inside a sentence (e.g. \"Electronics accounts for about 66% of the total at 7,424.53\") to support an insight, but do not enumerate every row.\n");
            sb.append("- A small COMPARISON table is allowed ONLY when it adds derived information not present in the raw result (e.g. ranking + share + commentary). Pure restatement of the result is forbidden.\n");
            sb.append("- The chart's footer aggregates (Sum / Avg / Min / Max) summarize ONE column only and may not match the visualized metric. Do NOT quote those footer numbers as if they describe the chart's metric; derive your own numbers from the actual result rows when needed.\n");
            sb.append("- Focus the response on: ranking, share / proportion, notable gaps, segment-level observations, and actionable suggestions — written as prose with bullet lists, not as data dumps.\n");
        } else {
            sb.append("- Briefly explain that the query returned no matching data\n");
            sb.append("- Analyze the SQL query and suggest possible reasons why no data was returned (e.g., filter conditions too restrictive, date range mismatch, data may not exist yet)\n");
            sb.append("- Suggest 1-2 concrete alternative approaches or modified queries the user could try\n");
            sb.append("- Keep the response concise and helpful — do NOT be overly verbose\n");
        }
        sb.append("\n");

        sb.append("Question: ").append(originalQuery).append("\n\n");

        // Add business context if available (helps LLM understand business domain)
        if (businessContext != null && !businessContext.isEmpty()) {
            sb.append("=== BUSINESS CONTEXT ===\n");
            sb.append(businessContext).append("\n");
            sb.append("=== END BUSINESS CONTEXT ===\n\n");
        }

        if (additionalInsightContext != null && !additionalInsightContext.isEmpty()) {
            sb.append("=== ADDITIONAL CONTEXT FROM OTHER TOOLS ===\n");
            sb.append(additionalInsightContext).append("\n");
            sb.append("=== END ADDITIONAL CONTEXT ===\n\n");
            sb.append("When relevant, compare the query result against the additional context above. ");
            sb.append("Be explicit about what is supported by database results versus external/document context. ");
            sb.append("Do not invent a comparison if the query result does not contain the needed category, date, or metric.\n\n");
        }

        // Add schema context if available (helps LLM understand column meanings)
        if (schemaContext != null && !schemaContext.isEmpty()) {
            sb.append("Schema Context (column definitions and descriptions):\n");
            sb.append(schemaContext).append("\n\n");
        }

        // Add generated SQL for reference
        if (generatedSql != null && !generatedSql.isEmpty()) {
            sb.append("SQL Query:\n```sql\n").append(generatedSql).append("\n```\n\n");
        }

        if (isExplainMode) {
            sb.append("Explain the SQL query above:");
        } else if (hasData) {
            if (pythonAnalysis != null && !pythonAnalysis.isEmpty()) {
                sb.append("Statistical Summary:\n");
                sb.append(pythonAnalysis).append("\n\n");
            } else {
                sb.append("Query Result:\n").append(convertToMarkdownTable(sqlResult)).append("\n\n");
            }

            sb.append("Analysis:");
        } else {
            sb.append("The query returned 0 rows. Please analyze and respond:");
        }
        return sb.toString();
    }

    /**
     * Convert SQL result (List of Maps) to Markdown table format
     */
    @SuppressWarnings("unchecked")
    private String convertToMarkdownTable(Object sqlResult) {
        if (sqlResult == null) {
            return "No data";
        }

        try {
            if (sqlResult instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) sqlResult;
                if (list.isEmpty()) {
                    return "No data";
                }

                // Get first item to extract headers
                Object first = list.get(0);
                if (first instanceof java.util.Map) {
                    java.util.List<java.util.Map<String, Object>> data = 
                        (java.util.List<java.util.Map<String, Object>>) list;
                    
                    // Extract headers from first row
                    java.util.Set<String> headerSet = data.get(0).keySet();
                    java.util.List<String> headers = new java.util.ArrayList<>(headerSet);
                    
                    StringBuilder table = new StringBuilder();
                    
                    // Header row
                    table.append("| ").append(String.join(" | ", headers)).append(" |\n");
                    
                    // Separator row
                    table.append("| ");
                    for (int i = 0; i < headers.size(); i++) {
                        table.append("---");
                        if (i < headers.size() - 1) table.append(" | ");
                    }
                    table.append(" |\n");
                    
                    // Data rows (no limit - show all rows for accurate analysis)
                    for (int i = 0; i < data.size(); i++) {
                        java.util.Map<String, Object> row = data.get(i);
                        table.append("| ");
                        for (int j = 0; j < headers.size(); j++) {
                            Object value = row.get(headers.get(j));
                            String cellValue = value != null ? formatCellValue(value) : "";
                            table.append(cellValue);
                            if (j < headers.size() - 1) table.append(" | ");
                        }
                        table.append(" |\n");
                    }

                    table.append("\n**Total rows: ").append(data.size()).append("**");
                    
                    return table.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to convert to markdown table: {}", e.getMessage());
        }
        
        // Fallback to toString
        return sqlResult.toString();
    }

    /**
     * Clean SQL from markdown code blocks (```sql ... ```)
     */
    private String cleanSqlFromMarkdown(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        String cleaned = sql.trim();

        // Remove ```sql or ``` at the beginning
        if (cleaned.startsWith("```sql")) {
            cleaned = cleaned.substring(6);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        // Remove ``` at the end
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    /**
     * Extract first SQL query from streamed response.
     * Supports markdown ```sql code blocks and legacy formats.
     */
    private String extractFirstSqlFromStreamedResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // Try markdown code block format first: ```sql ... ```
        int sqlBlockStart = response.indexOf("```sql");
        if (sqlBlockStart >= 0) {
            int sqlContentStart = response.indexOf("\n", sqlBlockStart);
            if (sqlContentStart >= 0) {
                sqlContentStart++; // skip the newline
                int sqlBlockEnd = response.indexOf("```", sqlContentStart);
                if (sqlBlockEnd > sqlContentStart) {
                    String sql = response.substring(sqlContentStart, sqlBlockEnd).trim();
                    return cleanSqlFromMarkdown(sql);
                } else {
                    // Block not closed yet (streaming) - extract to end or semicolon
                    String sql = response.substring(sqlContentStart).trim();
                    int semiIdx = sql.indexOf(";\n");
                    if (semiIdx > 0) {
                        sql = sql.substring(0, semiIdx + 1);
                    } else if (sql.contains(";")) {
                        sql = sql.substring(0, sql.lastIndexOf(";") + 1);
                    }
                    return cleanSqlFromMarkdown(sql);
                }
            }
        }

        // Legacy format: Find first SQL block (after ## title)
        int titleIdx = response.indexOf("## ");
        if (titleIdx < 0) {
            return extractSqlDirectly(response);
        }

        String afterTitle = response.substring(titleIdx);
        int sqlStartIdx = -1;
        int selectIdx = afterTitle.toUpperCase().indexOf("SELECT");
        int withIdx = afterTitle.toUpperCase().indexOf("WITH");

        if (selectIdx >= 0 && withIdx >= 0) {
            sqlStartIdx = Math.min(selectIdx, withIdx);
        } else if (selectIdx >= 0) {
            sqlStartIdx = selectIdx;
        } else if (withIdx >= 0) {
            sqlStartIdx = withIdx;
        }

        if (sqlStartIdx < 0) {
            return null;
        }

        // Find SQL end (next ## or --- or **Explanation)
        String sqlPart = afterTitle.substring(sqlStartIdx);
        int nextTitleIdx = sqlPart.indexOf("\n## ");
        int separatorIdx = sqlPart.indexOf("\n---");
        int explanationIdx = sqlPart.indexOf("\n**Explanation");
        int codeBlockEnd = sqlPart.indexOf("\n```");

        int endIdx = sqlPart.length();
        if (codeBlockEnd > 0) {
            endIdx = codeBlockEnd;
        } else if (explanationIdx > 0 && explanationIdx < endIdx) {
            endIdx = explanationIdx;
        } else if (nextTitleIdx > 0 && nextTitleIdx < endIdx) {
            endIdx = nextTitleIdx;
        } else if (separatorIdx > 0 && separatorIdx < endIdx) {
            endIdx = separatorIdx;
        }

        String sql = sqlPart.substring(0, endIdx).trim();
        sql = cleanSqlFromMarkdown(sql);

        int bulletIdx = sql.indexOf("\n-");
        if (bulletIdx > 0) {
            sql = sql.substring(0, bulletIdx).trim();
        }

        return sql.isEmpty() ? null : sql;
    }

    /**
     * Fallback: extract SQL directly from response without title markers.
     */
    private String extractSqlDirectly(String response) {
        // Try markdown code block first
        int sqlBlockStart = response.indexOf("```sql");
        if (sqlBlockStart >= 0) {
            int sqlContentStart = response.indexOf("\n", sqlBlockStart);
            if (sqlContentStart >= 0) {
                sqlContentStart++;
                int sqlBlockEnd = response.indexOf("```", sqlContentStart);
                if (sqlBlockEnd > sqlContentStart) {
                    return cleanSqlFromMarkdown(response.substring(sqlContentStart, sqlBlockEnd).trim());
                }
            }
        }

        String upper = response.toUpperCase();
        int selectIdx = upper.indexOf("SELECT");
        int withIdx = upper.indexOf("WITH");

        int startIdx = -1;
        if (selectIdx >= 0 && withIdx >= 0) {
            startIdx = Math.min(selectIdx, withIdx);
        } else if (selectIdx >= 0) {
            startIdx = selectIdx;
        } else if (withIdx >= 0) {
            startIdx = withIdx;
        }

        if (startIdx < 0) {
            return null;
        }

        String sqlPart = response.substring(startIdx);

        // Find end markers
        int codeBlockEnd = sqlPart.indexOf("\n```");
        if (codeBlockEnd > 0) {
            return cleanSqlFromMarkdown(sqlPart.substring(0, codeBlockEnd).trim());
        }

        int semiIdx = sqlPart.indexOf(";\n");
        if (semiIdx > 0) {
            return cleanSqlFromMarkdown(sqlPart.substring(0, semiIdx + 1).trim());
        }

        return cleanSqlFromMarkdown(sqlPart.trim());
    }

    /**
     * Format cell value for markdown table
     */
    private String formatCellValue(Object value) {
        if (value == null) return "";
        
        if (value instanceof Number) {
            // Format numbers with commas
            if (value instanceof Double || value instanceof Float) {
                return String.format("%,.2f", ((Number) value).doubleValue());
            } else {
                return String.format("%,d", ((Number) value).longValue());
            }
        }
        
        return value.toString();
    }

    /**
     * Execute SQL endpoint - called when user clicks "Run Query" button
     */
    @PostMapping("/agent/execute")
    @CrossOrigin
    public DataResult<ai.inquery.server.domain.core.query.QueryProcessingResult> executeGeneratedSql(
            @RequestBody ai.inquery.server.web.api.controller.ai.request.SqlExecuteRequest request) {
        try {
            log.info("Executing SQL: {}", request.getSql());

            // Validate dataSourceId is present
            if (request.getDataSourceId() == null) {
                log.error("dataSourceId is null, cannot execute SQL");
                ai.inquery.server.domain.core.query.QueryProcessingResult errorResult =
                    new ai.inquery.server.domain.core.query.QueryProcessingResult();
                errorResult.setGeneratedSql(request.getSql());
                errorResult.setResultData("Error: No database connection selected. Please select a database first.");
                errorResult.setThoughtProcess("SQL execution failed: No database connection.");
                return DataResult.of(errorResult);
            }

            // Set ConnectInfo for this request (required for SQL execution)
            {
                DataResult<DataSource> dsResult = dataSourceService.queryById(request.getDataSourceId());
                if (dsResult.success() && dsResult.getData() != null) {
                    DataSource ds = dsResult.getData();
                    ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
                    connectInfo.setDataSourceId(request.getDataSourceId());
                    connectInfo.setDbType(ds.getType());
                    connectInfo.setUrl(ds.getUrl());
                    connectInfo.setUser(ds.getUserName());
                    connectInfo.setPassword(ds.getPassword());
                    connectInfo.setHost(ds.getHost());
                    connectInfo.setPort(parsePortOrNull(ds.getPort(), "executeGeneratedSql"));
                    connectInfo.setDatabase(request.getDatabaseName());
                    connectInfo.setSchemaName(request.getSchemaName());
                    connectInfo.setDriver(ds.getDriver());
                    connectInfo.setSsh(ds.getSsh());
                    connectInfo.setSsl(ds.getSsl());
                    connectInfo.setJdbc(ds.getJdbc());
                    connectInfo.setExtendInfo(ds.getExtendInfo());
                    ai.inquery.spi.sql.InqueryContext.putContext(connectInfo);
                    log.info("Set ConnectInfo for SQL execution: dbType={}, dataSourceId={}", ds.getType(), request.getDataSourceId());
                }
            }

            // Set query source to AI_CHAT for operation log tracking
            ai.inquery.spi.sql.InqueryContext.setQuerySource(ai.inquery.spi.sql.InqueryContext.SOURCE_AI_CHAT);

            // Build execute param
            ai.inquery.server.domain.api.param.DlExecuteParam executeParam = new ai.inquery.server.domain.api.param.DlExecuteParam();
            executeParam.setSql(request.getSql());
            executeParam.setDataSourceId(request.getDataSourceId());
            executeParam.setDatabaseName(request.getDatabaseName());
            executeParam.setSchemaName(request.getSchemaName());
            executeParam.setConsoleId(0L);

            // Execute SQL
            ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> executionResult =
                dlTemplateService.execute(executeParam);

            // Build result
            ai.inquery.server.domain.core.query.QueryProcessingResult result =
                new ai.inquery.server.domain.core.query.QueryProcessingResult();
            result.setOriginalQuery(request.getOriginalQuery());
            result.setGeneratedSql(request.getSql());
            result.setNeedsExecution(false);

            if (executionResult.success() && executionResult.getData() != null) {
                result.setResultData(executionResult.getData());
                result.setThoughtProcess("Query executed successfully.");

                // Recommend chart if data exists
                if (!executionResult.getData().isEmpty()) {
                    ai.inquery.spi.model.ExecuteResult firstResult = executionResult.getData().get(0);
                    ai.inquery.server.domain.core.chart.ChartRecommendationEngine chartEngine =
                        ai.inquery.server.web.api.util.ApplicationContextUtil.getBean(
                            ai.inquery.server.domain.core.chart.ChartRecommendationEngine.class);
                    if (chartEngine != null) {
                        ai.inquery.server.domain.core.chart.ChartRecommendationEngine.ChartRecommendation recommendation =
                            chartEngine.recommendChart(firstResult, request.getOriginalQuery());
                        result.setRecommendedChart(recommendation.getChartType().name());
                        result.setChartConfidence(recommendation.getConfidence());
                        result.setChartXAxis(recommendation.getXAxis());
                        result.setChartYAxis(recommendation.getYAxis());
                        result.setChartDimension(recommendation.getDimension());
                        result.setChartDimensions(recommendation.getDimensions());
                        result.setChartXAxisFormat(recommendation.getXAxisFormat());
                        result.setChartYAxisFormat(recommendation.getYAxisFormat());
                        result.setChartLineVariant(recommendation.getLineVariant());
                        result.setChartPieVariant(recommendation.getPieVariant());
                        result.setChartBarOrientation(recommendation.getBarOrientation());
                        result.setChartOrder(recommendation.getOrder());
                    }
                }
            } else {
                result.setResultData(executionResult.getErrorMessage());
                result.setThoughtProcess("Query execution failed: " + executionResult.getErrorMessage());
            }

            return DataResult.of(result);
        } catch (Exception e) {
            log.error("SQL execution failed", e);
            ai.inquery.server.domain.core.query.QueryProcessingResult errorResult =
                new ai.inquery.server.domain.core.query.QueryProcessingResult();
            errorResult.setGeneratedSql(request.getSql());
            errorResult.setResultData("Execution error: " + e.getMessage());
            errorResult.setThoughtProcess("Query execution failed due to an error.");
            return DataResult.of(errorResult);
        } finally {
            // Clean up context
            ai.inquery.spi.sql.InqueryContext.removeQuerySource();
            ai.inquery.spi.sql.InqueryContext.removeContext();
        }
    }

    /**
     * Python analysis endpoint - analyzes query results using Python.
     * Called by frontend after SQL execution (both Manual and Auto modes).
     * Writes full result data to a temp CSV, LLM generates Python code,
     * Python executes with full data, returns statistics + charts.
     */
    @PostMapping("/agent/python/analyze")
    @CrossOrigin
    public DataResult<java.util.Map<String, Object>> analyzePython(
            @RequestBody ai.inquery.server.web.api.controller.ai.request.PythonAnalyzeRequest request) {
        try {
            if (!pythonEnvironmentSetup.isReady()) {
                return DataResult.error("PYTHON_NOT_READY",
                        pythonEnvironmentSetup.getErrorMessage() != null
                                ? pythonEnvironmentSetup.getErrorMessage()
                                : "Python environment is still initializing.");
            }

            if (request.getResultData() == null || request.getResultData().isEmpty()) {
                return DataResult.error("NO_DATA", "No result data to analyze.");
            }

            log.info("[Python] Starting analysis for query: {}", request.getUserQuestion());

            // Determine model
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
            String model = preferredProvider != null ? defaultModelFor(preferredProvider) : DEFAULT_GEMINI_MODEL;

            // Build full CSV from result data
            ai.inquery.spi.model.ExecuteResult execResult = request.getResultData().get(0);
            String fullCsv = buildFullCsvFromResult(execResult);
            int rowCount = execResult.getDataList() != null ? execResult.getDataList().size() : 0;

            // Write CSV to temp file
            java.nio.file.Path csvFile = java.nio.file.Files.createTempFile("inquery-python-", ".csv");
            java.nio.file.Files.writeString(csvFile, fullCsv);

            try {
                // Build preview for LLM (header + 10 rows)
                String[] csvLines = fullCsv.split("\n");
                int previewLines = Math.min(csvLines.length, 11);
                StringBuilder preview = new StringBuilder();
                for (int i = 0; i < previewLines; i++) {
                    preview.append(csvLines[i]).append("\n");
                }
                if (csvLines.length > previewLines) {
                    preview.append("... (").append(csvLines.length - 1).append(" total rows)\n");
                }

                // LLM generates Python code, PythonTools executes with full data
                ai.inquery.server.domain.core.langchain.tools.PythonTools pythonTools =
                        new ai.inquery.server.domain.core.langchain.tools.PythonTools(pythonEnvironmentSetup);

                String prompt = "The user asked: \"" + request.getUserQuestion() + "\"\n\n"
                        + "SQL query returned " + rowCount + " rows.\n"
                        + "Data preview (first 10 rows):\n" + preview + "\n"
                        + "The FULL dataset is already loaded as DataFrame 'df' (" + rowCount + " rows). "
                        + "Produce a statistical summary relevant to the user's question. "
                        + "Print results to stdout. Do NOT create charts.";

                dev.langchain4j.model.chat.ChatModel chatModel = langChainModelProvider.getChatModel(model);
                String pythonResult = pythonTools.executePythonWithDataFile(prompt, csvFile.toString(), chatModel);

                log.info("[Python] Analysis completed, result length: {}", pythonResult != null ? pythonResult.length() : 0);

                java.util.Map<String, Object> response = new java.util.HashMap<>();
                response.put("analysis", pythonResult);
                response.put("rowCount", rowCount);
                return DataResult.of(response);

            } finally {
                try { java.nio.file.Files.deleteIfExists(csvFile); } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            log.error("[Python] Analysis failed", e);
            return DataResult.error("PYTHON_ERROR", "Python analysis failed: " + e.getMessage());
        }
    }

    /**
     * Build full CSV string from ExecuteResult.
     */
    private String buildFullCsvFromResult(ai.inquery.spi.model.ExecuteResult result) {
        java.util.List<java.util.List<String>> data = result.getDataList();
        java.util.List<ai.inquery.spi.model.Header> headers = result.getHeaderList();
        if (data == null || data.isEmpty()) return "No data";

        StringBuilder csv = new StringBuilder();
        if (headers != null && !headers.isEmpty()) {
            csv.append(headers.stream()
                    .map(h -> escapeCsvField(h.getName()))
                    .collect(java.util.stream.Collectors.joining(","))).append("\n");
        }
        for (java.util.List<String> row : data) {
            csv.append(row.stream()
                    .map(this::escapeCsvField)
                    .collect(java.util.stream.Collectors.joining(","))).append("\n");
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

    /**
     * BigQuery DRY_RUN endpoint - estimates query cost without executing
     * Uses BigQuery SDK for accurate pre-execution cost estimation
     */
    @PostMapping("/agent/bigquery/dryrun")
    @CrossOrigin
    public DataResult<Map<String, Object>> bigQueryDryRun(
            @RequestBody ai.inquery.server.web.api.controller.ai.request.SqlExecuteRequest request) {
        try {
            log.info("BigQuery DRY_RUN request for SQL: {}", request.getSql());

            if (request.getDataSourceId() == null) {
                return DataResult.error("PARAM_ERROR", "DataSource ID is required");
            }

            if (StringUtils.isBlank(request.getSql())) {
                return DataResult.error("PARAM_ERROR", "SQL query is required");
            }

            // Get DataSource info
            DataResult<DataSource> dsResult = dataSourceService.queryById(request.getDataSourceId());
            if (!dsResult.success() || dsResult.getData() == null) {
                return DataResult.error("NOT_FOUND", "DataSource not found");
            }

            DataSource ds = dsResult.getData();
            
            // Verify it's BigQuery
            if (!"BIGQUERY".equalsIgnoreCase(ds.getType())) {
                return DataResult.error("INVALID_TYPE", "DRY_RUN is only supported for BigQuery");
            }

            // Build ConnectInfo
            ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
            connectInfo.setDataSourceId(request.getDataSourceId());
            connectInfo.setDbType(ds.getType());
            connectInfo.setExtendInfo(ds.getExtendInfo());

            // Get BigQueryDBManage and execute dryRun
            ai.inquery.plugin.bigquery.BigQueryDBManage bigQueryManager = 
                new ai.inquery.plugin.bigquery.BigQueryDBManage();
            
            Map<String, Object> result = bigQueryManager.dryRun(connectInfo, request.getSql());
            
            return DataResult.of(result);

        } catch (Exception e) {
            log.error("BigQuery DRY_RUN failed", e);
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", e.getMessage());
            return DataResult.of(errorResult);
        }
    }


    /**
     * Agent Chat Interface with SSE Streaming (real-time reasoning and content)
     * Sends events: reasoning, content, response, done
     * Supports conversation context via conversationId
     *
     * When executeQuery=true (Auto mode), uses LangChain4j Agent with Tool calling
     * When executeQuery=false (Manual mode), generates SQL only without execution
     */
    @GetMapping(value = "/agent/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @CrossOrigin(origins = "*", allowedHeaders = "*")
    public SseEmitter chatWithAgentStreamGet(ChatQueryRequest queryRequestWrapper, @RequestHeader Map<String, String> headers, HttpServletResponse response) {
        return chatWithAgentStream(queryRequestWrapper, headers, response);
    }

    @PostMapping(value = "/agent/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @CrossOrigin(origins = "*", allowedHeaders = "*")
    public SseEmitter chatWithAgentStreamPost(@RequestBody ChatQueryRequest queryRequestWrapper, @RequestHeader Map<String, String> headers, HttpServletResponse response) {
        return chatWithAgentStream(queryRequestWrapper, headers, response);
    }

    private SseEmitter chatWithAgentStream(ChatQueryRequest queryRequestWrapper, Map<String, String> headers, HttpServletResponse response) {
        // Disable buffering for real-time SSE streaming
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(CHAT_TIMEOUT);

        // Track active tool approval request IDs so they can be cancelled when the SSE connection dies
        final java.util.Set<String> activeApprovalRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        emitter.onCompletion(() -> {
            if (!activeApprovalRequestIds.isEmpty()) {
                log.info("SSE connection completed, cancelling {} pending approval(s)", activeApprovalRequestIds.size());
                toolApprovalManager.cancelAll(activeApprovalRequestIds);
                activeApprovalRequestIds.clear();
            }
        });
        emitter.onError(e -> {
            if (!activeApprovalRequestIds.isEmpty()) {
                log.info("SSE connection error, cancelling {} pending approval(s): {}", activeApprovalRequestIds.size(), e.getMessage());
                toolApprovalManager.cancelAll(activeApprovalRequestIds);
                activeApprovalRequestIds.clear();
            }
        });

        String model = queryRequestWrapper.getModel();
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);

        // Short-circuit: no usable provider -> reply with a same-language
        //   notice that links to /setting, then close the SSE stream
        //   without spinning up the background runner. Without this guard
        //   the request would either hit a Gemini "no API key" 400 or, if
        //   only OpenAI is left, burn the OpenAI quota for a trivial greeting.
        if (preferredProvider == null) {
            boolean hasAnyKey = hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient.OPENAI_KEY)
                || hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.CLAUDE_API_KEY)
                || hasConfiguredKey(configService, ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.GEMINI_API_KEY);
            NoProviderReason reason = hasAnyKey ? NoProviderReason.ALL_DISABLED : NoProviderReason.NO_KEY;
            String friendly = noProviderConfiguredMessage(queryRequestWrapper.getMessage(), reason);
            log.info("[chatWithAgentStream] short-circuiting: no usable AI provider ({}). Sending settings notice.", reason);
            try {
                emitter.send(SseEmitter.event().name("content").data(JSON.toJSONString(friendly)));
                emitter.send(SseEmitter.event().name("done").data("done"));
                response.flushBuffer();
            } catch (Exception e) {
                log.warn("Failed to emit no-provider notice over SSE: {}", e.getMessage());
            }
            // Persist into chat history so the notice survives a reload
            //   exactly like a normal assistant reply does.
            try {
                ai.inquery.server.domain.core.query.QueryProcessingResult fakeResult =
                    new ai.inquery.server.domain.core.query.QueryProcessingResult();
                fakeResult.setAiMessage(friendly);
                saveConversationHistory(
                    queryRequestWrapper.getConversationId(),
                    queryRequestWrapper.getMessage(),
                    fakeResult);
            } catch (Exception e) {
                log.warn("Failed to persist no-provider notice to conversation history: {}", e.getMessage());
            }
            try { emitter.complete(); } catch (Exception ignore) { /* already closed */ }
            return emitter;
        }

        // Always use the product-default model for the selected provider.
        model = defaultModelFor(preferredProvider);

        final String finalModel = model;
        final String conversationId = queryRequestWrapper.getConversationId();
        final String userMessage = queryRequestWrapper.getMessage();
        final Boolean executeQuery = queryRequestWrapper.getExecuteQuery() != null && queryRequestWrapper.getExecuteQuery();
        final Boolean skipClarification = queryRequestWrapper.getSkipClarification() != null && queryRequestWrapper.getSkipClarification();

        // Capture current thread's context to pass to background thread
        final ai.inquery.server.tools.common.model.Context currentContext =
            ai.inquery.server.tools.common.util.ContextUtils.queryContext();

        // Get dbType from dataSourceId before starting background thread
        final String dbType = getDbTypeFromDataSourceId(queryRequestWrapper.getDataSourceId());

        // Use frontend-provided conversation history (reliable), fall back to cache (legacy)
        final java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> conversationHistory;
        if (queryRequestWrapper.getConversationHistory() != null && !queryRequestWrapper.getConversationHistory().isEmpty()) {
            conversationHistory = queryRequestWrapper.getConversationHistory().stream()
                .map(msg -> {
                    var cm = new ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage();
                    cm.setRole(msg.getRole());
                    cm.setContent(msg.getContent());
                    cm.setGeneratedSql(msg.getGeneratedSql());
                    return cm;
                })
                .collect(java.util.stream.Collectors.toList());
            log.info("Using frontend-provided conversation history: {} messages for conversationId: {}",
                conversationHistory.size(), conversationId);
        } else {
            conversationHistory = loadConversationHistory(conversationId);
            log.info("Fallback to cache-based conversation history: {} messages for conversationId: {}",
                conversationHistory != null ? conversationHistory.size() : 0, conversationId);
        }
        log.info("Auto-Execute mode: {}", executeQuery);

        // Heartbeat to keep SSE connection alive during long processing
        java.util.concurrent.ScheduledExecutorService heartbeat =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (Exception e) {
                // Client disconnected, stop heartbeat
                heartbeat.shutdown();
            }
        }, 10, 15, java.util.concurrent.TimeUnit.SECONDS);

        // Process in background thread
        new Thread(() -> {
            try {
                // Restore context in new thread
                if (currentContext != null) {
                    ai.inquery.server.tools.common.util.ContextUtils.setContext(currentContext);
                }
                // Initialize DB session for this thread
                ai.inquery.server.domain.repository.Dbutils.setSession();

                // Set complete ConnectInfo for SQL execution (required for Deep Agent mode)
                if (queryRequestWrapper.getDataSourceId() != null) {
                    DataResult<DataSource> dsResult = dataSourceService.queryById(queryRequestWrapper.getDataSourceId());
                    if (dsResult.success() && dsResult.getData() != null) {
                        DataSource ds = dsResult.getData();
                        ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
                        connectInfo.setDataSourceId(queryRequestWrapper.getDataSourceId());
                        connectInfo.setDbType(ds.getType());
                        connectInfo.setUrl(ds.getUrl());
                        connectInfo.setUser(ds.getUserName());
                        connectInfo.setPassword(ds.getPassword());
                        connectInfo.setHost(ds.getHost());
                        connectInfo.setPort(parsePortOrNull(ds.getPort(), "chatWithAgentStream"));
                        connectInfo.setDatabase(queryRequestWrapper.getDatabaseName());
                        connectInfo.setSchemaName(queryRequestWrapper.getSchemaName());
                        connectInfo.setDriver(ds.getDriver());
                        connectInfo.setSsh(ds.getSsh());
                        connectInfo.setSsl(ds.getSsl());
                        connectInfo.setJdbc(ds.getJdbc());
                        connectInfo.setExtendInfo(ds.getExtendInfo());
                        ai.inquery.spi.sql.InqueryContext.putContext(connectInfo);
                        log.info("Set complete ConnectInfo for SQL execution: dbType={}, dataSourceId={}", ds.getType(), queryRequestWrapper.getDataSourceId());
                    } else if (dbType != null && !dbType.isEmpty()) {
                        // Fallback to minimal ConnectInfo if DataSource lookup fails
                        ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
                        connectInfo.setDbType(dbType);
                        connectInfo.setDataSourceId(queryRequestWrapper.getDataSourceId());
                        ai.inquery.spi.sql.InqueryContext.putContext(connectInfo);
                        log.warn("Set minimal ConnectInfo (DataSource lookup failed): dbType={}", dbType);
                    }
                }

                // Set query source to AI_CHAT for operation log tracking
                ai.inquery.spi.sql.InqueryContext.setQuerySource(ai.inquery.spi.sql.InqueryContext.SOURCE_AI_CHAT);

                ai.inquery.server.domain.core.query.QueryProcessingResult result;

                // Parse excludedTables from JSON string (sent by frontend)
                String excludedTablesJson = queryRequestWrapper.getExcludedTables();
                java.util.List<String> excludedTablesList = null;
                if (excludedTablesJson != null && !excludedTablesJson.isEmpty()) {
                    try {
                        excludedTablesList = com.alibaba.fastjson2.JSON.parseArray(excludedTablesJson, String.class);
                        log.info("Parsed {} excluded tables from request", excludedTablesList.size());
                    } catch (Exception e) {
                        log.error("Failed to parse excludedTables JSON: {}", excludedTablesJson, e);
                    }
                }

                // Single-path routing — agentMode/executeQuery are now
                // informational only. Auto (executeQuery=true) and Manual (executeQuery=false)
                // both go through the root agent; Manual just suppresses resultData/chart
                // so the frontend can keep its "show SQL, run later" UX.
                log.info("[chatWithAgentStream] Routing through InqueryRootAgent (executeQuery={})", executeQuery);
                result = runWithRootAgent(
                        userMessage, finalModel, queryRequestWrapper, conversationHistory,
                        emitter, response, activeApprovalRequestIds, executeQuery);

                // SSE content emission policy:
                //   * Data-query path (overview + query-option markdown) is
                //     streamed token-by-token from the Runner via the
                //     contentTokenCallback we passed in. No final content
                //     event is needed — that would render the same
                //     markdown twice in the Svelte client.
                //   * Non-data paths (small-talk reply, date-picker
                //     prompt, disambiguation prompt, etc.) produced no
                //     stream, so we emit the full message once here.
                boolean alreadyStreamed = result.getOverview() != null
                        || (result.getQueries() != null && !result.getQueries().isEmpty());
                String chatBody = result.getAiMessage();
                if (!alreadyStreamed && chatBody != null && !chatBody.isBlank()) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("content")
                                .data(JSON.toJSONString(chatBody)));
                        response.flushBuffer();
                    } catch (Exception e) {
                        log.warn("Failed to emit root-agent content event: {}", e.getMessage());
                    }
                }
                // Save conversation history with new message and response
                saveConversationHistory(conversationId, userMessage, result);

                // Send disambiguation event if query intent is ambiguous
                if (result.isNeedsDisambiguation() && result.getDisambiguationOptions() != null) {
                    log.info("[Disambiguation] Sending disambiguation options to frontend: {} options", 
                        result.getDisambiguationOptions().size());
                    emitter.send(SseEmitter.event()
                        .name("disambiguation")
                        .data(JSON.toJSONString(result.getDisambiguationOptions())));
                    response.flushBuffer();
                }

                // Send final response (with full result data)
                emitter.send(SseEmitter.event()
                    .name("response")
                    .data(JSON.toJSONString(result)));

                // Send done
                emitter.send(SseEmitter.event()
                    .name("done")
                    .data("complete"));

                emitter.complete();
            } catch (Exception e) {
                // Check if this is a client disconnect (Broken pipe / ClientAbortException)
                if (isClientAbortException(e)) {
                    log.debug("Client disconnected during SSE streaming (Broken pipe): {}", e.getMessage());
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignored) {}
                } else {
                    log.error("Streaming error", e);
                    // Never forward raw framework exception text to the
                    // client — the frontend persists SSE error payloads
                    // into the chat history. Use the same friendly,
                    // language-matched translation as the in-runner catch.
                    String userFacing = (e instanceof RuntimeException)
                            ? friendlyAgentFailureMessage((RuntimeException) e, userMessage)
                            : friendlyAgentFailureMessage(new RuntimeException(e.getMessage()), userMessage);
                    try {
                        emitter.send(SseEmitter.event()
                            .name("error")
                            .data(userFacing));
                        emitter.complete();
                    } catch (Exception ignored) {
                        // Client likely disconnected, just complete with error
                        try {
                            emitter.completeWithError(e);
                        } catch (Exception ignored2) {}
                    }
                }
            } finally {
                // Stop heartbeat
                heartbeat.shutdown();
                // Clean up thread context and DB session
                ai.inquery.server.domain.repository.Dbutils.removeSession();
                ai.inquery.server.tools.common.util.ContextUtils.removeContext();
                ai.inquery.spi.sql.InqueryContext.removeQuerySource();
                ai.inquery.spi.sql.InqueryContext.removeContext();
            }
        }).start();

        return emitter;
    }

    /**
     * Fetches business insights for the given dataSourceId and databaseName.
     * Returns null if not configured or error occurs.
     */
    private String getBusinessInsightContext(Long dataSourceId, String databaseName) {
        if (dataSourceId == null) {
            return null;
        }
        try {
            // Try with specific database name first
            ai.inquery.server.domain.api.model.BusinessInsightDTO insight =
                businessInsightService.getInsight(dataSourceId, databaseName != null ? databaseName : "");
            if (insight != null && insight.getInsightContent() != null && !insight.getInsightContent().isEmpty()) {
                log.info("Found business insight for dataSourceId: {}, databaseName: {} ({} chars)",
                    dataSourceId, databaseName, insight.getInsightContent().length());
                return insight.getInsightContent();
            }
            // If not found with database name, try with empty string (global for dataSource)
            if (databaseName != null && !databaseName.isEmpty()) {
                insight = businessInsightService.getInsight(dataSourceId, "");
                if (insight != null && insight.getInsightContent() != null && !insight.getInsightContent().isEmpty()) {
                    log.info("Found global business insight for dataSourceId: {} ({} chars)",
                        dataSourceId, insight.getInsightContent().length());
                    return insight.getInsightContent();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get business insight for dataSourceId {}: {}", dataSourceId, e.getMessage());
        }
        return null;
    }

    /**
     * Check if an exception is caused by client disconnection (Broken pipe / ClientAbortException).
     */
    private boolean isClientAbortException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * chat timeout
     */
    private static final Long CHAT_TIMEOUT = Duration.ofMinutes(50).toMillis();

    /**
     * Maximum prompt size guard (estimated tokens).
     *
     * NOTE: This is NOT the model's real token limit.
     * We estimate tokens as: chars / TOKEN_CONVERT_CHAR_LENGTH (default 4).
     * Some requests (e.g. workspace SQL generation) include schema/context and can exceed the old default.
     */
    @Value("${chatgpt.prompt.maxTokensEstimate:12000}")
    private Integer MAX_PROMPT_LENGTH;

    /**
     * token conversion string length
     */
    @Value("${chatgpt.prompt.tokenChars:4}")
    private Integer TOKEN_CONVERT_CHAR_LENGTH;

    /**
     * Return token size
     */
    private Integer RETURN_TOKEN_LENGTH = 150;


    /**
     * Custom model streaming output interface DEMO
     * <p>
     *     Note: For custom AI that uses its own local streaming output, the interface input and output must be consistent with this sample.
     * </p>
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    @PostMapping("/custom/stream/chat")
    @CrossOrigin
    public SseEmitter customChat(@RequestBody ChatRequest queryRequest) throws IOException {
        SseEmitter emitter = new SseEmitter(CHAT_TIMEOUT);

        // Set event handler for SSEEmitter
        emitter.onCompletion(() -> log.info(LocalDateTime.now() + ", on completion"));
        emitter.onTimeout(() -> {
            log.info(LocalDateTime.now() + ", uid# on timeout");
            emitter.complete();
        });

        // Start a new thread to generate SSE events
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    emitter.send(SseEmitter.event().name("message").data("Event " + i));
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    /**
     * Custom model non-streaming output interface DEMO
     * <p>
     *       Note: Use your own local flying flow output to customize the AI. The interface input and output must be consistent with this sample.
     * </p>
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    @PostMapping("/custom/non/stream/chat")
    @CrossOrigin
    public String customNonStreamChat(@RequestBody ChatRequest queryRequest) throws IOException {
        String data = "The custom AI sample interface is connected successfully! ! ! !";
        return data;
    }

    /**
     * SQL conversion model
     *
     * @param queryRequest
     * @param headers
     * @return
     * @throws IOException
     */
    @GetMapping("/chat")
    @CrossOrigin
    public SseEmitter completions(ChatQueryRequest queryRequest, @RequestHeader Map<String, String> headers,
                                  @RequestParam(required = false) String uid)
        throws IOException {
        //The default timeout is 30 seconds. If set to 0L, it will never timeout.
        SseEmitter sseEmitter = new SseEmitter(CHAT_TIMEOUT);

        // uid from query param or header (EventSource does not support custom headers)
        String uidValue = uid;
        if (StrUtil.isBlank(uidValue)) {
            uidValue = headers.get("uid");
        }
        if (StrUtil.isBlank(uidValue)) {
            throw new ParamBusinessException("uid");
        }

        //Prompt message cannot be empty
        if (StringUtils.isBlank(queryRequest.getMessage())) {
            throw new ParamBusinessException("message");
        }

        return distributeAISql(queryRequest, sseEmitter, uidValue);
    }

    /**
     * distribute with different AI
     *
     * @return
     */
    public SseEmitter distributeAISql(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        // If at least one provider key is configured, we auto-pick provider by priority:
        // Gemini > Claude > OpenAI
        AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
        if (preferredProvider != null) {
            // Force default model for the selected provider (per product rule)
            queryRequest.setModel(defaultModelFor(preferredProvider));
            uid = preferredProvider.getCode() + uid;
            switch (preferredProvider) {
                case OPENAI:
                    return chatWithOpenAi(queryRequest, sseEmitter, uid);
                case GEMINI:
                    return chatWithGemini(queryRequest, sseEmitter, uid);
                case CLAUDEAI:
                    return chatWithClaudeAi(queryRequest, sseEmitter, uid);
                default:
                    break;
            }
        }

        // Legacy routing (when no keys are configured)
        Config config = configService.find("ai.sql.source").getData();
        String aiSqlSource = AiSqlSourceEnum.OPENAI.getCode();
        if (Objects.nonNull(config)) {
            aiSqlSource = config.getContent();
        }
        
        // Auto-detect provider based on model name (if model is provided in request)
        String requestedModel = queryRequest.getModel();
        log.info("🔍 Model Debug - Requested model from frontend: '{}'", requestedModel);
        if (requestedModel != null && !requestedModel.isEmpty()) {
            String modelLower = requestedModel.toLowerCase();
            if (modelLower.startsWith("gemini")) {
                aiSqlSource = AiSqlSourceEnum.GEMINI.getCode();
                log.info("Auto-detected Gemini provider from model: {}", requestedModel);
            } else if (modelLower.startsWith("gpt") || modelLower.contains("openai")) {
                aiSqlSource = AiSqlSourceEnum.OPENAI.getCode();
                log.info("Auto-detected OpenAI provider from model: {}", requestedModel);
            } else if (modelLower.startsWith("claude")) {
                aiSqlSource = AiSqlSourceEnum.CLAUDEAI.getCode();
                log.info("Auto-detected Claude provider from model: {}", requestedModel);
            }
        }
        
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            aiSqlSourceEnum = AiSqlSourceEnum.OPENAI;
        }
        uid = aiSqlSourceEnum.getCode() + uid;
        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI :
                return chatWithOpenAi(queryRequest, sseEmitter, uid);
            case GEMINI:
                return chatWithGemini(queryRequest, sseEmitter, uid);
            case CLAUDEAI:
                return chatWithClaudeAi(queryRequest, sseEmitter, uid);
        }
        return chatWithOpenAi(queryRequest, sseEmitter, uid);
    }

    /**
     * Using the OPENAI SQL interface
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithOpenAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid)
        throws IOException {
        String prompt = buildPrompt(queryRequest);
        int estimatedTokens = prompt.length() / TOKEN_CONVERT_CHAR_LENGTH;
        if (estimatedTokens > MAX_PROMPT_LENGTH) {
            log.error("Prompt too long (estimated). maxTokensEstimate: {}, estimatedTokens: {}, chars: {}",
                MAX_PROMPT_LENGTH, estimatedTokens, prompt.length());
            throw new ParamBusinessException();
        }

        prompt = prompt.replaceAll("#", "");
        log.info("OpenAI Prompt: {}", prompt);
        log.info("🔍 Context Debug - UID: {}, contextLength: {}", uid, contextLength);
        
        // Load conversation history from cache (for context support)
        List<Map<String, String>> messages = new ArrayList<>();
        Object cachedMessages = LocalCache.CACHE.get(uid);
        log.info("🔍 Context Debug - Cached messages found: {}", cachedMessages != null);
        if (cachedMessages != null) {
            // Parse JSON string back to List
            String messagesJson = (String)cachedMessages;
            @SuppressWarnings("unchecked")
            List<Map> rawMessages = JSONUtil.toList(messagesJson, Map.class);
            if (CollectionUtils.isNotEmpty(rawMessages)) {
                // Convert to properly typed list
                for (Map rawMap : rawMessages) {
                    Map<String, String> typedMap = new java.util.HashMap<>();
                    rawMap.forEach((k, v) -> typedMap.put(String.valueOf(k), String.valueOf(v)));
                    messages.add(typedMap);
                }
                // Keep only recent messages based on contextLength
                int recentStartIndex = Math.max(0, messages.size() - contextLength + 1);
                messages = new ArrayList<>(messages.subList(recentStartIndex, messages.size()));
                log.info("Loaded {} previous messages from cache for context (out of {} total)", 
                    messages.size(), rawMessages.size());
            }
        }
        
        // Add current user message
        Map<String, String> userMessage = new java.util.HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        
        log.info("Total messages (including current) being sent to OpenAI: {}", messages.size());
        
        buildSseEmitter(sseEmitter, uid);

        // Use Official OpenAI Java SDK
        OfficialOpenAIClient client = OfficialOpenAIClient.getInstance();
        
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config apiKeyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
        Config apiHostConfig = configService.find(OpenAIClient.OPENAI_HOST).getData();
        Config modelConfig = configService.find(OpenAIClient.OPENAI_MODEL).getData();
        
        String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
        String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";
        
        // Use model from request if provided, otherwise use config or default
        String model = queryRequest.getModel();
        if (model == null || model.isEmpty()) {
            model = modelConfig != null ? modelConfig.getContent() : DEFAULT_OPENAI_MODEL;
        }
        
        // Always refresh client with current model (allows per-request model selection)
        client.refresh(apiKey, apiHost, model);
        log.info("OpenAI client configured - Model: {}, API Host: {}, Source: {}", 
            model, apiHost, queryRequest.getModel() != null ? "request" : "config");
        
        log.info("Requesting OpenAI API - Model: {}, API Host: {}", 
            client.getModel(), client.getApiHost());
        
        // Stream chat completion and get full response
        String assistantResponse = client.streamChatCompletion(messages, sseEmitter);
        
        // Add assistant's response to messages for context
        if (assistantResponse != null && !assistantResponse.isEmpty()) {
            Map<String, String> assistantMessage = new java.util.HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", assistantResponse);
            messages.add(assistantMessage);
            log.info("Added assistant response to cache, total messages: {}", messages.size());
        }
        
        // Save conversation history with both user and assistant messages
        String jsonToSave = JSONUtil.toJsonStr(messages);
        LocalCache.CACHE.put(uid, jsonToSave, LocalCache.TIMEOUT);
        log.info("🔍 Context Debug - Saved conversation to cache with {} messages for UID: {}", messages.size(), uid);
        log.info("Saved conversation to cache with {} messages", messages.size());
        
        return sseEmitter;
    }

    /**
     * Using the Google Gemini AI interface
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithGemini(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid)
        throws IOException {
        String prompt = buildPrompt(queryRequest);
        int estimatedTokens = prompt.length() / TOKEN_CONVERT_CHAR_LENGTH;
        if (estimatedTokens > MAX_PROMPT_LENGTH) {
            log.error("Prompt too long (estimated). maxTokensEstimate: {}, estimatedTokens: {}, chars: {}",
                MAX_PROMPT_LENGTH, estimatedTokens, prompt.length());
            throw new ParamBusinessException();
        }

        prompt = prompt.replaceAll("#", "");
        log.info("Gemini Prompt: {}", prompt);
        log.info("🔍 Context Debug - UID: {}, contextLength: {}", uid, contextLength);
        
        // Load conversation history from cache (for context support)
        List<Map<String, String>> messages = new ArrayList<>();
        Object cachedMessages = LocalCache.CACHE.get(uid);
        if (cachedMessages != null) {
            try {
                String messagesJson = (String) cachedMessages;
                @SuppressWarnings("unchecked")
                List<Map> rawMessages = JSONUtil.toList(messagesJson, Map.class);
                
                for (Map rawMsg : rawMessages) {
                    Map<String, String> msg = new java.util.HashMap<>();
                    msg.put("role", (String) rawMsg.get("role"));
                    msg.put("content", (String) rawMsg.get("content"));
                    messages.add(msg);
                }
                log.info("🔍 Context Debug - Cached messages found: {}", messages.size());
            } catch (Exception e) {
                log.error("Failed to parse cached messages: {}", e.getMessage());
                messages = new ArrayList<>();
            }
        }
        
        // Apply context limit
        if (messages.size() > contextLength) {
            messages = messages.subList(messages.size() - contextLength, messages.size());
        }
        
        // Add current user message
        Map<String, String> userMessage = new java.util.HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        
        log.info("Total messages (including current) being sent to Gemini: {}", messages.size());
        
        buildSseEmitter(sseEmitter, uid);

        // Use Google Gemini AI Client
        GeminiAIClient client = GeminiAIClient.getInstance();
        
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config apiKeyConfig = configService.find(GeminiAIClient.GEMINI_API_KEY).getData();
        Config apiHostConfig = configService.find(GeminiAIClient.GEMINI_HOST).getData();
        Config modelConfig = configService.find(GeminiAIClient.GEMINI_MODEL).getData();
        
        String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
        String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";
        
        // Use model from request if provided, otherwise use config or default
        String model = queryRequest.getModel();
        if (model == null || model.isEmpty()) {
            model = modelConfig != null ? modelConfig.getContent() : ModelMapper.getDefaultPrimaryModel();
        }
        
        // Map model names to correct API model names
        // Always refresh client with current model (allows per-request model selection)
        client.refresh(apiKey, apiHost, model);
        log.info("Gemini client configured - Model: {}, API Host: {}, Source: {}", 
            model, apiHost, queryRequest.getModel() != null ? "request" : "config");
        
        log.info("Requesting Gemini API - Model: {}, API Host: {}", 
            client.getModel(), client.getApiHost());
        
        // Stream chat completion and get full response
        String assistantResponse = client.streamChatCompletion(messages, sseEmitter);

        // Add assistant's response to messages for context
        if (assistantResponse != null && !assistantResponse.isEmpty()) {
            Map<String, String> assistantMessage = new java.util.HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", assistantResponse);
            messages.add(assistantMessage);

            log.info("Gemini Full Response: {}", assistantResponse);
        }

        // Save conversation history to cache
        String jsonToSave = JSONUtil.toJsonStr(messages);
        LocalCache.CACHE.put(uid, jsonToSave, LocalCache.TIMEOUT);
        log.info("🔍 Context Debug - Saved conversation to cache with {} messages for UID: {}", messages.size(), uid);

        // Send [DONE] and complete SSE connection (matching OpenAI/Claude format)
        sseEmitter.send(SseEmitter.event().id("[DONE]").data("[DONE]").reconnectTime(3000));
        sseEmitter.complete();

        return sseEmitter;
    }

    /**
     * get fast chat message
     *
     * @param uid
     * @param prompt
     * @return
     */
    private List<FastChatMessage> getFastChatMessage(String uid, String prompt) {
        Object cached = LocalCache.CACHE.get(uid);
        List<FastChatMessage> messages;
        
        if (cached instanceof List) {
            messages = (List<FastChatMessage>) cached;
            if (CollectionUtils.isNotEmpty(messages)) {
                if (messages.size() >= contextLength) {
                    messages = messages.subList(1, contextLength);
                }
            } else {
                messages = Lists.newArrayList();
            }
        } else if (cached instanceof String) {
            // Handle case where cache contains JSON string (legacy format)
            try {
                messages = JSONUtil.toList((String) cached, FastChatMessage.class);
                if (messages == null) {
                    messages = Lists.newArrayList();
                }
            } catch (Exception e) {
                log.warn("Failed to parse cached messages from JSON string: {}", e.getMessage());
                messages = Lists.newArrayList();
            }
        } else {
            messages = Lists.newArrayList();
        }
        
        FastChatMessage currentMessage = new FastChatMessage(FastChatRole.USER).setContent(prompt);
        messages.add(currentMessage);
        return messages;
    }


    /**
     * chat with claude ai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithClaudeAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        // Add system message with model identity (similar to claude.ai website)
        String requestedModel = queryRequest.getModel();
        String modelName = requestedModel != null ? requestedModel : DEFAULT_CLAUDE_MODEL;

        // Insert system message at the beginning if not already present
        boolean hasSystemMessage = messages.stream().anyMatch(m -> m.getRole() == FastChatRole.SYSTEM);
        if (!hasSystemMessage) {
            FastChatMessage systemMessage = new FastChatMessage(FastChatRole.SYSTEM);
            systemMessage.setContent("You are " + modelName + ", an AI assistant made by Anthropic. You are helpful, harmless, and honest.");
            messages.add(0, systemMessage);
        }

        buildSseEmitter(sseEmitter, uid);

        ClaudeAIEventSourceListener sourceListener = new ClaudeAIEventSourceListener(sseEmitter);
        // Pass the requested model from frontend to override default model
        // streamCompletions now returns the full response text using MessageAccumulator
        String assistantResponse = ClaudeAIClient.getInstance().streamCompletions(messages, sourceListener, requestedModel);

        // Add assistant's response to messages for context (multi-turn conversation support)
        if (assistantResponse != null && !assistantResponse.isEmpty()) {
            FastChatMessage assistantMessage = new FastChatMessage(FastChatRole.ASSISTANT);
            assistantMessage.setContent(assistantResponse);
            messages.add(assistantMessage);
            log.info("Claude: Added assistant response to cache, total messages: {}", messages.size());
        }

        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * construct sseEmitter
     *
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter buildSseEmitter(SseEmitter sseEmitter, String uid) throws IOException {
        sseEmitter.send(SseEmitter.event().id(uid).name("connect successfully!!!!").data(LocalDateTime.now()).reconnectTime(3000));
        sseEmitter.onCompletion(() -> {
            log.info(LocalDateTime.now() + ", uid#" + uid + ", on completion");
        });
        sseEmitter.onTimeout(
            () -> log.info(LocalDateTime.now() + ", uid#" + uid + ", on timeout#" + sseEmitter.getTimeout()));
        sseEmitter.onError(
            throwable -> {
                try {
                    log.info(LocalDateTime.now() + ", uid#" + "765431" + ", on error#" + throwable.toString());
                    sseEmitter.send(SseEmitter.event().id("765431").name("An exception occurs!").data(throwable.getMessage())
                        .reconnectTime(3000));
                } catch (IOException e) {
                    log.error("An exception occurs!{}", e.getMessage(), e);
                }
            }
        );
        return sseEmitter;
    }

    /**
     * Build schema parameters
     *
     * @param tableQueryParam
     * @param tableNames
     * @return
     */
    private String buildTableColumn(TableQueryParam tableQueryParam,
        List<String> tableNames) {
        if (CollectionUtils.isEmpty(tableNames)) {
            return "";
        }
        List<String> schemaContent = Lists.newArrayList();
        try {
             schemaContent = tableNames.stream().map(tableName -> {
                tableQueryParam.setTableName(tableName);
                return queryTableDdl(tableName, tableQueryParam);
            }).collect(Collectors.toList());
        } catch (Exception exception) {
            log.error("query table error, do nothing");
        }

        return JSON.toJSONString(schemaContent);
    }

    /**
     * query table schema
     *
     * @param tableName
     * @param request
     * @return
     */
    private String queryTableDdl(String tableName, TableQueryParam request) {
        ShowCreateTableParam param = new ShowCreateTableParam();
        param.setDataSourceId(request.getDataSourceId());

        // Handle fully qualified table names (e.g., DATABASE.SCHEMA.TABLE)
        String[] parts = tableName.split("\\.");
        if (parts.length == 3) {
            // database.schema.table format
            param.setDatabaseName(parts[0]);
            param.setSchemaName(parts[1]);
            param.setTableName(parts[2]);
            log.info("Parsed fully qualified table name: database={}, schema={}, table={}", parts[0], parts[1], parts[2]);
        } else if (parts.length == 2) {
            // schema.table format
            param.setDatabaseName(request.getDatabaseName());
            param.setSchemaName(parts[0]);
            param.setTableName(parts[1]);
            log.info("Parsed schema-qualified table name: schema={}, table={}", parts[0], parts[1]);
        } else {
            // simple table name
            param.setTableName(tableName);
            param.setDatabaseName(request.getDatabaseName());
            param.setSchemaName(request.getSchemaName());
        }
        DataResult<String> tableSchema = tableService.showCreateTable(param);
        String ddl = tableSchema.getData();

        // Replace table name with fully qualified name (database.schema.table) in DDL
        if (ddl != null && StringUtils.isNotBlank(param.getDatabaseName()) && StringUtils.isNotBlank(param.getSchemaName())) {
            String qualifiedTableName = param.getDatabaseName() + "." + param.getSchemaName() + "." + param.getTableName();
            // Replace "TABLE tableName" with "TABLE database.schema.tableName"
            ddl = ddl.replaceAll("(?i)(TABLE\\s+)" + java.util.regex.Pattern.quote(param.getTableName()), "$1" + qualifiedTableName);
        }

        return ddl;
    }

    /**
     * build prompt
     *
     * @param queryRequest
     * @return
     */
    private String buildPrompt(ChatQueryRequest queryRequest) {
        if (PromptType.TEXT_GENERATION.getCode().equals(queryRequest.getPromptType())) {
            return queryRequest.getMessage();
        }

        // For ChatRobot (general chat) without database, return simple message
        if ("ChatRobot".equals(queryRequest.getPromptType()) && queryRequest.getDataSourceId() == null) {
            return queryRequest.getMessage();
        }

        // If no dataSourceId provided, return simple message for general chat
        if (queryRequest.getDataSourceId() == null) {
            return queryRequest.getMessage();
        }

        // Query schema information
        String dataSourceType = queryDatabaseType(queryRequest);
        String properties = "";
        if (CollectionUtils.isNotEmpty(queryRequest.getTableNames())) {
            // Explicit table names provided (e.g., parsed from selected SQL)
            // Try Pinecone by table name first, then DDL fallback
            properties = mappingDatabaseSchemaByTableNames(queryRequest);
            if (StringUtils.isBlank(properties)) {
                log.info("Pinecone table name search returned empty, falling back to DDL for tables: {}", queryRequest.getTableNames());
                TableQueryParam queryParam = chatConverter.chat2tableQuery(queryRequest);
                properties = buildTableColumn(queryParam, queryRequest.getTableNames());
            }
        } else {
            properties = mappingDatabaseSchema(queryRequest);
        }
        String prompt = queryRequest.getMessage();
        String promptType = StringUtils.isBlank(queryRequest.getPromptType()) ? PromptType.NL_2_SQL.getCode()
            : queryRequest.getPromptType();
        PromptType pType = EasyEnumUtils.getEnum(PromptType.class, promptType);
        String ext = StringUtils.isNotBlank(queryRequest.getExt()) ? queryRequest.getExt() : "";
        
        // Build prompt with schema information (aligned with SqlGenerator; excludes output format/history)
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL generator.\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("- Return ONLY the raw SQL query (no markdown code blocks)\n");
        sb.append("- NO explanations, comments, or descriptions in ANY language\n");
        sb.append("- Use FULLY QUALIFIED table names ONLY in FROM/JOIN clauses: {database}.{schema}.{table}\n");
        sb.append("- For columns, use simple column names or table alias (e.g., `column_name` or `t.column_name`), NOT full paths like `database.schema.table.column`\n");
        sb.append("- STRING COMPARISON RULE: When filtering string columns, ALWAYS use `LOWER(column_name) = 'lowercase_value'` to handle potential case inconsistencies (e.g., `LOWER(country_code) = 'br'`).\n");
        sb.append("- Format SQL with proper line breaks and indentation\n");
        sb.append("- Put SELECT, FROM, WHERE, GROUP BY, ORDER BY on separate lines\n");
        sb.append("- End SQL with semicolon (;)\n\n");

        sb.append("Database Type: ").append(dataSourceType).append("\n\n");

        if (StringUtils.isNotBlank(properties)) {
            sb.append("Available Tables (with metadata):\n");
            sb.append(properties).append("\n\n");
        }

        sb.append("User Query: ").append(prompt).append("\n\n");
        sb.append("SQL:");

        String schemaProperty = sb.toString();
        switch (pType) {
            case SQL_2_SQL:
                schemaProperty = StringUtils.isNotBlank(queryRequest.getDestSqlType()) ? String.format(
                    "%s\n#\n### Target SQL type: %s", schemaProperty, queryRequest.getDestSqlType()) : String.format(
                    "%s\n#\n### Target SQL type: %s", schemaProperty, dataSourceType);
            default:
                break;
        }
        String cleanedInput = schemaProperty.replaceAll("[\r\t]", "");
        return cleanedInput;
    }

    /**
     * query OpenAI apikey for vector search
     *
     * @return
     */
    public String getApiKey() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return null;
        }
        return keyConfig.getContent();
    }

    /**
     * query database type
     *
     * @param queryRequest
     * @return
     */
    public String queryDatabaseType(ChatQueryRequest queryRequest) {
        // Query schema information
        DataResult<DataSource> dataResult = dataSourceService.queryById(queryRequest.getDataSourceId());
        String dataSourceType = dataResult.getData().getType();
        if (StringUtils.isBlank(dataSourceType)) {
            dataSourceType = "MYSQL";
        }
        return dataSourceType;
    }

    public String mappingDatabaseSchema(ChatQueryRequest queryRequest) {
        // Direct Pinecone search via SchemaSearcher
        try {
            // Apply Data Catalog toggles (excludedTables) if provided by frontend
            java.util.List<String> excludedTablesList = null;
            String excludedTablesJson = queryRequest.getExcludedTables();
            if (excludedTablesJson != null && !excludedTablesJson.isEmpty()) {
                try {
                    excludedTablesList = com.alibaba.fastjson2.JSON.parseArray(excludedTablesJson, String.class);
                    log.info("mappingDatabaseSchema - excluded tables: {}", excludedTablesList != null ? excludedTablesList.size() : 0);
                } catch (Exception e) {
                    log.warn("mappingDatabaseSchema - failed to parse excludedTables JSON", e);
                }
            }

            List<String> schemas = (excludedTablesList != null && !excludedTablesList.isEmpty())
                ? schemaSearcher.searchSchema(queryRequest.getMessage(), excludedTablesList)
                : schemaSearcher.searchSchema(queryRequest.getMessage());
            if (CollectionUtils.isNotEmpty(schemas)) {
                String result = JSON.toJSONString(schemas);
                log.info("Pinecone search result: {} schemas found", schemas.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("Pinecone search failed, falling back to gateway: {}", e.getMessage());
        }

        // Fallback: legacy gateway path
        String properties = "";
        String apiKey = getApiKey();
        if (StringUtils.isNotBlank(apiKey)) {
            boolean res = gatewayClientService.checkInWhite(new WhiteListRequest(apiKey, WhiteListTypeEnum.VECTOR.getCode())).getData();
            if (res) {
                properties = queryDatabaseSchema(queryRequest);
            }
        }
        return properties;
    }

    /**
     * Search Pinecone by explicit table names using metadata filter.
     * Returns schema context if found, empty string if not.
     */
    public String mappingDatabaseSchemaByTableNames(ChatQueryRequest queryRequest) {
        try {
            List<String> schemas = schemaSearcher.searchSchemaByTableNames(queryRequest.getTableNames());
            if (CollectionUtils.isNotEmpty(schemas)) {
                String result = JSON.toJSONString(schemas);
                log.info("Pinecone table name search result: {} schemas found for tables: {}",
                    schemas.size(), queryRequest.getTableNames());
                return result;
            }
        } catch (Exception e) {
            log.warn("Pinecone table name search failed: {}", e.getMessage());
        }
        return "";
    }

    /**
     * query database schema
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    public String queryDatabaseSchema(ChatQueryRequest queryRequest) {
        // request embedding
        FastChatEmbeddingResponse response = distributeAIEmbedding(queryRequest.getMessage());
        List<List<BigDecimal>> contentVector = new ArrayList<>();
        if (Objects.isNull(response) || CollectionUtils.isEmpty(response.getData())) {
            return "";
        }
        contentVector.add(response.getData().get(0).getEmbedding());

        // search embedding
        TableSchemaRequest tableSchemaRequest = new TableSchemaRequest();
        tableSchemaRequest.setSchemaVector(contentVector);
        tableSchemaRequest.setDataSourceId(queryRequest.getDataSourceId());
        tableSchemaRequest.setDatabaseName(queryRequest.getDatabaseName());
        tableSchemaRequest.setDataSourceSchema(queryRequest.getSchemaName());
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return "";
        }
        tableSchemaRequest.setApiKey(keyConfig.getContent());
        try {
            DataResult<TableSchemaResponse> result = gatewayClientService.schemaVectorSearch(tableSchemaRequest);
            List<String> schemas = Lists.newArrayList();
            if (Objects.nonNull(result.getData()) && CollectionUtils.isNotEmpty(result.getData().getTableSchemas())) {
                for(TableSchema data: result.getData().getTableSchemas()){
                    schemas.add(data.getTableSchema());
                }
            }
            if (CollectionUtils.isEmpty(schemas)) {
                return "";
            }
            String res = JSON.toJSONString(schemas);
            log.info("search vector result:{}", res);
            return res;
        } catch (Exception exception) {
            log.error("query table error, do nothing");
            return "";
        }
    }

    /**
     * query database schema
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    public String querySchemaByEs(ChatQueryRequest queryRequest) {
        // search embedding
        EsTableSchemaRequest tableSchemaRequest = new EsTableSchemaRequest();
        tableSchemaRequest.setSearchKey(queryRequest.getMessage());
        tableSchemaRequest.setDataSourceId(queryRequest.getDataSourceId());
        tableSchemaRequest.setDatabaseName(queryRequest.getDatabaseName());
        tableSchemaRequest.setSchemaName(queryRequest.getSchemaName());
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return "";
        }
        tableSchemaRequest.setApiKey(keyConfig.getContent());
        try {
            DataResult<EsTableSchemaResponse> result = gatewayClientService.schemaEsSearch(tableSchemaRequest);
            List<String> schemas = Lists.newArrayList();
            if (Objects.nonNull(result.getData()) && CollectionUtils.isNotEmpty(result.getData().getTableSchemas())) {
                for(EsTableSchema data: result.getData().getTableSchemas()){
                    schemas.add(data.getTableSchemaContent());
                }
            }
            if (CollectionUtils.isEmpty(schemas)) {
                return "";
            }
            String res = JSON.toJSONString(schemas);
            log.info("search es result:{}", res);
            return res;
        } catch (Exception exception) {
            log.error("query es table error, do nothing");
            return "";
        }
    }

    /**
     * distribute embedding with different AI
     * Uses Gemini embeddings for faster response from Asia regions
     *
     * @return
     */
    public FastChatEmbeddingResponse distributeAIEmbedding(String input) {
        // Use Gemini embeddings (faster than OpenAI from Asia)
        try {
            List<BigDecimal> embedding = GeminiAIClient.getInstance().generateEmbedding(input);
            
            // Convert to FastChatEmbeddingResponse format
            FastChatItem item = FastChatItem.builder()
                .embedding(embedding)
                .build();
            
            return FastChatEmbeddingResponse.builder()
                .data(List.of(item))
                .build();
        } catch (Exception e) {
            log.error("Failed to generate embedding with Gemini, falling back to OpenAI", e);
            // Fallback to OpenAI if Gemini fails
            try {
                return DirectOpenAIClient.getInstance().embeddings(input);
            } catch (Exception ex) {
                log.error("Failed to generate embedding with OpenAI", ex);
                return null;
            }
        }
    }

    /**
     * Get database type from dataSourceId
     * Used to determine VectorDB namespace (e.g., "SNOWFLAKE", "MYSQL")
     *
     * @param dataSourceId
     * @return dbType string (uppercase, as stored in DataSource)
     */
    private String getDbTypeFromDataSourceId(Long dataSourceId) {
        if (dataSourceId == null) {
            return null;
        }
        try {
            DataResult<DataSource> dataResult = dataSourceService.queryById(dataSourceId);
            if (dataResult != null && dataResult.getData() != null) {
                String dbType = dataResult.getData().getType();
                if (StringUtils.isNotBlank(dbType)) {
                    return dbType; // Keep original case (usually uppercase)
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get dbType from dataSourceId {}: {}", dataSourceId, e.getMessage());
        }
        return null;
    }

    /**
     * Load conversation history from cache for Inquery Agent context
     * Keeps last 10 messages for context
     */
    @SuppressWarnings("unchecked")
    private java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> loadConversationHistory(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        String cacheKey = "agent_conversation_" + conversationId;
        Object cached = LocalCache.CACHE.get(cacheKey);
        
        if (cached == null) {
            return new java.util.ArrayList<>();
        }
        
        try {
            String jsonStr = (String) cached;
            java.util.List<java.util.Map> rawList = cn.hutool.json.JSONUtil.toList(jsonStr, java.util.Map.class);
            java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> messages = new java.util.ArrayList<>();
            
            for (java.util.Map raw : rawList) {
                ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage msg = 
                    new ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage();
                msg.setRole((String) raw.get("role"));
                msg.setContent((String) raw.get("content"));
                msg.setGeneratedSql((String) raw.get("generatedSql"));
                messages.add(msg);
            }
            
            // Keep only last 10 messages for context
            int maxMessages = 10;
            if (messages.size() > maxMessages) {
                messages = messages.subList(messages.size() - maxMessages, messages.size());
            }
            
            return messages;
        } catch (Exception e) {
            log.warn("Failed to parse conversation history: {}", e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    /**
     * Save conversation history to cache for Inquery Agent context
     */
    private void saveConversationHistory(String conversationId, String userMessage, 
            ai.inquery.server.domain.core.query.QueryProcessingResult result) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        
        String cacheKey = "agent_conversation_" + conversationId;
        
        // Load existing history
        java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> history = 
            loadConversationHistory(conversationId);
        
        // Add user message
        ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage userMsg = 
            new ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage();
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        history.add(userMsg);
        
        // Add assistant response
        ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage assistantMsg = 
            new ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage();
        assistantMsg.setRole("assistant");
        
        // Build response content. Priority:
        //   1) aiMessage  — the natural-language reply the root agent produces
        //      for chat / clarification / non-SQL answers. The legacy
        //      classifier path used to leave this null, but the root agent
        //      always sets it (see InqueryRootAgentRunner).
        //   2) generatedSql — Manual mode (no aiMessage, just the SQL).
        //   3) resultData — last-resort dump.
        String responseContent = "";
        if (result.getAiMessage() != null && !result.getAiMessage().isBlank()) {
            responseContent = result.getAiMessage();
        } else if (result.getGeneratedSql() != null) {
            responseContent = "Generated SQL: " + result.getGeneratedSql();
        } else if (result.getResultData() != null) {
            responseContent = result.getResultData().toString();
        }
        assistantMsg.setContent(responseContent);
        assistantMsg.setGeneratedSql(result.getGeneratedSql());
        history.add(assistantMsg);
        
        // Keep only last 20 messages (10 pairs)
        int maxMessages = 20;
        if (history.size() > maxMessages) {
            history = history.subList(history.size() - maxMessages, history.size());
        }
        
        // Save to cache
        String jsonStr = cn.hutool.json.JSONUtil.toJsonStr(history);
        LocalCache.CACHE.put(cacheKey, jsonStr, LocalCache.TIMEOUT);
        log.info("Saved conversation history with {} messages for conversationId: {}", history.size(), conversationId);
    }

    // ================================
    // Auto Mode: Execute with SQL Fix (LEGACY — no longer wired)
    // ================================

    /**
     * Auto Mode SQL self-correction now lives entirely inside the root agent
     * (query_data → DataAnalysisAgent → SqlExecutionTools loop). This helper
     * is kept temporarily for reference and will be removed once the rest of
     * the controller is cleaned up.
     */
    @SuppressWarnings("unused")
    private ExecuteWithFixResult executeWithSqlFix(
            String sql, String schemaContext, String model,
            Long dataSourceId, String databaseName, String schemaName,
            SseEmitter emitter, HttpServletResponse httpResponse) {

        final int MAX_FIX_ATTEMPTS = 3;

        ai.inquery.server.domain.core.langchain.tools.calling.SqlExecutionTools tools =
                new ai.inquery.server.domain.core.langchain.tools.calling.SqlExecutionTools(
                        dlTemplateService, schemaSearcher,
                        dataSourceId, databaseName, schemaName,
                        msg -> sendThinkingEvent(emitter, httpResponse, msg),
                        (fixedSql, prevError, attempt) ->
                                sendSqlFixEvent(emitter, httpResponse, fixedSql, prevError, attempt - 1));

        try {
            dev.langchain4j.model.chat.ChatModel chatModel = langChainModelProvider.getToolCallingChatModel(model);
            ai.inquery.server.domain.core.langchain.agents.SqlExecutionAgent agent =
                    dev.langchain4j.service.AiServices.builder(
                            ai.inquery.server.domain.core.langchain.agents.SqlExecutionAgent.class)
                            .chatModel(chatModel)
                            .tools(tools)
                            .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(20))
                            .maxSequentialToolsInvocations(MAX_FIX_ATTEMPTS + 2)
                            .build();

            String truncatedSchema = schemaContext != null && schemaContext.length() > 4000
                    ? schemaContext.substring(0, 4000) : (schemaContext == null ? "" : schemaContext);
            String userIntent = """
                    Run this SQL and recover from any errors:

                    ```sql
                    %s
                    ```

                    Schema available for lookup:
                    %s
                    """.formatted(sql, truncatedSchema);

            String agentResponse = agent.runWithRetry(userIntent);
            log.info("[Auto+Fix] Agent finished after {} executeSql attempt(s)", tools.getAttempts());

            ai.inquery.server.domain.core.langchain.tools.calling.SqlExecutionTools.LastRun successRun =
                    tools.getLastSuccessfulRun();
            int attempts = Math.max(tools.getAttempts(), 1);
            boolean wasFixed = attempts > 1;

            if (successRun != null) {
                String finalSql = successRun.finalSql();
                String extractedFromResponse = extractSqlFromFixResponse(agentResponse);
                if (extractedFromResponse != null && !extractedFromResponse.isEmpty()) {
                    finalSql = extractedFromResponse;
                }
                return new ExecuteWithFixResult(finalSql, successRun.result(), attempts, wasFixed);
            }

            ai.inquery.server.domain.core.langchain.tools.calling.SqlExecutionTools.LastRun lastRun =
                    tools.getLastRun();
            String finalSql = lastRun != null ? lastRun.finalSql() : sql;
            String fromResponse = extractSqlFromFixResponse(agentResponse);
            if (fromResponse != null && !fromResponse.isEmpty()) finalSql = fromResponse;
            String errorMessage = lastRun != null && lastRun.errorMessage() != null
                    ? lastRun.errorMessage()
                    : "SQL execution failed after " + attempts + " attempt(s)";
            ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> errorResult =
                    new ai.inquery.server.tools.base.wrapper.result.ListResult<>();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage(errorMessage);
            return new ExecuteWithFixResult(finalSql, errorResult, attempts, wasFixed);

        } catch (Exception e) {
            log.error("[Auto+Fix] Agent execution failed: {}", e.getMessage(), e);
            ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> errorResult =
                    new ai.inquery.server.tools.base.wrapper.result.ListResult<>();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage(e.getMessage());
            return new ExecuteWithFixResult(sql, errorResult, Math.max(tools.getAttempts(), 1), tools.getAttempts() > 1);
        }
    }

    private String extractSqlFromFixResponse(String response) {
        if (response == null || response.isEmpty()) return null;

        String result = response.trim();

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "```(?:sql)?\\s*\\n?(.*?)\\n?\\s*```",
            java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            result = matcher.group(1).trim();
        }

        result = result.replaceAll("^\\s*/\\*.*?\\*/\\s*", "");

        return result.trim().isEmpty() ? null : result.trim();
    }

    private void sendThinkingEvent(SseEmitter emitter, HttpServletResponse httpResponse, String message) {
        try {
            emitter.send(SseEmitter.event()
                .name("thinking")
                .data(JSON.toJSONString(message)));
            httpResponse.flushBuffer();
        } catch (IOException e) {
            log.warn("Failed to send thinking event: {}", e.getMessage());
        }
    }

    private void sendSqlFixEvent(SseEmitter emitter, HttpServletResponse httpResponse,
                                  String fixedSql, String errorMessage, int attempt) {
        try {
            java.util.Map<String, Object> fixData = new java.util.LinkedHashMap<>();
            fixData.put("fixedSql", fixedSql);
            fixData.put("error", errorMessage);
            fixData.put("attempt", attempt);
            emitter.send(SseEmitter.event()
                .name("sql_fix")
                .data(JSON.toJSONString(fixData)));
            httpResponse.flushBuffer();
        } catch (IOException e) {
            log.warn("Failed to send sql_fix event: {}", e.getMessage());
        }
    }

    /**
     * Top-level chat handler. Routes the user message through
     * {@link ai.inquery.server.domain.core.langchain.agents.InqueryRootAgentRunner}
     * which decides between {@code query_data} (DataAnalysisAgent + SQL tools)
     * and external search/write tools via LangChain4j tool-calling.
     *
     * <p>Returns a thin {@link ai.inquery.server.domain.core.query.QueryProcessingResult}
     * so the existing SSE {@code response} / {@code done} flow can reuse the
     * same payload shape as the classic path.
     */
    /**
     * Why the chat request can't run. Used to tailor the same-language
     * "configure a provider" SSE response.
     */
    private enum NoProviderReason { NO_KEY, ALL_DISABLED }

    /**
     * Build a user-facing markdown notice when no AI provider is usable.
     * The message is intentionally short and links to the in-app settings
     * page via a markdown link so the SvelteKit MarkdownRenderer turns it
     * into a clickable {@code <a href="/setting">} (client-side nav).
     */
    private String noProviderConfiguredMessage(String userMessage, NoProviderReason reason) {
        UserFallbackLanguage lang = detectFallbackLanguage(userMessage);
        if (reason == NoProviderReason.NO_KEY) {
            return switch (lang) {
                case JAPANESE -> "**AIキーが登録されていません。**\n\nAIチャットを使用するには、[設定](/setting)でOpenAI、Claude、またはGemini APIキーを登録してください。";
                case CHINESE -> "**尚未注册 AI 密钥。**\n\n要使用 AI 聊天，请先在[设置](/setting)中注册 OpenAI、Claude 或 Gemini API 密钥。";
                case TURKISH -> "**AI anahtarı kayıtlı değil.**\n\nAI sohbeti kullanmak için lütfen önce [Ayarlar](/setting) sayfasında bir OpenAI, Claude veya Gemini API anahtarı ekleyin.";
                case ENGLISH -> "**No AI provider key configured.**\n\nPlease add an OpenAI, Claude, or Gemini API key in [Settings](/setting) before using the AI chat.";
            };
        }
        // ALL_DISABLED
        return switch (lang) {
            case JAPANESE -> "**すべてのAIプロバイダーが無効化されています。**\n\n[設定](/setting)で使用するプロバイダーを有効化してください。";
            case CHINESE -> "**所有 AI 提供商均已禁用。**\n\n请在[设置](/setting)中启用要使用的提供商。";
            case TURKISH -> "**Tüm AI sağlayıcıları devre dışı.**\n\nKullanmak istediğiniz sağlayıcıyı [Ayarlar](/setting) sayfasından etkinleştirin.";
            case ENGLISH -> "**All AI providers are disabled.**\n\nPlease enable the provider you want to use in [Settings](/setting).";
        };
    }

    /**
     * Translates a LangChain4j root-agent {@link RuntimeException} into a
     * user-facing message in the SAME language as the user's question.
     * The frontend persists whatever it receives over SSE into the chat
     * history, so leaking framework strings like
     * {@code "Something is wrong, exceeded 8 sequential tool executions"}
     * confuses users and looks like a crash. We classify a few known
     * patterns; the underlying exception is still logged with full stack
     * trace at the call site for operators.
     *
     * <p>Language detection is script-based and intentionally lightweight:
     * Japanese, Chinese, and Turkish get native fallback strings;
     * Korean (Hangul) user messages use English fallback strings;
     * everything else falls back to English. Normal LLM paths still use
     * the system prompt's same-language rule, so this only affects rare
     * exception/fallback paths.
     */
    private String friendlyAgentFailureMessage(RuntimeException ex, String userMessage) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = msg.toLowerCase();
        UserFallbackLanguage lang = detectFallbackLanguage(userMessage);

        if (lower.contains("sequential tool executions")) {
            return switch (lang) {
                case JAPANESE -> "探索が長くなりすぎて、回答をまとめられませんでした。もう少し具体的なテーブル名や指標名で聞き直してください。";
                case CHINESE -> "搜索过程过长，未能整理出答案。请使用更具体的表名或指标名称重新提问。";
                case TURKISH -> "Arama çok uzun sürdüğü için yanıtı toparlayamadım. Lütfen daha belirli bir tablo veya metrik adıyla tekrar sorun.";
                case ENGLISH -> "I couldn't wrap up an answer — the search ran too long. Please rephrase with a more specific table or metric name.";
            };
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return switch (lang) {
                case JAPANESE -> "AIモデルの応答が遅れています。しばらくしてからもう一度お試しください。";
                case CHINESE -> "AI 模型响应时间过长。请稍后重试。";
                case TURKISH -> "AI modelinin yanıtı gecikiyor. Lütfen biraz sonra tekrar deneyin.";
                case ENGLISH -> "The AI model is taking too long to respond. Please try again in a moment.";
            };
        }
        if (lower.contains("429") || lower.contains("resource_exhausted") || lower.contains("quota")) {
            return switch (lang) {
                case JAPANESE -> "AIモデルの利用上限に達しました。しばらくしてから再試行するか、請求状況を確認してください。";
                case CHINESE -> "AI 模型使用量已达到上限。请稍后重试，或检查计费状态。";
                case TURKISH -> "AI modeli kullanım kotasına ulaşıldı. Lütfen daha sonra tekrar deneyin veya faturalandırma durumunu kontrol edin.";
                case ENGLISH -> "The AI model usage quota has been reached. Please try again later or check your billing status.";
            };
        }
        // Catch-all for "the request reached the provider but the key was
        //   rejected / missing". This is a safety net — the entry-point
        //   short-circuit (`pickPreferredProvider == null`) already
        //   handles the obvious "no key" case before any LLM call. We
        //   still include "api key" / "api_key" / 401 because some
        //   provider SDKs return this even when the key is present but
        //   revoked/typoed.
        if (lower.contains("api key") || lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("invalid_api_key") || lower.contains("unauthorized")
                || lower.contains(" 401") || lower.contains("\"401\"")) {
            return switch (lang) {
                case JAPANESE -> "**AIキーの認証に失敗しました。**\n\n[設定](/setting)でAPIキーが正しいか確認してください。";
                case CHINESE -> "**AI 密钥验证失败。**\n\n请在[设置](/setting)中确认 API 密钥是否正确。";
                case TURKISH -> "**AI anahtarı kimlik doğrulaması başarısız oldu.**\n\n[Ayarlar](/setting) sayfasında API anahtarının doğruluğunu kontrol edin.";
                case ENGLISH -> "**AI key authentication failed.**\n\nPlease verify the API key in [Settings](/setting).";
            };
        }
        if (lower.contains("503") || lower.contains("unavailable")) {
            return switch (lang) {
                case JAPANESE -> "AIモデルサービスが一時的に不安定です。しばらくしてからもう一度お試しください。";
                case CHINESE -> "AI 模型服务暂时不可用。请稍后重试。";
                case TURKISH -> "AI modeli hizmeti geçici olarak kullanılamıyor. Lütfen biraz sonra tekrar deneyin.";
                case ENGLISH -> "The AI model service is temporarily unavailable. Please try again in a moment.";
            };
        }
        return switch (lang) {
            case JAPANESE -> "回答の生成中に問題が発生しました。同じ質問をもう一度送るか、少し言い換えてください。";
            case CHINESE -> "生成回答时出现问题。请重新发送相同问题，或稍微换一种说法。";
            case TURKISH -> "Yanıt oluşturulurken bir sorun oluştu. Lütfen aynı soruyu yeniden gönderin veya biraz farklı ifade edin.";
            case ENGLISH -> "Something went wrong while generating a response. Please resend the same question or rephrase it slightly.";
        };
    }

    /**
     * Script-based fallback language detector. This deliberately avoids
     * app UI locale because chat responses should follow the language the
     * user typed, not the language configured in Settings.
     */
    private static UserFallbackLanguage detectFallbackLanguage(String text) {
        if (text == null || text.isEmpty()) return UserFallbackLanguage.ENGLISH;
        boolean hasCjkUnified = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3)
                    || (c >= 0x1100 && c <= 0x11FF)
                    || (c >= 0x3130 && c <= 0x318F)) {
                return UserFallbackLanguage.ENGLISH;
            }
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                return UserFallbackLanguage.JAPANESE;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                hasCjkUnified = true;
            }
            if ("çğıöşüÇĞİÖŞÜ".indexOf(c) >= 0) {
                return UserFallbackLanguage.TURKISH;
            }
        }
        return hasCjkUnified ? UserFallbackLanguage.CHINESE : UserFallbackLanguage.ENGLISH;
    }

    private static String defaultDateRangePrompt(String userMessage) {
        return switch (detectFallbackLanguage(userMessage)) {
            case JAPANESE -> "期間を選択してください。";
            case CHINESE -> "请选择时间范围。";
            case TURKISH -> "Lütfen bir zaman aralığı seçin.";
            case ENGLISH -> "Please pick a time period.";
        };
    }

    private static String defaultClarificationPrompt(String userMessage) {
        return switch (detectFallbackLanguage(userMessage)) {
            case JAPANESE -> "もう一度言い換えていただけますか？";
            case CHINESE -> "可以请您换一种说法再问一次吗？";
            case TURKISH -> "Bunu yeniden ifade eder misiniz?";
            case ENGLISH -> "Could you rephrase that?";
        };
    }

    private enum UserFallbackLanguage {
        JAPANESE,
        CHINESE,
        TURKISH,
        ENGLISH
    }

    private ai.inquery.server.domain.core.query.QueryProcessingResult runWithRootAgent(
            String userMessage,
            String model,
            ChatQueryRequest req,
            java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> history,
            SseEmitter emitter,
            HttpServletResponse httpResponse,
            java.util.Set<String> activeApprovalIds,
            boolean executeQuery) {

        ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig;
        try {
            userConfig = userAIConfigService.getConfigInternal();
        } catch (Exception e) {
            log.warn("[runWithRootAgent] failed to load user AI config: {}", e.getMessage());
            userConfig = null;
        }

        String conversationText = null;
        if (history != null && !history.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var msg : history) {
                String role = "user".equals(msg.getRole()) ? "User" : "Assistant";
                sb.append(role).append(": ").append(msg.getContent()).append("\n");
                if (!"user".equals(msg.getRole())
                        && msg.getGeneratedSql() != null
                        && !msg.getGeneratedSql().isBlank()) {
                    sb.append("Previous SQL: ").append(msg.getGeneratedSql()).append("\n");
                }
            }
            conversationText = sb.toString().trim();
        }

        ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback callback =
                createToolApprovalCallback(emitter, httpResponse, activeApprovalIds);

        ai.inquery.server.domain.core.langchain.mcp.McpConnectionManager.McpConnectionResult mcpResult = null;
        dev.langchain4j.service.tool.ToolProvider mcpToolProvider = null;
        try {
            if (userConfig != null && mcpConnectionManager != null) {
                mcpResult = mcpConnectionManager.connect(userConfig);
                if (mcpResult != null && mcpResult.hasTools()) {
                    mcpToolProvider = mcpResult.toolProvider();
                    log.info("[runWithRootAgent] MCP tools attached: {}", mcpResult.getToolNames());
                }
            }
        } catch (Exception e) {
            log.warn("[runWithRootAgent] MCP connection failed, continuing without: {}", e.getMessage());
        }

        // Web Chat is rendered in Markdown; Slack Deep Agent path uses
        // "slack-mrkdwn" via its own runner instantiation.
        String outputFormat = "markdown";

        ai.inquery.server.domain.core.query.QueryProcessingResult result =
                new ai.inquery.server.domain.core.query.QueryProcessingResult();
        result.setOriginalQuery(userMessage);
        result.setQueryType(ai.inquery.server.domain.core.query.QueryType.AGENT);

        // Per-token SSE callback so the SQL-generation markdown streams to
        // the Svelte client byte-for-byte the same way the legacy
        // classifier path did. The Runner forwards every partial token
        // from the streaming chat model into this lambda.
        java.util.function.Consumer<String> contentTokenCallback = token -> {
            if (token == null || token.isEmpty()) return;
            try {
                emitter.send(SseEmitter.event()
                        .name("content")
                        .data(JSON.toJSONString(token)));
                httpResponse.flushBuffer();
            } catch (Exception ex) {
                log.debug("[runWithRootAgent] failed to emit content token: {}", ex.getMessage());
            }
        };
        java.util.function.Consumer<String> progressCallback = message -> {
            if (message == null || message.isBlank()) return;
            sendThinkingEvent(emitter, httpResponse, message);
        };

        try {
            // Resolve dialect once so the root agent and MetadataTools can
            // emit dialect-correct SQL (PostgreSQL has no SHOW TABLES IN,
            // BigQuery needs `region-*` prefix, etc.). Always resolvable
            // because chat requests must pick a connected data source.
            String dbType = getDbTypeFromDataSourceId(req.getDataSourceId());
            String businessInsight = getBusinessInsightContext(req.getDataSourceId(), req.getDatabaseName());

            // Resolve attachment ids → multimodal Content list + decide
            // whether the requested model can actually handle them
            // (auto-switch within the same provider when not, see
            // AttachmentContentBuilder). The result also gives us the
            // ordered list of attachment metas to link to the message
            // row after persistence.
            ai.inquery.server.domain.core.attachment.AttachmentContentBuilder.Built attachmentResolution;
            String effectiveModel = model;
            try {
                attachmentResolution = ai.inquery.server.domain.core.attachment.AttachmentContentBuilder.build(
                        chatAttachmentService,
                        ai.inquery.server.tools.common.util.ContextUtils.getUserId(),
                        req.getAttachmentIds(),
                        model);
                effectiveModel = attachmentResolution.effectiveModel();
            } catch (RuntimeException e) {
                log.warn("[runWithRootAgent] attachment resolution failed: {}", e.getMessage());
                String msg = e.getMessage() == null ? "Attachment error" : e.getMessage();
                result.setAiMessage(msg);
                result.setExplanation(msg);
                result.setNeedsExecution(false);
                if (contentTokenCallback != null) contentTokenCallback.accept(msg);
                return result;
            }

            // Surface the silent auto-switch to the UI: the Svelte
            // client renders a toast + a badge on the message bubble.
            if (attachmentResolution.modelSwitch() != null) {
                try {
                    var sw = attachmentResolution.modelSwitch();
                    java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
                    payload.put("from", sw.from());
                    payload.put("to", sw.to());
                    payload.put("reason", sw.reason());
                    emitter.send(SseEmitter.event()
                            .name("model_switched")
                            .data(JSON.toJSONString(payload)));
                    httpResponse.flushBuffer();
                } catch (Exception ex) {
                    log.debug("Failed to emit model_switched SSE event: {}", ex.getMessage());
                }
            }

            ai.inquery.server.domain.core.langchain.agents.InqueryRootAgentRunner runner =
                    new ai.inquery.server.domain.core.langchain.agents.InqueryRootAgentRunner(
                            langChainModelProvider, dlTemplateService, schemaSearcher,
                            webSearchService, referenceDocumentSearchService,
                            ai.inquery.server.tools.common.util.ContextUtils.getUserId(),
                            inqueryAgentService, sqlGenerator,
                            astValidator, chartRecommendationEngine,
                            effectiveModel, userConfig,
                            req.getDataSourceId(), req.getDatabaseName(), req.getSchemaName(),
                            dbType,
                            externalSearchHandlers, callback, conversationText,
                            businessInsight, mcpToolProvider, outputFormat, executeQuery,
                            contentTokenCallback, progressCallback);
            runner.setAttachments(attachmentResolution.contents());

            String agentResponse;
            try {
                boolean sqlGenerateFastPath = "sql".equalsIgnoreCase(req.getQueryType())
                        && (req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty());
                if (sqlGenerateFastPath) {
                    log.info("[runWithRootAgent] SQL generate fast path enabled (queryType={}, executeQuery={})",
                            req.getQueryType(), executeQuery);
                    agentResponse = runner.runSqlOnly(userMessage);
                } else {
                    agentResponse = runner.run(userMessage);
                }
            } catch (RuntimeException ex) {
                // LangChain4j surfaces sequential-tool-call cap, malformed
                // tool-call, model timeout, etc. as RuntimeException. We
                // never want the raw "Something is wrong, exceeded N
                // sequential tool executions" string to land in the chat
                // window or be persisted by the frontend. Translate to a
                // user-facing fallback and return the result on the
                // normal "non-data" path so SSE finishes cleanly with a
                // content/done pair instead of an error event.
                log.warn("[runWithRootAgent] root agent run failed gracefully: {}", ex.getMessage(), ex);
                String fallback = friendlyAgentFailureMessage(ex, userMessage);
                result.setAiMessage(fallback);
                result.setExplanation(fallback);
                result.setNeedsExecution(false);
                // Do not stream via contentTokenCallback here — chatWithAgentStream
                // emits one content event after runWithRootAgent returns.
                return result;
            }

            // Date-picker UX branch — the agent called request_date_range.
            if (runner.isNeedsDateRange()) {
                String prompt = runner.getDateRangePrompt() != null
                        ? runner.getDateRangePrompt()
                        : defaultDateRangePrompt(userMessage);
                result.setNeedsDateRange(true);
                result.setAiMessage(prompt);
                result.setExplanation(prompt);
                result.setNeedsExecution(false);
                return result;
            }

            // Disambiguation UX branch — the agent called request_clarification.
            if (runner.isNeedsClarification()) {
                String prompt = runner.getClarificationPrompt() != null
                        ? runner.getClarificationPrompt()
                        : defaultClarificationPrompt(userMessage);
                java.util.List<java.util.Map<String, String>> opts = new java.util.ArrayList<>();
                if (runner.getClarificationOptions() != null) {
                    for (String label : runner.getClarificationOptions()) {
                        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                        m.put("label", label);
                        m.put("query", label);
                        opts.add(m);
                    }
                }
                result.setNeedsDisambiguation(true);
                result.setDisambiguationOptions(opts);
                result.setAiMessage(prompt);
                result.setExplanation(prompt);
                result.setNeedsExecution(false);
                return result;
            }

            if (runner.getChartUpdate() != null) {
                result.setChartUpdate(runner.getChartUpdate());
                String message = runner.getChartUpdate().getMessage();
                if (message == null || message.isBlank()) {
                    message = agentResponse;
                }
                result.setAiMessage(message);
                result.setExplanation(message);
                result.setNeedsExecution(false);
                return result;
            }

            // Data-query path (Manual OR Auto): the runner already
            // produced the overview + query-option markdown.
            // Map every legacy QueryProcessingResult field so the Svelte
            // client renders byte-for-byte the same layout it did before
            // the migration. In Auto mode the runner also executed the
            // first SQL and produced a chart recommendation; in Manual
            // mode dataExecutionResult / dataChart stay null and the
            // frontend renders the "Run Query" button.
            if (runner.getDataFullResponse() != null && !runner.getDataFullResponse().isBlank()) {
                result.setAiMessage(runner.getDataFullResponse());
                if (runner.getDataGeneratedSql() != null) {
                    result.setGeneratedSql(runner.getDataGeneratedSql());
                }
                result.setSchemaContext(runner.getDataSchemaContext());
                if (runner.getDataQueries() != null && !runner.getDataQueries().isEmpty()) {
                    result.setQueries(runner.getDataQueries());
                    result.setOverview(runner.getDataOverview());
                    result.setAdditionalInsightContext(runner.getAdditionalInsightSummary());
                    result.setTitle(runner.getDataTitle());
                    result.setExplanation(runner.getDataExplanation());
                }

                // Multi-aspect analysis path. The runner already executed
                // every aspect in parallel, recommended a chart per aspect,
                // and produced one synthesis narrative. The frontend renders
                // a card grid + synthesis section directly from
                // result.queries + result.synthesis — there is no single
                // "primary" execute result to map.
                if (runner.isMultiAspect()) {
                    result.setMultiAspect(true);
                    result.setSynthesisGoal(runner.getSynthesisGoal());
                    result.setSynthesis(runner.getSynthesis());
                    result.setNeedsExecution(false);
                    result.setAiMessage(agentResponse == null || agentResponse.isBlank()
                            ? (runner.getSynthesisGoal() == null ? "Multi-aspect analysis complete." : runner.getSynthesisGoal())
                            : agentResponse);
                    result.setThoughtProcess("Multi-aspect analysis: " + runner.getDataQueries().size()
                            + " aspects executed in parallel + cross-aspect synthesis.");
                    return result;
                }

                ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> exec =
                        runner.getDataExecutionResult();
                if (exec != null && exec.success()
                        && exec.getData() != null && !exec.getData().isEmpty()) {
                    // Auto mode: execution succeeded — table + chart.
                    result.setResultData(exec.getData());
                    result.setNeedsExecution(false);
                    result.setThoughtProcess(
                            "The user is asking about data. Analyzing the query to understand what data is needed... "
                          + "Searching for relevant database schema and tables... Generating optimized SQL query "
                          + "based on the schema context. Query executed successfully with "
                          + exec.getData().size() + " result(s).");

                    var rec = runner.getDataChart();
                    if (rec != null) {
                        result.setRecommendedChart(rec.getChartType().name());
                        result.setChartConfidence(rec.getConfidence());
                        result.setChartXAxis(rec.getXAxis());
                        result.setChartYAxis(rec.getYAxis());
                        result.setChartDimension(rec.getDimension());
                        result.setChartDimensions(rec.getDimensions());
                        result.setChartXAxisFormat(rec.getXAxisFormat());
                        result.setChartYAxisFormat(rec.getYAxisFormat());
                        result.setChartLineVariant(rec.getLineVariant());
                        result.setChartPieVariant(rec.getPieVariant());
                        result.setChartBarOrientation(rec.getBarOrientation());
                        result.setChartRecommendResponse(rec.getRawResponse());
                        result.setChartRecommendReason(rec.getReason());
                    }
                    result.setSuggestedFollowUps(buildSuggestedFollowUps(userMessage, result, exec.getData().get(0)));
                } else if (exec != null) {
                    // Auto mode but execution failed.
                    result.setResultData(exec.getErrorMessage());
                    result.setNeedsExecution(false);
                    result.setThoughtProcess(
                            "The user is asking about data. ... Query execution encountered an issue.");
                } else {
                    // Manual mode: ship SQL only, wait for user to click Run.
                    if (runner.getDataGeneratedSql() != null) {
                        result.setResultData("```sql\n" + runner.getDataGeneratedSql() + "\n```");
                    }
                    result.setNeedsExecution(true);
                    result.setThoughtProcess("SQL query generated successfully. Click 'Run Query' to execute.");
                }
                return result;
            }

            // Non-data path (chat, write tools, search tools, etc.).
            result.setAiMessage(agentResponse);
            result.setExplanation(agentResponse);
            result.setNeedsExecution(false);

            if (runner.getLastSqlAttempted() != null) {
                result.setGeneratedSql(runner.getLastSqlAttempted());
            }
            return result;
        } finally {
            if (mcpResult != null && mcpResult.clients() != null) {
                for (var client : mcpResult.clients()) {
                    try { client.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private java.util.List<ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp> buildSuggestedFollowUps(
            String userMessage,
            ai.inquery.server.domain.core.query.QueryProcessingResult result,
            ai.inquery.spi.model.ExecuteResult executeResult) {
        if (result == null || executeResult == null || executeResult.getDataList() == null
                || executeResult.getDataList().isEmpty()) {
            return java.util.List.of();
        }

        java.util.List<String> columns = executeResult.getHeaderList() == null
                ? java.util.List.of()
                : executeResult.getHeaderList().stream()
                        .map(ai.inquery.spi.model.Header::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .filter(name -> !"Row Number".equalsIgnoreCase(name))
                        .limit(8)
                        .toList();
        if (columns.isEmpty()) {
            return java.util.List.of();
        }

        try {
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            AiSqlSourceEnum preferredProvider = pickPreferredProvider(configService);
            String primaryModel = defaultModelFor(preferredProvider);
            String model = ModelMapper.getFastModel(primaryModel != null ? primaryModel : DEFAULT_GEMINI_MODEL);
            String prompt = buildSuggestedFollowUpPrompt(userMessage, result, executeResult, columns);
            String response = aiService.generate(prompt, model);
            return parseSuggestedFollowUps(response);
        } catch (Exception e) {
            log.warn("[SuggestedFollowUps] failed to generate suggestions: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    private String buildSuggestedFollowUpPrompt(
            String userMessage,
            ai.inquery.server.domain.core.query.QueryProcessingResult result,
            ai.inquery.spi.model.ExecuteResult executeResult,
            java.util.List<String> columns) {
        return """
                You generate clickable follow-up analysis suggestions for a data assistant.

                Requirements:
                - Respond in the same language as the user's question.
                - Return ONLY a JSON array. No markdown, no prose.
                - Return 2 to 4 suggestions, or [] if no useful next analysis is supported by the evidence.
                - Each item must have: title, question, reason, type.
                - The question must be a complete user-facing request that can be sent back to the assistant.
                - Use only the user's question, SQL, table name, result columns, and result preview below.
                - Do not invent metrics, dimensions, date columns, segments, or business rules not present in the evidence.
                - Prefer suggestions that can be answered by existing data tools: queryData, compareSegments, validateDataQuality, profileTable, explainMetricDefinition.
                - CRITICAL: There is currently NO anomaly-detection or outlier-detection tool. Do NOT suggest questions that ask to detect anomalies, outliers, sudden drops/spikes, or abnormal changes unless the user explicitly asked for that in the current question.
                - For data trust follow-ups, phrase the question as data quality/profile checks (nulls, duplicates, freshness, column distribution), not anomaly detection.
                - Allowed type values only: trend, segment, driver, quality, profile, definition, other. Do not use anomaly or outlier as a type.

                User question:
                %s

                SQL:
                ```sql
                %s
                ```

                Primary table hint:
                %s

                Result columns:
                %s

                Result preview:
                %s

                JSON shape:
                [
                  {"title":"...", "question":"...", "reason":"...", "type":"trend|segment|driver|quality|profile|definition|other"}
                ]
                """.formatted(
                nullSafeForPrompt(userMessage),
                nullSafeForPrompt(result.getGeneratedSql()),
                nullSafeForPrompt(inferPrimaryTableName(result.getGeneratedSql())),
                String.join(", ", columns),
                formatResultPreviewForFollowUps(executeResult));
    }

    private String formatResultPreviewForFollowUps(ai.inquery.spi.model.ExecuteResult executeResult) {
        java.util.List<ai.inquery.spi.model.Header> headers = executeResult.getHeaderList();
        java.util.List<java.util.List<String>> rows = executeResult.getDataList();
        if (headers == null || headers.isEmpty() || rows == null || rows.isEmpty()) {
            return "No preview rows.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(headers.stream()
                .map(ai.inquery.spi.model.Header::getName)
                .collect(java.util.stream.Collectors.joining(" | ")))
                .append("\n");
        int rowLimit = Math.min(rows.size(), 8);
        for (int i = 0; i < rowLimit; i++) {
            java.util.List<String> row = rows.get(i);
            sb.append(row == null ? "" : String.join(" | ", row)).append("\n");
        }
        if (rows.size() > rowLimit) {
            sb.append("... ").append(rows.size() - rowLimit).append(" more rows");
        }
        return sb.toString().trim();
    }

    private java.util.List<ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp> parseSuggestedFollowUps(String response) {
        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp> parsed =
                com.alibaba.fastjson2.JSON.parseArray(json, ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp.class);
        if (parsed == null || parsed.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp> result =
                new java.util.ArrayList<>();
        java.util.Set<String> seenQuestions = new java.util.LinkedHashSet<>();
        for (ai.inquery.server.domain.core.query.QueryProcessingResult.SuggestedFollowUp item : parsed) {
            if (item == null || item.getQuestion() == null || item.getQuestion().isBlank()) {
                continue;
            }
            String question = item.getQuestion().trim();
            if (!seenQuestions.add(question.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            item.setQuestion(truncateText(question, 180));
            item.setTitle(truncateText(
                    item.getTitle() == null || item.getTitle().isBlank() ? question : item.getTitle().trim(),
                    40));
            item.setReason(item.getReason() == null ? null : truncateText(item.getReason().trim(), 160));
            item.setType(item.getType() == null || item.getType().isBlank() ? "other" : truncateText(item.getType().trim(), 32));
            result.add(item);
            if (result.size() >= 4) {
                break;
            }
        }
        return result;
    }

    private String extractJsonArray(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String trimmed = response.trim();
        int fencedStart = trimmed.indexOf("```");
        if (fencedStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fencedStart);
            int fencedEnd = trimmed.indexOf("```", contentStart > 0 ? contentStart + 1 : fencedStart + 3);
            if (contentStart > 0 && fencedEnd > contentStart) {
                trimmed = trimmed.substring(contentStart + 1, fencedEnd).trim();
            }
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private String nullSafeForPrompt(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String truncateText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private String inferPrimaryTableName(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\bfrom\\s+([`\"\\[]?[\\w.]+[`\"\\]]?)")
                .matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    private static class ExecuteWithFixResult {
        final String finalSql;
        final ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> executionResult;
        final int attempts;
        final boolean wasFixed;

        ExecuteWithFixResult(String finalSql,
                ai.inquery.server.tools.base.wrapper.result.ListResult<ai.inquery.spi.model.ExecuteResult> executionResult,
                int attempts, boolean wasFixed) {
            this.finalSql = finalSql;
            this.executionResult = executionResult;
            this.attempts = attempts;
            this.wasFixed = wasFixed;
        }
    }

    // ================================
    // AI Feedback API
    // ================================

    /**
     * Submit feedback for AI-generated content
     * Used for learning and improving AI responses
     *
     * @param request feedback request containing feedbackType (POSITIVE/NEGATIVE),
     *                responseType (SQL_GENERATION/RESULT_INTERPRETATION/DEEP_RESEARCH),
     *                and relevant context (question, generatedContent, etc.)
     * @return feedback id
     */
    @PostMapping("/feedback")
    public DataResult<Long> submitFeedback(@RequestBody ai.inquery.server.domain.api.param.AiFeedbackCreateParam request) {
        // Get current user ID
        Long userId = ai.inquery.server.tools.common.util.ContextUtils.getUserId();
        request.setUserId(userId);
        
        log.info("Received AI feedback: type={}, responseType={}, dataSourceId={}, userId={}", 
            request.getFeedbackType(), request.getResponseType(), request.getDataSourceId(), userId);
        
        return aiFeedbackService.create(request);
    }

    /**
     * Create a ToolApprovalCallback that sends approval requests to the frontend via SSE
     * and blocks until the user responds.
     * Tracks active request IDs so they can be cancelled when the SSE connection closes.
     */
    private ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback createToolApprovalCallback(
            SseEmitter emitter, jakarta.servlet.http.HttpServletResponse response,
            java.util.Set<String> activeApprovalRequestIds) {
        return new ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback() {
            @Override
            public ai.inquery.server.domain.core.langchain.tools.ToolApprovalResponse requestApproval(
                    ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest request)
                    throws ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager.ToolApprovalException {
                try {
                    // Send tool_approval SSE event to frontend
                    String json = JSON.toJSONString(request);
                    emitter.send(SseEmitter.event()
                            .name("tool_approval")
                            .data(json));
                    response.flushBuffer();
                    log.info("Sent tool_approval SSE event: tool={}, requestId={}", request.getToolName(), request.getRequestId());
                } catch (Exception e) {
                    log.error("Failed to send tool_approval event (client likely disconnected): {}", e.getMessage());
                    throw new ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager.ToolApprovalException(
                            "Failed to send approval request to client: " + e.getMessage());
                }

                // Track this request so it can be cancelled if the SSE connection closes
                activeApprovalRequestIds.add(request.getRequestId());
                try {
                    // Block until user responds via /agent/tool/approve endpoint
                    return toolApprovalManager.waitForApproval(request.getRequestId());
                } finally {
                    activeApprovalRequestIds.remove(request.getRequestId());
                }
            }

            @Override
            public void notifyToolResult(String requestId, boolean success, String error) {
                try {
                    java.util.Map<String, Object> resultEvent = new java.util.HashMap<>();
                    resultEvent.put("requestId", requestId);
                    resultEvent.put("success", success);
                    if (error != null) resultEvent.put("error", error);
                    emitter.send(SseEmitter.event()
                            .name("tool_result")
                            .data(JSON.toJSONString(resultEvent)));
                    response.flushBuffer();
                    log.info("Sent tool_result SSE event: requestId={}, success={}, error={}", requestId, success, error);
                } catch (Exception e) {
                    log.warn("Failed to send tool_result event: {}", e.getMessage());
                }
            }
        };
    }

    /**
     * Submit user approval/denial for a pending tool execution.
     * Called by frontend when user clicks approve/deny on the tool approval UI.
     */
    @PostMapping("/agent/tool/approve")
    public DataResult<Boolean> submitToolApproval(
            @RequestBody ai.inquery.server.domain.core.langchain.tools.ToolApprovalResponse response) {
        log.info("Tool approval received: requestId={}, approved={}", response.getRequestId(), response.isApproved());
        boolean found = toolApprovalManager.submitApproval(response);
        if (!found) {
            log.warn("No pending approval found for requestId: {}", response.getRequestId());
        }
        return DataResult.of(found);
    }

    /**
     * Search Jira users for the assignee dropdown in tool approval UI.
     * Proxies the Jira REST API user search using the current user's credentials.
     */
    @GetMapping("/tools/jira/users")
    public DataResult<java.util.List<java.util.Map<String, String>>> searchJiraUsers(
            @RequestParam(value = "query", defaultValue = "") String query) {
        try {
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam config =
                    userAIConfigService.getConfigInternal();
            if (config == null || config.getJiraBaseUrl() == null || config.getJiraApiToken() == null) {
                return DataResult.of(java.util.List.of());
            }

            String baseUrl = config.getJiraBaseUrl();
            String username = config.getJiraUsername();
            String apiToken = config.getJiraApiToken();
            String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes());

            // Jira Cloud REST API: user search
            String apiUrl = baseUrl + "/rest/api/3/user/search?query=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) + "&maxResults=20";

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>> typeRef =
                        new com.fasterxml.jackson.core.type.TypeReference<>() {};
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.List<java.util.Map<String, Object>> users = mapper.readValue(response.body(), typeRef);

                java.util.List<java.util.Map<String, String>> result = users.stream()
                        .filter(u -> "atlassian".equals(String.valueOf(u.get("accountType"))))
                        .map(u -> {
                            java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                            item.put("label", String.valueOf(u.getOrDefault("displayName", "")));
                            item.put("value", String.valueOf(u.getOrDefault("accountId", "")));
                            return item;
                        })
                        .toList();
                return DataResult.of(result);
            }
            log.warn("Jira user search failed: {} {}", response.statusCode(), response.body());
            return DataResult.of(java.util.List.of());
        } catch (Exception e) {
            log.warn("Jira user search error: {}", e.getMessage());
            return DataResult.of(java.util.List.of());
        }
    }

    /**
     * Get Jira issue types for the issue type dropdown in tool approval UI.
     * If project is provided, returns project-specific issue types.
     */
    @GetMapping("/tools/jira/issuetypes")
    public DataResult<java.util.List<java.util.Map<String, String>>> getJiraIssueTypes(
            @RequestParam(value = "project", defaultValue = "") String project) {
        try {
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam config =
                    userAIConfigService.getConfigInternal();
            if (config == null || config.getJiraBaseUrl() == null || config.getJiraApiToken() == null) {
                return DataResult.of(java.util.List.of());
            }

            String baseUrl = config.getJiraBaseUrl();
            String username = config.getJiraUsername();
            String apiToken = config.getJiraApiToken();
            String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes());

            // Use project-specific endpoint if project key/id is provided
            String apiUrl;
            if (project != null && !project.isBlank()) {
                apiUrl = baseUrl + "/rest/api/3/project/" + java.net.URLEncoder.encode(project, java.nio.charset.StandardCharsets.UTF_8) + "/statuses";
            } else {
                apiUrl = baseUrl + "/rest/api/3/issuetype";
            }

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

                if (project != null && !project.isBlank()) {
                    // Project-specific: response is array of { name, id, statuses: [...] }
                    // Each element represents an issue type for that project
                    com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>> typeRef =
                            new com.fasterxml.jackson.core.type.TypeReference<>() {};
                    java.util.List<java.util.Map<String, Object>> issueTypes = mapper.readValue(response.body(), typeRef);
                    java.util.List<java.util.Map<String, String>> result = issueTypes.stream()
                            .map(it -> {
                                java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                                item.put("label", String.valueOf(it.getOrDefault("name", "")));
                                item.put("value", String.valueOf(it.getOrDefault("name", "")));
                                return item;
                            })
                            .toList();
                    return DataResult.of(result);
                } else {
                    // Global: response is array of issue type objects
                    com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>> typeRef =
                            new com.fasterxml.jackson.core.type.TypeReference<>() {};
                    java.util.List<java.util.Map<String, Object>> issueTypes = mapper.readValue(response.body(), typeRef);
                    java.util.List<java.util.Map<String, String>> result = issueTypes.stream()
                            .filter(it -> !"subtask".equalsIgnoreCase(String.valueOf(it.getOrDefault("hierarchyLevel", "")))
                                    && !"true".equals(String.valueOf(it.getOrDefault("subtask", "false"))))
                            .map(it -> {
                                java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                                item.put("label", String.valueOf(it.getOrDefault("name", "")));
                                item.put("value", String.valueOf(it.getOrDefault("name", "")));
                                return item;
                            })
                            .toList();
                    return DataResult.of(result);
                }
            }
            log.warn("Jira issue types fetch failed: {} {}", response.statusCode(), response.body());
            return DataResult.of(java.util.List.of());
        } catch (Exception e) {
            log.warn("Jira issue types error: {}", e.getMessage());
            return DataResult.of(java.util.List.of());
        }
    }

    /**
     * Search Slack channels and users for the autocomplete in tool approval UI.
     * Combines channels (conversations.list) and users (users.list) into a single result.
     * Gracefully returns empty list if the bot token lacks required scopes
     * (channels:read for channels, users:read for DMs).
     */
    @GetMapping("/tools/slack/channels")
    public DataResult<java.util.List<java.util.Map<String, String>>> searchSlackTargets(
            @RequestParam(value = "query", defaultValue = "") String query) {
        try {
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam config =
                    userAIConfigService.getConfigInternal();
            if (config == null || config.getSlackUserToken() == null || config.getSlackUserToken().isBlank()) {
                return DataResult.of(java.util.List.of());
            }

            String token = config.getSlackUserToken();
            String lowerQuery = query.toLowerCase().trim();
            java.util.List<java.util.Map<String, String>> result = new java.util.ArrayList<>();

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // 1) Channels: conversations.list — fetch each type separately so a missing scope
            //    for one type (e.g. groups:read for private) doesn't block the other (channels:read for public)
            for (String channelType : new String[]{"public_channel", "private_channel"}) {
                try {
                    String channelsUrl = "https://slack.com/api/conversations.list"
                            + "?types=" + channelType + "&exclude_archived=true&limit=200";
                    java.net.http.HttpResponse<String> chResp = client.send(
                            java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(channelsUrl))
                                    .header("Authorization", "Bearer " + token)
                                    .GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());

                    com.fasterxml.jackson.databind.JsonNode chRoot = mapper.readTree(chResp.body());
                    if (chRoot.path("ok").asBoolean(false)) {
                        for (com.fasterxml.jackson.databind.JsonNode ch : chRoot.path("channels")) {
                            String name = ch.path("name").asText("");
                            String id = ch.path("id").asText("");
                            if (!lowerQuery.isEmpty() && !name.toLowerCase().contains(lowerQuery)) continue;
                            java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                            item.put("label", "#" + name);
                            item.put("value", id);
                            result.add(item);
                        }
                    } else {
                        log.debug("Slack conversations.list({}) skipped: {}", channelType, chRoot.path("error").asText("unknown"));
                    }
                } catch (Exception e) {
                    log.debug("Slack channels({}) fetch failed: {}", channelType, e.getMessage());
                }
            }

            // 2) Users for DM: users.list (needs users:read)
            try {
                String usersUrl = "https://slack.com/api/users.list?limit=200";
                java.net.http.HttpResponse<String> uResp = client.send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(usersUrl))
                                .header("Authorization", "Bearer " + token)
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

                com.fasterxml.jackson.databind.JsonNode uRoot = mapper.readTree(uResp.body());
                if (uRoot.path("ok").asBoolean(false)) {
                    for (com.fasterxml.jackson.databind.JsonNode u : uRoot.path("members")) {
                        if (u.path("is_bot").asBoolean(false)) continue;
                        if (u.path("deleted").asBoolean(false)) continue;
                        String realName = u.path("profile").path("real_name").asText("");
                        String displayName = u.path("profile").path("display_name").asText("");
                        String userId = u.path("id").asText("");
                        String label = !displayName.isEmpty() ? displayName : realName;
                        if (label.isEmpty() || userId.isEmpty()) continue;
                        if (!lowerQuery.isEmpty()
                                && !label.toLowerCase().contains(lowerQuery)
                                && !realName.toLowerCase().contains(lowerQuery)) continue;
                        java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                        item.put("label", "@" + label + (displayName.isEmpty() ? "" : " (" + realName + ")"));
                        item.put("value", userId);
                        result.add(item);
                    }
                } else {
                    log.warn("Slack users.list failed: {}",
                            uRoot.path("error").asText("unknown"));
                }
            } catch (Exception e) {
                log.debug("Slack users fetch failed: {}", e.getMessage());
            }

            return DataResult.of(result);
        } catch (Exception e) {
            log.warn("Slack targets search error: {}", e.getMessage());
            return DataResult.of(java.util.List.of());
        }
    }

}
