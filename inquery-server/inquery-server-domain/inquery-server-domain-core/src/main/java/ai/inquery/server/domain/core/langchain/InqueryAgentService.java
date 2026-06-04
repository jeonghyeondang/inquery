package ai.inquery.server.domain.core.langchain;

import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.langchain.agents.SupervisorAgent;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Service for managing LangChain4j-based Inquery Agents.
 * Conversation history is provided by the frontend per request via explicit context injection.
 */
@Slf4j
@Service
public class InqueryAgentService {

    @Autowired
    private LangChainModelProvider modelProvider;

    @Autowired
    private DlTemplateService dlTemplateService;

    @Autowired
    private SchemaSearcher schemaSearcher;

    @Autowired
    private ai.inquery.server.domain.api.service.AiFeedbackService aiFeedbackService;

    @Autowired(required = false)
    private ai.inquery.server.domain.api.service.AIService aiService;

    @Autowired
    private ai.inquery.server.domain.core.langchain.mcp.McpConnectionManager mcpConnectionManager;

    @Autowired
    private ai.inquery.server.domain.core.python.PythonEnvironmentSetup pythonEnvironmentSetup;

    /**
     * Process query with Deep Agent (Supervisor pattern).
     * Uses multi-agent orchestration for complex queries with automatic retry.
     *
     * @param modelName        LLM model to use
     * @param message          User's question
     * @param dataSourceId     Database connection ID
     * @param databaseName     Database name
     * @param schemaName       Schema name
     * @param progressCallback Callback for progress updates (optional)
     * @return SupervisorResult with full analysis
     */
    public SupervisorAgent.SupervisorResult processWithDeepAgent(
            String modelName,
            String message,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            Consumer<String> progressCallback
    ) {
        return processWithDeepAgent(modelName, message, dataSourceId, databaseName, schemaName, progressCallback, false);
    }

