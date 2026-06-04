package ai.inquery.server.domain.core.langchain.agents;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.api.service.ReferenceDocumentSearchService;
import ai.inquery.server.domain.core.chart.ChartRecommendationEngine;
import ai.inquery.server.domain.core.langchain.InqueryAgentService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService;
import ai.inquery.server.domain.core.langchain.tools.calling.MetadataTools;
import ai.inquery.server.domain.core.langchain.tools.calling.SearchTools;
import ai.inquery.server.domain.core.langchain.tools.calling.WriteTools;
import ai.inquery.server.domain.core.query.MarkdownQueryParser;
import ai.inquery.server.domain.core.query.QueryProcessingResult;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.domain.core.query.SqlGenerator;
import ai.inquery.server.domain.core.search.ExternalSearchHandler;
import ai.inquery.server.domain.core.security.AstValidator;
import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.model.Header;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;

/**
 * Builds and runs an {@link InqueryRootAgent} for a single user request.
 *
 * <p>Owns the per-request tool instances (search, write, query_data, UX
 * hint tools). The {@code query_data} tool faithfully replays the legacy
 * {@code QueryProcessingServiceImpl} pipeline (schema search →
 * {@link SqlGenerator} markdown → AST validate → execute + chart) so
 * tool-calling is the only behavioural difference from the deleted
 * JSON classifier path.
 *
 * <p>After the agent answers, the caller can read:
 * <ul>
 *   <li>{@link #getAgentResponse()} — the LLM's plain-language reply.</li>
 *   <li>{@link #getLastSqlResult()} — last successful {@code execute_sql}
 *       result, if any. Used to render rows in the UI (Auto mode).</li>
 *   <li>{@link #isNeedsDateRange()} / {@link #getDateRangePrompt()} —
 *       set when the agent calls {@code request_date_range}. The controller
 *       turns this into the original classifier's
 *       {@code needsDateRange + aiMessage} response.</li>
 *   <li>{@link #isNeedsClarification()} / {@link #getClarificationPrompt()} /
 *       {@link #getClarificationOptions()} — set when the agent calls
 *       {@code request_clarification}. The controller surfaces this as
 *       buttoned disambiguation UI.</li>
 * </ul>
 */
@Slf4j
public class InqueryRootAgentRunner {

    public static final int MAX_TOOL_INVOCATIONS = 12;
    public static final int CHAT_MEMORY_MESSAGES = 30;

    private final LangChainModelProvider modelProvider;
    private final DlTemplateService dlTemplateService;
    private final SchemaSearcher schemaSearcher;
    private final WebSearchService webSearchService;
    private final ReferenceDocumentSearchService referenceDocumentSearchService;
    private final Long userId;
    private final InqueryAgentService inqueryAgentService;
    private final SqlGenerator sqlGenerator;
    private final AstValidator astValidator;
    private final ChartRecommendationEngine chartEngine;
    /**
     * Optional callback that receives every streamed token from the
     * SQL-generation chat. The controller wires this to an SSE
     * {@code content} event so the Svelte client renders the
     * overview + query-option markdown progressively.
     */
    private final Consumer<String> contentTokenCallback;
    /**
     * Optional callback for user-visible progress updates. Unlike
     * {@link #contentTokenCallback}, this is not answer text; it drives
     * the frontend thinking/progress UI with real tool-stage updates.
     */
    private final Consumer<String> progressCallback;
    private String lastProgressMessage;

    private final String modelName;
    private final UserAIConfigSaveParam userConfig;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    /**
     * The user's connected database dialect (POSTGRESQL, SNOWFLAKE,
     * MYSQL, BIGQUERY, etc.). Surfaced to the LLM via the system-prompt
     * prefix and to {@link MetadataTools} so {@code run_readonly_sql}
     * probes use the correct vendor syntax. Each chat request always
     * has a connected data source, so this is non-null in practice.
     */
    private final String dbType;
    private final List<ExternalSearchHandler> externalSearchHandlers;
    private final ToolApprovalCallback approvalCallback;
    private final String conversationHistory;
    private final String businessContext;
    private final ToolProvider mcpToolProvider;
    private final String outputFormat;
    /**
     * Manual-mode flag preserved from the legacy classifier path. When
     * {@code false}, the {@code query_data} tool generates SQL but does NOT
     * execute it — the frontend renders the SQL with a "Run Query" button
     * and the user explicitly approves execution via the dedicated
     * /execute endpoint. When {@code true} (Auto mode) the tool runs the
     * SQL with retry semantics matching the legacy
     * {@code executeSqlWithRetry} helper.
     */
    private final boolean executeQuery;

    @Getter
    private String agentResponse;

    @Getter
    private String lastSqlAttempted;

    @Getter
    private ExecuteResult lastSqlResult;

    @Getter
    private String lastSqlError;

    /**
     * Data-query artefacts produced by {@code query_data}. Populated in
     * BOTH Manual mode (executeQuery=false) and Auto mode
     * (executeQuery=true) — the fields mirror the legacy
     * {@code QueryProcessingServiceImpl} response so the Svelte client
     * sees byte-identical payloads regardless of which path produced them.
     *
     * <p>In Manual mode {@link #dataExecutionResult} stays {@code null} and
     * the frontend renders SQL with a "Run Query" button. In Auto mode
     * the SQL is executed eagerly and {@link #dataExecutionResult} plus
     * {@link #dataChart} are filled so the table + chart render
     * immediately.
     */
    @Getter
    private String dataGeneratedSql;
    @Getter
    private String dataFullResponse;
    @Getter
    private String dataSchemaContext;
    @Getter
    private String dataOverview;
    @Getter
    private String dataTitle;
    @Getter
    private String dataExplanation;
    @Getter
    private List<QueryProcessingResult.QueryItem> dataQueries = new ArrayList<>();
    @Getter
    private String additionalInsightSummary;
    @Getter
    private ListResult<ExecuteResult> dataExecutionResult;
    @Getter
    private ChartRecommendationEngine.ChartRecommendation dataChart;
    @Getter
    private QueryProcessingResult.ChartUpdate chartUpdate;

    // ── Multi-aspect analysis state (set by runMultiAspectAnalysis tool) ──
    // When multiAspect is true, dataQueries holds 2-3 aspect QueryItems with
    // per-aspect chart fields populated, and synthesis holds the cross-aspect
    // narrative produced by the synthesis LLM.
    @Getter
    private boolean multiAspect;
    @Getter
    private String synthesisGoal;
    @Getter
    private String synthesis;

    @Getter
    private boolean needsDateRange;

    @Getter
    private String dateRangePrompt;

    @Getter
    private boolean needsClarification;

    @Getter
    private String clarificationPrompt;

    @Getter
    private List<String> clarificationOptions = new ArrayList<>();

    /**
     * Multimodal attachments for this turn. Built by
     * {@code AttachmentContentBuilder} from the request's
     * {@code attachmentIds}. Empty when the user attached nothing —
     * the AiServices proxy is fine with an empty list.
     */
    private List<Content> attachments = new ArrayList<>();

    public InqueryRootAgentRunner(LangChainModelProvider modelProvider,
                                  DlTemplateService dlTemplateService,
                                  SchemaSearcher schemaSearcher,
                                  WebSearchService webSearchService,
                                  ReferenceDocumentSearchService referenceDocumentSearchService,
                                  Long userId,
                                  InqueryAgentService inqueryAgentService,
                                  SqlGenerator sqlGenerator,
                                  AstValidator astValidator,
                                  ChartRecommendationEngine chartEngine,
                                  String modelName,
                                  UserAIConfigSaveParam userConfig,
                                  Long dataSourceId,
                                  String databaseName,
                                  String schemaName,
                                  String dbType,
                                  List<ExternalSearchHandler> externalSearchHandlers,
                                  ToolApprovalCallback approvalCallback,
                                  String conversationHistory,
                                  String businessContext,
                                  ToolProvider mcpToolProvider,
                                  String outputFormat,
                                  boolean executeQuery,
                                  Consumer<String> contentTokenCallback,
                                  Consumer<String> progressCallback) {
        this.modelProvider = modelProvider;
        this.dlTemplateService = dlTemplateService;
        this.schemaSearcher = schemaSearcher;
        this.webSearchService = webSearchService;
        this.referenceDocumentSearchService = referenceDocumentSearchService;
        this.userId = userId;
        this.inqueryAgentService = inqueryAgentService;
        this.sqlGenerator = sqlGenerator;
        this.astValidator = astValidator;
        this.chartEngine = chartEngine;
        this.contentTokenCallback = contentTokenCallback;
        this.progressCallback = progressCallback;
        this.modelName = modelName;
        this.userConfig = userConfig;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.dbType = dbType;
        this.externalSearchHandlers = externalSearchHandlers != null ? externalSearchHandlers : new ArrayList<>();
        this.approvalCallback = approvalCallback;
        this.conversationHistory = conversationHistory;
        this.businessContext = businessContext;
        this.mcpToolProvider = mcpToolProvider;
        this.outputFormat = outputFormat;
        this.executeQuery = executeQuery;
    }

