package ai.inquery.server.web.api.controller.slack;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.spi.model.TableColumn;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import ai.inquery.server.domain.core.langchain.ModelMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for processing Slack queries using Inquery AI.
 * Uses Auto mode (SQL generation + execution + analysis).
 * Follows KIRA-style thinking step UI pattern.
 */
@Slf4j
@Service
public class SlackChatService {

    @Autowired
    private SlackService slackService;

    @Autowired
    private ai.inquery.server.domain.core.langchain.InqueryAgentService inqueryAgentService;

    @Autowired
    private QuickChartService quickChartService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private TableService tableService;

    @Autowired
    private ai.inquery.server.domain.api.service.DataCatalogService dataCatalogService;

    @Autowired
    private ai.inquery.server.domain.api.service.AIService aiService;

    @Autowired
    private ai.inquery.server.domain.core.business.BusinessInsightService businessInsightService;

    @Autowired
    private ai.inquery.server.domain.core.query.SchemaSearcher schemaSearcher;

    private static final Pattern TABLE_PATH_PATTERN =
            Pattern.compile("\\[Table Path:\\s+([\\w.]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("(?:CREATE TABLE|Table:)\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);

    // Default models
    private static final String DEFAULT_MODEL = ModelMapper.getDefaultPrimaryModel();

