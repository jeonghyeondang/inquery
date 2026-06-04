package ai.inquery.server.web.api.controller.ai;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.agents.DeepResearchAgent;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService;
import ai.inquery.server.domain.core.query.DeepResearchClassifier;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.domain.core.security.AstValidator;
import ai.inquery.server.domain.repository.entity.DeepResearchSessionDO;
import ai.inquery.server.domain.repository.mapper.DeepResearchSessionMapper;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Deep Research Controller.
 * Handles Deep Research mode operations including classification, session management, and research execution.
 */
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/ai/deep-research")
@Slf4j
public class DeepResearchController {

    private static final long RESEARCH_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    // Research execution models. Picks the best speed/quality trade-off
    // per provider as of 2026-05 — see ModelMapper for the canonical
    // roster. OpenAI uses gpt-5.4-mini rather than gpt-5.5 because the
    // latter forces medium reasoning_effort with function tools (see
    // ChatController#DEFAULT_OPENAI_MODEL for the full rationale), and
    // iterative deep-research already issues many turns — every extra
    // 30-60s per turn would compound badly. Claude uses sonnet-4-6 since
    // Claude has no equivalent reasoning_effort constraint.
    private static final String GEMINI_MODEL = "gemini-3.5-flash";
    private static final String OPENAI_MODEL = "gpt-5.4-mini";
    private static final String CLAUDE_MODEL = "claude-sonnet-4-6";

    // Classification models. Same fast tier as execution for OpenAI here
    // — classify only emits a JSON plan, so we don't need a smaller SKU.
    private static final String GEMINI_CLASSIFY_MODEL = "gemini-3.5-flash";
    private static final String OPENAI_CLASSIFY_MODEL = "gpt-5.4-mini";
    private static final String CLAUDE_CLASSIFY_MODEL = "claude-haiku-4-5";

    private Integer parsePortOrNull(String rawPort) {
        if (rawPort == null || rawPort.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            log.warn("Invalid data source port: {}", rawPort);
            return null;
        }
    }

    @Autowired
    private DeepResearchClassifier deepResearchClassifier;

    @Autowired
    private LangChainModelProvider modelProvider;

    @Autowired
    private ai.inquery.server.domain.api.service.DlTemplateService dlTemplateService;

    @Autowired
    private SchemaSearcher schemaSearcher;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private DeepResearchSessionMapper sessionMapper;

    @Autowired
    private ConfigService configService;

    @Autowired
    private WebSearchService webSearchService;

    @Autowired
    private ai.inquery.server.domain.core.business.BusinessInsightService businessInsightService;

    @Autowired
    private ai.inquery.server.domain.core.python.PythonEnvironmentSetup pythonEnvironmentSetup;

    @Autowired(required = false)
    private AstValidator astValidator;

    /**
     * Get the preferred model based on configured API keys.
     * Priority: Gemini > Claude > OpenAI
     */
    private String getPreferredModel() {
        try {
            // Check Gemini
            DataResult<ai.inquery.server.domain.api.model.Config> geminiConfig = 
                configService.find(ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.GEMINI_API_KEY);
            if (geminiConfig != null && geminiConfig.getData() != null && 
                geminiConfig.getData().getContent() != null && !geminiConfig.getData().getContent().isEmpty()) {
                return GEMINI_MODEL;
            }

            // Check Claude
            DataResult<ai.inquery.server.domain.api.model.Config> claudeConfig = 
                configService.find(ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.CLAUDE_API_KEY);
            if (claudeConfig != null && claudeConfig.getData() != null && 
                claudeConfig.getData().getContent() != null && !claudeConfig.getData().getContent().isEmpty()) {
                return CLAUDE_MODEL;
            }

            // Check OpenAI
            DataResult<ai.inquery.server.domain.api.model.Config> openaiConfig = 
                configService.find(ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient.OPENAI_KEY);
            if (openaiConfig != null && openaiConfig.getData() != null && 
                openaiConfig.getData().getContent() != null && !openaiConfig.getData().getContent().isEmpty()) {
                return OPENAI_MODEL;
            }
        } catch (Exception e) {
            log.warn("Failed to check API key configuration: {}", e.getMessage());
        }

        // Default to Gemini
        return GEMINI_MODEL;
    }

