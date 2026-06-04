package ai.inquery.server.web.api.service.impl;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.AIService;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient;
import ai.inquery.server.web.api.controller.ai.openai.client.OfficialOpenAIClient;
import ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Implementation of AIService that delegates to specific AI clients.
 */
@Service
@Slf4j
public class AIServiceImpl implements AIService {

    @Override
    public String generate(String prompt, String model) {
        log.info("AIService generating with model: {}", model);
        if (model == null) {
            model = "gpt-5.4-mini";
        }

        // Apply prompt repetition for fast models (arxiv 2512.14982)
        prompt = ModelMapper.optimizePrompt(prompt, model);

        if (model.toLowerCase().contains("gpt") || model.toLowerCase().contains("openai")) {
            return generateWithOpenAI(prompt, model);
        } else if (model.toLowerCase().contains("gemini")) {
            return generateWithGemini(prompt, model);
        } else if (model.toLowerCase().contains("claude")) {
            return generateWithClaude(prompt, model);
        } else {
            return generateWithOpenAI(prompt, "gpt-5.4-mini");
        }
    }

    private String generateWithOpenAI(String prompt, String model) {
        try {
            OfficialOpenAIClient client = OfficialOpenAIClient.getInstance();
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            Config apiKeyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
            Config apiHostConfig = configService.find(OpenAIClient.OPENAI_HOST).getData();

            String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
            String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";

            client.refresh(apiKey, apiHost, model);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);

            // OfficialOpenAIClient.streamChatCompletion is dual-mode:
            //   passing a null SseEmitter switches it to "buffered" mode —
            //   it still consumes the SDK's streaming response under the
            //   hood (the SDK doesn't expose a typed sync chat-completion
            //   API in this version) but skips every SSE write and just
            //   returns the accumulated text. See the javadoc on
            //   OfficialOpenAIClient#streamChatCompletion for the
            //   contract and the history of the prior NPE bug.
            return client.streamChatCompletion(messages, null);
        } catch (Exception e) {
            log.error("Error generating with OpenAI", e);
            return "Error: " + e.getMessage();
        }
    }

    private String generateWithGemini(String prompt, String model) {
        try {
            GeminiAIClient client = GeminiAIClient.getInstance();
            ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
            Config apiKeyConfig = configService.find(GeminiAIClient.GEMINI_API_KEY).getData();
            Config apiHostConfig = configService.find(GeminiAIClient.GEMINI_HOST).getData();

            String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
            String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";

            client.refresh(apiKey, apiHost, model);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);

            // Use callback-based method to avoid SSE emitter null issue
            return client.streamChatCompletionWithCallback(messages, null);
        } catch (Exception e) {
            log.error("Error generating with Gemini", e);
            return "Error: " + e.getMessage();
        }
    }

    private String generateWithClaude(String prompt, String model) {
        try {
            ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAiStreamClient client =
                ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.getInstance();
            if (client == null) {
                log.error("Claude AI client not initialized");
                return "Error: Claude AI client not initialized. Please configure the API key.";
            }
            return client.streamCompletionsWithCallback(prompt, null, model);
        } catch (Exception e) {
            log.error("Error generating with Claude", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public String generateWithStreaming(String prompt, String model, Consumer<String> tokenConsumer) {
        log.info("AIService generating with streaming, model: {}", model);
        if (model == null) {
            model = ModelMapper.getDefaultPrimaryModel();
        }

        // Apply prompt repetition for fast models (arxiv 2512.14982)
        prompt = ModelMapper.optimizePrompt(prompt, model);

        if (model.toLowerCase().contains("gemini")) {
            return generateWithGeminiStreaming(prompt, model, tokenConsumer);
        } else if (model.toLowerCase().contains("claude")) {
            return generateWithClaudeStreaming(prompt, model, tokenConsumer);
        } else {
            // For other models, fall back to non-streaming and send all at once
            String result = generate(prompt, model);
            if (tokenConsumer != null && result != null) {
                tokenConsumer.accept(result);
            }
            return result;
        }
    }

    private String generateWithGeminiStreaming(String prompt, String model, Consumer<String> tokenConsumer) {
        try {
            GeminiAIClient client = GeminiAIClient.getInstance();

            try {
                ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
                Config apiKeyConfig = configService.find(GeminiAIClient.GEMINI_API_KEY).getData();
                Config apiHostConfig = configService.find(GeminiAIClient.GEMINI_HOST).getData();

                String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
                String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";

                client.refresh(apiKey, apiHost, model);
            } catch (Exception configEx) {
                log.debug("Config lookup skipped (async thread without SqlSession), using cached client credentials");
            }

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);

            return client.streamChatCompletionWithCallback(messages, tokenConsumer, model);
        } catch (Exception e) {
            log.error("Error generating with Gemini streaming", e);
            return "Error: " + e.getMessage();
        }
    }

    private String generateWithClaudeStreaming(String prompt, String model, Consumer<String> tokenConsumer) {
        try {
            ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAiStreamClient client =
                ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient.getInstance();
            if (client == null) {
                log.error("Claude AI client not initialized");
                return "Error: Claude AI client not initialized. Please configure the API key.";
            }
            return client.streamCompletionsWithCallback(prompt, tokenConsumer, model);
        } catch (Exception e) {
            log.error("Error generating with Claude streaming", e);
            return "Error: " + e.getMessage();
        }
    }
}
