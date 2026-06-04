package ai.inquery.server.web.api.controller.ai.claude.client;

import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.web.api.controller.ai.claude.listener.ClaudeAIEventSourceListener;
import ai.inquery.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.inquery.server.web.api.controller.ai.fastchat.model.FastChatRole;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Claude AI Stream Client using Anthropic SDK
 *
 * Based on official Anthropic Java SDK documentation:
 * https://github.com/anthropics/anthropic-sdk-java
 *
 */
@Slf4j
public class ClaudeAiStreamClient {

    @Getter
    private String apiKey;

    @Getter
    private String apiHost;

    @Getter
    private String model;

    private AnthropicClient client;

    private ClaudeAiStreamClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiHost = builder.apiHost;
        this.model = builder.model;
        initializeClient();
    }

    private void initializeClient() {
        try {
            if (StringUtils.isBlank(this.apiKey)) {
                log.error("Cannot initialize Claude AI client: API Key is blank");
                throw new IllegalArgumentException("Claude API Key cannot be blank");
            }

            AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder()
                    .apiKey(this.apiKey);

            // Set baseUrl if custom apiHost is provided (per documentation)
            if (StringUtils.isNotBlank(this.apiHost) && !this.apiHost.equals("https://api.anthropic.com")) {
                clientBuilder.baseUrl(this.apiHost);
                log.info("Using custom base URL: {}", this.apiHost);
            }

            this.client = clientBuilder.build();
            log.info("Claude AI client initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Claude AI client", e);
            throw e;
        }
    }

    public static ClaudeAiStreamClient.Builder builder() {
        return new ClaudeAiStreamClient.Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String apiHost;
        private String model;

        public Builder() {
        }

        public ClaudeAiStreamClient.Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public ClaudeAiStreamClient.Builder apiHost(String apiHost) {
            this.apiHost = apiHost;
            return this;
        }

        public ClaudeAiStreamClient.Builder model(String model) {
            this.model = model;
            return this;
        }

        public ClaudeAiStreamClient build() {
            return new ClaudeAiStreamClient(this);
        }
    }

    /**
     * Convert string model ID to Model enum or use string directly
     * Uses Model.of() which allows any model string (per SDK documentation)
     */
    private Model resolveModel(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            // Default to Claude Sonnet 4
            return Model.CLAUDE_SONNET_4_20250514;
        }

        // Use Model.of() for flexibility with any model ID string
        // This is the recommended approach per SDK documentation for forwards compatibility
        return Model.of(modelId);
    }

    /**
     * Stream completions using Anthropic SDK with MessageAccumulator
     *
     * @param messages List of chat messages
     * @param eventSourceListener Listener for streaming events
     * @param requestedModel Optional model ID from request (overrides default model if provided)
     * @return Full response text accumulated during streaming
     */
    public String streamCompletions(List<FastChatMessage> messages, ClaudeAIEventSourceListener eventSourceListener, String requestedModel) {
        if (Objects.isNull(eventSourceListener)) {
            log.error("param error: ClaudeAIEventSourceListener cannot be empty");
            throw new ParamBusinessException();
        }

        if (messages == null || messages.isEmpty()) {
            log.error("param error: messages cannot be empty");
            throw new ParamBusinessException("messages");
        }

        log.info("Claude AI, prompt: {}", messages.get(messages.size() - 1).getContent());

        try {
            // Determine model - prioritize requestedModel from frontend, then fall back to configured model
            String modelId = StringUtils.isNotBlank(requestedModel)
                ? requestedModel
                : (StringUtils.isNotBlank(this.model) ? this.model : "claude-sonnet-4-6");

            Model model = resolveModel(modelId);
            log.info("Using model - requested: {}, configured: {}, resolved: {}", requestedModel, this.model, model);

            // Create parameters with Model enum (per official documentation)
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4096L);

            // Extract system message and add it separately (Claude API requirement)
            StringBuilder systemPrompt = new StringBuilder();
            for (FastChatMessage fastMsg : messages) {
                if (fastMsg.getRole() == FastChatRole.SYSTEM) {
                    if (systemPrompt.length() > 0) {
                        systemPrompt.append("\n\n");
                    }
                    systemPrompt.append(fastMsg.getContent());
                }
            }

            // Set system prompt if exists
            if (systemPrompt.length() > 0) {
                paramsBuilder.system(systemPrompt.toString());
                log.info("System prompt set: {} chars", systemPrompt.length());
            }

            // Add non-system messages using addUserMessage/addAssistantMessage (per documentation)
            for (FastChatMessage fastMsg : messages) {
                FastChatRole role = fastMsg.getRole();
                String content = fastMsg.getContent();

                if (role == FastChatRole.USER) {
                    paramsBuilder.addUserMessage(content);
                } else if (role == FastChatRole.ASSISTANT) {
                    paramsBuilder.addAssistantMessage(content);
                }
                // System messages already handled above
            }

            // Build params
            MessageCreateParams params = paramsBuilder.build();
            log.info("Claude request built - model: {}", model);

            // Use MessageAccumulator for streaming (per official documentation)
            MessageAccumulator messageAccumulator = MessageAccumulator.create();

            // Stream the response with MessageAccumulator
            try (StreamResponse<RawMessageStreamEvent> streamResponse = client.messages().createStreaming(params)) {
                streamResponse.stream()
                        .peek(messageAccumulator::accumulate)
                        .forEach(event -> {
                            try {
                                eventSourceListener.handleAnthropicEvent(event);
                            } catch (Exception e) {
                                log.error("Error handling Anthropic event", e);
                                eventSourceListener.onFailure(null, e, null);
                            }
                        });
            }

            // Get accumulated message (per documentation)
            Message accumulatedMessage = messageAccumulator.message();
            String fullResponse = extractTextFromMessage(accumulatedMessage);

            log.info("finish invoking claude ai, response length: {}", fullResponse.length());
            return fullResponse;

        } catch (Exception e) {
            log.error("claude ai error", e);
            log.error("Exception details - message: {}, class: {}, cause: {}", e.getMessage(), e.getClass().getName(), e.getCause());
            if (e.getCause() != null) {
                log.error("Cause details - message: {}, class: {}", e.getCause().getMessage(), e.getCause().getClass().getName());
            }
            eventSourceListener.onFailure(null, e, null);
            throw new ParamBusinessException();
        }
    }

    /**
     * Extract text content from accumulated Message
     */
    private String extractTextFromMessage(Message message) {
        if (message == null || message.content() == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        message.content().forEach(block -> {
            block.text().ifPresent(textBlock -> {
                text.append(textBlock.text());
            });
        });
        return text.toString();
    }

    /**
     * Stream completions using Anthropic SDK (backward compatibility - uses configured model)
     *
     * @param messages List of chat messages
     * @param eventSourceListener Listener for streaming events
     * @return Full response text accumulated during streaming
     */
    public String streamCompletions(List<FastChatMessage> messages, ClaudeAIEventSourceListener eventSourceListener) {
        return streamCompletions(messages, eventSourceListener, null);
    }

    /**
     * Stream completions with a simple token callback (no SseEmitter needed).
     * Used by AIServiceImpl.generate() for non-UI contexts like Slack Deep Agent.
     *
     * @param prompt User prompt text
     * @param tokenConsumer Optional callback for each text chunk (can be null)
     * @param requestedModel Model ID to use (falls back to configured model)
     * @return Full response text
     */
    public String streamCompletionsWithCallback(String prompt, java.util.function.Consumer<String> tokenConsumer, String requestedModel) {
        if (prompt == null || prompt.isEmpty()) {
            log.error("param error: prompt cannot be empty");
            return "";
        }

        try {
            String modelId = StringUtils.isNotBlank(requestedModel)
                ? requestedModel
                : (StringUtils.isNotBlank(this.model) ? this.model : "claude-sonnet-4-6");

            Model resolvedModel = resolveModel(modelId);
            log.info("[Claude Callback] Using model: {}", resolvedModel);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(resolvedModel)
                    .maxTokens(4096L)
                    .addUserMessage(prompt)
                    .build();

            StringBuilder fullResponse = new StringBuilder();

            try (StreamResponse<RawMessageStreamEvent> streamResponse = client.messages().createStreaming(params)) {
                streamResponse.stream().forEach(event -> {
                    event.contentBlockDelta().stream().forEach(delta -> {
                        delta.delta().text().ifPresent(textDelta -> {
                            String text = textDelta.text();
                            if (text != null && !text.isEmpty()) {
                                fullResponse.append(text);
                                if (tokenConsumer != null) {
                                    tokenConsumer.accept(text);
                                }
                            }
                        });
                    });
                });
            }

            log.info("[Claude Callback] Completed, response length: {}", fullResponse.length());
            return fullResponse.toString();

        } catch (Exception e) {
            log.error("[Claude Callback] Error", e);
            return "Error: " + e.getMessage();
        }
    }
}