    /**
     * Process user query from Slack.
     * First classifies the query, then:
     * - CHAT: Returns conversational response
     * - DATA: Runs Auto mode (SQL generation + execution + analysis)
     * 
     * @param modelName LLM model to use (e.g., "gpt-5.4-mini", "claude-sonnet-4-6", "gemini-3.5-flash" etc.)
     */
    public void processQuery(String botToken, String channelId, String threadTs,
                             String userMessageTs, String userId, String query, Long dataSourceId,
                             String databaseName, String schemaName, String modelName) {
        // Use default model if not specified or if "inquery-agent" placeholder (same logic as AI-chat)
        final String model = resolveModel(modelName);
        log.info("[SlackChat] Processing query - channel: {}, user: {}, query: {}",
                channelId, userId, query.length() > 50 ? query.substring(0, 50) + "..." : query);
        log.info("[SlackChat] Thread: {}, userMessageTs: {}, hasSession: {}", 
                Thread.currentThread().getName(), userMessageTs, Dbutils.hasSession());

        // IMMEDIATELY show thinking status (before any LLM calls)
        // Run Slack API calls in PARALLEL for faster response
        
        // 1. Add 👀 reaction (fire and forget)
        java.util.concurrent.CompletableFuture.runAsync(() -> 
            slackService.addReaction(botToken, channelId, userMessageTs, "eyes"))
            .exceptionally(e -> { log.warn("[SlackChat] Failed to add reaction: {}", e.getMessage()); return null; });
        
        // 2. Try Assistant API first (fancy purple thinking UI)
        java.util.concurrent.CompletableFuture<Boolean> assistantFuture = 
            java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, ""));
        
        // 3. Also prepare thinking message as fallback
        java.util.concurrent.CompletableFuture<String> thinkingFuture = 
            java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                slackService.sendThinkingMessage(botToken, channelId, threadTs));
        
        // Wait for Assistant API result
        boolean useAssistantApi = false;
        try {
            useAssistantApi = assistantFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
            log.info("[SlackChat] Assistant API available: {}", useAssistantApi);
        } catch (Exception e) {
            log.warn("[SlackChat] Assistant API check failed: {}", e.getMessage());
        }
        
        // Wait for thinking message (we always need to know if it was sent)
        String thinkingTs = null;
        try {
            thinkingTs = thinkingFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[SlackChat] Failed to send thinking message: {}", e.getMessage());
        }
        
        // Keep thinking message even if Assistant API works
        // (setAssistantStatus with empty string doesn't show anything visible)
        // The thinking message provides better UX with step-by-step updates
        
        // Store for use in progress callback (final for lambda)
        final boolean assistantMode = useAssistantApi;
        final String thinkingMsgTs = thinkingTs;

        try {
            // Step 0.3: Get user info for personalized responses
            SlackService.SlackUserInfo userInfo = slackService.getUserInfo(botToken, userId);
            String userName = null;
            if (userInfo != null) {
                userName = userInfo.displayName() != null && !userInfo.displayName().isEmpty() 
                        ? userInfo.displayName() : userInfo.realName();
                log.info("[SlackChat] User identified: {}", userName);
            }
            
            // Step 0.5: Get conversation history for context-aware classification
            String conversationHistory = null;
            if (threadTs != null && !threadTs.isEmpty()) {
                conversationHistory = slackService.getThreadHistory(botToken, channelId, threadTs, 10);
                if (conversationHistory != null) {
                    log.info("[SlackChat] Retrieved conversation history: {} chars", conversationHistory.length());
                }
            }

            // NOTE: The legacy QueryClassifierTranslator pre-step has been removed.
            // SupervisorAgent now handles small-talk, schema-only questions, and
            // history-grounded answers directly inside its own LLM loop.

            slackService.removeReaction(botToken, channelId, userMessageTs, "eyes");
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            if (thinkingMsgTs != null) {
                slackService.updateThinkingStep(botToken, channelId, thinkingMsgTs, "Searching schema...");
            }

            // Step 3: DATA query - check if data source is configured
            if (dataSourceId == null) {
                if (assistantMode) {
                    slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
                }
                if (thinkingMsgTs != null) {
                    slackService.updateToCompleted(botToken, channelId, thinkingMsgTs, "Settings needed");
                }
                slackService.sendResultMessage(botToken, channelId, threadTs,
                        "⚠️ Database is not configured.\nPlease specify the default data source in Inquery settings.");
                return;
            }

            // Step 3.5: Setup InqueryContext with ConnectInfo for SQL execution
            if (!setupConnectInfo(dataSourceId, databaseName, schemaName)) {
                if (assistantMode) {
                    slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
                }
                if (thinkingMsgTs != null) {
                    slackService.updateToCompleted(botToken, channelId, thinkingMsgTs, "Connection failed");
                }
                slackService.sendResultMessage(botToken, channelId, threadTs,
                        "⚠️ Unable to set database connection information.\nPlease check the data source settings.");
                return;
            }

            // NOTE: The legacy date-range and schema-query short-circuits were
            // driven by QueryClassifierTranslator and have been removed.
            // SupervisorAgent handles those questions through its own tool loop.

            // Step 5: Process DATA query with Deep Agent
            // Throttle progress updates to avoid Slack API rate limits (chat.update: 1/sec per channel)
            final long PROGRESS_MIN_INTERVAL_MS = 2500; // 2.5 seconds between updates
            final long[] lastProgressUpdateTime = {0}; // mutable in lambda via array
            final String[] lastPendingStep = {null}; // buffer for skipped steps

            Consumer<String> progressCallback = step -> {
                // Handle special prefixed messages (not throttled - these are important one-time events)
                if (step != null && step.startsWith("TABLES:")) {
                    // Found tables - send as separate message with line breaks
                    String tables = step.substring(7).trim(); // Remove "TABLES:" prefix
                    // Format tables with line breaks
                    String formattedTables = tables.contains(",") 
                            ? "\n• `" + tables.replace(", ", "`\n• `") + "`"
                            : "`" + tables + "`";
                    slackService.sendResultMessage(botToken, channelId, threadTs,
                            "🔍 *Found tables:*" + formattedTables);
                    // Skip thinking step update here - SupervisorAgent will send "Generating SQL query..."
                    return;
                }
                if (step != null && step.startsWith("SQL:")) {
                    String sql = step.substring(4).trim();
                    if (sql != null && !sql.isEmpty()) {
                        sql = stripCodeBlock(sql);
                        slackService.sendResultMessage(botToken, channelId, threadTs,
                                "*Generated SQL:*\n```sql\n" + sql + "\n```");
                    }
                    return;
                }
                
                // Normal progress update - throttle to avoid Slack rate limits
                String translatedStep = translateStep(step);
                log.debug("[SlackChat] Progress update: {} -> {}", step, translatedStep);
                
                if (thinkingMsgTs != null) {
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastProgressUpdateTime[0];
                    
                    if (elapsed >= PROGRESS_MIN_INTERVAL_MS) {
                        // Enough time has passed - send update immediately
                        slackService.updateThinkingStep(botToken, channelId, thinkingMsgTs, translatedStep);
                        lastProgressUpdateTime[0] = now;
                        lastPendingStep[0] = null;
                    } else {
                        // Too soon - buffer this step (will be sent on next eligible call or at completion)
                        lastPendingStep[0] = translatedStep;
                        log.debug("[SlackChat] Throttled progress update ({}ms since last): {}", elapsed, translatedStep);
                    }
                }
            };

            String conversationId = "slack_" + channelId + "_" + UUID.randomUUID().toString().substring(0, 8);

            log.info("[SlackChat] Using model: {}", model);
            
            // Build user context for personalized responses (using userName from Step 0.3)
            String userContext = "";
            if (userName != null && !userName.isEmpty()) {
                userContext = String.format("""
                
                [CURRENT USER]
                - Name: %s
                - You are responding to this specific user. Use their name naturally when appropriate.
                """, userName);
            }
            
            // Slack-specific context for response formatting
            String slackContext = """
                [RESPONSE FORMAT - CRITICAL]
                - You MUST respond in the SAME LANGUAGE as the user's question.
                
                [SLACK FORMATTING - IMPORTANT]
                - Do NOT use standard markdown (###, **, etc.)
                - Use Slack mrkdwn format:
                  * Bold: *text* (single asterisks)
                  * Italic: _text_
                  * Strikethrough: ~text~
                  * Code: `code`
                  * Bullet points: • or just use dashes
                - Do NOT use headers (# or ###). Instead use *Bold Section Title:*
                - CRITICAL: Bold/italic requires word boundary (space or punctuation) after closing marker
                  * WRONG: *text*word or _text_word (no space - won't work)
                  * CORRECT: *text* word or *text*! or *text*, (space or punctuation after)
                - ALWAYS include a space after every *bold* word to ensure particles or brackets do not immediately follow.
                - Keep analysis concise (max 150 words)
                - Use simple bullet points, not nested lists
                """ + userContext;
            
            // Get inactive tables to exclude from schema search
            List<String> excludedTables = getInactiveTables(dataSourceId, databaseName, schemaName);
            if (excludedTables != null && !excludedTables.isEmpty()) {
                log.info("[SlackChat] Excluding {} inactive tables from schema search", excludedTables.size());
            }
            
            // Use translated query for better Vector DB search accuracy
            // The legacy classifier provided a translated/keyword query for
            // Vector DB search. Without it we pass the raw question; the root
            // agent / SchemaSearcher path now handles non-English reformulation.
            String searchQuery = query;
            
            // Get Business Insights for better analysis quality
            String businessInsight = getBusinessInsightContext(dataSourceId, databaseName);
            
            // Combine slackContext with conversationHistory and businessInsight
            String combinedContext = slackContext;
            
            // Add conversation history so Deep Agent can understand context (e.g., "this table", "that query")
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                combinedContext = "[CONVERSATION HISTORY - Refer to this for context when user mentions 'this', 'that', 'previous', etc.]\n" 
                        + conversationHistory + "\n\n" + combinedContext;
                log.info("[SlackChat] Added conversation history ({} chars) to context", conversationHistory.length());
            }
            
            if (businessInsight != null && !businessInsight.isEmpty()) {
                combinedContext = combinedContext + "\n\n[BUSINESS CONTEXT]\n" + businessInsight;
                log.info("[SlackChat] Added business insight ({} chars) to context", businessInsight.length());
            }
            
            ai.inquery.server.domain.core.langchain.agents.SupervisorAgent.SupervisorResult result = inqueryAgentService.processWithDeepAgent(
                    conversationId,
                    model,
                    query,
                    dataSourceId,
                    databaseName,
                    schemaName,
                    progressCallback,
                    true,  // Skip clarification for faster response
                    excludedTables,  // Exclude inactive tables from schema search
                    null,  // No conversation history
                    combinedContext,  // Slack formatting + Business Insights
                    searchQuery,  // English translation + keywords for Vector DB search
                    null  // No MCP user config for Slack context
            );

            // Flush any pending throttled progress step before showing result
            if (lastPendingStep[0] != null && thinkingMsgTs != null) {
                slackService.updateThinkingStep(botToken, channelId, thinkingMsgTs, lastPendingStep[0]);
                lastPendingStep[0] = null;
            }

            // Clear assistant status
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            handleResult(botToken, channelId, threadTs, thinkingMsgTs, query, result, assistantMode);

        } catch (Exception e) {
            log.error("[SlackChat] Error processing query", e);
            // Clear status indicators
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            slackService.removeReaction(botToken, channelId, userMessageTs, "eyes");
            if (thinkingMsgTs != null) {
                slackService.deleteMessage(botToken, channelId, thinkingMsgTs);
            }
            slackService.sendErrorMessage(botToken, channelId, threadTs, 
                    "❌ Error occurred while processing: " + e.getMessage());
        } finally {
            // Cleanup InqueryContext
            InqueryContext.removeContext();
            InqueryContext.removeQuerySource();
        }
    }

    /**
     * Strip markdown code block from SQL if present.
     * LLM sometimes wraps SQL in ```sql ... ``` blocks.
     */
    private String stripCodeBlock(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        
        // Remove ```sql ... ``` or ``` ... ```
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /**
     * Resolve model name to actual LLM model.
     * Same logic as AI-chat for consistency.
     * 
     * @param modelName Model name from config (may be null, empty, or "inquery-agent")
     * @return Actual LLM model name to use
     */
    private String resolveModel(String modelName) {
        if (modelName == null || modelName.isEmpty() || "inquery-agent".equals(modelName)) {
            return DEFAULT_MODEL;
        }
        return modelName;
    }

    /**
     * Setup InqueryContext with ConnectInfo for SQL execution.
     * This is required for SQL execution to work properly.
     * 
     * @return true if setup was successful, false otherwise
     */
    private boolean setupConnectInfo(Long dataSourceId, String databaseName, String schemaName) {
        try {
            var result = dataSourceService.queryById(dataSourceId);
            if (result == null || !result.getSuccess() || result.getData() == null) {
                log.error("[SlackChat] DataSource not found: {}", dataSourceId);
                return false;
            }
            DataSource ds = result.getData();

            // Fallback to URL/extendInfo if databaseName/schemaName not provided
            String effectiveDbName = databaseName;
            String effectiveSchemaName = schemaName;
            
            if (StringUtils.isBlank(effectiveDbName) || StringUtils.isBlank(effectiveSchemaName)) {
                // Try to get from extendInfo first
                var extendMap = ds.getExtendMap();
                if (StringUtils.isBlank(effectiveDbName) && extendMap.containsKey("database")) {
                    effectiveDbName = String.valueOf(extendMap.get("database"));
                }
                if (StringUtils.isBlank(effectiveSchemaName) && extendMap.containsKey("schema")) {
                    effectiveSchemaName = String.valueOf(extendMap.get("schema"));
                }
                
                // Try to parse from URL (Snowflake: jdbc:snowflake://...?db=XXX&schema=YYY)
                String url = ds.getUrl();
                if (StringUtils.isNotBlank(url)) {
                    if (StringUtils.isBlank(effectiveDbName)) {
                        effectiveDbName = extractUrlParam(url, "db");
                    }
                    if (StringUtils.isBlank(effectiveSchemaName)) {
                        effectiveSchemaName = extractUrlParam(url, "schema");
                    }
                }
                
                log.info("[SlackChat] Fallback database/schema from URL/extendInfo: db={}, schema={}", 
                        effectiveDbName, effectiveSchemaName);
            }

            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setDbType(ds.getType());
            connectInfo.setDataSourceId(ds.getId());
            connectInfo.setUrl(ds.getUrl());
            connectInfo.setUser(ds.getUserName());
            connectInfo.setPassword(ds.getPassword());
            connectInfo.setHost(ds.getHost());
            connectInfo.setPort(StringUtils.isNotBlank(ds.getPort()) ? Integer.parseInt(ds.getPort()) : null);
            connectInfo.setDatabase(effectiveDbName);
            connectInfo.setSchemaName(effectiveSchemaName);
            connectInfo.setDriver(ds.getDriver());
            connectInfo.setSsh(ds.getSsh());
            connectInfo.setSsl(ds.getSsl());
            connectInfo.setJdbc(ds.getJdbc());
            connectInfo.setExtendInfo(ds.getExtendInfo());
            // Set loginUser for connection pool reuse (shared with AI-chat)
            connectInfo.setLoginUser("slack_bot");
            // Set consoleId to enable connection pooling
            connectInfo.setConsoleId(-1L); // Special ID for Slack

            InqueryContext.putContext(connectInfo);
            InqueryContext.setQuerySource(InqueryContext.SOURCE_AI_CHAT);

            log.info("[SlackChat] Set ConnectInfo for SQL execution: dbType={}, dataSourceId={}, database={}, schema={}",
                    ds.getType(), dataSourceId, effectiveDbName, effectiveSchemaName);
            return true;
        } catch (Exception e) {
            log.error("[SlackChat] Failed to setup ConnectInfo", e);
            return false;
        }
    }
    
    /**
     * Extract parameter value from JDBC URL.
     * Supports formats: ?db=XXX&schema=YYY or ?database=XXX
     */
    private String extractUrlParam(String url, String paramName) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(paramName)) {
            return null;
        }
        try {
            // Find query string part
            int queryStart = url.indexOf('?');
            if (queryStart < 0) {
                return null;
            }
            String queryString = url.substring(queryStart + 1);
            String[] params = queryString.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2 && keyValue[0].equalsIgnoreCase(paramName)) {
                    return keyValue[1];
                }
            }
        } catch (Exception e) {
            log.warn("[SlackChat] Failed to parse URL param: {}", paramName, e);
        }
        return null;
    }

    /**
     * Warmup database connection for Slack Bot.
     * Creates a connection in the pool so first query doesn't have to wait.
     */
    public void warmupConnection(Long dataSourceId) {
        try {
            var result = dataSourceService.queryById(dataSourceId);
            if (result == null || !result.getSuccess() || result.getData() == null) {
                log.warn("[SlackChat] Cannot warmup - DataSource not found: {}", dataSourceId);
                return;
            }
            DataSource ds = result.getData();

            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setDbType(ds.getType());
            connectInfo.setDataSourceId(ds.getId());
            connectInfo.setUrl(ds.getUrl());
            connectInfo.setUser(ds.getUserName());
            connectInfo.setPassword(ds.getPassword());
            connectInfo.setHost(ds.getHost());
            connectInfo.setPort(StringUtils.isNotBlank(ds.getPort()) ? Integer.parseInt(ds.getPort()) : null);
            connectInfo.setDriver(ds.getDriver());
            connectInfo.setSsh(ds.getSsh());
            connectInfo.setSsl(ds.getSsl());
            connectInfo.setJdbc(ds.getJdbc());
            connectInfo.setExtendInfo(ds.getExtendInfo());
            connectInfo.setLoginUser("slack_bot");
            connectInfo.setConsoleId(-1L);

            InqueryContext.putContext(connectInfo);
            InqueryContext.setQuerySource(InqueryContext.SOURCE_AI_CHAT);

            // Get connection to trigger pool creation
            java.sql.Connection conn = InqueryContext.getConnection();
            if (conn != null && !conn.isClosed()) {
                log.info("[SlackChat] Warmup connection established - dbType: {}, dataSourceId: {}", 
                        ds.getType(), dataSourceId);
            }
        } catch (Exception e) {
            log.warn("[SlackChat] Connection warmup failed: {}", e.getMessage());
        } finally {
            InqueryContext.removeContext();
            InqueryContext.removeQuerySource();
        }
    }

    /**
     * Handle the result from Deep Agent.
     * @param assistantMode true if using Slack Assistant API (no thinking message to update)
     */
    private void handleResult(String botToken, String channelId, String threadTs,
                              String thinkingTs, String originalQuery,
                              ai.inquery.server.domain.core.langchain.agents.SupervisorAgent.SupervisorResult result,
                              boolean assistantMode) {
        if (result == null) {
            if (!assistantMode && thinkingTs != null) {
                slackService.updateToCompleted(botToken, channelId, thinkingTs, "Unable to generate result");
            }
            return;
        }

        // Check for clarification needed
        if (result.isNeedsClarification() && result.getClarificationOptions() != null 
                && !result.getClarificationOptions().isEmpty()) {
            handleClarification(botToken, channelId, threadTs, thinkingTs, result, assistantMode);
            return;
        }

        // Check for error
        if (!result.isSuccess()) {
            if (!assistantMode && thinkingTs != null) {
                slackService.updateToCompleted(botToken, channelId, thinkingTs, "Processing failed");
            }
            slackService.sendErrorMessage(botToken, channelId, threadTs, 
                    result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error");
            return;
        }

        // Send corrected SQL only if fixSql() changed it (original SQL was already sent during generation)
        String executedSql = result.getExecutedSql();
        String generatedSql = result.getGeneratedSql();
        if (executedSql != null && generatedSql != null && !executedSql.equals(generatedSql)) {
            String correctedSql = stripCodeBlock(executedSql);
            slackService.sendResultMessage(botToken, channelId, threadTs,
                    "*Corrected SQL:*\n```sql\n" + correctedSql + "\n```");
        }

        // Build result message
        StringBuilder messageBuilder = new StringBuilder();

        // Helper to update thinking status
        java.util.function.Consumer<String> updateThinking = (status) -> {
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, threadTs, "");
            }
            if (thinkingTs != null) {
                slackService.updateThinkingStep(botToken, channelId, thinkingTs, status);
            }
        };

        // Step 1: Upload CSV first (if result data available)
        if (result.getResultData() != null) {
            updateThinking.accept("Uploading data file...");
            String csvContent = convertToCsv(result.getResultData());
            if (csvContent != null && !csvContent.isEmpty()) {
                slackService.uploadCsvFile(
                        botToken, channelId, threadTs,
                        csvContent,
                        "data.csv",
                        "📁 Query Result (" + result.getRowCount() + " rows)"
                );
            }
        }

        // Step 2: Send chart image using QuickChart.io (URL-based, no Playwright needed)
        String chartType = null;
        if (result.getChartRecommendation() != null && result.getResultData() != null) {
            try {
                List<Map<String, Object>> dataList = extractDataList(result.getResultData());
                List<String> headers = extractHeaders(result.getResultData());

                // Check data size BEFORE sending thinking step to avoid unnecessary Slack API call
                if (dataList != null && !dataList.isEmpty() && dataList.size() <= 50) {
                    updateThinking.accept("Generating chart...");

                    // Extract chart type from recommendation
                    String[] chartInfo = quickChartService.getChartTypeAndReason(result.getChartRecommendation());
                    if (chartInfo != null) {
                        chartType = chartInfo[0];
                    }

                    // Generate QuickChart URL
                    String chartUrl = quickChartService.generateChartUrl(
                            result.getChartRecommendation(),
                            dataList,
                            headers,
                            truncateForTitle(originalQuery)
                    );

                    if (chartUrl != null) {
                        // Build chart title from type (e.g., "📊 LINE Chart")
                        String chartTitle = chartType != null
                                ? "📊 " + chartType + " Chart"
                                : "📊 Chart";

                        // Send chart using Image Block (Slack fetches image from URL)
                        slackService.sendChartImageBlock(
                                botToken, channelId, threadTs,
                                chartUrl,
                                chartTitle,
                                "Chart visualization for: " + originalQuery
                        );
                    }
                } else if (dataList != null && dataList.size() > 50) {
                    log.info("[SlackChat] Skipping chart - too many data points ({})", dataList.size());
                }
            } catch (Exception e) {
                log.warn("[SlackChat] Failed to generate chart", e);
            }
        }

        // Step 3: Send analysis result message (skip thinking step - goes directly to result)
        if (result.getAnalysis() != null && !result.getAnalysis().isEmpty()) {
            messageBuilder.append("*Analysis result:*\n")
                    .append(result.getAnalysis());
        }

        if (messageBuilder.length() > 0) {
            slackService.sendResultMessage(botToken, channelId, threadTs, messageBuilder.toString());
        }

        // Clear thinking status after all done
        if (assistantMode) {
            slackService.setAssistantStatus(botToken, channelId, threadTs, "");
        }
        if (thinkingTs != null) {
            slackService.deleteMessage(botToken, channelId, thinkingTs);
        }
    }

    /**
     * Extract data list from result data.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataList(Object resultData) {
        if (resultData instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map) {
                return (List<Map<String, Object>>) list;
            }
        }
        // Handle ExecuteResult format
        if (resultData instanceof ai.inquery.spi.model.ExecuteResult executeResult) {
            List<List<String>> dataList = executeResult.getDataList();
            List<ai.inquery.spi.model.Header> headerList = executeResult.getHeaderList();
            
            if (dataList != null && headerList != null) {
                return dataList.stream()
                        .map(row -> {
                            Map<String, Object> map = new java.util.LinkedHashMap<>();
                            for (int i = 0; i < headerList.size() && i < row.size(); i++) {
                                map.put(headerList.get(i).getName(), row.get(i));
                            }
                            return map;
                        })
                        .toList();
            }
        }
        return null;
    }

    /**
     * Extract headers from result data.
     */
    private List<String> extractHeaders(Object resultData) {
        if (resultData instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                return map.keySet().stream().map(Object::toString).toList();
            }
        }
        if (resultData instanceof ai.inquery.spi.model.ExecuteResult executeResult) {
            if (executeResult.getHeaderList() != null) {
                return executeResult.getHeaderList().stream()
                        .map(ai.inquery.spi.model.Header::getName)
                        .toList();
            }
        }
        return null;
    }

    /**
     * Handle clarification request.
     * @param assistantMode true if using Slack Assistant API (no thinking message to update)
     */
    private void handleClarification(String botToken, String channelId, String threadTs,
                                     String thinkingTs, ai.inquery.server.domain.core.langchain.agents.SupervisorAgent.SupervisorResult result,
                                     boolean assistantMode) {
        if (!assistantMode && thinkingTs != null) {
            slackService.updateToCompleted(botToken, channelId, thinkingTs, "Question needed");
        }

        List<ai.inquery.server.domain.core.langchain.agents.SupervisorAgent.ClarificationOption> options = result.getClarificationOptions();
        List<SlackService.SlackButton> buttons = options.stream()
                .map(opt -> new SlackService.SlackButton(opt.getLabel(), opt.getQuery()))
                .toList();

        String prompt = "Please select an option to clarify your question:";
        slackService.sendClarificationButtons(botToken, channelId, threadTs, prompt, buttons);
    }


    /**
     * Truncate query for use as chart title.
     */
    private String truncateForTitle(String query) {
        if (query == null) return "";
        if (query.length() <= 40) return query;
        return query.substring(0, 37) + "...";
    }

    /**
     * Get progress step message (returns as-is).
     */
    private String translateStep(String step) {
        return step != null ? step : "Processing...";
    }

    /**
     * Convert result data to CSV format.
     */
    private String convertToCsv(Object resultData) {
        if (resultData == null) return null;

        try {
            // Handle List<Map<String, Object>> format
            if (resultData instanceof List<?> list && !list.isEmpty()) {
                StringBuilder csv = new StringBuilder();

                // Get first row to extract headers
                Object firstRow = list.get(0);
                if (firstRow instanceof Map<?, ?> firstMap) {
                    // Headers
                    List<String> headers = firstMap.keySet().stream()
                            .map(Object::toString)
                            .toList();
                    csv.append(String.join(",", headers)).append("\n");

                    // Data rows
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> rowMap) {
                            List<String> values = headers.stream()
                                    .map(h -> {
                                        Object val = rowMap.get(h);
                                        if (val == null) return "";
                                        String strVal = val.toString();
                                        // Escape CSV special characters
                                        if (strVal.contains(",") || strVal.contains("\"") || strVal.contains("\n")) {
                                            strVal = "\"" + strVal.replace("\"", "\"\"") + "\"";
                                        }
                                        return strVal;
                                    })
                                    .toList();
                            csv.append(String.join(",", values)).append("\n");
                        }
                    }
                }

                return csv.toString();
            }

            // Handle ExecuteResult format (headerList + dataList)
            if (resultData instanceof ai.inquery.spi.model.ExecuteResult executeResult) {
                List<ai.inquery.spi.model.Header> headerList = executeResult.getHeaderList();
                List<List<String>> dataList = executeResult.getDataList();
                
                if (headerList == null || dataList == null) {
                    return null;
                }
                
                StringBuilder csv = new StringBuilder();
                
                // Headers
                List<String> headers = headerList.stream()
                        .map(ai.inquery.spi.model.Header::getName)
                        .toList();
                csv.append(String.join(",", headers)).append("\n");
                
                // Data rows
                for (List<String> row : dataList) {
                    List<String> escapedValues = row.stream()
                            .map(val -> {
                                if (val == null) return "";
                                String strVal = val;
                                // Escape CSV special characters
                                if (strVal.contains(",") || strVal.contains("\"") || strVal.contains("\n")) {
                                    strVal = "\"" + strVal.replace("\"", "\"\"") + "\"";
                                }
                                return strVal;
                            })
                            .toList();
                    csv.append(String.join(",", escapedValues)).append("\n");
                }
                
                return csv.toString();
            }

            return null;
        } catch (Exception e) {
            log.warn("[SlackChat] Failed to convert result to CSV", e);
            return null;
        }
    }

    /**
     * Handle schema query - LLM analyzes and explains table structure.
     * Uses Data Catalog if saved, otherwise uses DDL.
     *
     * <p>LEGACY: was driven by the removed QueryClassifierTranslator. Kept for
     * reference; will be deleted once Slack schema-question UX is folded into
     * SupervisorAgent's tool loop.
     */
    @SuppressWarnings("unused")
    private void handleSchemaQuery(String botToken, String channelId, String threadTs,
                                   String thinkingMsgTs, String userMessageTs, boolean assistantMode,
                                   List<String> targetTables, String databaseName, String schemaName,
                                   String originalQuery) {
        try {
            // Update thinking status
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            if (thinkingMsgTs != null) {
                slackService.updateThinkingStep(botToken, channelId, thinkingMsgTs, "Searching schema...");
            }

            // Remove duplicates from targetTables
            List<String> uniqueTables = targetTables.stream()
                    .map(String::toUpperCase)
                    .distinct()
                    .toList();
            log.info("[SlackChat] Schema query - tables: {}", uniqueTables);

            StringBuilder schemaContext = new StringBuilder();
            ConnectInfo connectInfo = InqueryContext.getConnectInfo();
            Long dataSourceId = connectInfo != null ? connectInfo.getDataSourceId() : null;
            
            // Get databaseName/schemaName from ConnectInfo if not provided (prevents JDBC fallback)
            String effectiveDbName = databaseName;
            String effectiveSchemaName = schemaName;
            if (connectInfo != null) {
                if (StringUtils.isBlank(effectiveDbName)) {
                    effectiveDbName = connectInfo.getDatabaseName();
                }
                if (StringUtils.isBlank(effectiveSchemaName)) {
                    effectiveSchemaName = connectInfo.getSchemaName();
                }
            }
            log.info("[SlackChat] Schema query - effectiveDb: {}, effectiveSchema: {}", effectiveDbName, effectiveSchemaName);

            // Resolve table names: exact match → vector/keyword search fallback
            List<String> resolvedTables = resolveTableNames(uniqueTables, dataSourceId, effectiveDbName, effectiveSchemaName);
            log.info("[SlackChat] Resolved tables: {} -> {}", uniqueTables, resolvedTables);

            for (String tableName : resolvedTables) {
                log.info("[SlackChat] Fetching catalog for table: {}", tableName);

                // First, find table in Data Catalog by name only (to get databaseName/schemaName)
                String tableDbName = effectiveDbName;
                String tableSchemaName = effectiveSchemaName;

                // If tableName contains dots (DB.SCHEMA.TABLE), parse the path
                String actualTableName = tableName;
                String[] parts = tableName.split("\\.");
                if (parts.length == 3) {
                    tableDbName = parts[0];
                    tableSchemaName = parts[1];
                    actualTableName = parts[2];
                } else if (parts.length == 2) {
                    tableSchemaName = parts[0];
                    actualTableName = parts[1];
                }
                tableName = actualTableName;

                var findResult = dataCatalogService.findTableByName(dataSourceId, tableName);
                if (findResult != null && findResult.getSuccess() && findResult.getData() != null) {
                    Map<String, String> foundTable = findResult.getData();
                    tableDbName = foundTable.get("databaseName");
                    tableSchemaName = foundTable.get("schemaName");
                    log.info("[SlackChat] Found table in Data Catalog: {}.{}.{}", tableDbName, tableSchemaName, tableName);
                }

                // Build query param with resolved database/schema
                TableQueryParam param = new TableQueryParam();
                param.setTableName(tableName);
                param.setDatabaseName(tableDbName);
                param.setSchemaName(tableSchemaName);
                param.setDataSourceId(dataSourceId);
                param.setRefresh(false);

                // Try Data Catalog first (has rich metadata)
                var catalogResult = dataCatalogService.queryCatalog(param);
                
                if (catalogResult != null && catalogResult.getSuccess() && catalogResult.getData() != null) {
                    Map<String, Object> catalog = catalogResult.getData();
                    String tableDesc = (String) catalog.get("tableDescription");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> columns = (List<Map<String, Object>>) catalog.get("columns");

                    schemaContext.append("=== Table: ").append(tableName).append(" ===\n");
                    
                    if (tableDesc != null && !tableDesc.isEmpty()) {
                        schemaContext.append("Description: ").append(tableDesc).append("\n\n");
                    }

                    schemaContext.append("Columns:\n");
                    if (columns != null) {
                        for (Map<String, Object> col : columns) {
                            String colName = (String) col.get("name");
                            String colType = (String) col.get("columnType");
                            String colDesc = (String) col.get("description");
                            String examples = (String) col.get("exampleValues");

                            schemaContext.append("- ").append(colName).append(" (").append(colType).append(")");
                            if (colDesc != null && !colDesc.isEmpty()) {
                                schemaContext.append(": ").append(colDesc);
                            }
                            if (examples != null && !examples.isEmpty() && !examples.equals("[]")) {
                                schemaContext.append(" [Examples: ").append(examples).append("]");
                            }
                            schemaContext.append("\n");
                        }
                    }
                    schemaContext.append("\n");
                } else {
                    // Fallback to DDL (basic column info)
                    List<TableColumn> columns = tableService.queryColumns(param);
                    if (CollectionUtils.isEmpty(columns)) {
                        schemaContext.append("=== Table: ").append(tableName).append(" ===\n");
                        schemaContext.append("Table not found.\n\n");
                        continue;
                    }

                    schemaContext.append("=== Table: ").append(tableName).append(" ===\n");
                    schemaContext.append("Columns:\n");
                    for (TableColumn col : columns) {
                        schemaContext.append("- ").append(col.getName()).append(" (").append(col.getColumnType()).append(")");
                        if (col.getComment() != null && !col.getComment().isEmpty()) {
                            schemaContext.append(": ").append(col.getComment());
                        }
                        schemaContext.append("\n");
                    }
                    schemaContext.append("\n");
                }
            }

            // Update thinking status
            if (thinkingMsgTs != null) {
                slackService.updateThinkingStep(botToken, channelId, thinkingMsgTs, "Analyzing table schema...");
            }

            // Build LLM prompt for schema analysis
            String prompt = buildSchemaAnalysisPrompt(resolvedTables, schemaContext.toString(), originalQuery);
            
            // Call LLM to analyze schema
            String analysis = aiService.generate(prompt, DEFAULT_MODEL);
            
            // Clear thinking status
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            if (thinkingMsgTs != null) {
                slackService.deleteMessage(botToken, channelId, thinkingMsgTs);
            }

            // Add checkmark
            slackService.removeReaction(botToken, channelId, userMessageTs, "eyes");
            slackService.addReaction(botToken, channelId, userMessageTs, "white_check_mark");

            // Send LLM analysis result
            slackService.sendResultMessage(botToken, channelId, threadTs, analysis);

        } catch (Exception e) {
            log.error("[SlackChat] Error handling schema query", e);
            if (assistantMode) {
                slackService.setAssistantStatus(botToken, channelId, userMessageTs, "");
            }
            if (thinkingMsgTs != null) {
                slackService.deleteMessage(botToken, channelId, thinkingMsgTs);
            }
            slackService.sendErrorMessage(botToken, channelId, threadTs,
                    "❌ Error occurred while searching table information: " + e.getMessage());
        }
    }

    /**
     * Resolve table names to actual table names/paths.
     * Priority: exact match in Data Catalog → vector DB semantic search → keyword fallback.
     */
    private List<String> resolveTableNames(List<String> tableNames, Long dataSourceId,
                                            String databaseName, String schemaName) {
        List<String> resolved = new ArrayList<>();
        for (String tableName : tableNames) {
            // 1) Exact match via Data Catalog
            var findResult = dataCatalogService.findTableByName(dataSourceId, tableName);
            if (findResult != null && findResult.getSuccess() && findResult.getData() != null) {
                log.info("[SlackChat] Exact match for '{}' in Data Catalog", tableName);
                resolved.add(tableName);
                continue;
            }

            // 2) Fallback: vector + keyword search via SchemaSearcher
            try {
                List<String> schemaResults = schemaSearcher.searchSchema(
                        tableName, null, dataSourceId, databaseName, schemaName);
                if (!schemaResults.isEmpty()) {
                    String extractedPath = extractFullPathFromDDL(schemaResults.get(0));
                    if (extractedPath != null) {
                        log.info("[SlackChat] Resolved via search: '{}' -> '{}'", tableName, extractedPath);
                        if (!resolved.contains(extractedPath)) {
                            resolved.add(extractedPath);
                        }
                        continue;
                    }
                }
            } catch (Exception e) {
                log.warn("[SlackChat] Schema search failed for '{}': {}", tableName, e.getMessage());
            }

            // 3) No match found — keep original name
            log.info("[SlackChat] No resolution found for '{}', using as-is", tableName);
            resolved.add(tableName);
        }
        return resolved;
    }

    private String extractFullPathFromDDL(String ddl) {
        Matcher pathMatcher = TABLE_PATH_PATTERN.matcher(ddl);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }
        Matcher m = TABLE_NAME_PATTERN.matcher(ddl);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Build prompt for LLM to analyze table schema.
     */
    private String buildSchemaAnalysisPrompt(List<String> tableNames, String schemaContext, String originalQuery) {
        return """
            You are a helpful data analyst assistant. Analyze the following database table(s) and provide a clear, concise explanation.

            [LANGUAGE - CRITICAL]
            - You MUST respond in the SAME LANGUAGE as the user's original question below.
            - If the user asked in English, respond entirely in English.

            [USER'S ORIGINAL QUESTION]
            %s

            [RESPONSE FORMAT]
            - Use Slack mrkdwn format (NOT standard markdown)
            - Bold: *text* (single asterisks)
            - Italic: _text_
            - Code: `code`
            - Bullet points: • or -
            - DO NOT use headers (# or ###). Use *Bold Section Title:* instead

            [RESPONSE STRUCTURE]
            1. *Table Overview:* Brief description of what the table is for (1-2 sentences)
            2. *Columns:* List ALL columns with brief explanations for each
               - Format: `column_name` (type): description
               - If column has a description in metadata, use it
               - If no description, infer from column name and type
            3. *Analysis Potential:* What kind of analysis is possible with this data

            [RULES]
            - Describe ALL columns, not just important ones
            - Focus on business meaning, not technical details
            - If table description exists in metadata, use it as primary source

            Tables to analyze: %s

            Schema Information:
            %s
            """.formatted(originalQuery, String.join(", ", tableNames), schemaContext);
    }

    /**
     * Get list of inactive tables (active=false) to exclude from schema search.
     * These tables have been toggled off in the Data Catalog.
     *
     * @param dataSourceId data source ID
     * @param databaseName database name
     * @param schemaName schema name
     * @return list of inactive table names, or empty list if none
     */
    private List<String> getInactiveTables(Long dataSourceId, String databaseName, String schemaName) {
        try {
            // Get all table active states from Data Catalog
            var result = dataCatalogService.getTableActiveStates(dataSourceId, databaseName, schemaName);
            if (result == null || !result.getSuccess() || result.getData() == null) {
                return java.util.Collections.emptyList();
            }

            // Filter for inactive tables (active=false)
            List<String> inactiveTables = new java.util.ArrayList<>();
            for (Map.Entry<String, Boolean> entry : result.getData().entrySet()) {
                if (Boolean.FALSE.equals(entry.getValue())) {
                    // Extract table name from key format "database.schema.table"
                    String tableKey = entry.getKey();
                    String[] parts = tableKey.split("\\.");
                    if (parts.length >= 3) {
                        // Use just the table name for Pinecone filter
                        inactiveTables.add(parts[parts.length - 1]);
                    }
                }
            }

            log.debug("[SlackChat] Found {} inactive tables for dataSourceId: {}", inactiveTables.size(), dataSourceId);
            return inactiveTables;
        } catch (Exception e) {
            log.warn("[SlackChat] Failed to get inactive tables, proceeding without exclusion: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
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
                log.info("[SlackChat] Found business insight for dataSourceId: {}, databaseName: {} ({} chars)",
                    dataSourceId, databaseName, insight.getInsightContent().length());
                return insight.getInsightContent();
            }
            // If not found with database name, try with empty string (global for dataSource)
            if (databaseName != null && !databaseName.isEmpty()) {
                insight = businessInsightService.getInsight(dataSourceId, "");
                if (insight != null && insight.getInsightContent() != null && !insight.getInsightContent().isEmpty()) {
                    log.info("[SlackChat] Found global business insight for dataSourceId: {} ({} chars)",
                        dataSourceId, insight.getInsightContent().length());
                    return insight.getInsightContent();
                }
            }
        } catch (Exception e) {
            log.debug("[SlackChat] Failed to get business insight for dataSourceId {}: {}", dataSourceId, e.getMessage());
        }
        return null;
    }
}
