package ai.inquery.server.domain.core.langchain.agents;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.core.langchain.tools.PythonTools;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService;
import ai.inquery.server.domain.core.query.MarkdownQueryParser;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.domain.core.security.AstValidator;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.common.model.Context;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.model.Header;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Deep Research Agent - orchestrates comprehensive research with multiple iterations.
 * 
 * Workflow:
 * 1. Generate derivative questions from original question
 * 2. Generate SQL for each question (using existing SqlWriterAgent)
 * 3. Execute queries in parallel
 * 4. Self-reflection to evaluate completeness
 * 5. Repeat steps 1-4 up to MAX_ITERATIONS
 * 6. Generate final comprehensive report
 */
@Slf4j
public class DeepResearchAgent {

    private static final int ITERATIONS_PER_STEP = 2;
    private static final int QUESTIONS_PER_ITERATION = 2;
    private static final int MAX_MD_SIZE_KB = 50;
    // Per-query result row threshold above which we hand the dataset to the
    // Python statistics tool instead of inlining a full markdown table.
    // Keeping this low ensures the cumulative researchMd stays bounded
    // without resorting to mid-evidence truncation at synthesis time.
    private static final int PYTHON_STATS_ROW_THRESHOLD = 50;
    private static final boolean TOOL_DRIVEN_RESEARCH_ENABLED = true;
    private static final int TOOL_AGENT_MAX_CALLS = 8;
    private static final int TOOL_STEP_MAX_SQL_CALLS = 3;
    private static final int TOOL_STEP_TARGET_SUCCESSFUL_SQL = 2;
    private static final int TOOL_STEP_MAX_SCHEMA_CALLS = 4;
    private static final int TOOL_STEP_MAX_WEB_CALLS = 1;
    private static final int REPORT_JSON_REPAIR_ATTEMPTS = 2;

    private final LangChainModelProvider modelProvider;
    private final DlTemplateService dlTemplateService;
    private final SchemaSearcher schemaSearcher;
    private final WebSearchService webSearchService;
    private final String modelName;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    private final AstValidator astValidator;

    // Python analysis for large result sets
    private PythonTools pythonTools;

    // Web search configuration
    private boolean enableWebSearch = false;
    private List<String> webSearchTopics;

    // Data Catalog excluded tables
    private List<String> excludedTables;

    // Business context from Data Catalog Business Insights
    private String businessContext;

    public void setExcludedTables(List<String> excludedTables) {
        this.excludedTables = excludedTables;
    }

    public void setBusinessContext(String businessContext) {
        this.businessContext = businessContext;
    }

    public void setPythonTools(PythonTools pythonTools) {
        this.pythonTools = pythonTools;
    }

    private final ExecutorService executorService;

    // Lazy-initialized agents
    private QuestionPlannerAgent questionPlanner;
    private SqlWriterAgent sqlWriter;
    private SelfReflectionAgent selfReflection;
    private SelfReflectionAgent fastSummarizer;
    private ReportSynthesizerAgent reportSynthesizer;
    private WebSearchAgent webSearchAgent;

    // Accumulated research data (MD format)
    private StringBuilder researchMd;
    private List<QueryResult> allQueryResults;
    private StringBuilder webResearchMd;
    private List<WebSearchService.SearchResult> allWebResults;

    public DeepResearchAgent(
            LangChainModelProvider modelProvider,
            DlTemplateService dlTemplateService,
            SchemaSearcher schemaSearcher,
            String modelName,
            Long dataSourceId,
            String databaseName,
            String schemaName
    ) {
        this(modelProvider, dlTemplateService, schemaSearcher, null, modelName, dataSourceId, databaseName, schemaName, null);
    }

    public DeepResearchAgent(
            LangChainModelProvider modelProvider,
            DlTemplateService dlTemplateService,
            SchemaSearcher schemaSearcher,
            WebSearchService webSearchService,
            String modelName,
            Long dataSourceId,
            String databaseName,
            String schemaName
    ) {
        this(modelProvider, dlTemplateService, schemaSearcher, webSearchService, modelName, dataSourceId,
                databaseName, schemaName, null);
    }

    public DeepResearchAgent(
            LangChainModelProvider modelProvider,
            DlTemplateService dlTemplateService,
            SchemaSearcher schemaSearcher,
            WebSearchService webSearchService,
            String modelName,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            AstValidator astValidator
    ) {
        this.modelProvider = modelProvider;
        this.dlTemplateService = dlTemplateService;
        this.schemaSearcher = schemaSearcher;
        this.webSearchService = webSearchService;
        this.modelName = modelName;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.astValidator = astValidator;
        this.executorService = Executors.newFixedThreadPool(15);
        this.researchMd = new StringBuilder();
        this.allQueryResults = new ArrayList<>();
        this.webResearchMd = new StringBuilder();
        this.allWebResults = new ArrayList<>();
    }

    /**
     * Enable web search with specified topics.
     */
    public DeepResearchAgent withWebSearch(boolean enable, List<String> topics) {
        this.enableWebSearch = enable;
        this.webSearchTopics = topics;
        return this;
    }