    /**
     * Process query with Deep Agent (Supervisor pattern).
     * Uses multi-agent orchestration for complex queries with automatic retry.
     *
     * @param modelName         LLM model to use
     * @param message           User's question
     * @param dataSourceId      Database connection ID
     * @param databaseName      Database name
     * @param schemaName        Schema name
     * @param progressCallback  Callback for progress updates (optional)
     * @param skipClarification If true, skip clarification step (user already selected an option)
     * @return SupervisorResult with full analysis
     */
    public SupervisorAgent.SupervisorResult processWithDeepAgent(
            String modelName,
            String message,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            Consumer<String> progressCallback,
            boolean skipClarification
    ) {
        log.info("Processing with Deep Agent - model: {}, message: {}, skipClarification: {}",
                modelName, message.length() > 50 ? message.substring(0, 50) + "..." : message, skipClarification);

        try {
            SupervisorAgent supervisor = new SupervisorAgent(
                    modelProvider,
                    dlTemplateService,
                    schemaSearcher,
                    modelName,
                    dataSourceId,
                    databaseName,
                    schemaName
            );
            supervisor.setAiFeedbackService(aiFeedbackService);
            supervisor.setAiService(aiService);

            return supervisor.process(message, progressCallback, skipClarification);
        } catch (Exception e) {
            log.error("Deep Agent processing failed", e);
            return SupervisorAgent.SupervisorResult.builder()
                    .originalQuery(message)
                    .success(false)
                    .errorMessage("Deep Agent failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Process query with Deep Agent (Supervisor pattern) with Data Catalog toggles applied.
     */
    public SupervisorAgent.SupervisorResult processWithDeepAgent(
            String modelName,
            String message,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            Consumer<String> progressCallback,
            boolean skipClarification,
            java.util.List<String> excludedTables
    ) {
        log.info("Processing with Deep Agent - model: {}, message: {}, skipClarification: {}, excludedTables: {}",
                modelName,
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                skipClarification,
                excludedTables != null ? excludedTables.size() : 0);

        try {
            SupervisorAgent supervisor = new SupervisorAgent(
                    modelProvider,
                    dlTemplateService,
                    schemaSearcher,
                    modelName,
                    dataSourceId,
                    databaseName,
                    schemaName
            );
            supervisor.setExcludedTables(excludedTables);
            supervisor.setAiFeedbackService(aiFeedbackService);
            supervisor.setAiService(aiService);

            return supervisor.process(message, progressCallback, skipClarification);
        } catch (Exception e) {
            log.error("Deep Agent processing failed", e);
            return SupervisorAgent.SupervisorResult.builder()
                    .originalQuery(message)
                    .success(false)
                    .errorMessage("Deep Agent failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Process query with Deep Agent (Supervisor pattern) with explicit context injection.
     */
    public SupervisorAgent.SupervisorResult processWithDeepAgent(
            String conversationId,
            String modelName,
            String message,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            Consumer<String> progressCallback,
            boolean skipClarification,
            java.util.List<String> excludedTables,
            java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> conversationHistory,
            String businessContext
    ) {
        return processWithDeepAgent(conversationId, modelName, message, dataSourceId, databaseName, schemaName,
                progressCallback, skipClarification, excludedTables, conversationHistory, businessContext, null, null);
    }

    /**
     * Process query with Deep Agent (Supervisor pattern) with:
     * - Explicit injection of conversation history + business context into prompt context
     * - Data Catalog toggles applied (excludedTables)
     * - Translated search query for better Vector DB search (searchQuery)
     * - CONTEXT_ANSWER support: Can answer directly from conversation history without new SQL
     */
    public SupervisorAgent.SupervisorResult processWithDeepAgent(
            String conversationId,
            String modelName,
            String message,
            Long dataSourceId,
            String databaseName,
            String schemaName,
            Consumer<String> progressCallback,
            boolean skipClarification,
            java.util.List<String> excludedTables,
            java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> conversationHistory,
            String businessContext,
            String searchQuery,
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig
    ) {
        log.info("Processing with Deep Agent - conversationId: {}, model: {}, skipClarification: {}, excludedTables: {}, history: {}, businessContext: {}, searchQuery: {}",
                conversationId,
                modelName,
                skipClarification,
                excludedTables != null ? excludedTables.size() : 0,
                conversationHistory != null ? conversationHistory.size() : 0,
                businessContext != null ? businessContext.length() + " chars" : "none",
                searchQuery != null ? searchQuery.length() + " chars" : "none");

        try {
            String historyContext = buildConversationHistoryContext(conversationHistory);

            // NOTE: The pre-classification step that used to short-circuit
            // CONTEXT_ANSWER queries and improve searchQuery has been removed
            // along with QueryClassifierTranslator. SupervisorAgent now handles
            // history-grounded answers and schema search directly.

            SupervisorAgent supervisor = new SupervisorAgent(
                    modelProvider,
                    dlTemplateService,
                    schemaSearcher,
                    modelName,
                    dataSourceId,
                    databaseName,
                    schemaName
            );
            supervisor.setConversationHistoryContext(historyContext);
            supervisor.setBusinessContext(businessContext);
            supervisor.setExcludedTables(excludedTables);
            supervisor.setSearchQuery(searchQuery);
            supervisor.setAiFeedbackService(aiFeedbackService);
            supervisor.setAiService(aiService);

            // Connect Python tools if environment is ready
            if (pythonEnvironmentSetup.isReady()) {
                supervisor.setPythonTools(
                        new ai.inquery.server.domain.core.langchain.tools.PythonTools(pythonEnvironmentSetup));
                log.info("Python tools connected for Deep Agent");
            }

            // Connect MCP tools if user has configured integrations
            ai.inquery.server.domain.core.langchain.mcp.McpConnectionManager.McpConnectionResult mcpResult = null;
            if (userConfig != null) {
                try {
                    mcpResult = mcpConnectionManager.connect(userConfig);
                    if (mcpResult.hasTools()) {
                        supervisor.setMcpToolProvider(mcpResult.toolProvider());
                        log.info("MCP tools connected for Deep Agent");
                    }
                } catch (Exception e) {
                    log.warn("Failed to connect MCP tools: {}", e.getMessage());
                }
            }

            try {
                return supervisor.process(message, progressCallback, skipClarification);
            } finally {
                if (mcpResult != null) {
                    mcpResult.close();
                }
            }
        } catch (Exception e) {
            log.error("Deep Agent processing failed", e);
            return SupervisorAgent.SupervisorResult.builder()
                    .originalQuery(message)
                    .success(false)
                    .errorMessage("Deep Agent failed: " + e.getMessage())
                    .build();
        }
    }

    private String buildConversationHistoryContext(
            java.util.List<ai.inquery.server.domain.api.param.QueryRequest.ConversationMessage> conversationHistory
    ) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONVERSATION HISTORY ===\n");
        for (var msg : conversationHistory) {
            if (msg == null) continue;
            String role = msg.getRole() != null ? msg.getRole() : "unknown";
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (content.isEmpty()) continue;
            sb.append(role).append(": ").append(content).append("\n");
        }
        sb.append("=== END CONVERSATION HISTORY ===\n\n");
        String out = sb.toString();
        return out.isBlank() ? null : out;
    }

    /**
     * Process query with external tools (MCP + Python + Database).
     * Lightweight tool calling without full SupervisorAgent orchestration.
     * Used by Manual/Auto Basic modes when query needs external service integration.
     * Database tools are included so the agent can do multi-step queries
     * (e.g., check DB first, then search Confluence if not found).
     *
     * @param modelName    LLM model to use
     * @param message      User's question
     * @param userConfig   User's MCP integration credentials
     * @param targetService Target MCP service, or null for all
     * @param dataSourceId Database connection ID (for DB tools)
     * @param databaseName Database name (for DB tools)
     * @param schemaName   Schema name (for DB tools)
     * @return Tool execution result, or null if no tools available
     */
    public String processWithExternalTools(String modelName, String message,
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig,
            String targetService,
            Long dataSourceId, String databaseName, String schemaName,
            String conversationHistory,
            ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback approvalCallback) {
        log.info("Processing with external tools (lightweight) - model: {}, targetService: {}, message: {}",
                modelName, targetService, message.length() > 50 ? message.substring(0, 50) + "..." : message);

        ai.inquery.server.domain.core.langchain.mcp.McpConnectionManager.McpConnectionResult mcpResult = null;
        try {
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getToolCallingChatModel(modelName);
            var builder = dev.langchain4j.service.AiServices.builder(ExternalToolAgent.class)
                    .chatModel(chatModel)
                    .maxSequentialToolsInvocations(5);

            java.util.List<Object> toolInstances = new java.util.ArrayList<>();

            // Pre-validate credentials before connecting MCP
            java.util.Map<String, String> credentialFailures = java.util.Map.of();
            if (userConfig != null) {
                credentialFailures = mcpConnectionManager.validateCredentials(userConfig);
                // If targeting a specific service and it failed validation, return early
                if (targetService != null && credentialFailures.containsKey(targetService.toLowerCase())) {
                    return credentialFailures.get(targetService.toLowerCase());
                }
            }

            // Connect only the target MCP service
            if (userConfig != null) {
                mcpResult = mcpConnectionManager.connect(userConfig, targetService);
                if (mcpResult.hasTools()) {
                    dev.langchain4j.service.tool.ToolProvider toolProvider = mcpResult.toolProvider();
                    // Wrap with approval if callback is provided
                    if (approvalCallback != null) {
                        // Build service base URL map for link generation in approval UI
                        java.util.Map<String, String> serviceUrls = new java.util.HashMap<>();
                        if (userConfig.getConfluenceBaseUrl() != null) serviceUrls.put("confluence", userConfig.getConfluenceBaseUrl());
                        if (userConfig.getJiraBaseUrl() != null) serviceUrls.put("jira", userConfig.getJiraBaseUrl());
                        if (userConfig.getGithubBaseUrl() != null) serviceUrls.put("github", userConfig.getGithubBaseUrl());
                        // Confluence credentials for resolving Space URL → Space ID in approval
                        java.util.Map<String, String> confCreds = new java.util.HashMap<>();
                        if (userConfig.getConfluenceBaseUrl() != null) confCreds.put("baseUrl", userConfig.getConfluenceBaseUrl());
                        if (userConfig.getConfluenceUsername() != null) confCreds.put("username", userConfig.getConfluenceUsername());
                        if (userConfig.getConfluenceApiToken() != null) confCreds.put("apiToken", userConfig.getConfluenceApiToken());
                        // Jira credentials for user search in approval UI
                        java.util.Map<String, String> jiraCreds = new java.util.HashMap<>();
                        if (userConfig.getJiraBaseUrl() != null) jiraCreds.put("baseUrl", userConfig.getJiraBaseUrl());
                        if (userConfig.getJiraUsername() != null) jiraCreds.put("username", userConfig.getJiraUsername());
                        if (userConfig.getJiraApiToken() != null) jiraCreds.put("apiToken", userConfig.getJiraApiToken());
                        toolProvider = new ai.inquery.server.domain.core.langchain.tools.ApprovalToolProvider(
                                toolProvider, approvalCallback, serviceUrls, confCreds, jiraCreds);
                        log.info("MCP tools wrapped with approval for target service: {}", targetService);
                    }
                    builder.toolProvider(toolProvider);
                    log.info("MCP tools connected for target service: {}", targetService);
                }
            }

            // When targeting a specific external service, MCP must be connected.
            // Without MCP, the LLM would misuse DB tools (e.g., querying Snowflake for Slack data).
            if (targetService != null && (mcpResult == null || !mcpResult.hasTools())) {
                log.warn("Target service '{}' requested but MCP connection failed. "
                        + "Skipping database tools to prevent misuse.", targetService);
                return "The " + targetService + " integration is not configured or failed to connect. "
                        + "Please check your " + targetService + " settings (API token, credentials) "
                        + "in the Settings page and try again.";
            }

            // Connect Database tools only when no specific external service is targeted.
            // When targetService is set (e.g., "slack"), the user explicitly wants that service
            // and the conversation history provides all necessary context.
            // Connecting DB tools causes the LLM to waste time searching the vector DB
            // for irrelevant schema information (e.g., searchSchema("slack channel")).
            if (dataSourceId != null && targetService == null) {
                toolInstances.add(new ai.inquery.server.domain.core.langchain.tools.DatabaseTools(
                        dlTemplateService, schemaSearcher, dataSourceId, databaseName, schemaName));
                log.info("Database tools connected for external tool processing");
            }

            // Connect Python tools only when not targeting a specific external service
            // (Python is for data analysis, not needed for Confluence/Slack/Jira/GitHub)
            if (targetService == null && pythonEnvironmentSetup.isReady()) {
                toolInstances.add(new ai.inquery.server.domain.core.langchain.tools.PythonTools(pythonEnvironmentSetup));
                log.info("Python tools connected for external tool processing");
            }

            if (!toolInstances.isEmpty()) {
                builder.tools(toolInstances.toArray());
            }

            boolean hasTools = !toolInstances.isEmpty()
                    || (mcpResult != null && mcpResult.hasTools());

            if (!hasTools) {
                log.info("No external tools available");
                return null;
            }

            // Collect actual available tool names for system message and hallucination hints
            java.util.List<String> availableToolNames = new java.util.ArrayList<>();
            if (mcpResult != null && mcpResult.hasTools()) {
                availableToolNames.addAll(mcpResult.getToolNames());
            }
            for (Object toolInstance : toolInstances) {
                for (java.lang.reflect.Method m : toolInstance.getClass().getMethods()) {
                    if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                        availableToolNames.add(m.getName());
                    }
                }
            }
            final String toolNamesList = String.join(", ", availableToolNames);
            log.info("Available tools for agent: [{}]", toolNamesList);

            // Don't crash on hallucinated tool names — return error with exact available tool list
            builder.hallucinatedToolNameStrategy((toolRequest) -> {
                log.warn("LLM hallucinated tool '{}' — available tools: [{}]", toolRequest.name(), toolNamesList);
                return dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        toolRequest,
                        "Error: Tool '" + toolRequest.name() + "' does NOT exist. "
                                + "The ONLY tools you can use are: [" + toolNamesList + "]. "
                                + "Do NOT invent tool names. Use ONLY these exact tool names.");
            });

            // Build unavailable services notice for system message
            final String unavailableServicesNotice;
            if (!credentialFailures.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\nUNAVAILABLE SERVICES (credential issues — inform the user if they ask about these):\n");
                for (var entry : credentialFailures.entrySet()) {
                    sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                unavailableServicesNotice = sb.toString();
            } else {
                unavailableServicesNotice = "";
            }

            // Always provide a system message with exact tool names to prevent hallucination.
            final String toolNames = toolNamesList;
            if (targetService != null) {
                // Targeted system message for specific service actions (send to Slack, create Jira ticket, etc.)
                // Note: Confluence is handled separately via ConfluenceService (not MCP)
                builder.systemMessageProvider(memoryId ->
                    "You are a helpful assistant. You ONLY have these tools available: [" + toolNames + "]\n\n"
                    + "CRITICAL RULES:\n"
                    + "1. ONLY call the exact tool names listed above. Do NOT invent or guess tool names.\n"
                    + "2. The conversation history already contains ALL the content you need. "
                    + "Do NOT try to fetch content from other services.\n"
                    + "3. When the user says 'send the above content', extract the relevant content "
                    + "directly from the conversation history and use it with the available tools.\n"
                    + "4. ALWAYS call the write tool to execute the action. NEVER respond with only text.\n"
                    + "   - For Slack: ALWAYS call slack_post_message. Use 'general' as channel_id if unknown. "
                    + "Do NOT call slack_list_channels first. Do NOT ask the user which channel — "
                    + "the user will specify the channel in the approval UI before confirming.\n"
                    + "   - CRITICAL: Slack does NOT support standard Markdown. You MUST convert ALL content to Slack mrkdwn format before sending.\n"
                    + "     Even if the conversation history contains Markdown-formatted content, you MUST reformat it:\n"
                    + "     * **text** → *text* (Slack uses single asterisks for bold)\n"
                    + "     * ### Heading → *Heading* (no heading syntax in Slack)\n"
                    + "     * Markdown tables (| col | col |) → use ``` code block with aligned plain text columns\n"
                    + "     * Italic: _text_\n"
                    + "     * Code: `code` or ```code block```\n"
                    + "     * Lists: use bullet • or dash -\n"
                    + "     Example: convert '| name | type |\\n| id | int |' → ```\\nname    type\\nid      int\\n```\n"
                    + "   - For Jira: Use reasonable defaults. "
                    + "The user will review and modify all parameters before the action executes.\n"
                    + "   - If a read tool fails with a permission error, IGNORE it and proceed with the write tool.\n"
                    + "5. Always respond in the same language as the user's question."
                    + unavailableServicesNotice
                );
            } else {
                // General system message for multi-service queries (no specific target)
                // Note: Confluence is handled separately via ConfluenceService (not MCP)
                builder.systemMessageProvider(memoryId ->
                    "You are a helpful assistant that can use external tools to complete tasks.\n"
                    + "You ONLY have these tools available: [" + toolNames + "]\n\n"
                    + "CRITICAL: ONLY call the exact tool names listed above. Do NOT invent or guess tool names.\n\n"
                    + "For multi-step queries, combine tools as needed. For example:\n"
                    + "1. Search the database first for table/column info\n"
                    + "2. Search external services for additional context\n"
                    + "3. Synthesize results from multiple sources\n\n"
                    + "Always respond in the same language as the user's question."
                    + unavailableServicesNotice
                );
            }

            ExternalToolAgent agent = builder.build();

            // Include conversation history so the agent has full context
            String fullMessage = message;
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                fullMessage = "## Conversation History\n" + conversationHistory
                        + "\n\n## Current Request\n" + message;
            }

            String result = agent.execute(fullMessage);
            log.info("External tool result length: {}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("External tool processing failed: {}", e.getMessage(), e);

            // Tool loop limit exceeded — differentiate search vs write failures
            if (e.getMessage() != null && e.getMessage().contains("sequential tool executions")) {
                log.info("Tool execution loop limit reached for '{}', treating as no-result", targetService);
                try {
                    dev.langchain4j.model.chat.ChatModel fallbackModel = modelProvider.getChatModel(modelName);
                    // Detect if the user wanted to write/send (not search)
                    String lowerMessage = message.toLowerCase();
                    boolean isWriteAction = lowerMessage.contains("send") || lowerMessage.contains("post")
                            || lowerMessage.contains("create") || lowerMessage.contains("write");
                    String noResultPrompt;
                    if (isWriteAction) {
                        noResultPrompt = "The user asked: \"" + message + "\"\n\n"
                                + "You tried to send/write content to " + (targetService != null ? targetService : "the external service")
                                + " but the operation failed due to a tool execution issue.\n\n"
                                + "Let the user know the action could not be completed. "
                                + "Suggest they check their service configuration (API token, permissions) in Settings. "
                                + "Do NOT mention any internal errors, tool limits, or technical details. "
                                + "Respond in the same language as the user's question.";
                    } else {
                        noResultPrompt = "The user asked: \"" + message + "\"\n\n"
                                + "You searched " + (targetService != null ? targetService : "the external service")
                                + " but could not find relevant information.\n\n"
                                + "Let the user know you searched but couldn't find a match. "
                                + "Suggest they try with different keywords or check if the content exists. "
                                + "Do NOT mention any internal errors, tool limits, or technical details. "
                                + "Respond in the same language as the user's question.";
                    }
                    return fallbackModel.chat(noResultPrompt);
                } catch (Exception fallbackError) {
                    log.warn("Fallback LLM also failed: {}", fallbackError.getMessage());
                    return null;
                }
            }

            // Actual errors: let LLM generate a user-friendly explanation
            try {
                dev.langchain4j.model.chat.ChatModel fallbackModel = modelProvider.getChatModel(modelName);
                String errorPrompt = "The user asked: \"" + message + "\"\n\n"
                        + "The external tool (" + (targetService != null ? targetService : "unknown") + ") failed with error: "
                        + e.getMessage() + "\n\n"
                        + "Explain this failure to the user in a friendly, concise way. "
                        + "Suggest what they can check (e.g., API token, service availability). "
                        + "Respond in the same language as the user's question.";
                return fallbackModel.chat(errorPrompt);
            } catch (Exception fallbackError) {
                log.warn("Fallback LLM error explanation also failed: {}", fallbackError.getMessage());
                return null; // null triggers SQL generation fallback
            }
        } finally {
            if (mcpResult != null) {
                mcpResult.close();
            }
        }
    }

    /**
     * Check if user has any MCP integrations configured.
     */
    public boolean hasExternalToolsConfigured(ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig) {
        if (userConfig == null) return false;
        return (userConfig.getSlackUserToken() != null && !userConfig.getSlackUserToken().isBlank())
                || (userConfig.getConfluenceApiToken() != null && !userConfig.getConfluenceApiToken().isBlank())
                || (userConfig.getJiraApiToken() != null && !userConfig.getJiraApiToken().isBlank())
                || (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isBlank());
    }

    /**
     * Generate a natural-language message when a service is unavailable.
     * Uses a fast model to produce a user-friendly response matching the user's language.
     */
    public String generateServiceUnavailableMessage(String userQuery, String serviceName, String reason, String model) {
        try {
            String fastModel = ModelMapper.getFastModel(model);
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(fastModel);
            String prompt = "The user asked: \"" + userQuery + "\"\n\n"
                    + "However, the " + serviceName + " integration is currently unavailable.\n"
                    + "Reason: " + reason + "\n\n"
                    + "Inform the user that this feature is unavailable and explain the reason in a friendly, concise way.\n"
                    + "Guide them to check Settings > Integrations to configure or fix the integration.\n"
                    + "Do NOT mention any internal errors or technical details like HTTP status codes.\n"
                    + "Respond in the same language as the user's question.";
            return chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("Failed to generate service unavailable message via LLM: {}", e.getMessage());
            return "⚠️ " + serviceName + " integration is unavailable: " + reason
                    + "\nPlease check Settings > Integrations.";
        }
    }

    /**
     * Generate an answer to a Confluence search query using wiki content as context.
     * Called when Confluence search results are available and need LLM interpretation.
     */
    public String generateConfluenceSearchResponse(String userQuery, String wikiContent, String model, String conversationHistory) {
        try {
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(model);
            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Confluence Wiki Content\n").append(wikiContent).append("\n\n");
            prompt.append("## User Question\n").append(userQuery).append("\n\n");
            prompt.append("Answer the user's question based on the Confluence wiki content above. ");
            prompt.append("Include the page URL as a reference. ");
            prompt.append("If the content doesn't fully answer the question, say so and include what you found. ");
            prompt.append("Respond in the same language as the user's question.");
            return chatModel.chat(prompt.toString());
        } catch (Exception e) {
            log.error("Failed to generate Confluence search response: {}", e.getMessage(), e);
            return "Failed to process Confluence search results. Please try again.";
        }
    }

    /**
     * Process a Confluence write request.
     * Step 1: LLM generates title + HTML content from conversation (simple chat, no agent/tool)
     * Step 2: Show approval UI to user
     * Step 3: On approval, create page via ConfluenceService directly
     */
    public String processWithConfluenceWrite(String modelName, String message,
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig,
            String conversationHistory,
            ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback approvalCallback) {

        log.info("Processing Confluence write - model: {}, message: {}",
                modelName, message.length() > 50 ? message.substring(0, 50) + "..." : message);

        try {
            // Step 1: LLM generates title + HTML content (simple chat call, no tool)
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(modelName);

            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Current Request\n").append(message).append("\n\n");
            prompt.append("Convert the conversation content into a Confluence wiki page.\n");
            prompt.append("Return ONLY a JSON object with exactly two fields:\n");
            prompt.append("- \"title\": a descriptive page title\n");
            prompt.append("- \"html\": the page content in well-structured HTML (h1, h2, p, ul, li, table, tr, th, td, code, pre, etc.)\n\n");
            prompt.append("RULES:\n");
            prompt.append("- Return ONLY the JSON object, no markdown code blocks, no explanation.\n");
            prompt.append("- The title and content should be in the same language as the user's request.\n");
            prompt.append("- Make the HTML content comprehensive and well-formatted.\n");

            String llmResponse = chatModel.chat(prompt.toString());
            log.info("Confluence write LLM response length: {}", llmResponse != null ? llmResponse.length() : 0);

            // Parse title and HTML from LLM response
            String pageTitle = "Untitled Page";
            String pageHtml = llmResponse;
            try {
                // Strip markdown code block wrapper if present
                String jsonStr = llmResponse.trim();
                if (jsonStr.startsWith("```")) {
                    jsonStr = jsonStr.replaceFirst("```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
                }
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonStr);
                if (json.has("title")) pageTitle = json.get("title").asText();
                if (json.has("html")) pageHtml = json.get("html").asText();
            } catch (Exception parseErr) {
                log.warn("Failed to parse LLM JSON response, using raw content: {}", parseErr.getMessage());
            }

            // Step 2: Show approval UI
            String defaultParentUrl = (userConfig.getConfluenceBaseUrl() != null && !userConfig.getConfluenceBaseUrl().isEmpty())
                    ? userConfig.getConfluenceBaseUrl() + "/wiki/spaces/"
                    : "";

            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest approvalRequest =
                    ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.builder()
                    .toolName("createConfluencePage")
                    .toolDisplayName("Create Confluence Page")
                    .description("Create a new page in Confluence")
                    .target(userConfig.getConfluenceBaseUrl() != null
                            ? java.net.URI.create(userConfig.getConfluenceBaseUrl()).getHost() : "Confluence")
                    .parameters(java.util.List.of(
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("parentUrl")
                                    .displayName("Parent Page URL")
                                    .type("text")
                                    .value(defaultParentUrl)
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("title")
                                    .displayName("Page Title")
                                    .type("text")
                                    .value(pageTitle)
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("content")
                                    .displayName("Content Preview")
                                    .type("html")
                                    .value(pageHtml)
                                    .required(true)
                                    .build()
                    ))
                    .build();

            ai.inquery.server.domain.core.langchain.tools.ToolApprovalResponse response =
                    approvalCallback.requestApproval(approvalRequest);

            // Step 3: On approval, create page directly
            java.util.Map<String, String> params = response.getParameters();
            String finalTitle = params != null && params.containsKey("title") ? params.get("title") : pageTitle;
            String finalContent = params != null && params.containsKey("content") ? params.get("content") : pageHtml;
            String finalParentUrl = params != null && params.containsKey("parentUrl") ? params.get("parentUrl") : defaultParentUrl;

            // Extract parentId and spaceKey from URL
            String parentId = extractPageIdFromUrl(finalParentUrl);
            String spaceKey = extractSpaceKeyFromUrl(finalParentUrl);

            if (spaceKey == null) {
                String error = "Could not extract space key from URL: " + finalParentUrl;
                log.warn("[ConfluenceWrite] {}", error);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return "Failed to create page: " + error;
            }

            // Resolve Space Key → Space ID
            ai.inquery.server.domain.core.impl.ConfluenceService confluenceService =
                    new ai.inquery.server.domain.core.impl.ConfluenceService(userConfig);
            String spaceId = confluenceService.resolveSpaceId(spaceKey);

            if (spaceId == null) {
                String error = "Could not resolve Space ID for key: " + spaceKey;
                log.warn("[ConfluenceWrite] {}", error);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return "Failed to create page: " + error;
            }

            log.info("[ConfluenceWrite] Creating page: spaceId={}, parentId={}, title='{}'", spaceId, parentId, finalTitle);
            String pageUrl = confluenceService.createPage(spaceId, finalTitle, finalContent, parentId);

            if (pageUrl != null) {
                log.info("[ConfluenceWrite] Page created successfully: {}", pageUrl);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), true, null);
                return "Page created successfully: " + pageUrl;
            } else {
                String error = "Page creation API returned null";
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return "Failed to create page. Please check your Confluence credentials and permissions.";
            }

        } catch (ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager.ToolApprovalException e) {
            log.info("[ConfluenceWrite] User denied page creation: {}", e.getMessage());
            return "Page creation was cancelled by the user.";
        } catch (Exception e) {
            log.error("Confluence write processing failed: {}", e.getMessage(), e);
            return "Failed to create Confluence page: " + e.getMessage();
        }
    }

    /** Extract page ID from Confluence URL (e.g., /pages/12345/Title → "12345") */
    private String extractPageIdFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/pages/(\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Extract space key from Confluence URL (e.g., /wiki/spaces/NFTMetaverse/... → "NFTMetaverse") */
    private String extractSpaceKeyFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/spaces/([^/]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    // ── Slack ──────────────────────────────────────────────────────────

    /**
     * Process a Slack write request (send message).
     * LLM converts content to Slack mrkdwn → approval UI → direct API call.
     */
    public String processWithSlackWrite(String modelName, String message,
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig,
            String conversationHistory,
            ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback approvalCallback) {

        log.info("Processing Slack write - message: {}",
                message.length() > 50 ? message.substring(0, 50) + "..." : message);

        try {
            // Step 1: LLM generates Slack message content
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(modelName);

            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Current Request\n").append(message).append("\n\n");
            prompt.append("Convert the conversation content into a Slack message.\n");
            prompt.append("Return ONLY a JSON object with exactly one field:\n");
            prompt.append("- \"text\": the message in Slack mrkdwn format\n\n");
            prompt.append("Slack mrkdwn rules:\n");
            prompt.append("- Bold: *text*, Italic: _text_, Strikethrough: ~text~, Code: `code`\n");
            prompt.append("- Code block: ```code```\n");
            prompt.append("- Lists: use • or - prefix\n");
            prompt.append("- NO standard Markdown (no **, no ###, no markdown tables)\n");
            prompt.append("- Tables: use ``` code block with aligned plain text\n");
            prompt.append("- Return ONLY the JSON, no markdown code blocks, no explanation.\n");

            String llmResponse = chatModel.chat(prompt.toString());

            // Parse message text from LLM response
            String messageText = llmResponse;
            try {
                String jsonStr = llmResponse.trim();
                if (jsonStr.startsWith("```")) {
                    jsonStr = jsonStr.replaceFirst("```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
                }
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonStr);
                if (json.has("text")) messageText = json.get("text").asText();
            } catch (Exception parseErr) {
                log.warn("Failed to parse Slack LLM JSON, using raw: {}", parseErr.getMessage());
            }

            // Step 2: Show approval UI
            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest approvalRequest =
                    ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.builder()
                    .toolName("sendSlackMessage")
                    .toolDisplayName("Send Slack Message")
                    .description("Send a message to Slack")
                    .target("Slack")
                    .parameters(java.util.List.of(
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("channel")
                                    .displayName("Channel / DM")
                                    .type("autocomplete")
                                    .value("general")
                                    .optionsEndpoint("/api/ai/tools/slack/channels")
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("text")
                                    .displayName("Message")
                                    .type("textarea")
                                    .value(messageText)
                                    .required(true)
                                    .build()
                    ))
                    .build();

            ai.inquery.server.domain.core.langchain.tools.ToolApprovalResponse response =
                    approvalCallback.requestApproval(approvalRequest);

            // Step 3: On approval, send message directly
            java.util.Map<String, String> params = response.getParameters();
            String finalChannel = params != null && params.containsKey("channel") ? params.get("channel") : "general";
            String finalText = params != null && params.containsKey("text") ? params.get("text") : messageText;

            ai.inquery.server.domain.core.impl.SlackService slackService =
                    new ai.inquery.server.domain.core.impl.SlackService(userConfig);

            log.info("[SlackWrite] Sending message to channel: {}", finalChannel);
            String result = slackService.postMessage(finalChannel, finalText);

            if (result != null) {
                log.info("[SlackWrite] Message sent successfully: {}", result);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), true, null);
                return "Message sent successfully: " + result;
            } else {
                String error = "Failed to send Slack message";
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return error + ". Please check your Slack token and permissions.";
            }

        } catch (ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager.ToolApprovalException e) {
            log.info("[SlackWrite] User denied: {}", e.getMessage());
            return "Message sending was cancelled by the user.";
        } catch (Exception e) {
            log.error("Slack write failed: {}", e.getMessage(), e);
            return "Failed to send Slack message: " + e.getMessage();
        }
    }

    /**
     * Generate response based on Slack search results (same pattern as Confluence search).
     */
    public String generateSlackSearchResponse(String userQuery, String slackContent, String model, String conversationHistory) {
        try {
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(model);
            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Slack Search Results\n").append(slackContent).append("\n\n");
            prompt.append("## User Question\n").append(userQuery).append("\n\n");
            prompt.append("Answer the user's question based on the Slack messages above. ");
            prompt.append("Include message URLs as references when available. ");
            prompt.append("If the results don't fully answer the question, say so and include what you found. ");
            prompt.append("Respond in the same language as the user's question.");
            return chatModel.chat(prompt.toString());
        } catch (Exception e) {
            log.error("Failed to generate Slack search response: {}", e.getMessage(), e);
            return "Failed to process Slack search results. Please try again.";
        }
    }

    // ── Jira ──────────────────────────────────────────────────────────

    /**
     * Process a Jira write request (create issue).
     * LLM extracts issue fields → approval UI → direct API call.
     */
    public String processWithJiraWrite(String modelName, String message,
            ai.inquery.server.domain.api.param.UserAIConfigSaveParam userConfig,
            String conversationHistory,
            ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback approvalCallback) {

        log.info("Processing Jira write - message: {}",
                message.length() > 50 ? message.substring(0, 50) + "..." : message);

        try {
            // Step 1: LLM generates issue fields
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(modelName);

            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Current Request\n").append(message).append("\n\n");
            prompt.append("Extract Jira issue fields from the conversation.\n");
            prompt.append("Return ONLY a JSON object with these fields:\n");
            prompt.append("- \"project\": project key (e.g., \"OVER\", \"DEV\")\n");
            prompt.append("- \"issueType\": issue type (e.g., \"Task\", \"Bug\", \"Story\")\n");
            prompt.append("- \"summary\": brief issue title\n");
            prompt.append("- \"description\": detailed description in plain text\n\n");
            prompt.append("Use reasonable defaults if not specified. Return ONLY the JSON, no markdown code blocks.\n");

            String llmResponse = chatModel.chat(prompt.toString());

            // Parse fields from LLM response
            String project = "OVER";
            String issueType = "Task";
            String summary = "New Issue";
            String description = "";
            try {
                String jsonStr = llmResponse.trim();
                if (jsonStr.startsWith("```")) {
                    jsonStr = jsonStr.replaceFirst("```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
                }
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonStr);
                if (json.has("project")) project = json.get("project").asText();
                if (json.has("issueType")) issueType = json.get("issueType").asText();
                if (json.has("summary")) summary = json.get("summary").asText();
                if (json.has("description")) description = json.get("description").asText();
            } catch (Exception parseErr) {
                log.warn("Failed to parse Jira LLM JSON: {}", parseErr.getMessage());
            }

            // Step 2: Show approval UI
            String jiraBaseUrl = userConfig.getJiraBaseUrl() != null ? userConfig.getJiraBaseUrl() : "";
            String target = jiraBaseUrl.isEmpty() ? "Jira" : jiraBaseUrl;
            try { target = java.net.URI.create(jiraBaseUrl).getHost(); } catch (Exception ignored) {}

            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest approvalRequest =
                    ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.builder()
                    .toolName("createJiraIssue")
                    .toolDisplayName("Create Jira Issue")
                    .description("Create a new issue in Jira")
                    .target(target)
                    .parameters(java.util.List.of(
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("project")
                                    .displayName("Project")
                                    .type("text")
                                    .value(project)
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("issueType")
                                    .displayName("Issue Type")
                                    .type("dropdown")
                                    .value(issueType)
                                    .optionsEndpoint("/api/ai/tools/jira/issuetypes?project=" + project)
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("summary")
                                    .displayName("Summary")
                                    .type("text")
                                    .value(summary)
                                    .required(true)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("assignee")
                                    .displayName("Assignee")
                                    .type("dropdown")
                                    .value("")
                                    .optionsEndpoint("/api/ai/tools/jira/users")
                                    .required(false)
                                    .build(),
                            ai.inquery.server.domain.core.langchain.tools.ToolApprovalRequest.ToolParameter.builder()
                                    .name("description")
                                    .displayName("Description")
                                    .type("textarea")
                                    .value(description)
                                    .required(true)
                                    .build()
                    ))
                    .build();

            ai.inquery.server.domain.core.langchain.tools.ToolApprovalResponse response =
                    approvalCallback.requestApproval(approvalRequest);

            // Step 3: On approval, create issue directly
            java.util.Map<String, String> params = response.getParameters();
            String finalProject = params != null && params.containsKey("project") ? params.get("project") : project;
            String finalIssueType = params != null && params.containsKey("issueType") ? params.get("issueType") : issueType;
            String finalSummary = params != null && params.containsKey("summary") ? params.get("summary") : summary;
            String finalDescription = params != null && params.containsKey("description") ? params.get("description") : description;
            String finalAssignee = params != null && params.containsKey("assignee") ? params.get("assignee") : null;

            ai.inquery.server.domain.core.impl.JiraService jiraService =
                    new ai.inquery.server.domain.core.impl.JiraService(userConfig);

            log.info("[JiraWrite] Creating issue: project={}, type={}, summary='{}'", finalProject, finalIssueType, finalSummary);
            String issueUrl = jiraService.createIssue(finalProject, finalIssueType, finalSummary, finalDescription, finalAssignee);

            if (issueUrl != null) {
                log.info("[JiraWrite] Issue created: {}", issueUrl);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), true, null);
                return "Issue created successfully: " + issueUrl;
            } else {
                String error = "Failed to create Jira issue";
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return error + ". Please check your Jira credentials and permissions.";
            }

        } catch (ai.inquery.server.domain.core.langchain.tools.ToolApprovalManager.ToolApprovalException e) {
            log.info("[JiraWrite] User denied: {}", e.getMessage());
            return "Issue creation was cancelled by the user.";
        } catch (Exception e) {
            log.error("Jira write failed: {}", e.getMessage(), e);
            return "Failed to create Jira issue: " + e.getMessage();
        }
    }