    /**
     * Attach pre-built multimodal contents (image / pdf / inline text).
     * Pass an empty list to keep the request text-only.
     *
     * @see ai.inquery.server.domain.core.attachment.AttachmentContentBuilder
     */
    public void setAttachments(List<Content> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    public String run(String userMessage) {
        emitProgress(progressMessage(userMessage, "root.analyzing"));
        List<Object> tools = new ArrayList<>();
        Consumer<String> progressKeyCallback = key -> emitProgress(progressMessage(userMessage, key));
        Map<String, String> searchResultsByService = new LinkedHashMap<>();

        tools.add(new SegmentComparisonTool());

        QueryDataTool queryDataTool = new QueryDataTool();
        tools.add(queryDataTool);

        // Metadata + verification-probe tools. search_data_catalog handles
        // schema discovery; lookup_table_metadata returns column/DDL context
        // plus lineage/source-query hints; run_readonly_sql exposes a guarded
        // SELECT path for
        // INFORMATION_SCHEMA and DISTINCT existence probes. All are scoped to
        // the current dataSource — schemaSearcher uses the vector DB for the
        // active user and dlTemplateService respects the ConnectInfo on the
        // thread.
        if (dlTemplateService != null && schemaSearcher != null && dataSourceId != null) {
            tools.add(new MetadataTools(
                    schemaSearcher, dlTemplateService,
                    dataSourceId, databaseName, schemaName, dbType,
                    progressKeyCallback));
        }

        if (webSearchService != null || !externalSearchHandlers.isEmpty()
                || referenceDocumentSearchService != null) {
            tools.add(new SearchTools(externalSearchHandlers, webSearchService,
                    referenceDocumentSearchService, userId, userConfig, modelName,
                    progressKeyCallback,
                    (service, result) -> searchResultsByService.put(service, result)));
        }
        if (inqueryAgentService != null && approvalCallback != null && userConfig != null) {
            tools.add(new WriteTools(inqueryAgentService, userConfig, modelName, conversationHistory,
                    approvalCallback, progressKeyCallback));
        }
        tools.add(new ChartUxTools());
        tools.add(new MultiAspectAnalysisTool());
        // UX hint tools — these don't perform IO, they only flip flags the
        // caller reads after .run() returns.
        tools.add(new DateRangeTool());
        tools.add(new ClarifyTool());

        AiServices<InqueryRootAgent> builder = AiServices.builder(InqueryRootAgent.class)
                .chatModel(modelProvider.getToolCallingChatModel(modelName))
                .tools(tools.toArray())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(CHAT_MEMORY_MESSAGES))
                .maxSequentialToolsInvocations(MAX_TOOL_INVOCATIONS);

        if (mcpToolProvider != null) {
            emitProgress(progressMessage(userMessage, "mcp.ready"));
            builder = builder.toolProvider(mcpToolProvider);
        }

        InqueryRootAgent agent = builder.build();
        emitProgress(progressMessage(userMessage, "root.selecting"));

        StringBuilder prompt = new StringBuilder();
        if (dbType != null && !dbType.isBlank()) {
            prompt.append("[Database dialect: ").append(dbType).append("]\n")
                    .append("Use this dialect's exact SQL syntax in run_readonly_sql ")
                    .append("(e.g. PostgreSQL/MySQL → INFORMATION_SCHEMA + LIMIT, ")
                    .append("Snowflake/Databricks → SHOW TABLES IN db.schema LIKE ..., ")
                    .append("BigQuery → `region-us`.INFORMATION_SCHEMA.TABLES). ")
                    .append("Never mix dialects.\n\n");
        }
        if (conversationHistory != null && !conversationHistory.isBlank()) {
            prompt.append(conversationHistory).append("\n\n");
        }
        if (outputFormat != null && !outputFormat.isBlank()
                && !"markdown".equalsIgnoreCase(outputFormat)) {
            prompt.append("[OUTPUT_FORMAT] ").append(outputFormat).append("\n");
            if ("slack-mrkdwn".equalsIgnoreCase(outputFormat)
                    || "slack".equalsIgnoreCase(outputFormat)) {
                prompt.append("Render the final reply in Slack mrkdwn: use *bold* (single asterisks), _italic_, ~strike~, ")
                        .append("> for quotes, single backticks for inline code, triple backticks for code blocks, ")
                        .append("• for bullets. Do NOT use Markdown # / ## headers or ** bold.\n");
            }
            prompt.append("\n");
        }
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        boolean hasTextAttachments = hasTextAttachments(attachments);
        String executionPlan = buildRootExecutionPlan(userMessage, hasAttachments, hasTextAttachments);
        if (executionPlan != null && !executionPlan.isBlank()) {
            prompt.append("[EXECUTION_PLAN]\n")
                    .append(executionPlan)
                    .append("\n[/EXECUTION_PLAN]\n\n");
        }
        prompt.append("Latest user message:\n").append(userMessage);

        // Text-like attachments (plain text, SVG, PPTX/DOCX/XLSX extracted
        // text) are more reliable when appended to the prompt body than when
        // passed as extra TextContent blocks to AiServices. Some provider
        // adapters only treat the first @UserMessage String as the textual
        // user message and keep additional Content entries for binary media.
        //
        // Keep image/PDF as multimodal Content, but inline text-derived
        // attachments so the model cannot miss them and answer "I can't see
        // the attachment".
        List<Content> mediaAttachments = new ArrayList<>();
        int inlineAttachmentCount = 0;
        if (attachments != null && !attachments.isEmpty()) {
            StringBuilder inlineText = new StringBuilder();
            for (Content attachment : attachments) {
                if (attachment instanceof TextContent textContent) {
                    String text = textContent.text();
                    if (text != null && !text.isBlank()) {
                        inlineText.append("\n\n[ATTACHED_TEXT_")
                                .append(++inlineAttachmentCount)
                                .append("]\n")
                                .append(text)
                                .append("\n[/ATTACHED_TEXT_")
                                .append(inlineAttachmentCount)
                                .append("]");
                    }
                } else {
                    mediaAttachments.add(attachment);
                }
            }
            if (inlineText.length() > 0) {
                prompt.append("\n\nAttached file contents follow. Use them as first-class context for the user's request:")
                        .append(inlineText);
            }
        }

        // attachments is always non-null; the AiServices proxy
        // appends the contents in declaration order onto the same
        // UserMessage as the text prompt.
        String raw;
        try {
            raw = mediaAttachments.isEmpty()
                    ? agent.answer(prompt.toString())
                    : agent.answer(prompt.toString(), mediaAttachments);
        } catch (RuntimeException ex) {
            // Gemini 3.5 can legally return a final candidate with
            // `content: {}` after a tool has already produced the answer.
            // LangChain4j 1.9's Gemini response DTO assumes `parts` is
            // non-null and throws while parsing that response. If our
            // query_data / compareSegments tool already populated the
            // structured markdown, surface it and skip the broken final
            // empty answer instead of failing the whole SSE turn.
            if (dataFullResponse != null && !dataFullResponse.isBlank()) {
                log.warn("[InqueryRootAgentRunner] agent final response parse failed after data tool; using generated data markdown: {}",
                        ex.getMessage());
                raw = "";
            } else if (isSequentialToolLimitException(ex)
                    && tryRecoverViaQueryData(queryDataTool, userMessage, conversationHistory)) {
                // Root agent burned the sequential-tool budget on catalog/metadata
                // probes before reaching query_data. Still answer with SQL/chart UX.
                log.warn("[InqueryRootAgentRunner] sequential tool limit hit; recovered via queryData fast path: {}",
                        ex.getMessage());
                raw = "";
            } else {
                throw ex;
            }
        }
        log.info("[InqueryRootAgentRunner] agent.answer returned {} chars; needsDateRange={}, needsClarification={}, lastSql={}, dataMarkdown={}, attachments={}, inlineTextAttachments={}, mediaAttachments={}",
                raw == null ? -1 : raw.length(),
                needsDateRange, needsClarification,
                queryDataTool.lastSqlAttempted != null,
                dataFullResponse != null,
                attachments == null ? 0 : attachments.size(),
                inlineAttachmentCount,
                mediaAttachments.size());

        // Data-query short-circuit: the tool already produced the full
        // overview + query-option markdown (and possibly executed it for Auto
        // mode). Ignore the agent's paraphrase (we explicitly ask the
        // agent to reply with the DATA_QUERY_DONE sentinel) and surface
        // the raw markdown verbatim so the Svelte
        // client renders byte-for-byte the same layout as the legacy
        // classifier path.
        if (dataFullResponse != null && !dataFullResponse.isBlank()) {
            if (!searchResultsByService.isEmpty()) {
                additionalInsightSummary = buildAdditionalInsightSummary(
                        userMessage, searchResultsByService, executeQuery);
                // Auto mode already has executed DB results, so the external
                // context is consumed by the post-execution interpretation.
                // Showing it in the overview would duplicate the final
                // comparison section. Manual keeps it visible as "compare
                // after running SQL" guidance.
                if (!executeQuery && additionalInsightSummary != null && !additionalInsightSummary.isBlank()) {
                    dataOverview = appendAdditionalInsights(dataOverview, additionalInsightSummary);
                }
            }
            this.agentResponse = dataFullResponse;
            this.lastSqlAttempted = dataGeneratedSql;
            return this.agentResponse;
        }

        // Safety net: if the LLM returned an empty body without flipping a UX
        // flag and without actually executing SQL, retry the SAME user prompt
        // through the plain (thinking-disabled) chat model. Gemini-3 in
        // particular sometimes writes the whole answer into its thinking
        // trace and emits no final text token when both returnThinking and
        // sendThinking are on (required for multi-turn tool calling).
        if ((raw == null || raw.isBlank())
                && !needsDateRange
                && !needsClarification
                && queryDataTool.lastSqlAttempted == null) {
            log.warn("[InqueryRootAgentRunner] empty agent.answer — falling back to plain chat model");
            try {
                ChatModel plain = modelProvider.getPlainChatModel(modelName);
                String fallback;
                if (mediaAttachments.isEmpty()) {
                    fallback = plain.chat(prompt.toString());
                } else {
                    // Multimodal fallback: build a UserMessage that
                    // carries both the text prompt and the same
                    // attachments we passed to the AiService. Without
                    // this, the fallback would lose the image / PDF
                    // context entirely.
                    java.util.List<Content> all = new ArrayList<>(mediaAttachments.size() + 1);
                    all.add(dev.langchain4j.data.message.TextContent.from(prompt.toString()));
                    all.addAll(mediaAttachments);
                    dev.langchain4j.data.message.UserMessage um =
                            dev.langchain4j.data.message.UserMessage.from(all);
                    fallback = plain.chat(java.util.List.of(um)).aiMessage().text();
                }
                if (fallback != null && !fallback.isBlank()) {
                    raw = fallback;
                } else {
                    raw = fallbackMessage(userMessage, true);
                }
            } catch (Exception fe) {
                log.warn("[InqueryRootAgentRunner] plain chat fallback failed: {}", fe.getMessage());
                raw = fallbackMessage(userMessage, false);
            }
        }
        this.agentResponse = raw;

        this.lastSqlAttempted = queryDataTool.lastSqlAttempted;
        this.lastSqlResult = queryDataTool.lastSqlResult;
        this.lastSqlError = queryDataTool.lastSqlError;

        return this.agentResponse;
    }

    /**
     * Workspace SQL generation fast path.
     *
     * <p>Workspace "Generate" already means "produce a SQL query for the
     * connected data source"; it does not need the root planner, generic
     * tool-routing loop, date-range UX, external search, or write tools.
     * Run the deterministic data workflow directly:
     *
     * <pre>
     * schema search -> SQL markdown generation -> AST validation -> markdown parse
     * </pre>
     *
     * <p>In Auto mode the same workflow still executes SQL and recommends a
     * chart, but workspace generate currently sends Manual mode.
     */
    public String runSqlOnly(String userMessage) {
        emitProgress(progressMessage(userMessage, "data.start"));
        QueryDataTool tool = new QueryDataTool();
        String sentinel = tool.queryDataFast(userMessage, conversationHistory);
        if (dataFullResponse != null && !dataFullResponse.isBlank()) {
            this.agentResponse = dataFullResponse;
        } else {
            this.agentResponse = sentinel;
        }
        this.lastSqlAttempted = dataGeneratedSql;
        this.lastSqlResult = lastSqlResult;
        this.lastSqlError = lastSqlError;
        log.info("[InqueryRootAgentRunner] SQL-only fast path complete: dataMarkdown={}, sql={}",
                dataFullResponse != null && !dataFullResponse.isBlank(),
                dataGeneratedSql != null && !dataGeneratedSql.isBlank());
        return this.agentResponse;
    }

    private String buildRootExecutionPlan(String userMessage, boolean hasAttachments, boolean hasTextAttachments) {
        emitProgress(progressMessage(userMessage, "root.planning"));
        if (hasAttachments && looksLikeAttachmentAnalysisRequest(userMessage)) {
            String plan = fallbackRootExecutionPlan(userMessage, true, hasTextAttachments);
            log.info("[InqueryRootAgentRunner] attachment-first execution plan:\n{}", plan);
            return plan;
        }
        String prompt = """
                You are the front planner for Inquery's tool-calling root agent.
                Create a concise execution plan for the next assistant turn.

                Available tool groups:
                - planAnalysis: broad business-problem diagnosis for users who do not know what analysis to ask for. It narrows the goal into measurable questions and candidate datasets before queryData.
                - queryData: internal database analysis. It must preserve the existing overview + SQL option(s) + chart/table UX.
                - compareSegments: internal segment/cohort/category comparison. It also returns the standard table/chart UX.
                - runMultiAspectAnalysis: parallel 2-3 SQL COMPLEMENTARY multi-aspect analysis with one synthesized cross-aspect narrative. Use ONLY when the answer truly cannot be produced by a single SQL/CTE/JOIN/window AND the user wants a cross-aspect synthesized answer (different schema/grain/entity per aspect, dashboard-style, cross-domain). Alternative perspectives on the same data are NOT aspects — those belong in follow-up suggestions. If torn between queryData and runMultiAspectAnalysis, choose queryData. MANDATORY PRECONDITION: every runMultiAspectAnalysis step in the plan MUST be preceded by search_data_catalog (and/or lookup_table_metadata) so each aspect's SQL targets real verified tables in the user's database. NEVER plan runMultiAspectAnalysis without a prior schema discovery step.
                - updatePreviousChart: UI-only visualization change for the latest already-rendered query result.
                - search_data_catalog, lookup_table_metadata, checkDataVolumeBatch, checkDataVolume, validateDataQuality, profileTable, explainMetricDefinition, probe_column_values, trace_table_lineage, explain_metric_source, run_readonly_sql: data discovery, metadata, scan-cost evidence (batch = many tables in one round-trip; single = deep evidence on one chosen table), quality, profiling, metric definitions, values, lineage, source logic, guarded probes.
                - search_web, search_confluence, search_slack, search_jira, search_github, search_google_drive, search_outlook: external/current/team context (Drive Docs/Sheets, Outlook mail when connected).
                - write tools: Slack/Confluence/Jira write actions with approval.
                - UX tools: request_date_range, request_clarification.

                Return only a compact plan in this format:
                Goal: ...
                Complexity: simple|multi_tool|needs_clarification|direct_answer
                Steps:
                1. tool_or_direct_answer - why
                2. ...
                Final UX:
                - ...

                Rules:
                - Use the user's main language.
                - Attachment-first routing: if this turn has attached file contents and the user asks to analyze/summarize/organize/explain "this", "the attached file", a presentation, a document, or similar, plan direct_answer from the attachment. Do NOT plan planAnalysis, search_data_catalog, queryData, compareSegments, or runMultiAspectAnalysis unless the user explicitly asks to verify/join/compare the attachment against the connected database.
                - When attached Office/text contents are available, treat them as the primary evidence for this turn. Internal database tools are secondary and opt-in only.
                - For broad business problems or goals ("why is X down?", "how do we improve Y?", "analyze this problem"), plan with planAnalysis first; if it finds candidate data, queryData should usually be the next/final data-producing step.
                - For data plus external context, run external search tools before query_data, then let result interpretation compare with saved context.
                - For data plus metadata/value/lineage uncertainty, run metadata/probe/lineage tools before query_data.
                - request_date_range is a SCAN-COST guardrail, NEVER a keyword reflex. Do NOT add request_date_range to the plan just because the user said "trend" / "over time" / "by month" / "history". Those words alone are NOT evidence.
                - request_date_range may appear in the plan ONLY when both hold:
                  (a) you have already planned a scan-cost evidence step (search_data_catalog → checkDataVolumeBatch, or checkDataVolume on a known table) earlier in the SAME plan, AND
                  (b) the user did not specify a time range, period, or window.
                  Otherwise, plan checkDataVolumeBatch (when there will be multiple candidate tables from search_data_catalog / planAnalysis) or checkDataVolume (when the table is already known) first, and let the agent decide at execution time whether request_date_range is needed based on the evidence.
                - For data questions where the table is not yet known, the typical plan is: search_data_catalog → checkDataVolumeBatch (on the returned candidates) → queryData (or checkDataVolume + request_date_range only if the chosen table is large/very_large unbounded).
                - For small/medium-looking summary tables (dim_*, *_summary, *_daily, *_monthly), prefer queryData with a bounded SQL directly. Do NOT preemptively plan request_date_range.
                - query_data should be the final data-producing tool when standard SQL/chart/table UX is expected.
                - For a multi-aspect dashboard request (e.g. "show overall health across X, Y, and Z together", "give me X, Y and Z together"), the typical plan is: search_data_catalog (with keywords for every aspect) → optional lookup_table_metadata on the top candidate(s) → runMultiAspectAnalysis as the final data-producing step. NEVER skip the schema discovery step before runMultiAspectAnalysis.
                - Do not invent unavailable tools.
                - If no tool is needed, plan direct_answer.

                Conversation context:
                %s

                Attachment context:
                - has_attachments: %s
                - has_text_extracted_attachments: %s

                Latest user message:
                %s
                """.formatted(
                conversationHistory == null ? "" : conversationHistory,
                hasAttachments,
                hasTextAttachments,
                userMessage);
        try {
            ChatModel plain = modelProvider.getPlainChatModel(modelName);
            String plan = plain.chat(prompt);
            if (plan == null || plan.isBlank()) {
                String fb = fallbackRootExecutionPlan(userMessage, hasAttachments, hasTextAttachments);
                log.info("[InqueryRootAgentRunner] root planner returned empty, using fallback plan:\n{}", fb);
                return fb;
            }
            String truncated = truncate(plan, 2500);
            log.info("[InqueryRootAgentRunner] root execution plan:\n{}", truncated);
            return truncated;
        } catch (Exception e) {
            log.warn("[InqueryRootAgentRunner] root execution planner failed: {}", e.getMessage());
            return fallbackRootExecutionPlan(userMessage, hasAttachments, hasTextAttachments);
        }
    }