    /**
     * Execute deep research process with plan steps executed in parallel.
     * Each step runs 2 iterations of question generation + SQL execution.
     *
     * @param question Original research question
     * @param planSteps Research plan steps (from classify). If null/empty, falls back to single-step mode.
     * @param progressCallback Callback for progress updates (SSE events)
     * @return DeepResearchResult with final report
     */
    public DeepResearchResult research(String question, List<PlanStep> planSteps, Consumer<ProgressEvent> progressCallback) {
        log.info("Starting Deep Research for question: {} with {} plan steps", question,
                planSteps != null ? planSteps.size() : 0);
        DeepResearchResult.DeepResearchResultBuilder resultBuilder = DeepResearchResult.builder()
                .originalQuestion(question);

        try {
            // Phase 0: Web Search (if enabled)
            String webContext = "";
            if (enableWebSearch && webSearchService != null) {
                reportProgress(progressCallback, "web_search", "Collecting external information...");
                webContext = performWebSearch(question, progressCallback);
                resultBuilder.webSearchContext(webContext);
            }

            // Phase 1: Verify DB data availability
            reportProgress(progressCallback, "planning", "Finding relevant data sources...");
            String fallbackSchema = findRelevantSchema(question);

            boolean hasDbData = fallbackSchema != null && !fallbackSchema.isEmpty();
            boolean hasWebData = !webContext.isEmpty();

            if (!hasDbData && !hasWebData) {
                return resultBuilder
                        .success(false)
                        .errorMessage("No relevant data sources found for your research question")
                        .build();
            }

            if (hasDbData) {
                resultBuilder.schemaContext(fallbackSchema);
            } else {
                log.info("No database schema found, proceeding with web search data only");
                fallbackSchema = "";
            }

            // Phase 2: Execute plan steps in parallel (each step = 2 iterations)
            if (hasDbData && planSteps != null && !planSteps.isEmpty()) {
                reportProgress(progressCallback, "question", "Executing research steps in parallel...");

                // Pre-initialize models/agents on the controller thread. Worker
                // threads do not have Dbutils' SqlSession, so a cache miss there
                // cannot read API keys from ConfigService.
                modelProvider.getToolCallingChatModel(modelName);
                modelProvider.getChatModel(ModelMapper.getFastModel(modelName));
                modelProvider.getPlainChatModel(modelName);
                getQuestionPlannerAgent();
                getSqlWriterAgent();
                getFastSummarizer();
                getReportSynthesizerAgent();

                final String schemaForFallback = fallbackSchema;
                final ConnectInfo mainThreadConnectInfo = InqueryContext.getConnectInfo();
                final Context mainThreadContext = ContextUtils.queryContext();
                List<CompletableFuture<StepResult>> stepFutures = new ArrayList<>();

                for (int i = 0; i < planSteps.size(); i++) {
                    final PlanStep step = planSteps.get(i);
                    final int stepIndex = i + 1;

                    CompletableFuture<StepResult> future = CompletableFuture.supplyAsync(() -> {
                        boolean openedSession = false;
                        try {
                            // Propagate request context to step worker thread.
                            if (mainThreadContext != null) {
                                ContextUtils.setContext(mainThreadContext);
                            }
                            if (mainThreadConnectInfo != null) {
                                InqueryContext.putContext(mainThreadConnectInfo);
                            }
                            if (!Dbutils.hasSession()) {
                                Dbutils.setSession();
                                openedSession = true;
                            }

                            if (TOOL_DRIVEN_RESEARCH_ENABLED) {
                                try {
                                    return runToolDrivenStep(question, step, stepIndex, planSteps.size(),
                                            schemaForFallback, progressCallback);
                                } catch (Exception e) {
                                    log.warn("Tool-driven step failed; falling back to fixed pipeline for step {}: {}",
                                            stepIndex, e.getMessage(), e);
                                }
                            }
                            return executeStep(question, step, stepIndex, planSteps.size(),
                                    schemaForFallback, progressCallback);
                        } finally {
                            if (openedSession) {
                                Dbutils.removeSession();
                            }
                            InqueryContext.removeContext();
                            ContextUtils.removeContext();
                        }
                    }, executorService);

                    stepFutures.add(future);
                }

                // Wait for all steps to complete
                CompletableFuture.allOf(stepFutures.toArray(new CompletableFuture[0])).join();

                // Collect results from all steps
                for (CompletableFuture<StepResult> future : stepFutures) {
                    try {
                        StepResult stepResult = future.get();
                        if (stepResult != null) {
                            allQueryResults.addAll(stepResult.getQueryResults());
                            researchMd.append(stepResult.getResearchMd());
                        }
                    } catch (Exception e) {
                        log.warn("Step execution failed: {}", e.getMessage());
                    }
                }

                log.info("All {} steps completed. Total queries: {}", planSteps.size(), allQueryResults.size());
                if (allQueryResults.stream().noneMatch(QueryResult::isSuccess)) {
                    return resultBuilder
                            .success(false)
                            .errorMessage("No database evidence could be collected for the research plan. Please retry or adjust the plan.")
                            .iterationsUsed(planSteps.size())
                            .totalQueriesExecuted(allQueryResults.size())
                            .totalWebSearches(allWebResults.size())
                            .build();
                }
            }

            // Phase 3: Generate final report
            reportProgress(progressCallback, "synthesizing", "Generating comprehensive report...");
            ResearchReport report = generateReport(question, "", webContext);
            resultBuilder.report(report);

            reportProgress(progressCallback, "finalizing", "Preparing final report...");
            return resultBuilder
                    .success(true)
                    .iterationsUsed(planSteps != null ? planSteps.size() * ITERATIONS_PER_STEP : 0)
                    .totalQueriesExecuted(allQueryResults.size())
                    .totalWebSearches(allWebResults.size())
                    .build();

        } catch (Exception e) {
            log.error("Deep Research failed", e);
            return resultBuilder
                    .success(false)
                    .errorMessage("Research failed: " + e.getMessage())
                    .build();
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * Execute a single plan step with 2 iterations.
     * Iteration 1: initial questions based on step context
     * Iteration 2: follow-up questions based on iteration 1 results
     */
    private StepResult executeStep(String originalQuestion, PlanStep step, int stepIndex, int totalSteps,
                                    String fallbackSchema, Consumer<ProgressEvent> progressCallback) {
        String stepLabel = String.format("[Step %d/%d] %s", stepIndex, totalSteps, step.getTitle());
        log.info("Starting {}", stepLabel);

        StepResult stepResult = new StepResult();
        StringBuilder stepMd = new StringBuilder();
        stepMd.append("\n## ").append(step.getTitle()).append("\n\n");

        String stepContext = "Step focus: " + step.getTitle() + "\nDetails: " + step.getDetails();

        for (int iteration = 1; iteration <= ITERATIONS_PER_STEP; iteration++) {
            log.info("{} - Iteration {}/{}", stepLabel, iteration, ITERATIONS_PER_STEP);
            reportProgress(progressCallback, "question",
                    String.format("%s - Generating questions (iteration %d)...", step.getTitle(), iteration));

            // Generate questions scoped to this step
            List<DerivedQuestion> questions;
            if (iteration == 1) {
                questions = generateStepQuestions(originalQuestion, stepContext, fallbackSchema);
            } else {
                questions = generateStepFollowUpQuestions(originalQuestion, stepContext,
                        stepMd.toString(), fallbackSchema);
            }

            if (questions.isEmpty()) {
                log.warn("{} - No questions generated in iteration {}", stepLabel, iteration);
                continue;
            }

            // Vector search + SQL generation per question (parallel)
            reportProgress(progressCallback, "query",
                    String.format("%s - Generating SQL (iteration %d)...", step.getTitle(), iteration));
            List<QueryPlan> queryPlans = generateSqlForQuestions(questions, fallbackSchema);

            if (queryPlans.isEmpty()) {
                log.warn("{} - No SQL generated in iteration {}", stepLabel, iteration);
                continue;
            }

            // Execute queries in parallel
            reportProgress(progressCallback, "executing",
                    String.format("%s - Executing %d queries...", step.getTitle(), queryPlans.size()));
            List<QueryResult> results = executeQueriesInParallel(queryPlans, progressCallback);
            stepResult.getQueryResults().addAll(results);

            // Summarize iteration data
            StringBuilder rawData = new StringBuilder();
            for (QueryResult result : results) {
                if (result.getQueryPlan() != null) {
                    DerivedQuestion q = result.getQueryPlan().getDerivedQuestion();
                    rawData.append("### ").append(q.getQuestion()).append("\n\n");
                    rawData.append("**SQL:**\n```sql\n").append(result.getQueryPlan().getSql()).append("\n```\n\n");
                    if (result.isSuccess()) {
                        rawData.append("**Results (").append(result.getRowCount()).append(" rows):**\n\n");
                        rawData.append(result.getFormattedResult()).append("\n\n");
                    } else {
                        rawData.append("**Error:** ").append(result.getErrorMessage()).append("\n\n");
                    }
                }
            }

            // Summarize with fast model
            try {
                SelfReflectionAgent summarizer = getFastSummarizer();
                String summary = summarizer.summarizeIteration(originalQuestion, rawData.toString());
                stepMd.append("### Iteration ").append(iteration).append(" Summary\n\n");
                stepMd.append(summary).append("\n\n");
            } catch (Exception e) {
                log.warn("{} - Summarization failed, using raw data", stepLabel, e);
                stepMd.append(rawData);
            }
        }

        stepResult.setResearchMd(stepMd.toString());
        log.info("Completed {} - {} queries executed", stepLabel, stepResult.getQueryResults().size());
        return stepResult;
    }

    /**
     * Tool-calling research loop for one plan step. The outer Deep Research
     * session/UI remains deterministic, but the step can choose which evidence
     * tools to call instead of running a fixed 2x2 question grid.
     */
    private StepResult runToolDrivenStep(String originalQuestion, PlanStep step, int stepIndex, int totalSteps,
                                         String fallbackSchema, Consumer<ProgressEvent> progressCallback) {
        String stepLabel = String.format("[Step %d/%d] %s", stepIndex, totalSteps, step.getTitle());
        log.info("Starting tool-driven {}", stepLabel);
        reportProgress(progressCallback, "question",
                String.format("%s - Selecting research tools...", step.getTitle()));

        StepResult stepResult = new StepResult();
        DeepResearchTools tools = new DeepResearchTools(
                originalQuestion, step, stepResult, fallbackSchema, progressCallback);

        DeepResearchToolAgent toolAgent = AiServices.builder(DeepResearchToolAgent.class)
                .chatModel(modelProvider.getToolCallingChatModel(modelName))
                .tools(tools)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .maxSequentialToolsInvocations(TOOL_AGENT_MAX_CALLS)
                .build();

        String finalNotes = toolAgent.researchStep(
                originalQuestion,
                step.getTitle(),
                step.getDetails(),
                fallbackSchema,
                businessContext != null ? businessContext : "",
                webResearchMd != null ? webResearchMd.toString() : "");

        StringBuilder stepMd = new StringBuilder();
        stepMd.append("\n## ").append(step.getTitle()).append("\n\n");
        if (tools.getEvidenceMarkdown().isBlank()) {
            log.warn("{} produced no tool evidence; falling back to fixed pipeline", stepLabel);
            return executeStep(originalQuestion, step, stepIndex, totalSteps, fallbackSchema, progressCallback);
        }
        stepMd.append(tools.getEvidenceMarkdown());
        if (finalNotes != null && !finalNotes.isBlank()) {
            stepMd.append("\n### Step Synthesis\n\n").append(finalNotes.trim()).append("\n\n");
        }

        if (tools.successfulSqlCount() == 0 && fallbackSchema != null && !fallbackSchema.isBlank()) {
            log.warn("{} produced no successful SQL evidence; falling back to fixed pipeline", stepLabel);
            return executeStep(originalQuestion, step, stepIndex, totalSteps, fallbackSchema, progressCallback);
        }

        stepResult.setResearchMd(stepMd.toString());
        log.info("Completed tool-driven {} - {} queries executed, sufficient={}",
                stepLabel, stepResult.getQueryResults().size(), tools.isEvidenceSufficient());
        return stepResult;
    }

    /**
     * Generate questions for a specific plan step (iteration 1).
     */
    private List<DerivedQuestion> generateStepQuestions(String originalQuestion, String stepContext, String schemaContext) {
        List<DerivedQuestion> questions = new ArrayList<>();
        String bizCtx = businessContext != null ? "Business Context:\n" + businessContext : "";

        try {
            QuestionPlannerAgent planner = getQuestionPlannerAgent();
            String prompt = originalQuestion + "\n\n" + stepContext;
            String response = planner.generateQuestions(prompt, schemaContext, QUESTIONS_PER_ITERATION, bizCtx);

            JSONArray jsonArray = parseJsonArray(response);
            if (jsonArray != null) {
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    DerivedQuestion q = new DerivedQuestion();
                    q.setQuestion(obj.getString("question"));
                    q.setStrategy(obj.getString("strategy"));
                    q.setPriority(obj.getIntValue("priority", 5));
                    questions.add(q);
                }
            }
        } catch (Exception e) {
            log.error("Step question generation failed", e);
        }
        return questions;
    }

    /**
     * Generate follow-up questions for a specific plan step (iteration 2+).
     */
    private List<DerivedQuestion> generateStepFollowUpQuestions(String originalQuestion, String stepContext,
                                                                 String collectedData, String schemaContext) {
        List<DerivedQuestion> questions = new ArrayList<>();
        String bizCtx = businessContext != null ? "Business Context:\n" + businessContext : "";

        try {
            QuestionPlannerAgent planner = getQuestionPlannerAgent();
            String prompt = originalQuestion + "\n\n" + stepContext;
            String response = planner.generateFollowUpQuestions(prompt, collectedData, schemaContext,
                    QUESTIONS_PER_ITERATION, bizCtx);

            JSONArray jsonArray = parseJsonArray(response);
            if (jsonArray != null) {
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    DerivedQuestion q = new DerivedQuestion();
                    q.setQuestion(obj.getString("question"));
                    q.setStrategy(obj.getString("strategy"));
                    q.setPriority(obj.getIntValue("priority", 5));
                    questions.add(q);
                }
            }
        } catch (Exception e) {
            log.error("Step follow-up question generation failed", e);
        }
        return questions;
    }

