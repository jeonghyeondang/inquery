
package ai.inquery.server.web.api.controller.ai.claude.client;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 */
@Slf4j
public class ClaudeAIClient {

    public static final String CLAUDE_API_KEY = "claude.apiKey";

    public static final String CLAUDE_API_HOST = "claude.apiHost";

    public static final String CLAUDE_MODEL = "claude.model";

    /**
     * Claude provider enabled flag (system_config). Stored as "true"/"false".
     * Absence is treated as enabled to preserve existing behaviour for users
     * who upgrade without re-saving their AI settings.
     */
    public static final String CLAUDE_ENABLED = "claude.enabled";

    private static ClaudeAiStreamClient CLAUDE_AI_STREAM_CLIENT;

    public static ClaudeAiStreamClient getInstance() {
        if (CLAUDE_AI_STREAM_CLIENT != null) {
            return CLAUDE_AI_STREAM_CLIENT;
        } else {
            return singleton();
        }
    }

    private static ClaudeAiStreamClient singleton() {
        if (CLAUDE_AI_STREAM_CLIENT == null) {
            synchronized (ClaudeAIClient.class) {
                if (CLAUDE_AI_STREAM_CLIENT == null) {
                    refresh();
                }
            }
        }
        return CLAUDE_AI_STREAM_CLIENT;
    }

    public static void refresh() {
        try {
            String apiKey = "";
            String apiHost = "https://api.anthropic.com";
            String model = "claude-sonnet-4-6";
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            Config apiKeyConfig = configService.find(CLAUDE_API_KEY).getData();
            if (apiKeyConfig != null && apiKeyConfig.getContent() != null) {
                apiKey = apiKeyConfig.getContent();
            }
            Config apiHostConfig = configService.find(CLAUDE_API_HOST).getData();
            if (apiHostConfig != null && apiHostConfig.getContent() != null) {
                apiHost = apiHostConfig.getContent();
            }
            Config modelConfig = configService.find(CLAUDE_MODEL).getData();
            if (modelConfig != null && modelConfig.getContent() != null) {
                model = modelConfig.getContent();
            }
            log.info("refresh claude apiKey:{}", maskApiKey(apiKey));
            
            if (StringUtils.isBlank(apiKey)) {
                log.warn("Claude API Key is empty, client will not be initialized");
                CLAUDE_AI_STREAM_CLIENT = null;
                return;
            }
            
            CLAUDE_AI_STREAM_CLIENT = ClaudeAiStreamClient.builder()
                    .apiKey(apiKey)
                    .apiHost(apiHost)
                    .model(model)
                    .build();
            log.info("Claude AI client initialized successfully - apiHost: {}, model: {}", apiHost, model);
        } catch (Exception e) {
            log.error("Error refreshing Claude AI client", e);
            CLAUDE_AI_STREAM_CLIENT = null;
        }
    }

    private static String maskApiKey(String input) {
        if (StringUtils.isBlank(input)) {
            return input;
        }

        StringBuilder maskedString = new StringBuilder(input);
        for (int i = input.length() / 4; i < input.length() / 2; i++) {
            maskedString.setCharAt(i, '*');
        }
        return maskedString.toString();
    }
}