    private String fallbackRootExecutionPlan(String userMessage, boolean hasAttachments, boolean hasTextAttachments) {
        if (hasAttachments && looksLikeAttachmentAnalysisRequest(userMessage)) {
            return switch (detectFallbackLanguage(userMessage)) {
                case JAPANESE -> """
                        Goal: 添付ファイルの内容を優先して分析する。
                        Complexity: direct_answer
                        Steps:
                        1. direct_answer - 添付内容に基づいて要約、主要インサイト、示唆を整理する。
                        2. 内部DBツールは、ユーザーがDBでの検証・結合・比較を明示した場合のみ使う。
                        Final UX:
                        - SQL/チャート/テーブルUXは作らず、通常のMarkdown回答にする。
                        """;
                case CHINESE -> """
                        Goal: 优先分析附件内容。
                        Complexity: direct_answer
                        Steps:
                        1. direct_answer - 基于附件内容总结、提炼关键洞察和启示。
                        2. 只有当用户明确要求用内部数据库验证/关联/比较时，才使用数据库工具。
                        Final UX:
                        - 不生成 SQL/图表/表格 UX，直接用 Markdown 回复。
                        """;
                case TURKISH -> """
                        Goal: Önce ekli dosyanın içeriğini analiz et.
                        Complexity: direct_answer
                        Steps:
                        1. direct_answer - Ek içeriğine dayanarak özet, temel içgörüler ve çıkarımlar sun.
                        2. İç veritabanı araçlarını yalnızca kullanıcı açıkça doğrulama/birleştirme/karşılaştırma isterse kullan.
                        Final UX:
                        - SQL/grafik/tablo UX'i oluşturma; normal Markdown yanıt ver.
                        """;
                case ENGLISH -> """
                        Goal: Analyze the attached file contents first.
                        Complexity: direct_answer
                        Steps:
                        1. direct_answer - summarize and organize key insights from the attachment.
                        2. Use internal database tools only if the user explicitly asks to verify/join/compare the attachment against the connected database.
                        Final UX:
                        - Plain Markdown answer; no SQL/chart/table UX.
                        """;
            };
        }
        return switch (detectFallbackLanguage(userMessage)) {
            case JAPANESE -> """
                    Goal: ユーザーの最新リクエストを正確に処理する。
                    Complexity: multi_tool
                    Steps:
                    1. テーブルが不明な場合は search_data_catalog または planAnalysis で候補を見つける。
                    2. 候補が複数あるかスキャンコストが不明な場合は checkDataVolumeBatch で一括サイズ確認する。
                    3. データ分析が必要なら query_data を最後のデータ生成ツールとして呼ぶ。request_date_range は large/very_large の証拠 + ユーザー指定の時間範囲が無い場合にのみ使う。
                    Final UX:
                    - 既存のSQL/チャート/テーブルUXを維持する。
                    """;
            case CHINESE -> """
                    Goal: 准确处理用户的最新请求。
                    Complexity: multi_tool
                    Steps:
                    1. 表名不明确时使用 search_data_catalog 或 planAnalysis 寻找候选。
                    2. 候选多于一个或扫描成本不确定时，使用 checkDataVolumeBatch 一次性确认所有大小。
                    3. 如果需要数据分析，将 query_data 作为最后的数据生成工具调用。request_date_range 仅在证据为 large/very_large 且用户未指定时间范围时使用。
                    Final UX:
                    - 保持现有 SQL/图表/表格 UX。
                    """;
            case TURKISH -> """
                    Goal: Kullanıcının son isteğini doğru şekilde ele almak.
                    Complexity: multi_tool
                    Steps:
                    1. Tablo belirsizse search_data_catalog veya planAnalysis ile adayları bul.
                    2. Birden fazla aday varsa veya tarama maliyeti belirsizse checkDataVolumeBatch ile tek seferde boyut kontrolü yap.
                    3. Veri analizi gerekiyorsa query_data aracını son veri üreten araç olarak çağır. request_date_range yalnızca kanıt large/very_large ise ve kullanıcı zaman aralığı belirtmemişse kullan.
                    Final UX:
                    - Mevcut SQL/grafik/tablo UX'ini koru.
                    """;
            case ENGLISH -> """
                    Goal: Handle the user's latest request accurately.
                    Complexity: multi_tool
                    Steps:
                    1. If the table is unclear, find candidates with search_data_catalog or planAnalysis.
                    2. If there are multiple candidates or scan cost is uncertain, run checkDataVolumeBatch to size them all in one round-trip.
                    3. If data analysis is needed, call query_data as the final data-producing tool. Use request_date_range ONLY when evidence shows large/very_large AND the user did not specify a time range.
                    Final UX:
                    - Preserve the existing SQL/chart/table UX.
                    """;
        };
    }

