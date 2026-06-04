package ai.inquery.server.web.api.controller.slack;

import ai.inquery.server.domain.api.param.SystemConfigParam;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Slack integration settings.
 * Handles configuration and connection testing.
 */
@Slf4j
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/slack")
public class SlackController {

    @Autowired
    private ConfigService configService;

    @Autowired
    private SlackService slackService;

    @Autowired
    private SlackBotHandler slackBotHandler;

    /**
     * Get Slack configuration.
     */
    @GetMapping("/config")
    @CrossOrigin
    public DataResult<SlackConfigResponse> getConfig() {
        try {
            SlackConfigResponse config = new SlackConfigResponse();

            // Get all Slack-related config values
            config.setBotToken(getConfigValue(SlackBotHandler.CONFIG_SLACK_BOT_TOKEN));
            config.setAppToken(getConfigValue(SlackBotHandler.CONFIG_SLACK_APP_TOKEN));
            config.setEnabled("true".equalsIgnoreCase(getConfigValue(SlackBotHandler.CONFIG_SLACK_ENABLED)));
            config.setDefaultDataSourceId(getConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_DATASOURCE_ID));
            config.setDefaultDatabase(getConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_DATABASE));
            config.setDefaultSchema(getConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_SCHEMA));
            config.setDefaultModel(getConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_MODEL));
            config.setConnected(slackBotHandler.isRunning());

            // Mask tokens for security
            if (StringUtils.isNotBlank(config.getBotToken())) {
                config.setBotToken(maskToken(config.getBotToken()));
            }
            if (StringUtils.isNotBlank(config.getAppToken())) {
                config.setAppToken(maskToken(config.getAppToken()));
            }

            return DataResult.of(config);
        } catch (Exception e) {
            log.error("[SlackController] Error getting config", e);
            return DataResult.error("SLACK_CONFIG_ERROR", "Failed to get Slack configuration");
        }
    }

    /**
     * Save Slack configuration.
     */
    @PostMapping("/config")
    @CrossOrigin
    public ActionResult saveConfig(@RequestBody SlackConfigRequest request) {
        try {
            // Save Bot Token (only if not masked)
            if (StringUtils.isNotBlank(request.getBotToken()) && !request.getBotToken().contains("***")) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_BOT_TOKEN, request.getBotToken());
            }

            // Save App Token (only if not masked)
            if (StringUtils.isNotBlank(request.getAppToken()) && !request.getAppToken().contains("***")) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_APP_TOKEN, request.getAppToken());
            }

            // Save enabled status
            saveConfigValue(SlackBotHandler.CONFIG_SLACK_ENABLED, String.valueOf(request.isEnabled()));

            // Save default data source settings
            if (request.getDefaultDataSourceId() != null) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_DATASOURCE_ID, request.getDefaultDataSourceId());
            }
            if (request.getDefaultDatabase() != null) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_DATABASE, request.getDefaultDatabase());
            }
            if (request.getDefaultSchema() != null) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_SCHEMA, request.getDefaultSchema());
            }
            if (request.getDefaultModel() != null) {
                saveConfigValue(SlackBotHandler.CONFIG_SLACK_DEFAULT_MODEL, request.getDefaultModel());
            }

            // Restart bot if enabled
            slackBotHandler.restart();

            log.info("[SlackController] Configuration saved successfully");
            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("[SlackController] Error saving config", e);
            return ActionResult.fail("SLACK_CONFIG_ERROR", "Failed to save Slack configuration", e.getMessage());
        }
    }

    /**
     * Test Slack connection with provided tokens.
     */
    @PostMapping("/test")
    @CrossOrigin
    public DataResult<TestConnectionResponse> testConnection(@RequestBody TestConnectionRequest request) {
        TestConnectionResponse response = new TestConnectionResponse();

        try {
            String botToken = request.getBotToken();

            // If token is masked, get from config
            if (StringUtils.isBlank(botToken) || botToken.contains("***")) {
                botToken = getConfigValue(SlackBotHandler.CONFIG_SLACK_BOT_TOKEN);
            }

            if (StringUtils.isBlank(botToken)) {
                response.setSuccess(false);
                response.setMessage("Bot token is required");
                return DataResult.of(response);
            }

            // Validate token prefix
            if (botToken.startsWith("xoxp-")) {
                response.setSuccess(false);
                response.setMessage("Wrong token type: this is a User Token (xoxp-). Bot Token must start with xoxb-.");
                return DataResult.of(response);
            }
            if (!botToken.startsWith("xoxb-")) {
                response.setSuccess(false);
                response.setMessage("Invalid token format. Bot Token must start with xoxb-.");
                return DataResult.of(response);
            }

            boolean success = slackService.testConnection(botToken);
            response.setSuccess(success);
            response.setMessage(success ? "Connection successful!" : "Connection failed. Please check your token.");

            return DataResult.of(response);
        } catch (Exception e) {
            log.error("[SlackController] Error testing connection", e);
            response.setSuccess(false);
            response.setMessage("Connection test failed: " + e.getMessage());
            return DataResult.of(response);
        }
    }

    /**
     * Get bot status.
     */
    @GetMapping("/status")
    @CrossOrigin
    public DataResult<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", slackBotHandler.isRunning());
        status.put("enabled", "true".equalsIgnoreCase(getConfigValue(SlackBotHandler.CONFIG_SLACK_ENABLED)));
        return DataResult.of(status);
    }

    /**
     * Start Slack bot manually.
     */
    @PostMapping("/start")
    @CrossOrigin
    public ActionResult startBot() {
        try {
            slackBotHandler.startIfEnabled();
            return slackBotHandler.isRunning() 
                    ? ActionResult.isSuccess() 
                    : ActionResult.fail("SLACK_START_ERROR", "Failed to start Slack bot", "Check configuration");
        } catch (Exception e) {
            log.error("[SlackController] Error starting bot", e);
            return ActionResult.fail("SLACK_START_ERROR", "Failed to start", e.getMessage());
        }
    }

    /**
     * Stop Slack bot manually.
     */
    @PostMapping("/stop")
    @CrossOrigin
    public ActionResult stopBot() {
        try {
            slackBotHandler.stop();
            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("[SlackController] Error stopping bot", e);
            return ActionResult.fail("SLACK_STOP_ERROR", "Failed to stop", e.getMessage());
        }
    }

    /**
     * Get available LLM models for Slack integration.
     * Only returns models where API key is configured.
     */
    @GetMapping("/available-models")
    @CrossOrigin
    public DataResult<List<AvailableModel>> getAvailableModels() {
        List<AvailableModel> models = new ArrayList<>();

        try {
            // Check OpenAI API key
            String openaiKey = getConfigValue(LangChainModelProvider.OPENAI_KEY);
            if (StringUtils.isNotBlank(openaiKey)) {
                models.add(new AvailableModel("gpt-5.4-mini", "GPT-5.4 mini (OpenAI)"));
            }

            // Check Claude API key
            String claudeKey = getConfigValue(LangChainModelProvider.CLAUDE_KEY);
            if (StringUtils.isNotBlank(claudeKey)) {
                models.add(new AvailableModel("claude-sonnet-4-6", "Claude Sonnet 4.6 (Anthropic)"));
            }

            // Check Gemini API key
            String geminiKey = getConfigValue(LangChainModelProvider.GEMINI_KEY);
            if (StringUtils.isNotBlank(geminiKey)) {
                models.add(new AvailableModel(ModelMapper.getDefaultPrimaryModel(), "Gemini 3.5 Flash (Google)"));
            }

            log.info("[SlackController] Available models: {}", models.size());
            return DataResult.of(models);
        } catch (Exception e) {
            log.error("[SlackController] Error getting available models", e);
            return DataResult.error("MODEL_CHECK_ERROR", "Failed to check available models");
        }
    }

    // Helper methods

    private String getConfigValue(String code) {
        try {
            var result = configService.find(code);
            if (result != null && result.getData() != null) {
                return result.getData().getContent();
            }
        } catch (Exception e) {
            log.warn("[SlackController] Failed to get config: {}", code);
        }
        return null;
    }

    private void saveConfigValue(String code, String value) {
        SystemConfigParam param = new SystemConfigParam();
        param.setCode(code);
        param.setContent(value);
        param.setSummary("Slack integration configuration");
        configService.createOrUpdate(param);
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "***";
        }
        return token.substring(0, 6) + "***" + token.substring(token.length() - 4);
    }

    // Request/Response DTOs

    @Data
    public static class SlackConfigRequest {
        private String botToken;
        private String appToken;
        private boolean enabled;
        private String defaultDataSourceId;
        private String defaultDatabase;
        private String defaultSchema;
        private String defaultModel;
    }

    @Data
    public static class SlackConfigResponse {
        private String botToken;
        private String appToken;
        private boolean enabled;
        private boolean connected;
        private String defaultDataSourceId;
        private String defaultDatabase;
        private String defaultSchema;
        private String defaultModel;
    }

    @Data
    public static class TestConnectionRequest {
        private String botToken;
    }

    @Data
    public static class TestConnectionResponse {
        private boolean success;
        private String message;
    }

    @Data
    public static class AvailableModel {
        private String value;
        private String label;

        public AvailableModel(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }
}