    /**
     * Get a fast model for classification (plan generation only, no deep analysis).
     */
    private String getClassifyModel() {
        try {
            DataResult<ai.inquery.server.domain.api.model.Config> geminiConfig =
                configService.find(ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient.GEMINI_API_KEY);
            if (geminiConfig != null && geminiConfig.getData() != null &&
                geminiConfig.getData().getContent() != null && !geminiConfig.getData().getContent().isEmpty()) {
                return GEMINI_CLASSIFY_MODEL;
            }

            DataResult<ai.inquery.server.domain.api.model.Config> claudeConfig =
                configService.find(ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.CLAUDE_API_KEY);
            if (claudeConfig != null && claudeConfig.getData() != null &&
                claudeConfig.getData().getContent() != null && !claudeConfig.getData().getContent().isEmpty()) {
                return CLAUDE_CLASSIFY_MODEL;
            }

            DataResult<ai.inquery.server.domain.api.model.Config> openaiConfig =
                configService.find(ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient.OPENAI_KEY);
            if (openaiConfig != null && openaiConfig.getData() != null &&
                openaiConfig.getData().getContent() != null && !openaiConfig.getData().getContent().isEmpty()) {
                return OPENAI_CLASSIFY_MODEL;
            }
        } catch (Exception e) {
            log.warn("Failed to check API key configuration: {}", e.getMessage());
        }
        return GEMINI_CLASSIFY_MODEL;
    }

    /**
     * Classify a Deep Research query and generate a research plan.
     * Now includes schema context for realistic plan generation.
     */
    @PostMapping("/classify")
    @CrossOrigin
    public DataResult<DeepResearchClassifier.DeepResearchClassification> classify(
            @RequestBody ClassifyRequest request) {
        try {
            String model = getClassifyModel();
            log.info("Classifying Deep Research query with model: {}, dataSourceId: {}", 
                    model, request.getDataSourceId());

            // Get schema context if dataSourceId is provided
            String schemaContext = null;
            if (request.getDataSourceId() != null) {
                try {
                    // Set ConnectInfo for VectorDB namespace lookup
                    String dbType = getDbTypeFromDataSourceId(request.getDataSourceId());
                    if (dbType != null) {
                        ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
                        connectInfo.setDbType(dbType);
                        connectInfo.setDataSourceId(request.getDataSourceId());
                        ai.inquery.spi.sql.InqueryContext.putContext(connectInfo);
                    }
                    
                    List<String> excludedTables = request.getExcludedTables();
                    List<String> schemas = (excludedTables != null && !excludedTables.isEmpty())
                        ? schemaSearcher.searchSchema(request.getQuestion(), excludedTables)
                        : schemaSearcher.searchSchema(request.getQuestion());
                    if (schemas != null && !schemas.isEmpty()) {
                        schemaContext = String.join("\n---\n", schemas.subList(0, Math.min(10, schemas.size())));
                    }
                } catch (Exception e) {
                    log.warn("Schema search failed during classification: {}", e.getMessage());
                } finally {
                    ai.inquery.spi.sql.InqueryContext.removeContext();
                }
            }

            // Fetch business insight for richer context
            String businessContext = getBusinessInsightContext(
                    request.getDataSourceId(), request.getDatabaseName());

            DeepResearchClassifier.DeepResearchClassification result =
                deepResearchClassifier.classify(
                    request.getQuestion(),
                    model,
                    request.getConversationHistory(),
                    schemaContext,
                    request.getExcludedTables(),
                    businessContext
                );

            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Deep Research classification failed", e);
            return DataResult.error("CLASSIFICATION_ERROR", "Failed to classify query: " + e.getMessage());
        }
    }