    /**
     * Perform web search and collect external information.
     */
    private String performWebSearch(String question, Consumer<ProgressEvent> progressCallback) {
        StringBuilder webMd = new StringBuilder();
        webMd.append("# Web Research Results\n\n");

        try {
            reportProgress(progressCallback, "web_search", "Searching the web with native LLM...");

            // Single native LLM web search call (search + synthesize in one step)
            WebSearchService.WebSearchResponse response = webSearchService.searchWithLLM(question, modelName);

            // Store sources
            if (response.getSources() != null) {
                allWebResults.addAll(response.getSources());
            }

            // Store synthesized text
            if (response.getSynthesizedText() != null && !response.getSynthesizedText().isEmpty()) {
                webResearchMd.append("\n## Synthesized Web Research\n\n");
                webResearchMd.append(response.getSynthesizedText()).append("\n");

                webMd.append(response.getSynthesizedText()).append("\n\n");
            }

            // Append source references
            if (response.getSources() != null && !response.getSources().isEmpty()) {
                webMd.append("## Sources\n\n");
                for (WebSearchService.SearchResult source : response.getSources()) {
                    webMd.append(source.toMarkdown()).append("\n");
                }
            }

        } catch (Exception e) {
            log.error("Web search failed: {}", e.getMessage());
            webMd.append("\n*Web search encountered an error: ").append(e.getMessage()).append("*\n");
        }

        return webMd.toString();
    }

    private String findRelevantSchema(String query) {
        try {
            List<String> schemas = (excludedTables != null && !excludedTables.isEmpty())
                ? schemaSearcher.searchSchema(query, excludedTables, dataSourceId, databaseName, schemaName)
                : schemaSearcher.searchSchema(query, null, dataSourceId, databaseName, schemaName);
            if (schemas == null || schemas.isEmpty()) {
                return null;
            }
            return schemas.stream()
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.error("Schema search failed", e);
            return null;
        }
    }

    /**
     * For each derived question: vector search + SQL generation in parallel.
     * Each question gets its own schema context from Pinecone for accuracy.
     */
    private List<QueryPlan> generateSqlForQuestions(List<DerivedQuestion> questions, String fallbackSchemaContext) {
        if (questions.isEmpty()) {
            return new ArrayList<>();
        }

        log.info("Generating SQL for {} questions in parallel (per-question vector search)...", questions.size());
        long startTime = System.currentTimeMillis();

        SqlWriterAgent writer = getSqlWriterAgent();
        final ConnectInfo parentConnectInfo = InqueryContext.getConnectInfo();
        final Context parentContext = ContextUtils.queryContext();

        List<CompletableFuture<QueryPlan>> futures = new ArrayList<>();

        for (DerivedQuestion question : questions) {
            CompletableFuture<QueryPlan> future = CompletableFuture.supplyAsync(() -> {
                boolean openedSession = false;
                try {
                    if (parentContext != null) {
                        ContextUtils.setContext(parentContext);
                    }
                    if (parentConnectInfo != null) {
                        InqueryContext.putContext(parentConnectInfo);
                    }
                    if (!Dbutils.hasSession()) {
                        Dbutils.setSession();
                        openedSession = true;
                    }

                    // Step 1: Vector search for this specific question
                    String questionSchema = findRelevantSchema(question.getQuestion());
                    if (questionSchema == null || questionSchema.isEmpty()) {
                        log.warn("No schema found for question: '{}', using fallback",
                                question.getQuestion().substring(0, Math.min(50, question.getQuestion().length())));
                        questionSchema = fallbackSchemaContext;
                    }

                    // Step 2: Generate SQL with question-specific schema
                    String sql = writer.writeSql(question.getQuestion(), questionSchema);
                    sql = stripCodeBlock(sql);

                    if (sql != null && !sql.isEmpty()) {
                        QueryPlan plan = new QueryPlan();
                        plan.setDerivedQuestion(question);
                        plan.setSql(sql);

                        log.info("Generated SQL for '{}': {}",
                                question.getQuestion().substring(0, Math.min(50, question.getQuestion().length())),
                                sql.substring(0, Math.min(100, sql.length())));
                        return plan;
                    }
                } catch (Exception e) {
                    log.warn("SQL generation failed for question: '{}'",
                            question.getQuestion().substring(0, Math.min(50, question.getQuestion().length())), e);
                } finally {
                    if (openedSession) {
                        Dbutils.removeSession();
                    }
                    InqueryContext.removeContext();
                    ContextUtils.removeContext();
                }
                return null;
            }, executorService);

            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsed = System.currentTimeMillis() - startTime;

        List<QueryPlan> plans = futures.stream()
                .map(f -> {
                    try { return f.get(); } catch (Exception e) { return null; }
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());

        log.info("Parallel SQL generation completed in {}ms: {}/{} queries generated", elapsed, plans.size(), questions.size());
        return plans;
    }

    /**
     * Execute queries in parallel using CompletableFuture.
     * ConnectionPool now supports connection reuse by dataSourceId, so parallel execution works.
     */
    private List<QueryResult> executeQueriesInParallel(List<QueryPlan> plans, Consumer<ProgressEvent> progressCallback) {
        if (plans.isEmpty()) {
            return new ArrayList<>();
        }
        
        log.info("Executing {} queries in parallel...", plans.size());
        long startTime = System.currentTimeMillis();
        
        // Capture parent thread's ConnectInfo for propagation
        final ConnectInfo parentConnectInfo = InqueryContext.getConnectInfo();
        final Context parentContext = ContextUtils.queryContext();
        
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        
        for (int i = 0; i < plans.size(); i++) {
            final int queryIndex = i;
            QueryPlan plan = plans.get(i);
            
            CompletableFuture<QueryResult> future = CompletableFuture.supplyAsync(() -> {
                QueryResult result = new QueryResult();
                result.setQueryPlan(plan);
                boolean openedSession = false;
                
                try {
                    // Propagate request context to worker thread
                    if (parentContext != null) {
                        ContextUtils.setContext(parentContext);
                    }
                    if (parentConnectInfo != null) {
                        InqueryContext.putContext(parentConnectInfo);
                    }
                    if (!Dbutils.hasSession()) {
                        Dbutils.setSession();
                        openedSession = true;
                    }
                    
                    DlExecuteParam param = new DlExecuteParam();
                    param.setSql(plan.getSql());
                    param.setDataSourceId(dataSourceId);
                    param.setDatabaseName(databaseName);
                    param.setSchemaName(schemaName);
                    param.setConsoleId(0L);
                    
                    long queryStart = System.currentTimeMillis();
                    ListResult<ExecuteResult> execResult = dlTemplateService.execute(param);
                    long queryElapsed = System.currentTimeMillis() - queryStart;
                    
                    log.info("Query {} executed in {}ms", queryIndex + 1, queryElapsed);
                    
                    if (execResult.success() && execResult.getData() != null && !execResult.getData().isEmpty()) {
                        ExecuteResult data = execResult.getData().get(0);
                        result.setSuccess(true);
                        result.setRowCount(data.getDataList() != null ? data.getDataList().size() : 0);

                        if (result.getRowCount() > PYTHON_STATS_ROW_THRESHOLD && pythonTools != null) {
                            // Large result + Python available: statistical summary
                            result.setFormattedResult(runPythonStatistics(
                                    plan.getDerivedQuestion().getQuestion(),
                                    data,
                                    result.getRowCount()
                            ));
                        } else if (result.getRowCount() > PYTHON_STATS_ROW_THRESHOLD) {
                            // Large result + no Python: truncate to threshold rows
                            result.setFormattedResult(formatResultForMdTruncated(
                                    data, plan.getDerivedQuestion().getQuestion(), PYTHON_STATS_ROW_THRESHOLD));
                        } else {
                            // Small result: full MD table
                            result.setFormattedResult(formatResultForMd(data, plan.getDerivedQuestion().getQuestion()));
                        }
                    } else {
                        result.setSuccess(false);
                        result.setErrorMessage(execResult.getErrorMessage());
                        log.warn("Query {} failed: {}", queryIndex + 1, execResult.getErrorMessage());
                    }
                } catch (Exception e) {
                    result.setSuccess(false);
                    result.setErrorMessage(e.getMessage());
                    log.warn("Query {} execution failed: {}", queryIndex + 1, e.getMessage());
                } finally {
                    if (openedSession) {
                        Dbutils.removeSession();
                    }
                    InqueryContext.removeContext();
                    ContextUtils.removeContext();
                }
                
                return result;
            }, executorService);
            
            futures.add(future);
        }
        
        // Wait for all queries to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        long totalElapsed = System.currentTimeMillis() - startTime;
        log.info("All {} queries completed in {}ms (parallel)", plans.size(), totalElapsed);
        
        // Collect results
        List<QueryResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        QueryResult errorResult = new QueryResult();
                        errorResult.setSuccess(false);
                        errorResult.setErrorMessage(e.getMessage());
                        return errorResult;
                    }
                })
                .collect(Collectors.toList());
        
        reportProgress(progressCallback, "query_execution", 
                String.format("Executed %d queries in %dms", plans.size(), totalElapsed));
        