    private static boolean hasTextAttachments(List<Content> attachments) {
        if (attachments == null || attachments.isEmpty()) return false;
        for (Content attachment : attachments) {
            if (attachment instanceof TextContent textContent
                    && textContent.text() != null
                    && !textContent.text().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeAttachmentAnalysisRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String n = userMessage.toLowerCase(Locale.ROOT);
        return n.contains("ppt")
                || n.contains("presentation")
                || n.contains("document")
                || n.contains("attachment")
                || n.contains("attached")
                || n.contains("upload")
                || n.contains("file")
                || n.contains("insight")
                || n.contains("analy")
                || n.contains("summar")
                || n.contains("organiz");
    }

    public class SegmentComparisonTool {
        @Tool("Compare groups, cohorts, categories, regions, customer types, or other segments and return the standard table/chart/analysis payload. Prefer this over queryData when the user's main intent is comparison/difference between segments. This tool accepts a natural-language comparison question and internally generates read-only aggregate SQL, executes it in Auto mode, and recommends a visualization.")
        public String compareSegments(
                @P("The current natural-language segment comparison question. Keep the user's exact segments, metrics, filters, and language.") String question,
                @P("Optional compact summary of relevant prior data context only: known table names, columns, verified values, filters, grain, generated SQL, or limitations. Pass empty string if standalone.") String conversationContext
        ) {
            if (question == null || question.isBlank()) {
                return "compareSegments: 'question' is empty.";
            }
            if (dataFullResponse != null && !dataFullResponse.isBlank()) {
                return "DATA_QUERY_DONE. A data result already exists for this turn. "
                        + "STOP calling tools. Reply with exactly \"DATA_QUERY_DONE\".";
            }

            String comparisonQuestion = """
                    Segment comparison task:
                    %s

                    Generate the best read-only aggregate comparison query for this request.
                    Prefer one row per segment with comparable metric columns. If the user asks
                    for multiple metrics with very different scales, keep them in the table and
                    let chart recommendation choose the most useful primary visualization.
                    """.formatted(question.trim());
            log.info("[InqueryRootAgentRunner] compareSegments invoked (executeQuery={}, hasContext={}): {}",
                    executeQuery, conversationContext != null && !conversationContext.isBlank(), question);
            emitProgress(progressMessage(question, "metadata.compare"));
            QueryDataTool dataTool = new QueryDataTool();
            String result = dataTool.runDataQuery(comparisonQuestion, dataTool.normalizeConversationContext(conversationContext));
            enrichSegmentComparisonResult(question);
            return result;
        }
    }

    private void enrichSegmentComparisonResult(String question) {
        if (!executeQuery || dataExecutionResult == null || !dataExecutionResult.success()
                || dataExecutionResult.getData() == null || dataExecutionResult.getData().isEmpty()) {
            return;
        }
        ExecuteResult source = dataExecutionResult.getData().get(0);
        if (source == null || source.getHeaderList() == null || source.getDataList() == null
                || source.getHeaderList().size() < 3 || source.getDataList().size() < 2) {
            return;
        }

        List<Header> headers = source.getHeaderList();
        int rowNumberOffset = isRowNumberHeader(headers.get(0)) ? 1 : 0;
        if (headers.size() <= rowNumberOffset + 1) return;

        int segmentIndex = rowNumberOffset;
        List<Integer> metricIndexes = new ArrayList<>();
        for (int i = rowNumberOffset + 1; i < headers.size(); i++) {
            if (allRowsNumeric(source.getDataList(), i)) {
                metricIndexes.add(i);
            }
        }
        if (metricIndexes.isEmpty()) return;

        List<List<String>> rows = source.getDataList();
        List<String> baseline = rows.get(0);
        String baselineSegment = cell(baseline, segmentIndex);
        List<List<String>> comparisonRows = new ArrayList<>();
        int rowNumber = 1;

        for (int metricIndex : metricIndexes) {
            String metricName = headers.get(metricIndex).getName();
            Double baselineValue = parseNumber(cell(baseline, metricIndex));
            if (baselineValue == null) continue;

            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<String> comparison = rows.get(rowIndex);
                Double comparisonValue = parseNumber(cell(comparison, metricIndex));
                if (comparisonValue == null) continue;

                double signedGap = comparisonValue - baselineValue;
                double absoluteGap = Math.abs(signedGap);
                Double percentGap = baselineValue == 0.0 ? null : signedGap / Math.abs(baselineValue) * 100.0;
                Double ratio = baselineValue == 0.0 ? null : comparisonValue / baselineValue;
                String comparisonSegment = cell(comparison, segmentIndex);
                String winner = comparisonValue > baselineValue ? comparisonSegment
                        : comparisonValue < baselineValue ? baselineSegment
                        : "tie";

                comparisonRows.add(List.of(
                        String.valueOf(rowNumber++),
                        metricName,
                        baselineSegment,
                        comparisonSegment,
                        formatComparisonNumber(baselineValue),
                        formatComparisonNumber(comparisonValue),
                        formatComparisonNumber(absoluteGap),
                        percentGap == null ? "" : formatComparisonNumber(percentGap),
                        ratio == null ? "" : formatComparisonNumber(ratio),
                        winner
                ));
            }
        }
        if (comparisonRows.isEmpty()) return;

        source.setHeaderList(List.of(
                header("Row Number", "INQUERY_ROW_NUMBER"),
                header("metric", "STRING"),
                header("baseline_segment", "STRING"),
                header("comparison_segment", "STRING"),
                header("baseline_value", "NUMERIC"),
                header("comparison_value", "NUMERIC"),
                header("absolute_gap", "NUMERIC"),
                header("percent_gap_vs_baseline", "NUMERIC"),
                header("ratio_to_baseline", "NUMERIC"),
                header("winner", "STRING")
        ));
        source.setDataList(comparisonRows);
        source.setDescription("Segment comparison summary with derived gap, percent gap, ratio, and winner columns.");
        lastSqlResult = source;

        dataChart = new ChartRecommendationEngine.ChartRecommendation(
                ChartRecommendationEngine.ChartType.BAR,
                0.95,
                "Segment comparison results are enriched with derived gap metrics; absolute_gap is the clearest comparison visualization.",
                "metric",
                "absolute_gap",
                (List<String>) null,
                null,
                "comma",
                null,
                null,
                "vertical",
                "y_desc"
        );

        if (dataQueries != null && !dataQueries.isEmpty()) {
            QueryProcessingResult.QueryItem first = dataQueries.get(0);
            first.setTitle(first.getTitle() == null || first.getTitle().isBlank()
                    ? "## 📊 Segment comparison"
                    : first.getTitle());
            String enrichmentNote = "Results are post-processed as a comparison summary showing per-metric deltas, percent change vs baseline, ratios, and leading segments.";
            first.setExplanation(first.getExplanation() == null || first.getExplanation().isBlank()
                    ? "- " + enrichmentNote
                    : first.getExplanation() + "\n- " + enrichmentNote);
        }
        if (dataOverview != null && !dataOverview.contains("deltas, percent change, ratios")) {
            dataOverview = dataOverview + "\n\nComparison-only enrichment: per-metric deltas, percent change, ratios, and leading segments were computed.";
        }
        log.info("[InqueryRootAgentRunner] compareSegments enriched result: metrics={}, rows={}",
                metricIndexes.size(), comparisonRows.size());
    }

    private boolean isRowNumberHeader(Header header) {
        if (header == null) return false;
        String name = header.getName();
        String type = header.getDataType();
        return "Row Number".equalsIgnoreCase(name) || "INQUERY_ROW_NUMBER".equalsIgnoreCase(type);
    }

    private boolean allRowsNumeric(List<List<String>> rows, int index) {
        if (rows == null || rows.isEmpty()) return false;
        for (List<String> row : rows) {
            if (parseNumber(cell(row, index)) == null) return false;
        }
        return true;
    }

    private String cell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) return "";
        return row.get(index) == null ? "" : row.get(index);
    }

    private Double parseNumber(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.trim()
                    .replace(",", "")
                    .replace("%", "");
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatComparisonNumber(Double value) {
        if (value == null) return "";
        if (Math.abs(value - Math.rint(value)) < 0.0000001) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private Header header(String name, String dataType) {
        Header header = new Header();
        header.setName(name);
        header.setDataType(dataType);
        return header;
    }

    public class ChartUxTools {
        @Tool("Adjust the visualization type for an already-rendered query result without running data tools again. Also propose a fresh chart title that fits the new chart type. Does NOT re-execute SQL.")
        public String updatePreviousChart(
                @P("Target visualization type. Allowed values: BAR, LINE, PIE, SCATTER, TABLE, CARD.") String chartType,
                @P("Plain-text chart title (no markdown, no asterisks, no backticks, no quotes, no leading dashes or numbering). Concise — typically 4-12 words — describing WHAT the chart shows, not the user's command. Use the user's language. Examples: 'Revenue share by category', 'Monthly active users by plan'. Leave empty to keep the previous title.") String chartTitle,
                @P("One short chat sentence in the user's language confirming the change. This is shown in the chat thread only — it is NOT used as the chart title. Just confirm the chart was updated.") String message
        ) {
            QueryProcessingResult.ChartUpdate update = new QueryProcessingResult.ChartUpdate();
            update.setTarget("latest_query");
            update.setChartType(normalizeChartType(chartType));
            update.setChartTitle(sanitizeChartTitle(chartTitle));
            update.setMessage(message == null || message.isBlank()
                    ? "Chart updated."
                    : truncate(message.trim(), 300));
            chartUpdate = update;
            return "CHART_UPDATE_DONE. The frontend will update the latest rendered result. "
                    + "Do not call query_data. Reply with only a short confirmation.";
        }
    }

    /** Max aspects per single multi-aspect call (UI grid is 3-col). */
    public static final int MAX_MULTI_ASPECTS = 3;
    /** Min aspects to qualify as multi-aspect (2 or 3). */
    public static final int MIN_MULTI_ASPECTS = 2;

    /**
     * Tool: runMultiAspectAnalysis. Use ONLY for true complementary
     * cross-aspect analysis (different schema/grain/entity that cannot be
     * expressed as a single SQL/CTE/JOIN, AND the user wants a synthesized
     * cross-aspect answer). Otherwise use queryData.
     */
    public class MultiAspectAnalysisTool {
        @Tool("""
            Run a COMPLEMENTARY multi-aspect analysis: 2-3 SQL queries that
            each answer a different facet of the user's question, executed in
            parallel, then synthesized into one cross-aspect narrative.

            STRICT preconditions — ALL must hold:
              1) The answer truly requires multiple SQLs that CANNOT be merged
                 into a single SQL/CTE/JOIN/window (schema/grain/entity differ).
              2) The user explicitly wants a cross-aspect / multi-dimension /
                 dashboard-style synthesized answer.
              3) Each aspect is COMPLEMENTARY, not an alternative perspective
                 on the same data. Alternative perspectives are surfaced via
                 follow-up suggestions instead.
              4) MANDATORY SCHEMA GATE: every table referenced in every aspect's
                 SQL MUST come from a search_data_catalog or lookup_table_metadata
                 result you already got THIS turn. NEVER invent table or column
                 names from general knowledge ("customer_orders", "sales",
                 "users" etc. are NOT real tables until search_data_catalog
                 returns them). If you have not yet performed schema discovery,
                 call search_data_catalog (and optionally lookup_table_metadata
                 on top candidates) FIRST, then call this tool.
            If any precondition fails, call queryData with a single SQL.

            Each aspect provides title + sql + reason. Aspects are AST-validated,
            then every referenced table is probed against the live database.
            If any referenced table does not exist the tool returns
            'MULTI_ASPECT_FAILED: tables ... do not exist ...' — when you see
            that error you MUST call search_data_catalog with relevant keywords
            and retry runMultiAspectAnalysis using only the verified tables it
            returns. Successful aspects are executed concurrently. After all
            aspects finish a single synthesis LLM call produces (a) a 1-2
            sentence insight for each aspect and (b) one cross-aspect synthesis
            narrative. The frontend renders aspects as a grid of cards with the
            synthesis section below.

            Return value 'MULTI_ASPECT_DONE' means rendering is fully handled —
            do not call queryData afterwards. Reply with a one-line confirmation
            in the user's language; the frontend already shows the cards and
            synthesis.
            """)
        public String runMultiAspectAnalysis(
                @P("Cross-aspect synthesis goal — what insight the user is asking for, in the user's language. Example: 'Overall customer health (purchase frequency, average order value, category diversity)'.") String synthesisGoal,
                @P("JSON array of 2-3 aspects. Each item: {\"title\":\"plain text card title in user's language\",\"sql\":\"SELECT ... ;\",\"reason\":\"why this aspect is needed and why it cannot be merged with the others into one SQL\"}. SQL must be a single executable statement, no comments outside the statement, no DDL/DML.") String aspectsJson
        ) {
            try {
                return executeMultiAspectAnalysis(synthesisGoal, aspectsJson);
            } catch (Exception e) {
                log.warn("[MultiAspectAnalysisTool] failed: {}", e.getMessage(), e);
                return "MULTI_ASPECT_FAILED: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + ". Fall back to queryData with a single SQL.";
            }
        }
    }

    private String executeMultiAspectAnalysis(String synthesisGoalRaw, String aspectsJson) {
        emitProgress(progressMessage(null, "multi_aspect.start"));
        List<MultiAspectInput> parsedAspects = parseMultiAspectInput(aspectsJson);
        if (parsedAspects.size() < MIN_MULTI_ASPECTS) {
            return "MULTI_ASPECT_FAILED: need at least " + MIN_MULTI_ASPECTS
                    + " aspects, got " + parsedAspects.size()
                    + ". Fall back to queryData.";
        }
        if (parsedAspects.size() > MAX_MULTI_ASPECTS) {
            log.info("[MultiAspectAnalysisTool] truncating from {} to {} aspects",
                    parsedAspects.size(), MAX_MULTI_ASPECTS);
            parsedAspects = parsedAspects.subList(0, MAX_MULTI_ASPECTS);
        }
        for (MultiAspectInput a : parsedAspects) {
            if (astValidator != null && !astValidator.validate(a.sql)) {
                return "MULTI_ASPECT_FAILED: aspect '" + a.title + "' SQL failed AST validation. "
                        + "Fall back to queryData and try again with corrected SQL.";
            }
        }

        // Schema verification gate. We mirror the queryData pattern where
        // findSchemaContext is mandatory: extract every table referenced by
        // the proposed aspects and probe each with a cheap zero-row query.
        // If any table is missing we abort BEFORE running anything against
        // the user database, returning a structured hint so the root agent
        // automatically calls search_data_catalog and retries with real
        // tables instead of executing hallucinated SQL.
        Set<String> referencedTables = new java.util.LinkedHashSet<>();
        for (MultiAspectInput a : parsedAspects) {
            referencedTables.addAll(extractReferencedTables(a.sql));
        }
        if (!referencedTables.isEmpty()) {
            List<String> missing = findMissingTables(referencedTables);
            if (!missing.isEmpty()) {
                log.info("[MultiAspectAnalysisTool] aborting: tables not found in current database: {}", missing);
                return "MULTI_ASPECT_FAILED: the following tables referenced by your aspects do not exist in this database: "
                        + String.join(", ", missing)
                        + ". You MUST call search_data_catalog (or lookup_table_metadata) with relevant keywords first to find the real tables in this database, then retry runMultiAspectAnalysis with SQL that uses only verified tables. NEVER assume table names from general knowledge — every table must come from a schema search result this turn.";
            }
        }

        // Parallel execute + chart recommend per aspect using the DeepResearch
        // ConnectInfo-propagation pattern (each worker thread re-puts the parent
        // ConnectInfo before calling DlTemplateService).
        emitProgress(progressMessage(null, "multi_aspect.execute"));
        final ConnectInfo parentConnectInfo = InqueryContext.getConnectInfo();
        ExecutorService executor = multiAspectExecutor();
        List<CompletableFuture<MultiAspectResult>> futures = new ArrayList<>();
        for (int i = 0; i < parsedAspects.size(); i++) {
            final int idx = i;
            final MultiAspectInput input = parsedAspects.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                MultiAspectResult r = new MultiAspectResult();
                r.input = input;
                r.aspectId = "a" + (idx + 1);
                try {
                    if (parentConnectInfo != null) {
                        InqueryContext.putContext(parentConnectInfo);
                    }
                    DlExecuteParam param = new DlExecuteParam();
                    param.setSql(input.sql);
                    param.setDataSourceId(dataSourceId);
                    param.setDatabaseName(databaseName);
                    param.setSchemaName(schemaName);
                    ListResult<ExecuteResult> exec = dlTemplateService.execute(param);
                    if (exec != null && exec.success() && exec.getData() != null && !exec.getData().isEmpty()) {
                        r.execute = exec.getData().get(0);
                        // NOTE: chart recommendation runs on the main thread
                        // below — LangChainModelProvider.getConfig depends on
                        // the MyBatis SqlSession bound to the request thread,
                        // which is NOT propagated to worker pools. Calling
                        // chartEngine.recommendChart here loaded an empty API
                        // key and silently fell back to a default chart.
                    } else {
                        r.errorMessage = exec == null
                                ? "Execution returned null"
                                : (exec.getErrorMessage() == null ? "Unknown execution error" : exec.getErrorMessage());
                    }
                } catch (Exception ex) {
                    r.errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    log.warn("[MultiAspectAnalysisTool] aspect '{}' failed: {}", input.title, r.errorMessage);
                }
                return r;
            }, executor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception waitEx) {
            log.warn("[MultiAspectAnalysisTool] aspect execution wait failed: {}", waitEx.getMessage());
        }
        List<MultiAspectResult> results = new ArrayList<>();
        for (CompletableFuture<MultiAspectResult> f : futures) {
            try {
                results.add(f.getNow(failedAspect("future not ready")));
            } catch (Exception ex) {
                results.add(failedAspect(ex.getMessage()));
            }
        }

        // Chart recommendation MUST run on the main request thread because
        // LangChainModelProvider.getConfig resolves the OpenAI key from a
        // MyBatis SqlSession bound to this thread (not propagated to the
        // worker pool). Sequential 2-3 LLM calls add ~1-2 seconds total —
        // negligible vs the parallel SQL phase that just finished.
        if (chartEngine != null) {
            for (MultiAspectResult r : results) {
                if (r.execute == null || r.input == null) continue;
                try {
                    r.chart = chartEngine.recommendChart(r.execute, r.input.title, modelName);
                } catch (Exception ce) {
                    log.warn("[MultiAspectAnalysisTool] chart recommend failed for aspect '{}': {}",
                            r.input.title, ce.getMessage());
                }
            }
        }

        emitProgress(progressMessage(null, "multi_aspect.synthesize"));
        Map<String, String> synthMap = runMultiAspectSynthesis(synthesisGoalRaw, results);

        // Map results into dataQueries (QueryItem with per-aspect chart fields)
        // so the existing frontend grid renderer + maximize logic just works.
        List<QueryProcessingResult.QueryItem> items = new ArrayList<>();
        for (MultiAspectResult r : results) {
            QueryProcessingResult.QueryItem qi = new QueryProcessingResult.QueryItem();
            qi.setTitle(r.input == null ? "Aspect" : r.input.title);
            qi.setSql(r.input == null ? "" : r.input.sql);
            qi.setExplanation(r.input == null ? "" : r.input.reason);
            qi.setResult(r.execute);
            qi.setAspectId(r.aspectId);
            qi.setAspectReason(r.input == null ? null : r.input.reason);
            qi.setAspectErrorMessage(r.errorMessage);
            qi.setAspectInsight(synthMap.get(r.aspectId));
            applyChartRecommendation(qi, r.chart);
            items.add(qi);
        }
        this.dataQueries = items;
        this.multiAspect = true;
        this.synthesisGoal = synthesisGoalRaw;
        this.synthesis = synthMap.get("__synthesis__");
        // Mark dataFullResponse non-null so downstream "data was produced"
        // checks (e.g. additionalInsightSummary trigger, agentResponse mapping)
        // know a structured payload exists even though there is no markdown.
        if (this.dataFullResponse == null || this.dataFullResponse.isBlank()) {
            this.dataFullResponse = "[MULTI_ASPECT_RESULT]";
        }
        if (this.dataOverview == null || this.dataOverview.isBlank()) {
            this.dataOverview = synthesisGoalRaw;
        }
        emitProgress(progressMessage(null, "multi_aspect.done"));
        return "MULTI_ASPECT_DONE. Rendered " + items.size()
                + " aspect cards plus synthesis. Reply with only a one-line confirmation in the user's language.";
    }

    /** Per-aspect input parsed from the LLM JSON. */
    private static class MultiAspectInput {
        String title;
        String sql;
        String reason;
    }

    /** Per-aspect execution + chart result. */
    private static class MultiAspectResult {
        MultiAspectInput input;
        String aspectId;
        ExecuteResult execute;
        ChartRecommendationEngine.ChartRecommendation chart;
        String errorMessage;
    }

    private MultiAspectResult failedAspect(String message) {
        MultiAspectResult r = new MultiAspectResult();
        r.errorMessage = message == null ? "unknown error" : message;
        return r;
    }

    private List<MultiAspectInput> parseMultiAspectInput(String aspectsJson) {
        List<MultiAspectInput> out = new ArrayList<>();
        if (aspectsJson == null || aspectsJson.isBlank()) return out;
        try {
            com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(aspectsJson.trim());
            if (arr == null) return out;
            for (int i = 0; i < arr.size(); i++) {
                com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                MultiAspectInput a = new MultiAspectInput();
                a.title = nullSafe(o.getString("title"));
                a.sql = nullSafe(o.getString("sql"));
                a.reason = nullSafe(o.getString("reason"));
                if (a.sql.isBlank() || a.title.isBlank()) continue;
                out.add(a);
            }
        } catch (Exception e) {
            log.warn("[MultiAspectAnalysisTool] failed to parse aspects JSON: {}", e.getMessage());
        }
        return out;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Extract every table referenced by the SQL using JSqlParser's
     * TablesNamesFinder (same parser the AstValidator already uses).
     * Returns lowercased names (with optional schema/database prefix
     * preserved) for case-insensitive comparison. Returns an empty set
     * if parsing fails — the caller treats "no extractable tables" as
     * "skip the gate" so a parser limitation never blocks a valid query.
     */
    private Set<String> extractReferencedTables(String sql) {
        Set<String> out = new java.util.LinkedHashSet<>();
        try {
            net.sf.jsqlparser.statement.Statement stmt =
                    net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            if (stmt instanceof net.sf.jsqlparser.statement.select.Select) {
                net.sf.jsqlparser.util.TablesNamesFinder finder =
                        new net.sf.jsqlparser.util.TablesNamesFinder();
                List<String> names = finder.getTableList(
                        (net.sf.jsqlparser.statement.select.Select) stmt);
                if (names != null) {
                    for (String n : names) {
                        if (n != null && !n.isBlank()) {
                            out.add(n.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[MultiAspectAnalysisTool] table name extraction failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Probe each table with {@code SELECT * FROM <table> WHERE 1=0}. This
     * works across every dialect we support, costs ~0 (no rows scanned)
     * and surfaces "relation does not exist" instantly when the LLM
     * hallucinated a table name. Returns the subset of inputs that were
     * NOT found in the current database/schema, preserving the original
     * table-name string so the error hint shown to the LLM is verbatim.
     */
    private List<String> findMissingTables(Set<String> tableNames) {
        List<String> missing = new ArrayList<>();
        for (String table : tableNames) {
            if (table == null || table.isBlank()) continue;
            try {
                DlExecuteParam p = new DlExecuteParam();
                p.setSql("SELECT * FROM " + table + " WHERE 1=0");
                p.setDataSourceId(dataSourceId);
                p.setDatabaseName(databaseName);
                p.setSchemaName(schemaName);
                ListResult<ExecuteResult> r = dlTemplateService.execute(p);
                if (r == null || !r.success()) {
                    missing.add(table);
                }
            } catch (Exception e) {
                missing.add(table);
            }
        }
        return missing;
    }

    private ExecutorService multiAspectExecutor() {
        // Bounded pool sized to MAX_MULTI_ASPECTS. Daemon threads so the JVM
        // does not wait on us at shutdown. Created fresh per call because
        // multi-aspect calls are rare and short-lived; this avoids holding a
        // persistent pool on the per-request InqueryRootAgentRunner instance.
        return Executors.newFixedThreadPool(MAX_MULTI_ASPECTS, r -> {
            Thread t = new Thread(r, "multi-aspect-worker");
            t.setDaemon(true);
            return t;
        });
    }

    private void applyChartRecommendation(QueryProcessingResult.QueryItem qi,
                                          ChartRecommendationEngine.ChartRecommendation rec) {
        if (rec == null) return;
        if (rec.getChartType() != null) qi.setRecommendedChart(rec.getChartType().name());
        qi.setChartXAxis(rec.getXAxis());
        qi.setChartYAxis(rec.getYAxis());
        qi.setChartDimension(rec.getDimension());
        qi.setChartDimensions(rec.getDimensions());
        qi.setChartXAxisFormat(rec.getXAxisFormat());
        qi.setChartYAxisFormat(rec.getYAxisFormat());
        qi.setChartLineVariant(rec.getLineVariant());
        qi.setChartPieVariant(rec.getPieVariant());
        qi.setChartBarOrientation(rec.getBarOrientation());
        qi.setChartOrder(rec.getOrder());
    }

    /**
     * Run a single synthesis LLM call that returns:
     *  - one 1-2 sentence insight per aspect (keyed by aspectId)
     *  - one cross-aspect synthesis narrative (keyed by "__synthesis__")
     * The same STRICT OUTPUT RESTRICTIONS as the single-query interpreter
     * apply: no ASCII charts, no raw markdown tables of the data, no row
     * dumps. The UI already shows tables and charts for each aspect.
     */
    private Map<String, String> runMultiAspectSynthesis(String synthesisGoal, List<MultiAspectResult> results) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            StringBuilder ctx = new StringBuilder();
            for (MultiAspectResult r : results) {
                ctx.append("=== Aspect ").append(r.aspectId).append(" — ")
                        .append(r.input == null ? "" : r.input.title).append(" ===\n");
                if (r.input != null) {
                    ctx.append("Reason: ").append(truncate(r.input.reason, 400)).append("\n");
                    ctx.append("SQL:\n```sql\n").append(truncate(r.input.sql, 1200)).append("\n```\n");
                }
                if (r.errorMessage != null) {
                    ctx.append("Status: FAILED — ").append(truncate(r.errorMessage, 400)).append("\n\n");
                    continue;
                }
                ctx.append("Status: OK\n");
                ctx.append("Result preview (top rows):\n");
                ctx.append(formatExecutePreview(r.execute, 20)).append("\n\n");
            }
            String prompt = """
                    You are a senior data analyst producing a COMPLEMENTARY multi-aspect synthesis.

                    Output a STRICT JSON object with this exact shape and no extra keys:
                    {
                      "perAspect": { "a1": "1-2 sentence insight in the user's language", "a2": "...", "a3": "..." },
                      "synthesis": "Cross-aspect narrative answering the synthesis goal in the user's language"
                    }

                    Strict rules:
                    - Respond in the SAME LANGUAGE as the synthesis goal.
                    - perAspect: keys MUST match the aspect ids actually present in the context (a1, a2, ...). Each value: 1 to 2 plain sentences, no bullet lists, no markdown headers, no code fences.
                    - synthesis: 2 to 5 short paragraphs OR up to 6 bullet points. Focus on cross-aspect ranking / share / gaps / drivers / actionable suggestions.
                    - NEVER include ASCII / text-art charts (no `●`, `█`, `|`, `─`, `+`, `-`, `*`, `·` art). Charts are rendered by the UI.
                    - NEVER re-print the raw result as a markdown table, CSV, JSON, or row-by-row list. Tables are rendered by the UI.
                    - You MAY cite a handful of specific numbers inline inside a sentence to support an insight; do not enumerate every row.
                    - For FAILED aspects: in perAspect explain in one sentence that the aspect failed and what would be needed to recover; in synthesis explicitly call out which aspects are missing so the user knows the answer is partial.

                    Synthesis goal:
                    %s

                    Aspect context:
                    %s
                    """.formatted(nullSafe(synthesisGoal), ctx.toString().trim());
            ChatModel chatModel = modelProvider.getPlainChatModel(modelName);
            String response = chatModel.chat(prompt);
            String json = extractJsonObject(response);
            if (json != null) {
                com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(json);
                if (obj != null) {
                    com.alibaba.fastjson2.JSONObject per = obj.getJSONObject("perAspect");
                    if (per != null) {
                        for (String k : per.keySet()) {
                            String v = per.getString(k);
                            if (v != null && !v.isBlank()) out.put(k, v.trim());
                        }
                    }
                    String synth = obj.getString("synthesis");
                    if (synth != null && !synth.isBlank()) out.put("__synthesis__", synth.trim());
                }
            }
        } catch (Exception e) {
            log.warn("[MultiAspectAnalysisTool] synthesis LLM call failed: {}", e.getMessage());
        }
        if (!out.containsKey("__synthesis__")) {
            out.put("__synthesis__", "Could not generate a combined synthesis. Please review each card individually.");
        }
        return out;
    }

    private String formatExecutePreview(ExecuteResult exec, int maxRows) {
        if (exec == null || exec.getHeaderList() == null || exec.getDataList() == null) {
            return "(no rows)";
        }
        StringBuilder sb = new StringBuilder();
        List<Header> headers = exec.getHeaderList();
        List<String> headerNames = new ArrayList<>();
        for (Header h : headers) headerNames.add(h.getName() == null ? "" : h.getName());
        sb.append(String.join(" | ", headerNames)).append("\n");
        int rows = Math.min(exec.getDataList().size(), maxRows);
        for (int i = 0; i < rows; i++) {
            List<String> row = exec.getDataList().get(i);
            sb.append(row == null ? "" : String.join(" | ", row)).append("\n");
        }
        int total = exec.getDataList().size();
        if (total > rows) {
            sb.append("... ").append(total - rows).append(" more rows (UI shows the full table)");
        } else {
            sb.append("(").append(total).append(" rows total)");
        }
        return sb.toString();
    }

    private String extractJsonObject(String response) {
        if (response == null) return null;
        String s = response.trim();
        int fenceStart = s.indexOf("```");
        if (fenceStart >= 0) {
            int firstNl = s.indexOf('\n', fenceStart);
            int fenceEnd = s.indexOf("```", fenceStart + 3);
            if (firstNl >= 0 && fenceEnd > firstNl) {
                s = s.substring(firstNl + 1, fenceEnd).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    /**
     * Strip markdown noise and accidental command-style phrasing from a
     * chart title proposed by the LLM. Chart canvases render the title as
     * plain text, so stars, backticks, hash headers, leading dashes /
     * numbering, surrounding quotes, and trailing periods all look bad.
     * Returns null when the LLM did not propose a title (caller keeps the
     * previous title).
     */
    private String sanitizeChartTitle(String rawTitle) {
        if (rawTitle == null) return null;
        String cleaned = rawTitle.trim();
        if (cleaned.isEmpty()) return null;
        // Drop markdown bold/italic/code markers and headers anywhere in the string.
        cleaned = cleaned.replaceAll("[`*_~#]", "");
        // Drop wrapping quotes (matched pairs only — preserve legitimate apostrophes).
        cleaned = cleaned.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "");
        // Drop leading list markers like "1. ", "- ", "• ".
        cleaned = cleaned.replaceAll("^\\s*(?:[-•]|\\d+\\.)\\s+", "");
        // Collapse internal whitespace.
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Drop trailing period / Korean full stop — chart titles read better without them.
        cleaned = cleaned.replaceAll("[.。．]+$", "").trim();
        if (cleaned.isEmpty()) return null;
        // 120 chars is enough even for verbose languages; clamp to keep canvas layout sane.
        return cleaned.length() > 120 ? cleaned.substring(0, 120).trim() : cleaned;
    }

    private String normalizeChartType(String chartType) {
        if (chartType == null) return "BAR";
        String normalized = chartType.trim().toUpperCase()
                .replace(" ", "_")
                .replace("-", "_");
        return switch (normalized) {
            case "BAR", "LINE", "PIE", "SCATTER", "TABLE", "CARD" -> normalized;
            default -> "BAR";
        };
    }

    private String buildAdditionalInsightSummary(String userMessage, Map<String, String> searchResultsByService,
                                                 boolean dataWasExecuted) {
        if (searchResultsByService == null || searchResultsByService.isEmpty()) return null;
        StringBuilder context = new StringBuilder();
        searchResultsByService.forEach((service, result) -> context.append("## ")
                .append(service)
                .append("\n")
                .append(truncate(result, 2500))
                .append("\n\n"));
        String prompt = """
                Summarize the additional non-database tool results for the user's multi-domain question.

                Rules:
                - Write in the SAME LANGUAGE as the user's question.
                - Keep it concise: 3-6 bullets maximum.
                - Preserve concrete source names or URLs when present.
                - Do not restate SQL, chart, or database results; those are rendered separately.
                - Data execution status: %s.
                - If the data was not executed yet, present these results only as comparison context for after the user runs the SQL. Do not claim the database results were already compared.
                - If a service was not configured or returned no results, mention that briefly.

                User question:
                %s

                Tool results:
                %s
                """.formatted(dataWasExecuted ? "executed" : "not executed yet", userMessage, context);
        try {
            return modelProvider.getPlainChatModel(modelName).chat(prompt);
        } catch (Exception e) {
            log.warn("[InqueryRootAgentRunner] additional insight summary failed: {}", e.getMessage());
            return renderFallbackAdditionalInsights(searchResultsByService);
        }
    }

    private String appendAdditionalInsights(String overview, String additionalSummary) {
        if (additionalSummary == null || additionalSummary.isBlank()) return overview;
        String base = overview == null ? "" : overview.trim();
        String section = "**" + additionalInsightHeading(additionalSummary) + "**\n\n" + additionalSummary.trim();
        return base.isBlank() ? section : base + "\n\n---\n\n" + section;
    }

    private String additionalInsightHeading(String text) {
        return switch (detectFallbackLanguage(text)) {
            case JAPANESE -> "追加で確認した外部/ドキュメントのインサイト";
            case CHINESE -> "额外确认的外部/文档洞察";
            case TURKISH -> "Ek dış/doküman içgörüleri";
            case ENGLISH -> "Additional insights from other sources";
        };
    }

    private String renderFallbackAdditionalInsights(Map<String, String> searchResultsByService) {
        StringBuilder sb = new StringBuilder();
        searchResultsByService.forEach((service, result) -> sb.append("- **")
                .append(service)
                .append("**: ")
                .append(truncate(result.replaceAll("\\s+", " "), 500))
                .append("\n"));
        return sb.toString().trim();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * Fallback message in the language the user typed. The normal model
     * path follows the root prompt's same-language rule; this only covers
     * rare cases where the model emitted no final text or the plain-chat
     * fallback failed.
     */
    private static String fallbackMessage(String userMessage, boolean askForMoreDetail) {
        return switch (detectFallbackLanguage(userMessage)) {
            case JAPANESE -> askForMoreDetail
                    ? "回答を生成できませんでした。質問をもう少し具体的に言い換えていただけますか？"
                    : "回答を生成できませんでした。しばらくしてからもう一度お試しください。";
            case CHINESE -> askForMoreDetail
                    ? "未能生成回答。请把问题描述得更具体一些。"
                    : "未能生成回答。请稍后重试。";
            case TURKISH -> askForMoreDetail
                    ? "Yanıt oluşturamadım. Soruyu biraz daha ayrıntılı şekilde yeniden ifade eder misiniz?"
                    : "Yanıt oluşturamadım. Lütfen biraz sonra tekrar deneyin.";
            case ENGLISH -> askForMoreDetail
                    ? "I couldn't generate a response. Could you rephrase the question with a bit more detail?"
                    : "I couldn't generate a response. Please try again in a moment.";
        };
    }

    /**
     * Script-based fallback language detector. We intentionally do not use
     * the app UI locale: chat answers should follow the user's message.
     */
    private static FallbackLanguage detectFallbackLanguage(String text) {
        if (text == null || text.isEmpty()) return FallbackLanguage.ENGLISH;
        boolean hasCjkUnified = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3)
                    || (c >= 0x1100 && c <= 0x11FF)
                    || (c >= 0x3130 && c <= 0x318F)) {
                return FallbackLanguage.ENGLISH;
            }
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                return FallbackLanguage.JAPANESE;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                hasCjkUnified = true;
            }
            if ("çğıöşüÇĞİÖŞÜ".indexOf(c) >= 0) {
                return FallbackLanguage.TURKISH;
            }
        }
        return hasCjkUnified ? FallbackLanguage.CHINESE : FallbackLanguage.ENGLISH;
    }

    private void emitProgress(String message) {
        if (progressCallback == null || message == null || message.isBlank()) return;
        if (message.equals(lastProgressMessage)) return;
        lastProgressMessage = message;
        try {
            progressCallback.accept(message);
        } catch (Exception e) {
            log.debug("[InqueryRootAgentRunner] progress callback threw: {}", e.getMessage());
        }
    }

    private static String progressMessage(String userMessage, String key) {
        return switch (detectFallbackLanguage(userMessage)) {
            case JAPANESE -> switch (key) {
                case "root.analyzing" -> "質問を分析しています...";
                case "root.planning" -> "作業計画を立てています...";
                case "root.selecting" -> "必要な情報をどこで探すか判断しています...";
                case "mcp.ready" -> "接続された外部ツールを確認しました...";
                case "data.plan" -> "データ分析の手順を組み立てています...";
                case "data.start" -> "データに関する質問として分析を開始しています...";
                case "data.schema_search" -> "関連するテーブルとカラムを探しています...";
                case "data.sql_generate" -> "データ分析用のSQLを準備しています...";
                case "data.sql_validate" -> "生成されたSQLを検証しています...";
                case "data.manual_ready" -> "実行前に確認するSQLを整理しています...";
                case "data.execute" -> "クエリを実行しています...";
                case "data.sql_fix" -> "クエリのエラーを修正して再試行しています...";
                case "data.chart" -> "結果に合う可視化を検討しています...";
                case "metadata.lookup" -> "テーブル情報を確認しています...";
                case "metadata.lineage" -> "テーブルのリネージ情報を確認しています...";
                case "metadata.probe" -> "データベースで候補を確認しています...";
                case "metadata.compare" -> "セグメントの違いを比較しています...";
                case "metadata.plan" -> "分析方針を設計しています...";
                case "metadata.quality" -> "データ品質を確認しています...";
                case "metadata.definition" -> "指標定義を確認しています...";
                case "metadata.profile" -> "テーブル分布をプロファイリングしています...";
                case "metadata.volume" -> "参照するデータ量を確認しています...";
                case "search.confluence" -> "Wikiドキュメントを検索しています...";
                case "search.reference_documents" -> "アップロードされた参照ドキュメントを検索しています...";
                case "search.slack" -> "Slackの会話を検索しています...";
                case "search.jira" -> "Jiraの課題を検索しています...";
                case "search.github" -> "GitHubの情報を検索しています...";
                case "search.google" -> "Google Driveのドキュメントを検索しています...";
                case "search.outlook" -> "Outlookメールを検索しています...";
                case "search.web" -> "Webで最新情報を検索しています...";
                case "write.slack" -> "Slackメッセージの下書きを準備しています...";
                case "write.confluence" -> "Confluenceページの下書きを準備しています...";
                case "write.jira" -> "Jira課題の下書きを準備しています...";
                case "ux.date_range" -> "必要な期間情報を確認しています...";
                case "ux.clarify" -> "質問を正確に理解するための選択肢を準備しています...";
                case "multi_aspect.start" -> "複数の観点を同時に分析する準備をしています...";
                case "multi_aspect.execute" -> "各観点のクエリを並列実行しています...";
                case "multi_aspect.synthesize" -> "観点ごとの結果を統合して分析しています...";
                case "multi_aspect.done" -> "統合分析が完了しました。";
                default -> "回答を準備しています...";
            };
            case CHINESE -> switch (key) {
                case "root.analyzing" -> "正在分析问题...";
                case "root.planning" -> "正在制定执行计划...";
                case "root.selecting" -> "正在判断需要从哪里查找信息...";
                case "mcp.ready" -> "已检查已连接的外部工具...";
                case "data.plan" -> "正在规划数据分析流程...";
                case "data.start" -> "已识别为数据问题，正在开始分析...";
                case "data.schema_search" -> "正在查找相关表和字段...";
                case "data.sql_generate" -> "正在准备数据分析 SQL...";
                case "data.sql_validate" -> "正在验证生成的 SQL...";
                case "data.manual_ready" -> "正在整理待确认的 SQL...";
                case "data.execute" -> "正在执行查询...";
                case "data.sql_fix" -> "正在修正查询错误并重试...";
                case "data.chart" -> "正在评估适合结果的可视化方式...";
                case "metadata.lookup" -> "正在查看表信息...";
                case "metadata.lineage" -> "正在查看表血缘信息...";
                case "metadata.probe" -> "正在数据库中确认候选项...";
                case "metadata.compare" -> "正在比较分组差异...";
                case "metadata.plan" -> "正在设计分析方向...";
                case "metadata.quality" -> "正在检查数据质量...";
                case "metadata.definition" -> "正在查看指标定义...";
                case "metadata.profile" -> "正在分析表分布...";
                case "metadata.volume" -> "正在确认要查询的数据规模...";
                case "search.confluence" -> "正在搜索 Wiki 文档...";
                case "search.reference_documents" -> "正在搜索已上传的参考文档...";
                case "search.slack" -> "正在搜索 Slack 对话...";
                case "search.jira" -> "正在搜索 Jira 问题...";
                case "search.github" -> "正在搜索 GitHub 资料...";
                case "search.google" -> "正在搜索 Google Drive 文档...";
                case "search.outlook" -> "正在搜索 Outlook 邮件...";
                case "search.web" -> "正在网页上搜索最新信息...";
                case "write.slack" -> "正在准备 Slack 消息草稿...";
                case "write.confluence" -> "正在准备 Confluence 页面草稿...";
                case "write.jira" -> "正在准备 Jira 问题草稿...";
                case "ux.date_range" -> "正在确认所需的时间范围...";
                case "ux.clarify" -> "正在准备选项以更准确理解问题...";
                case "multi_aspect.start" -> "正在准备并行分析多个角度...";
                case "multi_aspect.execute" -> "正在并行执行各角度的查询...";
                case "multi_aspect.synthesize" -> "正在综合各角度结果进行分析...";
                case "multi_aspect.done" -> "综合分析已完成。";
                default -> "正在准备回答...";
            };
            case TURKISH -> switch (key) {
                case "root.analyzing" -> "Sorunuzu analiz ediyorum...";
                case "root.planning" -> "Çalışma planını oluşturuyorum...";
                case "root.selecting" -> "Gerekli bilgiyi nerede arayacağımı belirliyorum...";
                case "mcp.ready" -> "Bağlı harici araçları kontrol ettim...";
                case "data.plan" -> "Veri analizi akışını planlıyorum...";
                case "data.start" -> "Bunu bir veri sorusu olarak analiz etmeye başlıyorum...";
                case "data.schema_search" -> "İlgili tabloları ve sütunları arıyorum...";
                case "data.sql_generate" -> "Veri analizi sorgusunu hazırlıyorum...";
                case "data.sql_validate" -> "Oluşturulan SQL'i doğruluyorum...";
                case "data.manual_ready" -> "Çalıştırmadan önce onaylanacak SQL'i hazırlıyorum...";
                case "data.execute" -> "Sorguyu çalıştırıyorum...";
                case "data.sql_fix" -> "Sorgu hatasını düzeltip yeniden deniyorum...";
                case "data.chart" -> "Sonuçlara uygun görselleştirmeyi değerlendiriyorum...";
                case "metadata.lookup" -> "Tablo bilgilerini kontrol ediyorum...";
                case "metadata.lineage" -> "Tablo soy ağacı bilgilerini kontrol ediyorum...";
                case "metadata.probe" -> "Veritabanında adayları kontrol ediyorum...";
                case "metadata.compare" -> "Segment farklarını karşılaştırıyorum...";
                case "metadata.plan" -> "Analiz yaklaşımını tasarlıyorum...";
                case "metadata.quality" -> "Veri kalitesini kontrol ediyorum...";
                case "metadata.definition" -> "Metrik tanımını kontrol ediyorum...";
                case "metadata.profile" -> "Tablo dağılımını profilliyorum...";
                case "metadata.volume" -> "Sorgulanacak veri hacmini kontrol ediyorum...";
                case "search.confluence" -> "Wiki belgelerini arıyorum...";
                case "search.reference_documents" -> "Yüklenen referans belgeleri arıyorum...";
                case "search.slack" -> "Slack konuşmalarını arıyorum...";
                case "search.jira" -> "Jira kayıtlarını arıyorum...";
                case "search.github" -> "GitHub içeriklerini arıyorum...";
                case "search.google" -> "Google Drive belgelerini arıyorum...";
                case "search.outlook" -> "Outlook e-postalarını arıyorum...";
                case "search.web" -> "Web'de güncel bilgileri arıyorum...";
                case "write.slack" -> "Slack mesaj taslağını hazırlıyorum...";
                case "write.confluence" -> "Confluence sayfa taslağını hazırlıyorum...";
                case "write.jira" -> "Jira kayıt taslağını hazırlıyorum...";
                case "ux.date_range" -> "Gerekli zaman aralığını netleştiriyorum...";
                case "ux.clarify" -> "Soruyu daha iyi anlamak için seçenekler hazırlıyorum...";
                case "multi_aspect.start" -> "Birden çok yönü paralel olarak analiz etmeye hazırlanıyorum...";
                case "multi_aspect.execute" -> "Her yönün sorgusunu paralel çalıştırıyorum...";
                case "multi_aspect.synthesize" -> "Yönlere göre sonuçları birleştirip analiz ediyorum...";
                case "multi_aspect.done" -> "Bütünleşik analiz tamamlandı.";
                default -> "Yanıtı hazırlıyorum...";
            };
            case ENGLISH -> switch (key) {
                case "root.analyzing" -> "Analyzing your question...";
                case "root.planning" -> "Planning the work...";
                case "root.selecting" -> "Deciding where to find the information...";
                case "mcp.ready" -> "Checking connected external tools...";
                case "data.plan" -> "Planning the data analysis workflow...";
                case "data.start" -> "Starting the data analysis flow...";
                case "data.schema_search" -> "Finding relevant tables and columns...";
                case "data.sql_generate" -> "Preparing the data analysis query...";
                case "data.sql_validate" -> "Validating the generated SQL...";
                case "data.manual_ready" -> "Preparing the SQL for your review...";
                case "data.execute" -> "Running the query...";
                case "data.sql_fix" -> "Fixing the query and trying again...";
                case "data.chart" -> "Checking the best visualization for the result...";
                case "metadata.lookup" -> "Checking table information...";
                case "metadata.lineage" -> "Checking table lineage...";
                case "metadata.probe" -> "Checking candidates in the database...";
                case "metadata.compare" -> "Comparing segment differences...";
                case "metadata.plan" -> "Designing the analysis approach...";
                case "metadata.quality" -> "Checking data quality...";
                case "metadata.definition" -> "Checking metric definition...";
                case "metadata.profile" -> "Profiling table distribution...";
                case "metadata.volume" -> "Checking the data volume to query...";
                case "search.confluence" -> "Searching wiki documents...";
                case "search.reference_documents" -> "Searching uploaded reference documents...";
                case "search.slack" -> "Searching Slack conversations...";
                case "search.jira" -> "Searching Jira issues...";
                case "search.github" -> "Searching GitHub materials...";
                case "search.google" -> "Searching Google Drive documents...";
                case "search.outlook" -> "Searching Outlook email...";
                case "search.web" -> "Searching the web for current information...";
                case "write.slack" -> "Preparing a Slack message draft...";
                case "write.confluence" -> "Preparing a Confluence page draft...";
                case "write.jira" -> "Preparing a Jira issue draft...";
                case "ux.date_range" -> "Checking the needed time range...";
                case "ux.clarify" -> "Preparing options to clarify the question...";
                case "multi_aspect.start" -> "Preparing to analyze multiple aspects in parallel...";
                case "multi_aspect.execute" -> "Running each aspect's query in parallel...";
                case "multi_aspect.synthesize" -> "Synthesizing insights across the aspects...";
                case "multi_aspect.done" -> "Multi-aspect analysis complete.";
                default -> "Preparing an answer...";
            };
        };
    }

    private enum FallbackLanguage {
        JAPANESE,
        CHINESE,
        TURKISH,
        ENGLISH
    }

    /**
     * Tool exposed to the root agent for ALL data questions.
     *
     * <p>Faithfully replays the legacy {@code QueryProcessingServiceImpl}
     * flow so the only difference from the classifier path is WHO decides
     * the tool gets called (LLM tool-calling vs. JSON classifier). The
     * code path inside the tool is identical: schema-search →
     * {@link SqlGenerator#buildStreamingPrompt} → streaming markdown →
     * SQL extraction → AST validate → (Auto only) execute + chart.
     */
    public class QueryDataTool {

        String lastSqlAttempted;
        ExecuteResult lastSqlResult;
        String lastSqlError;
        boolean queryDataAlreadyRun;

        private interface DataWorkflowAgent {
            @SystemMessage("""
                    You are the internal data workflow agent for query_data.
                    Choose and call the available tools in the order that best
                    answers the user's data request.

                    Tool contract:
                    - search_schema finds relevant database context.
                    - generate_sql creates the standard overview + SQL option markdown.
                    - validate_sql extracts and validates the generated SQL.
                    - parse_response maps markdown into the existing UI payload fields.
                    - execute_sql runs the validated SQL only when execution mode allows it.
                    - recommend_chart chooses chart metadata after execution.

                    Rules:
                    - You may skip unnecessary tools, but do not call execute_sql
                      before SQL has been generated and validated.
                    - For normal data analysis, the useful order is usually
                      search_schema -> generate_sql -> validate_sql ->
                      parse_response -> execute_sql -> recommend_chart.
                    - In Manual mode, stop after parse_response.
                    - Do not produce a user-facing answer. Return only DATA_WORKFLOW_DONE.
                    """)
            String run(@UserMessage String prompt);
        }

        @Tool("Answer general questions about the user's internal database — metrics, KPIs, counts, breakdowns, aggregations, dimension/ID lookups, code meanings. Generates SQL and (in Auto mode) executes it, returning the standard overview + useful SQL option(s) + chart UX. For explicit segment/cohort/category comparisons or difference analysis, prefer compareSegments. This data pipeline is stateless: pass only the current request in question and put any relevant prior data context in conversation_context. For pure table-structure / column / lineage questions use lookup_table_metadata instead; for INFORMATION_SCHEMA or DISTINCT existence probes use run_readonly_sql.")
        public String queryData(
                @P("The current natural-language question to answer with the user's data. Do not include the full chat transcript here.") String question,
                @P("Optional compact summary of relevant prior data context only: previous table names, generated SQL, date ranges, filters, grain, verified values, known limitations, or what was just found. Pass empty string if standalone; do not include the full chat transcript.") String conversationContext
        ) {
            if (queryDataAlreadyRun || (dataFullResponse != null && !dataFullResponse.isBlank())) {
                log.info("[InqueryRootAgentRunner] queryData duplicate call ignored: {}", question);
                return "DATA_QUERY_DONE. query_data already completed in this turn. "
                        + "STOP calling tools. Reply with exactly \"DATA_QUERY_DONE\".";
            }
            queryDataAlreadyRun = true;
            log.info("[InqueryRootAgentRunner] queryData invoked (executeQuery={}, hasContext={}): {}",
                    executeQuery, conversationContext != null && !conversationContext.isBlank(), question);
            emitProgress(progressMessage(question, "data.start"));
            return runDataQuery(question, normalizeConversationContext(conversationContext));
        }

        String queryDataFast(String question, String conversationContext) {
            if (queryDataAlreadyRun || (dataFullResponse != null && !dataFullResponse.isBlank())) {
                log.info("[InqueryRootAgentRunner] queryData fast path duplicate ignored: {}", question);
                return "DATA_QUERY_DONE";
            }
            queryDataAlreadyRun = true;
            log.info("[InqueryRootAgentRunner] queryData fast path invoked (executeQuery={}, hasContext={}): {}",
                    executeQuery, conversationContext != null && !conversationContext.isBlank(), question);
            emitProgress(progressMessage(question, "data.start"));
            return runDataQueryFast(question, normalizeConversationContext(conversationContext));
        }

        /**
         * Single legacy-compatible pipeline for Manual + Auto modes.
         */
        private String runDataQuery(String question, String conversationContext) {
            return new DataQueryWorkflow(question, conversationContext).run();
        }

        private String runDataQueryFast(String question, String conversationContext) {
            return new DataQueryWorkflow(question, conversationContext).runFast();
        }

        /**
         * Safe internal workflow behind query_data.
         *
         * <p>The root agent still sees one stable query_data tool, but the
         * data path is split into explicit workflow steps. This gives us the
         * tool-calling-style boundaries we want without letting the LLM reorder
         * SQL generation, validation, execution, charting, or response payload
         * assembly in ways that would break the existing UI contract.
         */
        private class DataQueryWorkflow {
            private final State state;

            DataQueryWorkflow(String question, String conversationContext) {
                this.state = new State(question, conversationContext);
            }

            String run() {
                try {
                    DataWorkflowAgent agent = AiServices.builder(DataWorkflowAgent.class)
                            .chatModel(modelProvider.getToolCallingChatModel(modelName))
                            .tools(new DataWorkflowTools())
                            .maxSequentialToolsInvocations(MAX_TOOL_INVOCATIONS)
                            .build();
                    agent.run(buildWorkflowPrompt());
                } catch (Exception e) {
                    log.warn("[InqueryRootAgentRunner] data workflow agent failed, falling back to safe flow: {}",
                            e.getMessage());
                    runSafeFallbackFlow();
                }

                String stopReason = finalizeWorkflow();
                if (stopReason != null) return stopReason;

                return "DATA_QUERY_DONE. Auto mode — SQL executed and results attached. "
                        + "STOP calling tools. Reply with exactly \"DATA_QUERY_DONE\".";
            }

            String runFast() {
                runSafeFallbackFlow();
                String stopReason = finalizeWorkflow();
                if (stopReason != null) return stopReason;
                return "DATA_QUERY_DONE";
            }

            private String buildWorkflowPrompt() {
                return """
                        User data question:
                        %s

                        Conversation context:
                        %s

                        Execution mode:
                        %s

                        Preserve the existing Inquery data UX. The final UI payload
                        is assembled by the server from your tool side effects.
                        """.formatted(
                        state.question,
                        state.conversationContext == null ? "" : state.conversationContext,
                        executeQuery ? "Auto: execute SQL and recommend chart when useful" : "Manual: generate SQL only");
            }

            private void runSafeFallbackFlow() {
                if (state.schemaList.isEmpty()) findSchemaContext(state.question);
                if (state.fullResponse == null || state.fullResponse.isBlank()) {
                    generateSqlMarkdown(state.question);
                }
                if (state.generatedSql == null || state.generatedSql.isBlank()) {
                    validateGeneratedSql();
                }
                if (dataQueries == null || dataQueries.isEmpty()) {
                    parseGeneratedResponse();
                }
                if (executeQuery && dataExecutionResult == null) {
                    executeSql();
                }
                if (executeQuery && dataChart == null) {
                    recommendChart();
                }
            }

            private String finalizeWorkflow() {
                if (state.stopReason != null) return state.stopReason;
                if (state.fullResponse == null || state.fullResponse.isBlank()) {
                    runSafeFallbackFlow();
                }
                if (state.stopReason != null) return state.stopReason;
                if (!executeQuery) {
                    emitProgress(progressMessage(state.question, "data.manual_ready"));
                    return "DATA_QUERY_DONE. Manual mode — SQL is ready for user approval. "
                            + "STOP calling tools. Reply with exactly \"DATA_QUERY_DONE\".";
                }
                if (dataExecutionResult == null && state.generatedSql != null && !state.generatedSql.isBlank()) {
                    executeSql();
                }
                if (dataChart == null) {
                    recommendChart();
                }
                return null;
            }

            private class DataWorkflowTools {

                @Tool("Find relevant schema/table/column context for the current data question.")
                public String searchSchema(
                        @P("Short search phrase for relevant tables and columns. Use the user question if unsure.") String searchHint
                ) {
                    findSchemaContext(searchHint);
                    return "Schema search complete. Matched context blocks: " + state.schemaList.size();
                }

                @Tool("Generate the standard Inquery overview + SQL option markdown. Does not execute SQL.")
                public String generateSql(
                        @P("The SQL goal to answer. Keep the user's original intent and language.") String sqlGoal
                ) {
                    if (state.schemaList.isEmpty()) {
                        findSchemaContext(sqlGoal == null || sqlGoal.isBlank() ? state.question : sqlGoal);
                    }
                    String result = generateSqlMarkdown(sqlGoal == null || sqlGoal.isBlank() ? state.question : sqlGoal);
                    return result == null ? "SQL markdown generated." : result;
                }

                @Tool("Extract and validate the first generated SQL query.")
                public String validateSql() {
                    if (state.fullResponse == null || state.fullResponse.isBlank()) {
                        generateSql(state.question);
                    }
                    String result = validateGeneratedSql();
                    return result == null ? "SQL validation passed." : result;
                }

                @Tool("Parse generated markdown into overview, query title, SQL, and explanation fields for the existing UI.")
                public String parseResponse() {
                    if (state.fullResponse == null || state.fullResponse.isBlank()) {
                        generateSql(state.question);
                    }
                    parseGeneratedResponse();
                    return "Response parsed. Query options: " + (dataQueries == null ? 0 : dataQueries.size());
                }

                @Tool("Execute the validated SQL in Auto mode with retry and LLM-fix correction.")
                public String executeSql() {
                    if (!executeQuery) {
                        emitProgress(progressMessage(state.question, "data.manual_ready"));
                        return "Manual mode: SQL is ready; execution skipped.";
                    }
                    if (state.generatedSql == null || state.generatedSql.isBlank()) {
                        String validation = validateSql();
                        if (state.stopReason != null) return validation;
                    }
                    DataQueryWorkflow.this.executeSql();
                    return dataExecutionResult != null && dataExecutionResult.success()
                            ? "SQL execution complete."
                            : "SQL execution attempted.";
                }

                @Tool("Recommend chart metadata for the executed SQL result.")
                public String recommendChart() {
                    if (!executeQuery) return "Manual mode: chart recommendation skipped.";
                    if (dataExecutionResult == null) {
                        executeSql();
                    }
                    DataQueryWorkflow.this.recommendChart();
                    return dataChart != null ? "Chart recommendation complete." : "No chart recommendation available.";
                }
            }

            private void findSchemaContext(String searchHint) {
                try {
                    String schemaQuery = reformulateForSchemaSearch(
                            buildSchemaSearchQuestion(
                                    searchHint == null || searchHint.isBlank() ? state.question : searchHint,
                                    state.conversationContext));
                    emitProgress(progressMessage(state.question, "data.schema_search"));
                    List<String> hits = schemaSearcher.searchSchema(
                            schemaQuery, null, dataSourceId, databaseName, schemaName);
                    state.schemaList = hits != null ? hits : new ArrayList<>();
                } catch (Exception e) {
                    log.warn("[InqueryRootAgentRunner] schema search failed: {}", e.getMessage());
                    state.schemaList = new ArrayList<>();
                }
                state.schemaContext = state.schemaList.isEmpty() ? ""
                        : String.join("\n---\n", state.schemaList);
                dataSchemaContext = state.schemaContext;
            }

            private String generateSqlMarkdown(String sqlGoal) {
                try {
                    // Context boundary: the root agent sees chat history, but
                    // the data workflow receives only the current request plus
                    // explicit data context.
                    String prompt = sqlGenerator.buildStreamingPrompt(
                            state.question, state.schemaList, null, businessContext,
                            buildSqlGenerationContext(sqlGoal));
                    emitProgress(progressMessage(state.question, "data.sql_generate"));
                    state.fullResponse = generateMarkdown(prompt);
                } catch (Exception e) {
                    log.warn("[InqueryRootAgentRunner] sqlGenerator chat failed: {}", e.getMessage());
                    lastSqlError = e.getMessage();
                    state.stopReason = "SQL generation failed: " + e.getMessage();
                    return state.stopReason;
                }
                if (state.fullResponse == null || state.fullResponse.isBlank()) {
                    state.stopReason = "SQL generator returned an empty response. Tell the user we had a transient model issue and to retry.";
                    return state.stopReason;
                }
                dataFullResponse = state.fullResponse;
                return null;
            }

            private String buildSqlGenerationContext(String sqlGoal) {
                StringBuilder sb = new StringBuilder();
                if (state.conversationContext != null && !state.conversationContext.isBlank()) {
                    sb.append(state.conversationContext).append("\n\n");
                }
                if (sqlGoal != null && !sqlGoal.isBlank() && !sqlGoal.equals(state.question)) {
                    sb.append("Data workflow SQL goal: ").append(sqlGoal);
                }
                String value = sb.toString().trim();
                return value.isBlank() ? null : value;
            }

            private String validateGeneratedSql() {
                emitProgress(progressMessage(state.question, "data.sql_validate"));
                state.generatedSql = MarkdownQueryParser.extractFirstSql(state.fullResponse);
                if (state.generatedSql == null || state.generatedSql.isBlank()
                        || !MarkdownQueryParser.looksLikeSql(state.generatedSql)) {
                    log.warn("[InqueryRootAgentRunner] no usable SQL extracted from generator output");
                    state.stopReason = "SQL writer produced no SQL. Tell the user the question was too vague and ask for more detail.";
                    return state.stopReason;
                }
                dataGeneratedSql = state.generatedSql;
                lastSqlAttempted = state.generatedSql;

                if (astValidator != null && !astValidator.validate(state.generatedSql)) {
                    log.warn("[InqueryRootAgentRunner] AST validation failed for generated SQL");
                    state.stopReason = "DATA_QUERY_DONE. SQL generated but failed AST validation. STOP calling tools. Reply with \"DATA_QUERY_DONE\".";
                    return state.stopReason;
                }
                return null;
            }

            private void parseGeneratedResponse() {
                MarkdownQueryParser.ParsedMarkdown parsed = MarkdownQueryParser.parse(state.fullResponse);
                dataOverview = parsed.overview;
                // Hard cap on the LLM occasionally violating the SINGLE-QUERY
                // policy in SqlGenerator. Alternative perspectives surface
                // through SuggestedFollowUp suggestions (a fresh chat turn),
                // never as extra in-message queries that would be hard for the
                // user to find and would trigger duplicate result/interpretation
                // rendering.
                if (parsed.queries.size() > 1) {
                    log.warn("[InqueryRootAgentRunner] SqlGenerator returned {} queries; keeping only the first per single-query policy.",
                            parsed.queries.size());
                    dataQueries = java.util.List.of(parsed.queries.get(0));
                } else {
                    dataQueries = parsed.queries;
                }
                if (!dataQueries.isEmpty()) {
                    dataTitle = dataQueries.get(0).getTitle();
                    dataExplanation = dataQueries.get(0).getExplanation();
                }
            }

            private void executeSql() {
                try {
                    emitProgress(progressMessage(state.question, "data.execute"));
                    dataExecutionResult = executeWithRetry(
                            state.generatedSql, state.question, state.schemaContext);
                    if (dataExecutionResult != null && dataExecutionResult.success()
                            && dataExecutionResult.getData() != null
                            && !dataExecutionResult.getData().isEmpty()) {
                        lastSqlResult = dataExecutionResult.getData().get(0);
                    } else if (dataExecutionResult != null) {
                        lastSqlError = dataExecutionResult.getErrorMessage();
                    }
                } catch (Exception e) {
                    log.warn("[InqueryRootAgentRunner] SQL execution failed: {}", e.getMessage());
                    lastSqlError = e.getMessage();
                }
            }

            private void recommendChart() {
                if (lastSqlResult == null) return;
                try {
                    emitProgress(progressMessage(state.question, "data.chart"));
                    dataChart = chartEngine != null
                            ? chartEngine.recommendChart(lastSqlResult, state.question, modelName)
                            : null;
                } catch (Exception ce) {
                    log.warn("[InqueryRootAgentRunner] chart recommendation failed: {}", ce.getMessage());
                }
            }

            private class State {
                final String question;
                final String conversationContext;
                List<String> schemaList = new ArrayList<>();
                String schemaContext = "";
                String fullResponse;
                String generatedSql;
                String stopReason;

                State(String question, String conversationContext) {
                    this.question = question;
                    this.conversationContext = conversationContext;
                }
            }
        }

        private String normalizeConversationContext(String context) {
            if (context == null || context.isBlank()) return null;
            String trimmed = context.trim();
            return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
        }

        private String buildSchemaSearchQuestion(String question, String conversationContext) {
            if (conversationContext == null || conversationContext.isBlank()) {
                return question;
            }
            return "Previous data context: " + conversationContext + "\nCurrent question: " + question;
        }

        /**
         * Generate the SQL-generation markdown. Uses
         * {@link StreamingChatModel} so individual tokens can flow to the
         * SSE {@code content} event when the controller wired a callback;
         * falls back to a blocking {@link ChatModel#chat(String)} when no
         * callback is wired (Slack Deep Agent, MCP, etc.).
         *
         * <p>Hybrid reasoning policy: this is the NL→SQL hot path, so we
         * request {@link LangChainModelProvider.ReasoningEffort#MEDIUM}
         * even though chat-style traffic elsewhere stays on LOW. Schema
         * disambiguation, join planning, and predicate construction
         * benefit measurably from extra deliberation, and a wrong SQL is
         * far more expensive (DB round-trip + retry + user trust) than
         * the ~5-15s of additional reasoning latency. Callers that need
         * a faster path should use {@link #invokeSqlFix} (fast model,
         * still LOW) for cleanup work.
         */
        private String generateMarkdown(String prompt) {
            LangChainModelProvider.ReasoningEffort sqlEffort = LangChainModelProvider.ReasoningEffort.MEDIUM;
            if (contentTokenCallback == null) {
                return modelProvider.getChatModel(modelName, sqlEffort).chat(prompt);
            }
            StreamingChatModel streaming;
            try {
                streaming = modelProvider.getStreamingChatModel(modelName, sqlEffort);
            } catch (Exception e) {
                log.warn("[InqueryRootAgentRunner] streaming model unavailable, falling back to blocking chat: {}",
                        e.getMessage());
                return modelProvider.getChatModel(modelName, sqlEffort).chat(prompt);
            }

            StringBuilder buffer = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> err = new AtomicReference<>();

            long startedAt = System.currentTimeMillis();
            streaming.chat(prompt, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse == null) return;
                    buffer.append(partialResponse);
                    try {
                        contentTokenCallback.accept(partialResponse);
                    } catch (Exception ex) {
                        log.debug("[InqueryRootAgentRunner] content token callback threw: {}",
                                ex.getMessage());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    log.info("[InqueryRootAgentRunner] SQL streaming generation completed in {}ms (chars={})",
                            System.currentTimeMillis() - startedAt, buffer.length());
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("[InqueryRootAgentRunner] SQL streaming generation failed after {}ms: {}",
                            System.currentTimeMillis() - startedAt,
                            error == null ? null : error.getMessage());
                    err.set(error);
                    latch.countDown();
                }
            });

            try {
                if (!latch.await(45, TimeUnit.SECONDS)) {
                    log.warn("[InqueryRootAgentRunner] SQL streaming generation timed out after 45s (chars={})",
                            buffer.length());
                    if (buffer.length() == 0) {
                        throw new RuntimeException("SQL generation timed out before receiving any response tokens");
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("SQL generation interrupted", ie);
            }
            if (err.get() != null) {
                throw new RuntimeException(err.get());
            }
            return buffer.toString();
        }

        /**
         * Execute SQL with retry and LLM-fix self-correction. Matches the
         * {@link SupervisorAgent#executeSqlWithRetry} contract that Slack
         * Deep Agent uses: transient/IO errors trigger an exponential-backoff
         * retry with the SAME SQL, while permanent DB errors (missing column,
         * type mismatch, syntax, etc.) trigger one LLM "fix this SQL" call
         * (cheap fast model) and the corrected SQL is re-executed.
         *
         * <p>{@link #dataGeneratedSql} and {@link #lastSqlAttempted} are
         * updated on every fix so the UI can display the final, executed
         * SQL — not the initial (broken) one. The fixed SQL also has to
         * pass {@link AstValidator} before we'll run it.
         */
        private ListResult<ExecuteResult> executeWithRetry(String sql, String userQuestion, String schemaContext) {
            int maxRetries = 3;
            String currentSql = sql;
            ListResult<ExecuteResult> last = null;
            String lastError;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                lastError = null;
                try {
                    log.info("[InqueryRootAgentRunner] executing SQL (attempt {}/{}): {}",
                            attempt, maxRetries,
                            currentSql.length() > 100 ? currentSql.substring(0, 100) + "..." : currentSql);

                    DlExecuteParam p = new DlExecuteParam();
                    p.setSql(currentSql);
                    p.setDataSourceId(dataSourceId);
                    p.setDatabaseName(databaseName);
                    p.setSchemaName(schemaName);
                    p.setConsoleId(0L);

                    ListResult<ExecuteResult> result = dlTemplateService.execute(p);
                    if (result.success()) return result;
                    last = result;
                    lastError = result.getErrorMessage();

                    if (isRetryableError(lastError)) {
                        backoff(attempt, maxRetries);
                        continue;
                    }
                } catch (Exception e) {
                    lastError = e.getMessage();
                    log.warn("[InqueryRootAgentRunner] SQL execution exception: {}", lastError);
                    if (!isRetryableException(e) || attempt >= maxRetries) {
                        throw new RuntimeException("Failed to execute SQL", e);
                    }
                    backoff(attempt, maxRetries);
                    continue;
                }

                if (attempt >= maxRetries || lastError == null) break;

                String fixed = invokeSqlFix(currentSql, lastError, schemaContext, userQuestion);
                if (fixed == null || fixed.isBlank() || fixed.equals(currentSql)) {
                    log.info("[InqueryRootAgentRunner] LLM-fix produced no usable change; stopping retry");
                    break;
                }
                if (astValidator != null && !astValidator.validate(fixed)) {
                    log.warn("[InqueryRootAgentRunner] LLM-fixed SQL failed AST validation; stopping retry");
                    break;
                }
                emitProgress(progressMessage(userQuestion, "data.sql_fix"));
                log.info("[InqueryRootAgentRunner] LLM-fixed SQL ({} -> {} chars), retrying",
                        currentSql.length(), fixed.length());
                currentSql = fixed;
                dataGeneratedSql = currentSql;
                this.lastSqlAttempted = currentSql;
            }
            return last;
        }

        private void backoff(int attempt, int maxRetries) {
            if (attempt >= maxRetries) return;
            long waitMs = (long) Math.pow(2, attempt) * 1000L;
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Single "fix this SQL" LLM call — mirrors
         * {@link SupervisorAgent#executeSqlWithRetry}'s fix branch (fast
         * model, prompt focuses on schema-grounded minimal patches). Used
         * only for permanent DB errors; transient/IO errors get retried
         * verbatim.
         */
        private String invokeSqlFix(String brokenSql, String error,
                                    String schemaContext, String userQuestion) {
            try {
                String fastModel = ModelMapper.getFastModel(modelName);
                String prompt = buildFixSqlPrompt(brokenSql, error, schemaContext, userQuestion);
                String raw = modelProvider.getChatModel(fastModel).chat(prompt);
                if (raw == null || raw.isBlank()) return null;
                String extracted = MarkdownQueryParser.extractFirstSql(raw);
                if (extracted != null && MarkdownQueryParser.looksLikeSql(extracted)) {
                    return extracted;
                }
                String trimmed = raw.trim();
                return MarkdownQueryParser.looksLikeSql(trimmed) ? trimmed : null;
            } catch (Exception e) {
                log.warn("[InqueryRootAgentRunner] LLM-fix call failed: {}", e.getMessage());
                return null;
            }
        }

        private String buildFixSqlPrompt(String originalSql, String error,
                                         String schemaContext, String userQuestion) {
            return """
                    Imagine you are a senior data engineer debugging a failed SQL query.
                    You've fixed thousands of SQL errors in production systems.

                    When this engineer sees an error, they:
                    - Analyze the error message carefully
                    - Check the schema to find correct table/column names
                    - Apply the minimal fix needed

                    Common fixes:
                    - TABLE_NOT_FOUND: Find correct table name from schema
                    - COLUMN_NOT_FOUND: Find correct column name from schema
                    - SYNTAX_ERROR: Fix SQL syntax
                    - TYPE_MISMATCH: Add proper type casting

                    Return ONLY the fixed SQL wrapped in a ```sql ... ``` code block.
                    No explanations, no commentary outside the code block.

                    Original Question:
                    %s

                    Original SQL:
                    %s

                    Error:
                    %s

                    Schema:
                    %s
                    """.formatted(
                            userQuestion == null ? "" : userQuestion,
                            originalSql,
                            error == null ? "" : error,
                            schemaContext == null ? "" : schemaContext);
        }

        private boolean isRetryableError(String msg) {
            if (msg == null) return false;
            String lower = msg.toLowerCase();
            return lower.contains("timeout") || lower.contains("connection")
                    || lower.contains("temporarily") || lower.contains("retry")
                    || lower.contains("deadlock") || lower.contains("network");
        }

        private boolean isRetryableException(Exception e) {
            String n = e.getClass().getSimpleName().toLowerCase();
            return n.contains("timeout") || n.contains("io")
                    || n.contains("network") || n.contains("connection");
        }
    }

    /**
     * Tool that surfaces a date-range picker UI on the client side.
     * Does NOT actually call any backend service — it just flips the
     * needsDateRange flag the controller reads after run() returns.
     */
    public class DateRangeTool {
        @Tool("Ask the user to pick a date range via the frontend date-picker UI. Use primarily as a scan-cost guardrail after the root agent has evidence that an unbounded data query may hit a large table. Do NOT call this merely because a metric is time-related. Prefer checkDataVolume first when the relevant table is known and scan cost is uncertain. Do NOT call this for metadata, dimension lookups, definitions, lineage, quality/profile reports, or small/bounded summary queries.")
        public String requestDateRange(
                @P("Short prompt in the SAME language as the user's question. Explain that the date range is needed to keep the data scan bounded, e.g. 'Which time period should I query to keep the scan manageable?'") String question
        ) {
            log.info("[InqueryRootAgentRunner] requestDateRange invoked: {}", question);
            emitProgress(progressMessage(question, "ux.date_range"));
            needsDateRange = true;
            dateRangePrompt = question;
            return "Date range picker shown. Stop calling tools and reply ONLY with the question text (no extra commentary): "
                    + question;
        }
    }

    /**
     * Tool that surfaces 2–3 disambiguation buttons in the chat UI.
     * Does NOT actually call any backend service — it just flips the
     * needsClarification flag the controller reads after run() returns.
     */
    public class ClarifyTool {
        @Tool("Ask the user to disambiguate by showing 2–3 button options on the frontend. Call this INSTEAD of writing a plain-text reply when the question is genuinely ambiguous.")
        public String requestClarification(
                @P("Short disambiguation prompt in the user's language") String question,
                @P("Button label 1 — a rephrased candidate question (required)") String option1,
                @P("Button label 2 — a rephrased candidate question (required)") String option2,
                @P("Button label 3 — leave empty (\"\") if not needed") String option3
        ) {
            log.info("[InqueryRootAgentRunner] requestClarification invoked: {}", question);
            emitProgress(progressMessage(question, "ux.clarify"));
            needsClarification = true;
            clarificationPrompt = question;
            List<String> opts = new ArrayList<>();
            if (option1 != null && !option1.isBlank()) opts.add(option1);
            if (option2 != null && !option2.isBlank()) opts.add(option2);
            if (option3 != null && !option3.isBlank()) opts.add(option3);
            clarificationOptions = opts;
            return "Disambiguation options shown. Stop calling tools and reply ONLY with the question text (no extra commentary): "
                    + question;
        }
    }

    /**
     * Best-effort reformulation of a natural-language question into
     * schema-search keywords. The vector DB embeddings are trained on
     * English table/column descriptions, so for non-English questions we
     * extract a short English keyword list with one fast LLM call.
     *
     * <p>Returns the original question unchanged for ASCII-only inputs to
     * avoid an unnecessary LLM round-trip.
     */
    private String reformulateForSchemaSearch(String question) {
        if (question == null || question.isBlank()) return question;
        boolean hasNonAscii = question.chars().anyMatch(c -> c > 127);
        if (!hasNonAscii) return question;
        try {
            String fastModel = ModelMapper.getFastModel(modelName);
            ChatModel fast = modelProvider.getPlainChatModel(fastModel);
            String reformulated = fast.chat("""
                    Rewrite the user's question as a short English keyword list
                    (3-8 words) optimized for a database schema / table-name
                    vector search. Output ONLY the keywords, no quotes, no
                    explanation, no punctuation other than spaces.

                    Question:
                    %s
                    """.formatted(question));
            if (reformulated == null || reformulated.isBlank()) return question;
            String cleaned = reformulated.trim().replaceAll("[\"`]", "");
            int newline = cleaned.indexOf('\n');
            if (newline > 0) cleaned = cleaned.substring(0, newline);
            log.info("[InqueryRootAgentRunner] schema-search reformulated via {}: '{}' -> '{}'",
                    fastModel, question, cleaned);
            return cleaned;
        } catch (Exception e) {
            log.warn("[InqueryRootAgentRunner] schema-search reformulation failed, using original: {}", e.getMessage());
            return question;
        }
    }

    /** Convenience for callers that only have a {@link ListResult} on hand. */
    public static ExecuteResult firstResult(ListResult<ExecuteResult> result) {
        if (result == null || result.getData() == null || result.getData().isEmpty()) return null;
        return result.getData().get(0);
    }

    private static boolean isSequentialToolLimitException(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null) return false;
        return ex.getMessage().toLowerCase().contains("sequential tool executions");
    }

    /**
     * Last-resort recovery when the root agent exhausts its tool budget during
     * catalog/metadata exploration. Skips straight to the data pipeline so the
     * user still gets SQL + table (+ chart in Auto mode).
     */
    private boolean tryRecoverViaQueryData(QueryDataTool tool, String userMessage, String conversationContext) {
        if (dataSourceId == null || sqlGenerator == null) {
            return false;
        }
        if (dataFullResponse != null && !dataFullResponse.isBlank()) {
            return true;
        }
        try {
            emitProgress(progressMessage(userMessage, "data.start"));
            String ctx = conversationContext == null || conversationContext.isBlank()
                    ? null
                    : (conversationContext.trim().length() > 2000
                            ? conversationContext.trim().substring(0, 2000)
                            : conversationContext.trim());
            tool.queryDataFast(userMessage, ctx);
            return dataFullResponse != null && !dataFullResponse.isBlank();
        } catch (Exception e) {
            log.warn("[InqueryRootAgentRunner] queryData recovery failed: {}", e.getMessage());
            return false;
        }
    }
}