    /**
     * Start a Deep Research session.
     * Creates a session record and returns the session ID.
     */
    @PostMapping("/start")
    @CrossOrigin
    public DataResult<StartResearchResponse> startResearch(@RequestBody StartResearchRequest request) {
        try {
            // Create session record
            DeepResearchSessionDO session = new DeepResearchSessionDO();
            session.setGmtCreate(new Date());
            session.setGmtModified(new Date());
            session.setChatRoomId(request.getChatRoomId());
            session.setQuestion(request.getQuestion());
            session.setResearchPlan(request.getResearchPlanJson());
            session.setStatus("PLANNING");
            session.setCurrentIteration(0);
            session.setTotalQueriesExecuted(0);
            session.setUserId(request.getUserId());

            sessionMapper.insert(session);

            StartResearchResponse response = new StartResearchResponse();
            response.setSessionId(session.getId());
            response.setStatus("PLANNING");

            return DataResult.of(response);
        } catch (Exception e) {
            log.error("Failed to start Deep Research session", e);
            return DataResult.error("START_ERROR", "Failed to start research: " + e.getMessage());
        }
    }

    /**
     * Execute Deep Research with SSE streaming.
     * Sends progress events during research execution.
     */
    @GetMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CrossOrigin(origins = "*", allowedHeaders = "*")
    public SseEmitter executeResearchStream(
            @RequestParam Long sessionId,
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String excludedTablesJson,
            @RequestHeader Map<String, String> headers,
            HttpServletResponse response) {

        // Disable buffering for real-time SSE streaming
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(RESEARCH_TIMEOUT);

        String model = getPreferredModel();
        log.info("Executing Deep Research with model: {}, sessionId: {}", model, sessionId);

        // Start heartbeat thread to keep SSE connection alive during long operations
        java.util.concurrent.atomic.AtomicBoolean researchComplete = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread heartbeatThread = new Thread(() -> {
            log.info("Heartbeat thread started for sessionId: {}", sessionId);
            try {
                // Wait for SSE connection to be established before sending heartbeats
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                log.info("Heartbeat thread interrupted during initial wait");
                return;
            }
            int heartbeatCount = 0;
            while (!researchComplete.get()) {
                try {
                    if (!researchComplete.get()) {
                        try {
                            heartbeatCount++;
                            log.debug("Sending heartbeat #{} for sessionId: {}", heartbeatCount, sessionId);
                            emitter.send(SseEmitter.event().name("heartbeat").data("keep-alive"));
                            response.flushBuffer();
                        } catch (Exception e) {
                            // Connection closed, exit heartbeat thread
                            log.warn("Heartbeat failed for sessionId: {}, error: {}", sessionId, e.getMessage());
                            break;
                        }
                    }
                    Thread.sleep(3000); // Send heartbeat every 3 seconds (more frequent)
                } catch (InterruptedException e) {
                    log.info("Heartbeat thread interrupted for sessionId: {}", sessionId);
                    break;
                }
            }
            log.info("Heartbeat thread ended for sessionId: {}, total heartbeats sent: {}", sessionId, heartbeatCount);
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        new Thread(() -> {
            try {
                // Initialize DB session for this thread
                ai.inquery.server.domain.repository.Dbutils.setSession();

                // Build FULL ConnectInfo from DataSource (same as ConnectionInfoHandler.toInfo())
                // This is required for database connection creation with proper credentials
                if (dataSourceId != null) {
                    ai.inquery.spi.sql.ConnectInfo connectInfo = buildConnectInfo(dataSourceId, databaseName, schemaName);
                    if (connectInfo != null) {
                        ai.inquery.spi.sql.InqueryContext.putContext(connectInfo);
                    }
                }

                // Load session
                DeepResearchSessionDO session = sessionMapper.selectById(sessionId);
                if (session == null) {
                    sendError(emitter, "Session not found");
                    return;
                }

                // Update session status
                session.setStatus("RUNNING");
                session.setGmtModified(new Date());
                sessionMapper.updateById(session);

                // Send planning event
                sendEvent(emitter, "planning", "Starting research execution...", response);

                // Create Deep Research Agent with web search support
                DeepResearchAgent agent = new DeepResearchAgent(
                        modelProvider,
                        dlTemplateService,
                        schemaSearcher,
                        webSearchService,
                        model,
                        dataSourceId,
                        databaseName,
                        schemaName,
                        astValidator
                );
                // Connect Python tools for statistical analysis of large result sets
                if (pythonEnvironmentSetup.isReady()) {
                    agent.setPythonTools(
                            new ai.inquery.server.domain.core.langchain.tools.PythonTools(pythonEnvironmentSetup));
                    log.info("Python tools connected for Deep Research");
                }

                if (excludedTablesJson != null && !excludedTablesJson.isEmpty()) {
                    try {
                        agent.setExcludedTables(com.alibaba.fastjson2.JSON.parseArray(excludedTablesJson, String.class));
                    } catch (Exception e) {
                        log.warn("Failed to parse excludedTables for Deep Research: {}", e.getMessage());
                    }
                }

                // Inject business insight context
                String businessCtx = getBusinessInsightContext(dataSourceId, databaseName);
                if (businessCtx != null) {
                    agent.setBusinessContext(businessCtx);
                    log.info("Injected business context for Deep Research ({} chars)", businessCtx.length());
                }

                // Enable web search for Deep Research (always include external context)
                // Deep Research should always combine database analysis with external web context
                try {
                    List<String> webTopics = new java.util.ArrayList<>();
                    boolean requiresWebSearch = true; // Default to true for Deep Research
                    
                    if (session.getResearchPlan() != null) {
                        com.alibaba.fastjson2.JSONObject planJson = JSON.parseObject(session.getResearchPlan());
                        // Override with explicit false only if specifically set
                        Boolean explicitRequiresWebSearch = planJson.getBoolean("requiresWebSearch");
                        if (explicitRequiresWebSearch != null) {
                            requiresWebSearch = explicitRequiresWebSearch;
                        }
                        
                        com.alibaba.fastjson2.JSONArray topicsArray = planJson.getJSONArray("webSearchTopics");
                        if (topicsArray != null && !topicsArray.isEmpty()) {
                            for (int i = 0; i < topicsArray.size(); i++) {
                                webTopics.add(topicsArray.getString(i));
                            }
                        }
                    }
                    
                    // If no specific topics, derive from the question
                    if (webTopics.isEmpty() && session.getQuestion() != null) {
                        webTopics.add(session.getQuestion()); // Use the question itself as a search topic
                    }
                    
                    if (requiresWebSearch && webSearchService != null) {
                        agent.withWebSearch(true, webTopics);
                        log.info("Web search enabled for Deep Research with topics: {}", webTopics);
                    }
                } catch (Exception e) {
                    log.warn("Failed to configure web search: {}", e.getMessage());
                    // Still try to enable web search with the question as topic
                    if (session.getQuestion() != null && webSearchService != null) {
                        agent.withWebSearch(true, List.of(session.getQuestion()));
                        log.info("Web search enabled with default topic (question): {}", session.getQuestion());
                    }
                }

                // Parse plan steps from session
                List<DeepResearchAgent.PlanStep> planSteps = new java.util.ArrayList<>();
                if (session.getResearchPlan() != null) {
                    try {
                        com.alibaba.fastjson2.JSONObject plan = JSON.parseObject(session.getResearchPlan());
                        com.alibaba.fastjson2.JSONArray stepsArray = plan.getJSONArray("steps");
                        if (stepsArray != null) {
                            for (int i = 0; i < stepsArray.size(); i++) {
                                com.alibaba.fastjson2.JSONObject stepJson = stepsArray.getJSONObject(i);
                                DeepResearchAgent.PlanStep step = new DeepResearchAgent.PlanStep();
                                step.setTitle(stepJson.getString("label") != null ? stepJson.getString("label") : stepJson.getString("title"));
                                step.setDetails(stepJson.getString("description") != null ? stepJson.getString("description") : stepJson.getString("details"));
                                step.setIcon(stepJson.getString("icon"));
                                planSteps.add(step);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse plan steps: {}", e.getMessage());
                    }
                }
                log.info("Parsed {} plan steps for Deep Research", planSteps.size());

                // Execute research with progress callback
                DeepResearchAgent.DeepResearchResult result = agent.research(
                        session.getQuestion(),
                        planSteps,
                        (progressEvent) -> {
                            try {
                                sendEvent(emitter, progressEvent.getType(), progressEvent.getMessage(), response);

                                // Update session progress
                                if ("iteration".equals(progressEvent.getType())) {
                                    int iteration = extractIterationNumber(progressEvent.getMessage());
                                    session.setCurrentIteration(iteration);
                                    session.setGmtModified(new Date());
                                    sessionMapper.updateById(session);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to send progress event: {}", e.getMessage());
                            }
                        }
                );

                // Update session with result
                if (result.isSuccess()) {
                    session.setStatus("COMPLETED");
                    session.setReportJson(JSON.toJSONString(result.getReport()));
                    session.setTotalQueriesExecuted(result.getTotalQueriesExecuted());
                    // Clear MD content after report is generated (no longer needed)
                    session.setMdContent(null);

                    // Send report
                    sendEvent(emitter, "report", JSON.toJSONString(result.getReport()), response);
                } else {
                    session.setStatus("FAILED");
                    session.setErrorMessage(result.getErrorMessage());
                    // Clear MD content on failure too
                    session.setMdContent(null);
                    sendError(emitter, result.getErrorMessage());
                }

                session.setGmtModified(new Date());
                sessionMapper.updateById(session);

                // Send complete
                researchComplete.set(true);
                heartbeatThread.interrupt();
                sendEvent(emitter, "complete", "Research completed", response);
                emitter.complete();

            } catch (Exception e) {
                log.error("Deep Research execution failed", e);
                researchComplete.set(true);
                heartbeatThread.interrupt();
                try {
                    sendError(emitter, e.getMessage());
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                // Clean up
                researchComplete.set(true);
                heartbeatThread.interrupt();
                ai.inquery.server.domain.repository.Dbutils.removeSession();
                ai.inquery.spi.sql.InqueryContext.removeContext();
            }
        }).start();

        return emitter;
    }

    /**
     * Get research session status.
     */
    @GetMapping("/status/{sessionId}")
    @CrossOrigin
    public DataResult<DeepResearchSessionDO> getStatus(@PathVariable Long sessionId) {
        try {
            DeepResearchSessionDO session = sessionMapper.selectById(sessionId);
            if (session == null) {
                return DataResult.error("NOT_FOUND", "Session not found");
            }
            return DataResult.of(session);
        } catch (Exception e) {
            log.error("Failed to get session status", e);
            return DataResult.error("STATUS_ERROR", "Failed to get status: " + e.getMessage());
        }
    }

    /**
     * Get research report.
     */
    @GetMapping("/report/{sessionId}")
    @CrossOrigin
    public DataResult<DeepResearchAgent.ResearchReport> getReport(@PathVariable Long sessionId) {
        try {
            DeepResearchSessionDO session = sessionMapper.selectById(sessionId);
            if (session == null) {
                return DataResult.error("NOT_FOUND", "Session not found");
            }

            if (!"COMPLETED".equals(session.getStatus())) {
                return DataResult.error("NOT_READY", "Research not completed yet");
            }

            DeepResearchAgent.ResearchReport report = 
                JSON.parseObject(session.getReportJson(), DeepResearchAgent.ResearchReport.class);

            return DataResult.of(report);
        } catch (Exception e) {
            log.error("Failed to get report", e);
            return DataResult.error("REPORT_ERROR", "Failed to get report: " + e.getMessage());
        }
    }

    /**
     * Get latest research session for a chat room.
     */
    @GetMapping("/room/{chatRoomId}/latest")
    @CrossOrigin
    public DataResult<DeepResearchSessionDO> getLatestByRoom(@PathVariable Long chatRoomId) {
        try {
            DeepResearchSessionDO session = sessionMapper.findLatestCompletedByRoomId(chatRoomId);
            if (session == null) {
                // Check for active session
                session = sessionMapper.findActiveByRoomId(chatRoomId);
            }
            if (session == null) {
                return DataResult.error("NOT_FOUND", "No research session found for this chat room");
            }
            return DataResult.of(session);
        } catch (Exception e) {
            log.error("Failed to get latest session", e);
            return DataResult.error("QUERY_ERROR", "Failed to query session: " + e.getMessage());
        }
    }

    /**
     * Delete a research session (cleanup).
     */
    @DeleteMapping("/{sessionId}")
    @CrossOrigin
    public DataResult<Boolean> deleteSession(@PathVariable Long sessionId) {
        try {
            int deleted = sessionMapper.deleteById(sessionId);
            return DataResult.of(deleted > 0);
        } catch (Exception e) {
            log.error("Failed to delete session", e);
            return DataResult.error("DELETE_ERROR", "Failed to delete session: " + e.getMessage());
        }
    }

    /**
     * Generate infographic HTML from a completed Deep Research report via SSE streaming.
     * Streams HTML chunks in real-time as the LLM generates the infographic.
     */
    @GetMapping(value = "/infographic/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CrossOrigin(origins = "*", allowedHeaders = "*")
    public SseEmitter generateInfographicStream(
            @RequestParam Long sessionId,
            HttpServletResponse response) {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(RESEARCH_TIMEOUT);

        String model = getPreferredModel();
        log.info("Generating infographic for sessionId: {} with model: {}", sessionId, model);

        new Thread(() -> {
            try {
                // Initialize DB session for this thread (required for config lookups)
                ai.inquery.server.domain.repository.Dbutils.setSession();

                // Load session and get report
                DeepResearchSessionDO session = sessionMapper.selectById(sessionId);
                if (session == null || session.getReportJson() == null) {
                    sendError(emitter, "Research session or report not found");
                    emitter.complete();
                    return;
                }

                // Send generating event
                sendEvent(emitter, "generating", "Creating infographic from research report...", response);

                // Build prompt with report content
                String reportJson = session.getReportJson();
                String prompt = buildInfographicPrompt(reportJson, session.getQuestion());

                // Stream HTML using LangChain4j StreamingChatModel
                StreamingChatModel streamingModel = modelProvider.getStreamingChatModel(model);

                // Accumulate HTML chunks for saving
                StringBuilder htmlBuilder = new StringBuilder();
                // Some models wrap the response in ```html ... ``` despite the
                // prompt forbidding it. Buffer the head of the stream until we
                // see actual HTML (`<`) so we can strip the opening fence
                // before forwarding any chunk to the client.
                StringBuilder headBuffer = new StringBuilder();
                boolean[] headerStripped = new boolean[]{false};

                streamingModel.chat(prompt, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        try {
                            htmlBuilder.append(partialResponse);

                            String toEmit;
                            if (headerStripped[0]) {
                                toEmit = partialResponse;
                            } else {
                                headBuffer.append(partialResponse);
                                int htmlStart = headBuffer.indexOf("<");
                                // Wait for the actual HTML element to begin, but give
                                // up after 256 chars to avoid stalling on prose-only
                                // openings.
                                if (htmlStart < 0 && headBuffer.length() < 256) {
                                    return;
                                }
                                String cleanedHead = stripLeadingHtmlFence(headBuffer.toString());
                                headerStripped[0] = true;
                                toEmit = cleanedHead;
                                if (toEmit.isEmpty()) {
                                    return;
                                }
                            }

                            emitter.send(SseEmitter.event()
                                .name("html_chunk")
                                .data(JSON.toJSONString(toEmit)));
                        } catch (IOException e) {
                            log.warn("Failed to send infographic chunk: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        try {
                            // Save infographic HTML to session, defensively stripping
                            // any markdown fences the model added against the prompt.
                            String fullHtml = stripHtmlCodeFence(htmlBuilder.toString());
                            if (!fullHtml.isEmpty()) {
                                session.setInfographicHtml(fullHtml);
                                session.setGmtModified(new Date());
                                sessionMapper.updateById(session);
                                log.info("Saved infographic HTML for sessionId: {}, length: {}", sessionId, fullHtml.length());
                            }

                            emitter.send(SseEmitter.event().name("complete").data(""));
                            emitter.complete();
                        } catch (IOException e) {
                            log.warn("Failed to send complete event: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Infographic generation failed", error);
                        try {
                            sendError(emitter, error.getMessage());
                        } catch (IOException e) {
                            // Ignore
                        }
                        emitter.completeWithError(error);
                    }
                });

            } catch (Exception e) {
                log.error("Infographic generation failed for sessionId: {}", sessionId, e);
                try {
                    sendError(emitter, e.getMessage());
                    emitter.complete();
                } catch (IOException ignored) {}
            } finally {
                ai.inquery.server.domain.repository.Dbutils.removeSession();
            }
        }).start();

        return emitter;
    }

    /**
     * Build the prompt for infographic HTML generation from research report data.
     */
    private String buildInfographicPrompt(String reportJson, String originalQuestion) {
        int currentYear = java.time.Year.now().getValue();
        return """
            Generate an infographic HTML page from the research report below.
            Output raw HTML only (start with <!DOCTYPE html>, no markdown code blocks).
            Do NOT wrap the response in ```html ... ``` fences. The very first character of your response must be `<`.
            Tables must contain real data values. Current year: %d

            PDF EXPORT RULES (this HTML will be rendered by headless Chromium and exported as A4 PDF):
            - Use normal document flow (block elements stacking vertically). Avoid position:fixed/absolute for content.
            - Each major section should be wrapped in a block-level element (div or section).
            - Add these CSS rules in <style> for clean page breaks:
              section, .card, .stat-block { page-break-inside: avoid; break-inside: avoid; }
              h1, h2, h3 { page-break-after: avoid; break-after: avoid; }
              table thead { display: table-header-group; }
              table tr { page-break-inside: avoid; break-inside: avoid; }
              p, li { orphans: 3; widows: 3; }
            - Keep individual sections compact enough to fit on one A4 page (max ~900px height per section).
            - If a section is too tall, split it into multiple smaller sections.
            - Do NOT use viewport-height units (vh) for content sizing. Use px, rem, or auto.

            Design references: Stripe Annual Letter, Linear changelog, Vercel dashboard, Bloomberg Terminal,
            Apple product pages, Spotify Wrapped, GitHub Copilot landing page, Raycast website.
            Dark theme.

            QUESTION: %s

            REPORT DATA:
            %s
            """.formatted(currentYear, originalQuestion, reportJson);
    }

    // Helper methods

    /**
     * Strip a leading markdown code fence (```html / ``` / ```HTML / ```Html...)
     * from the head of an infographic stream. Returns the substring starting at
     * the first `<` character so streamed HTML rendering doesn't show the fence.
     */
    private static String stripLeadingHtmlFence(String head) {
        if (head == null || head.isEmpty()) return "";
        int firstAngle = head.indexOf('<');
        if (firstAngle < 0) {
            // No HTML yet — drop any obvious fence noise so we don't push it.
            String trimmed = head.replaceFirst("^\\s*```[a-zA-Z]*\\s*", "");
            return trimmed.equals(head) ? head : trimmed;
        }
        String prefix = head.substring(0, firstAngle);
        // Remove a leading ```html / ``` fence (case-insensitive language tag).
        String cleanedPrefix = prefix.replaceFirst("(?is)^\\s*```[a-zA-Z]*\\s*", "");
        return cleanedPrefix + head.substring(firstAngle);
    }

    /**
     * Defensively strip ```html ... ``` (or ``` ... ```) fences that some
     * models add around generated infographic HTML, even when the prompt
     * forbids it. Used before persisting the final HTML.
     */
    private static String stripHtmlCodeFence(String html) {
        if (html == null) return "";
        String trimmed = html.trim();
        if (trimmed.isEmpty()) return "";
        // Leading fence with optional language tag (html, HTML, Html, etc.).
        trimmed = trimmed.replaceFirst("(?is)^```[a-zA-Z]*\\s*\\n?", "");
        // Trailing fence.
        trimmed = trimmed.replaceFirst("(?is)\\n?```\\s*$", "");
        return trimmed;
    }

    private void sendEvent(SseEmitter emitter, String type, String data, HttpServletResponse response) throws IOException {
        emitter.send(SseEmitter.event()
                .name(type)
                .data(data));
        response.flushBuffer();
    }

    private void sendError(SseEmitter emitter, String message) throws IOException {
        emitter.send(SseEmitter.event()
                .name("error")
                .data(message));
    }

    private int extractIterationNumber(String message) {
        // Extract iteration number from message like "Starting iteration 2 of 3"
        try {
            if (message.contains("iteration")) {
                String[] parts = message.split(" ");
                for (int i = 0; i < parts.length; i++) {
                    if ("iteration".equals(parts[i]) && i + 1 < parts.length) {
                        return Integer.parseInt(parts[i + 1]);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract iteration number from: {}", message);
        }
        return 1;
    }

    private String getDbTypeFromDataSourceId(Long dataSourceId) {
        if (dataSourceId == null) return null;
        try {
            DataResult<DataSource> result = dataSourceService.queryById(dataSourceId);
            if (result.success() && result.getData() != null) {
                return result.getData().getType();
            }
        } catch (Exception e) {
            log.warn("Failed to get dbType: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Build full ConnectInfo from DataSource (mirrors ConnectionInfoHandler.toInfo()).
     * This is required for proper database connection creation with credentials.
     */
    private ai.inquery.spi.sql.ConnectInfo buildConnectInfo(Long dataSourceId, String databaseName, String schemaName) {
        if (dataSourceId == null) return null;
        
        try {
            DataResult<DataSource> result = dataSourceService.queryById(dataSourceId);
            if (!result.success() || result.getData() == null) {
                log.error("Failed to get DataSource for id: {}", dataSourceId);
                return null;
            }
            
            DataSource dataSource = result.getData();
            ai.inquery.spi.sql.ConnectInfo connectInfo = new ai.inquery.spi.sql.ConnectInfo();
            
            // Essential connection info
            connectInfo.setDataSourceId(dataSourceId);
            connectInfo.setDbType(dataSource.getType());
            connectInfo.setUrl(dataSource.getUrl());
            connectInfo.setUser(dataSource.getUserName());
            connectInfo.setPassword(dataSource.getPassword());
            connectInfo.setDatabase(databaseName);
            connectInfo.setSchemaName(schemaName);
            
            // Additional info (important for Snowflake keypair auth!)
            connectInfo.setAlias(dataSource.getAlias());
            connectInfo.setDriver(dataSource.getDriver());
            connectInfo.setSsh(dataSource.getSsh());
            connectInfo.setSsl(dataSource.getSsl());
            connectInfo.setJdbc(dataSource.getJdbc());
            connectInfo.setExtendInfo(dataSource.getExtendInfo()); // Contains keypair auth info
            connectInfo.setHost(dataSource.getHost());
            connectInfo.setPort(parsePortOrNull(dataSource.getPort()));
            
            // Console settings for connection pool
            connectInfo.setConsoleId(0L); // AI execution doesn't have console
            connectInfo.setConsoleOwn(false);
            connectInfo.setLoginUser("0"); // System user for AI operations
            
            // Driver config
            ai.inquery.spi.config.DriverConfig driverConfig = dataSource.getDriverConfig();
            if (driverConfig != null && driverConfig.notEmpty()) {
                connectInfo.setDriverConfig(driverConfig);
            }
            
            log.info("Built ConnectInfo for dataSourceId: {}, dbType: {}, hasExtendInfo: {}", 
                    dataSourceId, dataSource.getType(), 
                    dataSource.getExtendInfo() != null && !dataSource.getExtendInfo().isEmpty());
            
            return connectInfo;
        } catch (Exception e) {
            log.error("Failed to build ConnectInfo for dataSourceId: {}", dataSourceId, e);
            return null;
        }
    }

    // Request/Response DTOs

    @Data
    public static class ClassifyRequest {
        private String question;
        private String conversationHistory;
        private Long dataSourceId;
        private String databaseName;
        private String schemaName;
        private List<String> excludedTables;
    }

    @Data
    public static class StartResearchRequest {
        private Long chatRoomId;
        private String question;
        private String researchPlanJson;
        private Long userId;
        private boolean requiresWebSearch;
        private List<String> webSearchTopics;
        private List<String> excludedTables;
    }

    @Data
    public static class StartResearchResponse {
        private Long sessionId;
        private String status;
    }

    private String getBusinessInsightContext(Long dataSourceId, String databaseName) {
        if (dataSourceId == null) return null;
        try {
            ai.inquery.server.domain.api.model.BusinessInsightDTO insight =
                businessInsightService.getInsight(dataSourceId, databaseName != null ? databaseName : "");
            if (insight != null && insight.getInsightContent() != null && !insight.getInsightContent().isEmpty()) {
                return insight.getInsightContent();
            }
            if (databaseName != null && !databaseName.isEmpty()) {
                insight = businessInsightService.getInsight(dataSourceId, "");
                if (insight != null && insight.getInsightContent() != null && !insight.getInsightContent().isEmpty()) {
                    return insight.getInsightContent();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get business insight: {}", e.getMessage());
        }
        return null;
    }
}