        return results;
    }

    private QueryResult executeQueryPlanWithRetry(QueryPlan plan, String schemaContext) {
        QueryResult result = new QueryResult();
        result.setQueryPlan(plan);
        String currentSql = plan.getSql();
        ListResult<ExecuteResult> last = null;
        String lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (astValidator != null && !astValidator.validateWithSchema(currentSql, databaseName, schemaName)) {
                lastError = "SQL failed read-only AST validation";
                result.setSuccess(false);
                result.setErrorMessage(lastError);
                return result;
            }

            try {
                DlExecuteParam param = new DlExecuteParam();
                param.setSql(currentSql);
                param.setDataSourceId(dataSourceId);
                param.setDatabaseName(databaseName);
                param.setSchemaName(schemaName);
                param.setConsoleId(0L);

                long queryStart = System.currentTimeMillis();
                last = dlTemplateService.execute(param);
                long queryElapsed = System.currentTimeMillis() - queryStart;
                log.info("Deep Research SQL executed in {}ms (attempt {}/3)", queryElapsed, attempt);

                if (last.success() && last.getData() != null && !last.getData().isEmpty()) {
                    ExecuteResult data = last.getData().get(0);
                    plan.setSql(currentSql);
                    result.setQueryPlan(plan);
                    result.setSuccess(true);
                    result.setRowCount(data.getDataList() != null ? data.getDataList().size() : 0);
                    if (result.getRowCount() > PYTHON_STATS_ROW_THRESHOLD && pythonTools != null) {
                        result.setFormattedResult(runPythonStatistics(
                                plan.getDerivedQuestion().getQuestion(), data, result.getRowCount()));
                    } else if (result.getRowCount() > PYTHON_STATS_ROW_THRESHOLD) {
                        result.setFormattedResult(formatResultForMdTruncated(
                                data, plan.getDerivedQuestion().getQuestion(), PYTHON_STATS_ROW_THRESHOLD));
                    } else {
                        result.setFormattedResult(formatResultForMd(data, plan.getDerivedQuestion().getQuestion()));
                    }
                    return result;
                }

                lastError = last.getErrorMessage();
                if (isRetryableError(lastError)) {
                    backoff(attempt, 3);
                    continue;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                if (!isRetryableException(e) || attempt >= 3) {
                    result.setSuccess(false);
                    result.setErrorMessage(lastError);
                    return result;
                }
                backoff(attempt, 3);
                continue;
            }

            if (attempt >= 3 || lastError == null) {
                break;
            }

            String fixedSql = invokeSqlFix(currentSql, lastError, schemaContext,
                    plan.getDerivedQuestion() != null ? plan.getDerivedQuestion().getQuestion() : "");
            if (fixedSql == null || fixedSql.isBlank() || fixedSql.equals(currentSql)) {
                break;
            }
            currentSql = fixedSql;
        }

        result.setSuccess(false);
        result.setErrorMessage(lastError != null ? lastError
                : (last != null ? last.getErrorMessage() : "SQL execution failed"));
        return result;
    }

    private String invokeSqlFix(String brokenSql, String error, String schemaContext, String userQuestion) {
        try {
            String prompt = """
                    Imagine you are a senior data engineer debugging a failed SQL query.
                    Apply the minimal schema-grounded fix. Return ONLY the fixed SQL wrapped in a ```sql code block.

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
                    brokenSql == null ? "" : brokenSql,
                    error == null ? "" : error,
                    schemaContext == null ? "" : schemaContext);

            String raw = modelProvider.getChatModel(ModelMapper.getFastModel(modelName)).chat(prompt);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String extracted = MarkdownQueryParser.extractFirstSql(raw);
            if (extracted != null && MarkdownQueryParser.looksLikeSql(extracted)) {
                return extracted;
            }
            String trimmed = raw.trim();
            return MarkdownQueryParser.looksLikeSql(trimmed) ? trimmed : null;
        } catch (Exception e) {
            log.warn("Deep Research SQL fix failed: {}", e.getMessage());
            return null;
        }
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

    private void backoff(int attempt, int maxRetries) {
        if (attempt >= maxRetries) return;
        long waitMs = (long) Math.pow(2, attempt) * 1000L;
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private class DeepResearchTools {
        private final String originalQuestion;
        private final PlanStep step;
        private final StepResult stepResult;
        private final String fallbackSchema;
        private final Consumer<ProgressEvent> progressCallback;
        private final StringBuilder evidenceMarkdown = new StringBuilder();
        private boolean evidenceSufficient;
        private int schemaCallCount;
        private int sqlCallCount;
        private int webCallCount;

        DeepResearchTools(String originalQuestion, PlanStep step, StepResult stepResult,
                          String fallbackSchema, Consumer<ProgressEvent> progressCallback) {
            this.originalQuestion = originalQuestion;
            this.step = step;
            this.stepResult = stepResult;
            this.fallbackSchema = fallbackSchema;
            this.progressCallback = progressCallback;
        }

        @Tool("Find relevant tables and columns for a research topic. Use before SQL if the needed data source is uncertain.")
        public String searchResearchSchema(
                @P("Natural-language topic or metric to locate in the schema") String topic
        ) {
            if (schemaCallCount >= TOOL_STEP_MAX_SCHEMA_CALLS) {
                return "Schema lookup limit reached for this step. Use collected schema evidence or run a focused SQL query.";
            }
            schemaCallCount++;
            reportProgress(progressCallback, "planning", step.getTitle() + " - Searching schema...");
            String schema = findRelevantSchema(topic);
            if (schema == null || schema.isBlank()) {
                return "No relevant schema found for: " + topic;
            }
            evidenceMarkdown.append("### Schema Evidence: ").append(topic).append("\n\n")
                    .append(schema).append("\n\n");
            return schema;
        }

        @Tool("Generate, validate, and execute a read-only SQL query for one concrete research question. Use this to collect database evidence.")
        public String runResearchSql(
                @P("A concrete, answerable data question. Include target dimensions, metrics, filters, and grain.") String dataQuestion
        ) {
            if (evidenceSufficient || successfulSqlCount() >= TOOL_STEP_TARGET_SUCCESSFUL_SQL) {
                evidenceSufficient = true;
                return "SQL evidence target reached for this step. Do not call runResearchSql again; call analyzeResearchData if needed, then provide the final step synthesis.";
            }
            if (sqlCallCount >= TOOL_STEP_MAX_SQL_CALLS) {
                evidenceSufficient = successfulSqlCount() > 0;
                return "SQL query limit reached for this step (" + TOOL_STEP_MAX_SQL_CALLS
                        + "). Do not call runResearchSql again; synthesize from collected evidence.";
            }
            sqlCallCount++;
            reportProgress(progressCallback, "query", step.getTitle() + " - Generating SQL...");
            String schema = findRelevantSchema(dataQuestion);
            if (schema == null || schema.isBlank()) {
                schema = fallbackSchema;
            }
            if (schema == null || schema.isBlank()) {
                return "No database schema available for this question.";
            }

            try {
                String sql = getSqlWriterAgent().writeSql(dataQuestion, schema);
                sql = stripCodeBlock(sql);
                if (sql == null || sql.isBlank()) {
                    return "SQL generation returned empty output.";
                }

                DerivedQuestion derivedQuestion = new DerivedQuestion();
                derivedQuestion.setQuestion(dataQuestion);
                derivedQuestion.setStrategy("tool_calling");
                derivedQuestion.setPriority(5);

                QueryPlan plan = new QueryPlan();
                plan.setDerivedQuestion(derivedQuestion);
                plan.setSql(sql);

                reportProgress(progressCallback, "executing", step.getTitle() + " - Executing SQL...");
                QueryResult result = executeQueryPlanWithRetry(plan, schema);
                stepResult.getQueryResults().add(result);

                evidenceMarkdown.append("### ").append(dataQuestion).append("\n\n")
                        .append("**SQL:**\n```sql\n")
                        .append(result.getQueryPlan() != null ? result.getQueryPlan().getSql() : sql)
                        .append("\n```\n\n");

                if (result.isSuccess()) {
                    evidenceMarkdown.append("**Results (").append(result.getRowCount()).append(" rows):**\n\n")
                            .append(result.getFormattedResult()).append("\n\n");
                    if (successfulSqlCount() >= TOOL_STEP_TARGET_SUCCESSFUL_SQL) {
                        evidenceSufficient = true;
                        return "SQL succeeded with " + result.getRowCount() + " rows.\n"
                                + result.getFormattedResult()
                                + "\n\nEvidence target reached for this step. Do not call runResearchSql again.";
                    }
                    return "SQL succeeded with " + result.getRowCount() + " rows.\n" + result.getFormattedResult();
                }

                evidenceMarkdown.append("**Error:** ").append(result.getErrorMessage()).append("\n\n");
                return "SQL failed: " + result.getErrorMessage();
            } catch (Exception e) {
                log.warn("runResearchSql failed: {}", e.getMessage(), e);
                return "SQL tool error: " + e.getMessage();
            }
        }

        @Tool("Search the web for external context relevant to the current research step. Use only when outside knowledge improves the report.")
        public String searchResearchWeb(
                @P("Focused web research topic") String topic
        ) {
            if (webSearchService == null) {
                return "Web search service is unavailable.";
            }
            if (webCallCount >= TOOL_STEP_MAX_WEB_CALLS) {
                return "Web search limit reached for this step. Use existing web evidence.";
            }
            webCallCount++;
            reportProgress(progressCallback, "web_search", step.getTitle() + " - Searching web...");
            try {
                WebSearchService.WebSearchResponse response = webSearchService.searchWithLLM(topic, modelName);
                if (response.getSources() != null) {
                    allWebResults.addAll(response.getSources());
                }
                String synthesized = response.getSynthesizedText() != null ? response.getSynthesizedText() : "";
                if (!synthesized.isBlank()) {
                    webResearchMd.append("\n## ").append(topic).append("\n\n").append(synthesized).append("\n");
                    evidenceMarkdown.append("### Web Evidence: ").append(topic).append("\n\n")
                            .append(synthesized).append("\n\n");
                }
                return synthesized.isBlank() ? "No web synthesis returned." : synthesized;
            } catch (Exception e) {
                log.warn("searchResearchWeb failed: {}", e.getMessage(), e);
                return "Web search failed: " + e.getMessage();
            }
        }

        @Tool("Summarize the evidence collected so far for this step and identify gaps. Use before deciding whether evidence is sufficient.")
        public String analyzeResearchData(
                @P("What to analyze or what gap to check in the collected evidence") String focus
        ) {
            if (evidenceMarkdown.isEmpty()) {
                return "No evidence collected yet.";
            }
            try {
                reportProgress(progressCallback, "synthesizing", step.getTitle() + " - Analyzing evidence...");
                String summary = getFastSummarizer().summarizeIteration(
                        originalQuestion + "\nFocus: " + focus,
                        evidenceMarkdown.toString());
                evidenceMarkdown.append("### Evidence Analysis\n\n").append(summary).append("\n\n");
                return summary;
            } catch (Exception e) {
                log.warn("analyzeResearchData failed: {}", e.getMessage());
                return "Collected evidence:\n" + evidenceMarkdown;
            }
        }

        @Tool("Mark this research step as sufficiently supported by collected evidence. Use only after at least one successful database result, or clear web-only evidence when no DB data is needed.")
        public String markEvidenceSufficient(
                @P("Brief reason why the collected evidence is enough for this step") String reason
        ) {
            evidenceSufficient = true;
            evidenceMarkdown.append("### Evidence Sufficiency\n\n").append(reason).append("\n\n");
            return "Evidence marked sufficient: " + reason;
        }

        String getEvidenceMarkdown() {
            return evidenceMarkdown.toString();
        }

        boolean isEvidenceSufficient() {
            return evidenceSufficient;
        }

        long successfulSqlCount() {
            return stepResult.getQueryResults().stream().filter(QueryResult::isSuccess).count();
        }
    }
    
    private String formatResultForMd(ExecuteResult result, String question) {
        if (result == null || result.getDataList() == null || result.getDataList().isEmpty()) {
            return "No data returned";
        }

        StringBuilder sb = new StringBuilder();
        List<Header> headers = result.getHeaderList();
        List<List<String>> data = result.getDataList();

        int rowLimit = data.size();

        // Header row
        if (headers != null && !headers.isEmpty()) {
            sb.append("| ");
            sb.append(headers.stream()
                    .filter(h -> !"Row Number".equals(h.getName()))
                    .map(Header::getName)
                    .collect(Collectors.joining(" | ")));
            sb.append(" |\n");

            // Separator
            sb.append("| ");
            sb.append(headers.stream()
                    .filter(h -> !"Row Number".equals(h.getName()))
                    .map(h -> "---")
                    .collect(Collectors.joining(" | ")));
            sb.append(" |\n");
        }

        // Data rows
        for (int i = 0; i < rowLimit; i++) {
            List<String> row = data.get(i);
            sb.append("| ");
            // Skip Row Number column
            int startIdx = (headers != null && !headers.isEmpty() && "Row Number".equals(headers.get(0).getName())) ? 1 : 0;
            for (int j = startIdx; j < row.size(); j++) {
                sb.append(row.get(j) != null ? row.get(j) : "");
                if (j < row.size() - 1) sb.append(" | ");
            }
            sb.append(" |\n");
        }

        if (data.size() > rowLimit) {
            sb.append("\n*...and ").append(data.size() - rowLimit).append(" more rows*\n");
        }

        return sb.toString();
    }

    /**
     * Run Python statistical analysis on large result sets (100+ rows).
     * Writes full CSV to temp file, LLM generates Python code, executes with full data.
     */
    private String runPythonStatistics(String question, ExecuteResult data, int rowCount) {
        try {
            String fullCsv = buildCsvFromResult(data);
            Path csvFile = Files.createTempFile("inquery-dr-python-", ".csv");
            Files.writeString(csvFile, fullCsv);

            // Build preview (header + 10 rows) for LLM context
            String[] csvLines = fullCsv.split("\n");
            int previewLines = Math.min(csvLines.length, 11);
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < previewLines; i++) {
                preview.append(csvLines[i]).append("\n");
            }
            if (csvLines.length > previewLines) {
                preview.append("... (").append(csvLines.length - 1).append(" total rows)\n");
            }

            String prompt = "Research question: \"" + question + "\"\n\n"
                    + "SQL query returned " + rowCount + " rows.\n"
                    + "Data preview (first 10 rows):\n" + preview + "\n"
                    + "The FULL dataset is already loaded as DataFrame 'df' (" + rowCount + " rows).\n\n"
                    + "Produce a comprehensive statistical summary relevant to the research question. "
                    + "Include: counts, averages, min/max, percentages, top-N rankings, trends, groupby summaries. "
                    + "Print results to stdout. Do NOT create charts.";

            String result = pythonTools.executePythonWithDataFile(
                    prompt, csvFile.toString(), modelProvider.getChatModel(modelName));

            // Clean up temp file
            try { Files.deleteIfExists(csvFile); } catch (Exception ignored) {}

            if (result != null && !result.isBlank() && !result.startsWith("Error:") && !result.startsWith("Python execution")) {
                log.info("Python statistics generated for {} rows ({} chars)", rowCount, result.length());
                return "**Statistical Summary (Python analysis of " + rowCount + " rows):**\n\n" + result;
            } else {
                log.warn("Python statistics failed, falling back to truncated MD table: {}", result);
                return formatResultForMdTruncated(data, question, PYTHON_STATS_ROW_THRESHOLD);
            }
        } catch (Exception e) {
            log.warn("Python statistics failed, falling back to truncated MD table", e);
            return formatResultForMdTruncated(data, question, PYTHON_STATS_ROW_THRESHOLD);
        }
    }

    /**
     * Build full CSV string from ExecuteResult for Python processing.
     */
    private String buildCsvFromResult(ExecuteResult data) {
        StringBuilder csv = new StringBuilder();
        List<Header> headers = data.getHeaderList();
        List<List<String>> rows = data.getDataList();

        // Header row
        if (headers != null) {
            csv.append(headers.stream()
                    .filter(h -> !"Row Number".equals(h.getName()))
                    .map(h -> escapeCsv(h.getName()))
                    .collect(Collectors.joining(",")));
            csv.append("\n");
        }

        // Data rows
        int startIdx = (headers != null && !headers.isEmpty() && "Row Number".equals(headers.get(0).getName())) ? 1 : 0;
        if (rows != null) {
            for (List<String> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int j = startIdx; j < row.size(); j++) {
                    if (j > startIdx) line.append(",");
                    line.append(escapeCsv(row.get(j)));
                }
                csv.append(line).append("\n");
            }
        }

        return csv.toString();
    }

    private String formatResultForMdTruncated(ExecuteResult result, String question, int maxRows) {
        if (result == null || result.getDataList() == null || result.getDataList().isEmpty()) {
            return "No data returned";
        }
        StringBuilder sb = new StringBuilder();
        List<Header> headers = result.getHeaderList();
        List<List<String>> data = result.getDataList();
        int rowLimit = Math.min(data.size(), maxRows);

        if (headers != null && !headers.isEmpty()) {
            sb.append("| ");
            sb.append(headers.stream()
                    .filter(h -> !"Row Number".equals(h.getName()))
                    .map(Header::getName)
                    .collect(Collectors.joining(" | ")));
            sb.append(" |\n| ");
            sb.append(headers.stream()
                    .filter(h -> !"Row Number".equals(h.getName()))
                    .map(h -> "---")
                    .collect(Collectors.joining(" | ")));
            sb.append(" |\n");
        }

        int startIdx = (headers != null && !headers.isEmpty() && "Row Number".equals(headers.get(0).getName())) ? 1 : 0;
        for (int i = 0; i < rowLimit; i++) {
            List<String> row = data.get(i);
            sb.append("| ");
            for (int j = startIdx; j < row.size(); j++) {
                sb.append(row.get(j) != null ? row.get(j) : "");
                if (j < row.size() - 1) sb.append(" | ");
            }
            sb.append(" |\n");
        }

        if (data.size() > rowLimit) {
            sb.append("\n*...and ").append(data.size() - rowLimit).append(" more rows (Python analysis unavailable)*\n");
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private ResearchReport generateReport(String question, String schemaContext, String webContext) {
        ResearchReport report = new ResearchReport();
        StringBuilder combinedResearch = new StringBuilder();

        try {
            ReportSynthesizerAgent synthesizer = getReportSynthesizerAgent();
            
            // Combine database research and web research.
            // Per-query results are already kept compact by runPythonStatistics()
            // (>= PYTHON_STATS_ROW_THRESHOLD rows -> stats summary). If the
            // cumulative MD still grows beyond MAX_MD_SIZE_KB we semantically
            // compress it via the fast summarizer instead of arbitrarily
            // truncating — losing evidence at this stage would silently
            // weaken the final report.
            String dbResearch = researchMd != null ? researchMd.toString() : "";
            if (!dbResearch.isEmpty()) {
                combinedResearch.append("# Database Research\n\n");
                combinedResearch.append(compressIfTooLong(dbResearch, MAX_MD_SIZE_KB * 1024, question));
            }
            
            if (webContext != null && !webContext.isEmpty()) {
                combinedResearch.append("\n\n# External Research (Web)\n\n");
                combinedResearch.append(compressIfTooLong(webContext, MAX_MD_SIZE_KB * 1024, question));
                if (webResearchMd != null && !webResearchMd.toString().isEmpty()) {
                    combinedResearch.append(compressIfTooLong(webResearchMd.toString(),
                            MAX_MD_SIZE_KB * 1024, question));
                }
            }
            
            String bizCtx = businessContext != null ? "Business Context:\n" + businessContext : "";
            JSONObject json = generateStrictReportJson(synthesizer, question,
                    combinedResearch.toString(), schemaContext, bizCtx);
            report = parseReportJson(json);
            report = normalizeReportStructure(report, question);
        } catch (Exception e) {
            log.error("Report generation failed", e);
        }

        if (!hasReportContent(report)) {
            ResearchReport deterministic = buildFallbackReport(question);
            deterministic.setCitations(report.getCitations());
            deterministic.setWebSources(report.getWebSources());
            report = deterministic;
        }

        // Fallback: If no webSources from LLM but we have collected web results, use them
        if ((report.getWebSources() == null || report.getWebSources().isEmpty()) 
                && allWebResults != null && !allWebResults.isEmpty()) {
            log.info("Adding {} web sources from collected results (LLM didn't include them)", allWebResults.size());
            List<WebSource> webSources = new ArrayList<>();
            for (WebSearchService.SearchResult result : allWebResults) {
                WebSource source = new WebSource();
                source.setUrl(result.getUrl());
                source.setTitle(result.getTitle());
                try {
                    java.net.URL url = new java.net.URL(result.getUrl());
                    source.setDomain(url.getHost().replace("www.", ""));
                } catch (Exception e) {
                    source.setDomain("");
                }
                webSources.add(source);
            }
            report.setWebSources(webSources);
        }

        // Also ensure DB citations are populated if not provided by LLM
        if ((report.getCitations() == null || report.getCitations().isEmpty()) 
                && allQueryResults != null && !allQueryResults.isEmpty()) {
            log.info("Adding {} DB citations from query results (LLM didn't include them)", allQueryResults.size());
            List<Citation> citations = new ArrayList<>();
            int citationId = 1;
            for (QueryResult qr : allQueryResults) {
                if (qr.isSuccess() && qr.getQueryPlan() != null) {
                    Citation citation = new Citation();
                    citation.setId(String.valueOf(citationId++));
                    // Build full table path
                    String tablePath = buildTablePath(qr.getQueryPlan().getDerivedQuestion().getQuestion());
                    citation.setTable(tablePath);
                    citation.setQuery(qr.getQueryPlan().getSql());
                    citation.setDescription(qr.getQueryPlan().getDerivedQuestion().getQuestion());
                    citations.add(citation);
                }
            }
            report.setCitations(citations);
        }

        return report;
    }

    private JSONObject generateStrictReportJson(ReportSynthesizerAgent synthesizer, String question,
                                                String researchData, String schemaContext, String businessContext) {
        String response = synthesizer.generateReport(question, researchData, schemaContext, businessContext);
        JSONObject json = parseJsonObject(response);
        if (isValidReportJson(json)) {
            return json;
        }

        String lastResponse = response;
        for (int attempt = 1; attempt <= REPORT_JSON_REPAIR_ATTEMPTS; attempt++) {
            log.warn("Deep Research report JSON contract invalid; repair attempt {}/{}",
                    attempt, REPORT_JSON_REPAIR_ATTEMPTS);
            String repaired = repairReportJson(question, researchData, schemaContext, businessContext, lastResponse);
            json = parseJsonObject(repaired);
            if (isValidReportJson(json)) {
                return json;
            }
            lastResponse = repaired;
        }

        log.error("Deep Research report JSON contract could not be repaired; using deterministic server-side report object");
        return JSON.parseObject(JSON.toJSONString(buildFallbackReport(question)));
    }

    private String repairReportJson(String question, String researchData, String schemaContext,
                                    String businessContext, String invalidResponse) {
        String prompt = """
                You are repairing a Deep Research final report JSON.

                The previous response was invalid or did not match the required contract.
                Return STRICT JSON only. No markdown fence. No explanation.

                Required JSON contract:
                {
                  "title": "string",
                  "language": "ko|en|...",
                  "sections": [
                    { "title": "Executive Summary or localized title", "content": "markdown", "citations": [] },
                    { "title": "Key Findings or localized title", "content": "markdown", "citations": [] },
                    { "title": "Detailed Analysis or localized title", "content": "markdown", "citations": [] },
                    { "title": "Data Summary or localized title", "content": "markdown", "citations": [] },
                    { "title": "Conclusions and Recommendations or localized title", "content": "markdown", "citations": [] }
                  ],
                  "citations": [],
                  "webSources": []
                }

                Hard rules:
                - sections MUST contain exactly 5 objects in the order above.
                - Every section.content MUST be non-empty markdown.
                - Keep the report language consistent with the original question.
                - Escape all newlines and quotes correctly for JSON strings.
                - If citations are uncertain, use an empty citations array instead of invalid citation objects.

                Original question:
                %s

                Business context:
                %s

                Schema context:
                %s

                Collected research data:
                %s

                Invalid previous response:
                %s
                """.formatted(
                safePromptText(question, 2000),
                safePromptText(businessContext, 4000),
                safePromptText(schemaContext, 8000),
                safePromptText(researchData, 30000),
                safePromptText(invalidResponse, 12000)
        );
        return modelProvider.getPlainChatModel(modelName).chat(prompt);
    }

    private ResearchReport parseReportJson(JSONObject json) {
        ResearchReport report = new ResearchReport();
        if (json == null) {
            return report;
        }

        report.setTitle(json.getString("title"));
        report.setLanguage(json.getString("language"));

        JSONArray sectionsArray = json.getJSONArray("sections");
        if (sectionsArray != null) {
            List<ReportSection> sections = new ArrayList<>();
            for (int i = 0; i < sectionsArray.size(); i++) {
                JSONObject sectionJson = sectionsArray.getJSONObject(i);
                ReportSection section = new ReportSection();
                section.setTitle(sectionJson.getString("title"));
                section.setContent(sectionJson.getString("content"));

                JSONArray sectionCitationsArray = sectionJson.getJSONArray("citations");
                if (sectionCitationsArray != null) {
                    List<SectionCitation> sectionCitations = new ArrayList<>();
                    for (int j = 0; j < sectionCitationsArray.size(); j++) {
                        JSONObject citJson = sectionCitationsArray.getJSONObject(j);
                        SectionCitation sc = new SectionCitation();
                        sc.setNumber(citJson.getIntValue("number"));
                        sc.setType(citJson.getString("type"));
                        sc.setTitle(citJson.getString("title"));

                        if ("database".equals(sc.getType())) {
                            sc.setTable(citJson.getString("table"));
                            sc.setQuery(citJson.getString("query"));
                        } else if ("web".equals(sc.getType())) {
                            sc.setUrl(citJson.getString("url"));
                        }

                        sectionCitations.add(sc);
                    }
                    section.setCitations(sectionCitations);
                    log.debug("Section '{}' has {} inline citations", section.getTitle(), sectionCitations.size());
                }
                sections.add(section);
            }
            report.setSections(sections);
        }

        JSONArray citationsArray = json.getJSONArray("citations");
        if (citationsArray != null) {
            List<Citation> citations = new ArrayList<>();
            for (int i = 0; i < citationsArray.size(); i++) {
                JSONObject citationJson = citationsArray.getJSONObject(i);
                Citation citation = new Citation();
                citation.setId(citationJson.getString("id"));
                citation.setTable(citationJson.getString("table"));
                citation.setQuery(citationJson.getString("query"));
                citation.setDescription(citationJson.getString("description"));
                citations.add(citation);
            }
            report.setCitations(citations);
        }

        JSONArray webSourcesArray = json.getJSONArray("webSources");
        if (webSourcesArray != null && !webSourcesArray.isEmpty()) {
            List<WebSource> webSources = new ArrayList<>();
            for (int i = 0; i < webSourcesArray.size(); i++) {
                JSONObject sourceJson = webSourcesArray.getJSONObject(i);
                WebSource source = new WebSource();
                source.setUrl(sourceJson.getString("url"));
                source.setTitle(sourceJson.getString("title"));
                try {
                    java.net.URL url = new java.net.URL(sourceJson.getString("url"));
                    source.setDomain(url.getHost().replace("www.", ""));
                } catch (Exception e) {
                    source.setDomain(sourceJson.getString("domain"));
                }
                webSources.add(source);
            }
            report.setWebSources(webSources);
        }

        return report;
    }

    private boolean isValidReportJson(JSONObject json) {
        if (json == null || json.getString("title") == null || json.getString("title").isBlank()) {
            return false;
        }
        JSONArray sections = json.getJSONArray("sections");
        if (sections == null || sections.size() != 5) {
            return false;
        }
        for (int i = 0; i < sections.size(); i++) {
            JSONObject section = sections.getJSONObject(i);
            if (section == null
                    || section.getString("title") == null || section.getString("title").isBlank()
                    || section.getString("content") == null || section.getString("content").isBlank()) {
                return false;
            }
            if (!section.containsKey("citations")) {
                return false;
            }
        }
        return true;
    }

    private ResearchReport normalizeReportStructure(ResearchReport report, String question) {
        if (report == null) {
            return buildFallbackReport(question);
        }
        if (report.getSections() != null && report.getSections().size() == 5 && hasReportContent(report)) {
            return report;
        }

        ResearchReport normalized = buildFallbackReport(question);
        normalized.setTitle(report.getTitle() != null && !report.getTitle().isBlank()
                ? report.getTitle() : normalized.getTitle());
        normalized.setLanguage(report.getLanguage() != null && !report.getLanguage().isBlank()
                ? report.getLanguage() : normalized.getLanguage());
        normalized.setCitations(report.getCitations());
        normalized.setWebSources(report.getWebSources());

        if (report.getSections() != null && !report.getSections().isEmpty()) {
            String joined = report.getSections().stream()
                    .filter(section -> section != null && section.getContent() != null && !section.getContent().isBlank())
                    .map(section -> "### " + (section.getTitle() != null ? section.getTitle() : "Analysis")
                            + "\n\n" + section.getContent())
                    .collect(Collectors.joining("\n\n"));
            if (!joined.isBlank() && normalized.getSections() != null && normalized.getSections().size() >= 3) {
                normalized.getSections().get(2).setContent(joined);
            }
        }
        return normalized;
    }

    private boolean hasReportContent(ResearchReport report) {
        return report != null
                && report.getSections() != null
                && report.getSections().stream()
                        .anyMatch(section -> section != null
                                && section.getContent() != null
                                && !section.getContent().isBlank());
    }

    private ResearchReport buildFallbackReport(String question) {
        ResearchReport fallback = new ResearchReport();
        fallback.setTitle("Research Report");
        fallback.setLanguage(detectLanguageCode(question));

        String markdown = researchMd != null ? researchMd.toString().trim() : "";
        if (markdown.isEmpty() && webResearchMd != null) {
            markdown = webResearchMd.toString().trim();
        }

        List<ReportSection> sections = new ArrayList<>();
        sections.add(makeReportSection(
                "Executive Summary",
                buildExecutiveSummaryFallback(question)));
        sections.add(makeReportSection(
                "Key Findings",
                buildKeyFindingsFallback()));
        sections.add(makeReportSection(
                "Detailed Analysis",
                buildDetailedAnalysisFallback(markdown)));
        sections.add(makeReportSection(
                "Data Summary",
                buildDataSummaryFallback()));
        sections.add(makeReportSection(
                "Conclusions and Recommendations",
                buildRecommendationsFallback()));

        fallback.setSections(sections);
        return fallback;
    }

    private ReportSection makeReportSection(String title, String content) {
        ReportSection section = new ReportSection();
        section.setTitle(title);
        section.setContent(content);
        return section;
    }

    private String detectLanguageCode(String question) {
        if (isKorean(question)) {
            return "ko";
        }
        return "en";
    }

    private boolean isKorean(String text) {
        return text != null && text.chars().anyMatch(ch -> ch >= 0xAC00 && ch <= 0xD7A3);
    }

    private String buildExecutiveSummaryFallback(String question) {
        long successful = allQueryResults == null ? 0 : allQueryResults.stream().filter(QueryResult::isSuccess).count();
        int webSources = allWebResults == null ? 0 : allWebResults.size();
        return "The research request `" + safeInline(question) + "` was analyzed using "
                + successful + " successful database queries and " + webSources
                + " external web sources. The final LLM report JSON could not be fully parsed, so the collected evidence has been organized into the standard report structure below.";
    }

    private String buildKeyFindingsFallback() {
        StringBuilder sb = new StringBuilder();
        List<QueryResult> successful = successfulQueryResults();
        int limit = Math.min(successful.size(), 8);
        if (limit == 0) {
            return "- No successful database evidence was collected. Check the research plan or data source.";
        }
        for (int i = 0; i < limit; i++) {
            QueryResult result = successful.get(i);
            String q = result.getQueryPlan() != null && result.getQueryPlan().getDerivedQuestion() != null
                    ? result.getQueryPlan().getDerivedQuestion().getQuestion()
                    : "Analysis query";
            sb.append("- `").append(safeInline(q)).append("` returned ")
                    .append(result.getRowCount()).append(" rows of evidence.\n");
        }
        return sb.toString();
    }

    private String buildDetailedAnalysisFallback(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "No detailed analysis body was collected.";
        }
        String normalized = markdown.replaceAll("(?m)^##\\s+", "### ");
        int maxChars = 12000;
        if (normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars)
                    + "\n\n_Additional evidence was omitted due to length limits._";
        }
        return normalized;
    }

    private String buildDataSummaryFallback() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Metric | Value |\n|---|---:|\n");
        sb.append("| Successful SQL evidence | ").append(successfulQueryResults().size()).append(" |\n");
        sb.append("| Total SQL attempts | ").append(allQueryResults != null ? allQueryResults.size() : 0).append(" |\n");
        sb.append("| Web sources | ").append(allWebResults != null ? allWebResults.size() : 0).append(" |\n\n");
        sb.append("### Key SQL Executed\n\n");
        for (QueryResult result : successfulQueryResults().stream().limit(5).toList()) {
            if (result.getQueryPlan() != null && result.getQueryPlan().getSql() != null) {
                sb.append("```sql\n").append(result.getQueryPlan().getSql()).append("\n```\n\n");
            }
        }
        return sb.toString();
    }

    private String buildRecommendationsFallback() {
        return "- Prioritize the segments, regions, and value metrics supported by successful SQL evidence.\n"
                + "- For large result sets, use Python summaries or additional filters to validate detailed patterns.\n"
                + "- Use web sources as market context, while keeping database evidence as the primary decision basis.\n";
    }

    private List<QueryResult> successfulQueryResults() {
        if (allQueryResults == null) {
            return List.of();
        }
        return allQueryResults.stream()
                .filter(QueryResult::isSuccess)
                .collect(Collectors.toList());
    }

    private String safeInline(String text) {
        if (text == null) return "";
        return text.replace("`", "'").replace("\n", " ").trim();
    }

    private String safePromptText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, maxChars) + "\n\n[TRUNCATED]";
    }

    /**
     * Semantically compresses oversized research markdown rather than
     * truncating it. Truncation silently drops the tail of evidence — a
     * subtle correctness bug at synthesis time — so when the body exceeds
     * the cap we delegate to the fast summarizer to compress while
     * preserving every concrete number / citation.
     */
    private String compressIfTooLong(String text, int maxChars, String originalQuestion) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        log.info("Research markdown is {} chars (cap {}). Compressing via fast summarizer.",
                text.length(), maxChars);
        try {
            String summary = getFastSummarizer().summarizeIteration(originalQuestion, text);
            if (summary != null && !summary.isBlank()) {
                return summary + "\n\n_[compressed from " + text.length() + " chars of source evidence]_\n";
            }
        } catch (Exception e) {
            log.warn("Fast summarizer compression failed; keeping head of evidence and noting truncation", e);
        }
        return text.substring(0, maxChars)
                + "\n\n_[additional evidence omitted due to length cap]_\n";
    }

    // Helper methods for JSON parsing
    private JSONArray parseJsonArray(String response) {
        try {
            String json = extractJson(response);
            if (json != null && json.startsWith("[")) {
                // Clean up invalid escape characters
                json = cleanJsonString(json);
                // Ensure JSON is properly closed
                json = ensureJsonArrayClosed(json);
                return JSON.parseArray(json);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON array: {}", e.getMessage());
            // Log first 500 chars of response for debugging
            if (response != null) {
                log.debug("Raw response (first 500 chars): {}", 
                    response.substring(0, Math.min(500, response.length())));
            }
        }
        return null;
    }

    private JSONObject parseJsonObject(String response) {
        try {
            String json = extractJson(response);
            if (json != null && json.startsWith("{")) {
                // Clean up invalid escape characters
                json = cleanJsonString(json);
                // Ensure JSON is properly closed
                json = ensureJsonObjectClosed(json);
                return JSON.parseObject(json);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON object: {}", e.getMessage());
            // Log first 500 chars of response for debugging
            if (response != null) {
                log.debug("Raw response (first 500 chars): {}", 
                    response.substring(0, Math.min(500, response.length())));
            }
        }
        return null;
    }

    /**
     * Clean JSON string by fixing common escape character issues
     */
    private String cleanJsonString(String json) {
        if (json == null) return null;
        
        // Fix invalid escape sequences (e.g., \' should be ')
        json = json.replace("\\'", "'");
        // Fix unescaped backslashes that aren't valid escape sequences
        // Valid JSON escapes: quotes, backslash, slash, b, f, n, r, t, u+4hex
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '/' || 
                    next == 'b' || next == 'f' || next == 'n' || 
                    next == 'r' || next == 't' || next == 'u') {
                    sb.append(c);
                } else {
                    // Skip invalid backslash or escape it
                    sb.append("\\\\");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Ensure JSON array is properly closed with ]
     */
    private String ensureJsonArrayClosed(String json) {
        if (json == null) return null;
        json = json.trim();
        
        // Count brackets
        int openBrackets = 0;
        int closeBrackets = 0;
        boolean inString = false;
        char prevChar = 0;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') openBrackets++;
                if (c == ']') closeBrackets++;
            }
            prevChar = c;
        }
        
        // Add missing closing brackets
        while (closeBrackets < openBrackets) {
            json = json + "]";
            closeBrackets++;
        }
        
        return json;
    }

    /**
     * Ensure JSON object is properly closed with }
     */
    private String ensureJsonObjectClosed(String json) {
        if (json == null) return null;
        json = json.trim();
        
        // Count braces
        int openBraces = 0;
        int closeBraces = 0;
        boolean inString = false;
        char prevChar = 0;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') openBraces++;
                if (c == '}') closeBraces++;
            }
            prevChar = c;
        }
        
        // Add missing closing braces
        while (closeBraces < openBraces) {
            json = json + "}";
            closeBraces++;
        }
        
        return json;
    }

    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();

        // Remove markdown code blocks
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            log.debug("extractJson: found ```json block, start={}, end={}", start, end);
            if (end > start) {
                String extracted = trimmed.substring(start, end).trim();
                log.debug("extractJson: extracted {} chars from json block", extracted.length());
                return extracted;
            } else {
                // Code block not closed - LLM response might be truncated
                log.warn("extractJson: ```json block not closed (truncated response?), extracting from start to end");
                String extracted = trimmed.substring(start).trim();
                // Remove any trailing ``` if partially present
                if (extracted.endsWith("`")) {
                    extracted = extracted.replaceAll("`+$", "");
                }
                return extracted;
            }
        }
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            // Skip any language identifier (e.g., "sql", "json")
            int newlineIdx = trimmed.indexOf('\n', start);
            if (newlineIdx > start && newlineIdx - start < 10) {
                start = newlineIdx + 1;
            }
            int end = trimmed.indexOf("```", start);
            log.debug("extractJson: found ``` block, start={}, end={}", start, end);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            } else {
                // Code block not closed
                log.warn("extractJson: ``` block not closed, extracting from start to end");
                return trimmed.substring(start).trim();
            }
        }

        // Find first [ or {
        int arrayStart = trimmed.indexOf('[');
        int objStart = trimmed.indexOf('{');

        if (arrayStart >= 0 && (objStart < 0 || arrayStart < objStart)) {
            log.debug("extractJson: found array at position {}", arrayStart);
            return trimmed.substring(arrayStart);
        }
        if (objStart >= 0) {
            log.debug("extractJson: found object at position {}", objStart);
            return trimmed.substring(objStart);
        }

        log.warn("extractJson: no JSON structure found in response");
        return trimmed;
    }

    private String stripCodeBlock(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text.trim();

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "```(?:sql)?\\s*\\n?(.*?)\\n?\\s*```",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            result = matcher.group(1).trim();
        }

        result = result.replaceAll("/\\*[\\s\\S]*?\\*/", "").trim();

        return result;
    }

    private void reportProgress(Consumer<ProgressEvent> callback, String type, String message) {
        if (callback != null) {
            callback.accept(new ProgressEvent(type, message));
        }
    }

    /**
     * Build full table path (DATABASE.SCHEMA.TABLE) for citations.
     */
    private String buildTablePath(String questionOrTable) {
        // If already looks like a full path, return as is
        if (questionOrTable != null && questionOrTable.contains(".") && !questionOrTable.contains(" ")) {
            return questionOrTable;
        }
        
        // Build from connection context
        StringBuilder path = new StringBuilder();
        if (databaseName != null && !databaseName.isEmpty()) {
            path.append(databaseName);
            if (schemaName != null && !schemaName.isEmpty()) {
                path.append(".").append(schemaName);
            }
        }
        
        // If we have a path prefix, it's likely a table reference issue
        // Just return the prefix for now - the actual table will be in the query
        if (path.length() > 0) {
            return path.toString();
        }
        
        return "DB_QUERY";
    }

    // Lazy agent initialization
    private QuestionPlannerAgent getQuestionPlannerAgent() {
        if (questionPlanner == null) {
            questionPlanner = AiServices.builder(QuestionPlannerAgent.class)
                    .chatModel(modelProvider.getChatModel(modelName))
                    .build();
        }
        return questionPlanner;
    }

    // Hybrid reasoning: per-derived-question SQL generation in deep
    // research is the same NL→SQL hot path as the main runner. Use
    // MEDIUM effort so each sub-question's SQL benefits from extra
    // schema/predicate deliberation. The other lazy agents in this file
    // (planner, self-reflection, etc.) stay on LOW because they're either
    // structured-output orchestration or short verbalizations.
    private SqlWriterAgent getSqlWriterAgent() {
        if (sqlWriter == null) {
            sqlWriter = AiServices.builder(SqlWriterAgent.class)
                    .chatModel(modelProvider.getChatModel(
                            modelName, LangChainModelProvider.ReasoningEffort.MEDIUM))
                    .build();
        }
        return sqlWriter;
    }

    private SelfReflectionAgent getSelfReflectionAgent() {
        if (selfReflection == null) {
            selfReflection = AiServices.builder(SelfReflectionAgent.class)
                    .chatModel(modelProvider.getChatModel(modelName))
                    .build();
        }
        return selfReflection;
    }

    private SelfReflectionAgent getFastSummarizer() {
        if (fastSummarizer == null) {
            String fastModel = ModelMapper.getFastModel(modelName);
            fastSummarizer = AiServices.builder(SelfReflectionAgent.class)
                    .chatModel(modelProvider.getChatModel(fastModel))
                    .chatRequestTransformer(ModelMapper.promptRepetitionTransformer(fastModel))
                    .build();
        }
        return fastSummarizer;
    }

    private ReportSynthesizerAgent getReportSynthesizerAgent() {
        if (reportSynthesizer == null) {
            // The synthesizer must emit a complete multi-section JSON
            // report. The default plain chat model caps output at 8192
            // tokens which silently truncates the JSON mid-response —
            // that, not "LLM gave us bad JSON", was the root cause of
            // the broken report UX. Use the long-form variant so the
            // model has enough output budget to honor the contract.
            reportSynthesizer = AiServices.builder(ReportSynthesizerAgent.class)
                    .chatModel(modelProvider.getLongFormChatModel(modelName))
                    .build();
        }
        return reportSynthesizer;
    }

    private WebSearchAgent getWebSearchAgent() {
        if (webSearchAgent == null) {
            webSearchAgent = AiServices.builder(WebSearchAgent.class)
                    .chatModel(modelProvider.getChatModel(modelName))
                    .build();
        }
        return webSearchAgent;
    }

    // Inner classes for data structures
    @Data
    public static class ProgressEvent {
        private final String type;
        private final String message;
    }

    @Data
    public static class DerivedQuestion {
        private String question;
        private String strategy;
        private int priority;
    }

    @Data
    public static class QueryPlan {
        private DerivedQuestion derivedQuestion;
        private String sql;
    }

    @Data
    public static class QueryResult {
        private QueryPlan queryPlan;
        private boolean success;
        private String errorMessage;
        private int rowCount;
        private String formattedResult;
    }

    @Data
    @Builder
    public static class DeepResearchResult {
        private String originalQuestion;
        private String schemaContext;
        private String webSearchContext;
        private boolean success;
        private String errorMessage;
        private int iterationsUsed;
        private int totalQueriesExecuted;
        private int totalWebSearches;
        private ResearchReport report;
    }

    @Data
    public static class ResearchReport {
        private String title;
        private String language;
        private List<ReportSection> sections;
        private List<Citation> citations;
        private List<WebSource> webSources;
    }

    @Data
    public static class WebSource {
        private String url;
        private String title;
        private String domain;
    }

    @Data
    public static class ReportSection {
        private String title;
        private String content;
        private List<ReportTable> tables;
        private List<SectionCitation> citations;  // Gemini-style inline citations
        private boolean expanded = true;
    }

    @Data
    public static class SectionCitation {
        private int number;           // Citation number (¹, ², ³, etc.)
        private String type;          // "database" or "web"
        // For database sources
        private String table;         // Full path: DATABASE.SCHEMA.TABLE
        private String query;
        // For web sources
        private String url;
        // Common
        private String title;         // Brief description
    }

    @Data
    public static class ReportTable {
        private String caption;
        private String markdown;
        private String citationTable;
        private String citationQuery;
    }

    @Data
    public static class Citation {
        private String id;
        private String table;
        private String query;
        private String description;
    }

    @Data
    public static class PlanStep {
        private String title;
        private String details;
        private String icon;
    }

    @Data
    public static class StepResult {
        private String researchMd = "";
        private List<QueryResult> queryResults = new ArrayList<>();
    }
}

interface DeepResearchToolAgent {
    @SystemMessage("""
            You are a tool-calling Deep Research step agent.

            Goal:
            - Collect enough concrete evidence for ONE research plan step.
            - Prefer database evidence when schema/data exists.
            - Use web evidence only when external context improves interpretation.
            - Do not follow a fixed number of iterations; stop when evidence is sufficient.

            Tool policy:
            - Use searchResearchSchema when table/metric coverage is uncertain.
            - Use runResearchSql for concrete quantitative evidence. Ask one focused data question per call.
            - HARD LIMIT: runResearchSql is capped at 3 calls per step. Usually 1-2 successful SQL results are enough.
            - Once a tool response says the evidence target or SQL limit is reached, do NOT call runResearchSql again.
            - Use searchResearchWeb at most once per step, only when needed.
            - Use analyzeResearchData before finalizing if multiple evidence items need synthesis.
            - Use markEvidenceSufficient before your final answer once evidence is enough.

            Safety:
            - Never invent numbers. Use only tool results.
            - If database evidence is needed, collect at least one successful SQL result before marking sufficient.
            - Keep the final answer concise; the backend stores detailed evidence separately.
            """)
    @UserMessage("""
            Original research question:
            {{question}}

            Current plan step:
            {{stepTitle}}

            Step details:
            {{stepDetails}}

            Initial schema context:
            {{schemaContext}}

            Business context:
            {{businessContext}}

            Existing web research context:
            {{webContext}}

            Collect evidence for this step using tools, then return a concise step synthesis.
            """)
    String researchStep(
            @V("question") String question,
            @V("stepTitle") String stepTitle,
            @V("stepDetails") String stepDetails,
            @V("schemaContext") String schemaContext,
            @V("businessContext") String businessContext,
            @V("webContext") String webContext
    );
}