    /**
     * Generate response based on Jira search results (same pattern as Confluence search).
     */
    public String generateJiraSearchResponse(String userQuery, String jiraContent, String model, String conversationHistory) {
        try {
            dev.langchain4j.model.chat.ChatModel chatModel = modelProvider.getChatModel(model);
            StringBuilder prompt = new StringBuilder();
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                prompt.append("## Conversation History\n").append(conversationHistory).append("\n\n");
            }
            prompt.append("## Jira Search Results\n").append(jiraContent).append("\n\n");
            prompt.append("## User Question\n").append(userQuery).append("\n\n");
            prompt.append("Answer the user's question based on the Jira issues above. ");
            prompt.append("Include issue URLs as references. ");
            prompt.append("If the results don't fully answer the question, say so and include what you found. ");
            prompt.append("Respond in the same language as the user's question.");
            return chatModel.chat(prompt.toString());
        } catch (Exception e) {
            log.error("Failed to generate Jira search response: {}", e.getMessage(), e);
            return "Failed to process Jira search results. Please try again.";
        }
    }

    /**
     * Simple tool calling interface for external service integration.
     * Not the full SupervisorAgent — just LLM + tools.
     * System message is provided dynamically via systemMessageProvider to include exact tool names.
     */
    interface ExternalToolAgent {
        String execute(@dev.langchain4j.service.UserMessage String query);
    }

    /**
     * Clear caches (use when API keys are updated).
     */
    public void clearAllCaches() {
        modelProvider.clearCache();
        log.info("Cleared model provider cache");
    }

}
