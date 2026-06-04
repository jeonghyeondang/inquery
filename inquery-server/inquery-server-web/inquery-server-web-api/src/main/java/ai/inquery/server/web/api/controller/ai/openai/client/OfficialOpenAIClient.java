package ai.inquery.server.web.api.controller.ai.openai.client;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Client using Official OpenAI Java SDK
 */
@Slf4j
public class OfficialOpenAIClient {
    private static volatile OfficialOpenAIClient instance;
    private OpenAIClient client;
    private String model = "gpt-5.4-mini";
    private String apiKey;
    private String apiHost;

    private OfficialOpenAIClient() {
        // Private constructor for singleton
    }

    public static OfficialOpenAIClient getInstance() {
        if (instance == null) {
            synchronized (OfficialOpenAIClient.class) {
                if (instance == null) {
                    instance = new OfficialOpenAIClient();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize or refresh the OpenAI client with new configuration
     */
    public void refresh(String apiKey, String apiHost, String model) {
        this.apiKey = apiKey;
        this.apiHost = apiHost != null && !apiHost.isEmpty() ? apiHost : "https://api.openai.com/v1";
        this.model = model != null && !model.isEmpty() ? model : "gpt-5.4-mini";

        log.info("Initializing Official OpenAI Client - Model: {}, API Host: {}", this.model, this.apiHost);

        // Build OpenAI client with custom base URL and organization
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(this.apiKey)
                .organization("org-ZYNH7igKRmGUKdDXdhnRrWUG");  // Explicitly set organization ID

        if (!this.apiHost.equals("https://api.openai.com/v1")) {
            builder.baseUrl(this.apiHost);
        }

        this.client = builder.build();
    }

    /**
     * Stream chat completion using SSE.
     *
     * <p><b>Dual mode by design.</b> When {@code sseEmitter} is non-null
     * we forward each delta to the browser and emit {@code [DONE]} on
     * completion (legacy ChatController SSE path). When {@code sseEmitter}
     * is {@code null} we treat this as a "non-streaming" call: we still
     * consume the SDK's streaming response (the official Java SDK doesn't
     * expose a typed sync chat-completion API in this version) but skip
     * every SSE write and return the accumulated text. Callers that just
     * need a String — {@code AIServiceImpl.generateWithOpenAI}, Supervisor
     * SQL-fix, classifiers, error interpreters — rely on this contract.
     *
     * <p>Prior to 2026-05 a {@code null} emitter would NPE inside the
     * stream callback because every helper assumed SSE was wired. That
     * silently killed every fast-model OpenAI fallback (Slack analysis,
     * SQL fix, deep-research classifier, etc.) with
     * {@code "Cannot invoke \"SseEmitter.send(Object)\" because
     * \"sseEmitter\" is null"}.
     *
     * @return full response text (always — even on error, returns "" so
     *         callers can keep flowing instead of unwrapping an exception)
     */
    public String streamChatCompletion(List<Map<String, String>> messages, SseEmitter sseEmitter) {
        if (client == null) {
            log.error("OpenAI client not initialized. Please call refresh() first.");
            sendError(sseEmitter, "OpenAI client not initialized");
            return "";
        }

        final boolean stream = sseEmitter != null;

        try {
            // Build request using ChatModel.GPT_4_1
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.GPT_4_1);

            // Add messages
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                
                if ("user".equals(role)) {
                    paramsBuilder.addUserMessage(content);
                } else if ("system".equals(role)) {
                    paramsBuilder.addSystemMessage(content);
                } else if ("assistant".equals(role)) {
                    paramsBuilder.addAssistantMessage(content);
                }
            }

            ChatCompletionCreateParams params = paramsBuilder.build();

            log.info("Sending {} request to OpenAI - Model: {}, Messages: {}",
                    stream ? "streaming" : "buffered", model, messages.size());

            // Stream the response using StreamResponse
            StringBuilder fullResponse = new StringBuilder();
            try (StreamResponse<ChatCompletionChunk> streamResponse = 
                    client.chat().completions().createStreaming(params)) {
                
                streamResponse.stream().forEach(chunk -> {
                    try {
                        if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                            ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                            
                            // Send content if present
                            if (choice.delta() != null && choice.delta().content().isPresent()) {
                                String content = choice.delta().content().get();
                                fullResponse.append(content);
                                if (stream) {
                                    String jsonResponse = String.format("{\"content\":\"%s\"}",
                                            escapeJson(content));
                                    sseEmitter.send(jsonResponse);
                                }
                            }
                            
                            // Check if stream is done
                            if (choice.finishReason().isPresent()) {
                                log.info("OpenAI Full Response: {}", fullResponse.toString());
                                if (stream) {
                                    sseEmitter.send("[DONE]");
                                    sseEmitter.complete();
                                }
                            }
                        }
                    } catch (IOException e) {
                        log.error("Error sending SSE event: {}", e.getMessage(), e);
                        sendError(sseEmitter, "Error sending response: " + e.getMessage());
                    }
                });
            }
            
            // Return full response for caching
            String responseText = fullResponse.toString();
            log.info("Returning full response for caching, length: {}", responseText.length());
            return responseText;

        } catch (Exception e) {
            log.error("Error during OpenAI streaming: {}", e.getMessage(), e);
            sendError(sseEmitter, "Error: " + e.getMessage());
            return "";
        }
    }

    private void sendError(SseEmitter sseEmitter, String errorMessage) {
        // Buffered (non-streaming) callers pass null — there's nothing to
        // emit, the error is already logged by the caller.
        if (sseEmitter == null) {
            return;
        }
        try {
            String jsonError = String.format("{\"error\":\"%s\"}", escapeJson(errorMessage));
            sseEmitter.send(jsonError);
            sseEmitter.send("[DONE]");
            sseEmitter.complete();
        } catch (IOException e) {
            log.error("Error sending error message: {}", e.getMessage(), e);
            sseEmitter.completeWithError(e);
        }
    }

    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public String getModel() {
        return model;
    }

    public String getApiHost() {
        return apiHost;
    }

    public String getApiKey() {
        return apiKey;
    }
}

