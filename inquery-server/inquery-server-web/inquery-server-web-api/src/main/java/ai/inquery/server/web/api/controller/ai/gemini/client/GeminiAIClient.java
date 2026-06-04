package ai.inquery.server.web.api.controller.ai.gemini.client;

import ai.inquery.server.domain.core.langchain.ModelMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini AI Client (Official SDK)
 * 
 */
@Slf4j
public class GeminiAIClient {
    private static volatile GeminiAIClient instance;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 2000;
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofMinutes(5))
        .writeTimeout(Duration.ofSeconds(30))
        .build();
    
    private String model = ModelMapper.getDefaultPrimaryModel();
    private String apiKey;
    private String apiHost;
    private Client cachedClient;
    private String cachedClientApiKey;

    /**
     * Gemini AI API KEY
     */
    public static final String GEMINI_API_KEY = "gemini.apiKey";

    /**
     * Gemini AI API Host
     */
    public static final String GEMINI_HOST = "gemini.apiHost";

    /**
     * Gemini AI Model
     */
    public static final String GEMINI_MODEL = "gemini.model";

    /**
     * Gemini provider enabled flag (system_config). Stored as "true"/"false".
     * Absence is treated as enabled to preserve existing behaviour for users
     * who upgrade without re-saving their AI settings.
     */
    public static final String GEMINI_ENABLED = "gemini.enabled";

    private GeminiAIClient() {
        // Private constructor for singleton
    }

    public static GeminiAIClient getInstance() {
        if (instance == null) {
            synchronized (GeminiAIClient.class) {
                if (instance == null) {
                    instance = new GeminiAIClient();
                    refreshFromConfig();  // Auto-load config from DB on first call
                }
            }
        }
        return instance;
    }

    /**
     * Refresh client configuration
     */
    public void refresh(String apiKey, String apiHost, String model) {
        this.apiKey = apiKey;
        this.apiHost = apiHost != null && !apiHost.isEmpty() ? apiHost : "generativelanguage.googleapis.com";
        this.model = model != null && !model.isEmpty() ? model : ModelMapper.getDefaultPrimaryModel();
        
        log.info("Gemini Client Refreshed - Model: {}, API Host: {}", this.model, this.apiHost);
    }

    /**
     * Get or create a cached Gemini Client. Recreates only when API key changes.
     */
    private Client getOrCreateClient() {
        if (cachedClient == null || !apiKey.equals(cachedClientApiKey)) {
            cachedClient = Client.builder().apiKey(apiKey).build();
            cachedClientApiKey = apiKey;
            log.info("Gemini SDK Client created (apiKey changed or first use)");
        }
        return cachedClient;
    }

    /**
     * Refresh client configuration from database
     * This ensures latest config is loaded even if server started before config was set
     */
    public static void refreshFromConfig() {
        try {
            ai.inquery.server.domain.api.service.ConfigService configService = 
                ai.inquery.server.web.api.util.ApplicationContextUtil.getBean(
                    ai.inquery.server.domain.api.service.ConfigService.class);
            
            String apiKey = "";
            String apiHost = "";
            String model = "";
            
            ai.inquery.server.domain.api.model.Config apiKeyConfig = configService.find(GEMINI_API_KEY).getData();
            if (apiKeyConfig != null && apiKeyConfig.getContent() != null && !apiKeyConfig.getContent().isEmpty()) {
                apiKey = apiKeyConfig.getContent();
            }
            
            ai.inquery.server.domain.api.model.Config hostConfig = configService.find(GEMINI_HOST).getData();
            if (hostConfig != null && hostConfig.getContent() != null && !hostConfig.getContent().isEmpty()) {
                apiHost = hostConfig.getContent();
            }
            
            ai.inquery.server.domain.api.model.Config modelConfig = configService.find(GEMINI_MODEL).getData();
            if (modelConfig != null && modelConfig.getContent() != null && !modelConfig.getContent().isEmpty()) {
                model = modelConfig.getContent();
            }
            
            getInstance().refresh(apiKey, apiHost, model);
        } catch (Exception e) {
            log.warn("Failed to refresh Gemini client from config: {}", e.getMessage());
        }
    }

    /**
     * Check if Gemini is configured
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiHost() {
        return apiHost;
    }

    /**
     * Stream chat completion using Gemini AI (Official SDK)
     * 
     * @param messages List of conversation messages
     * @param sseEmitter SSE emitter for streaming responses
     * @return Full response text
     */
    public String streamChatCompletion(List<Map<String, String>> messages, SseEmitter sseEmitter) {
        if (apiKey == null || apiKey.isEmpty()) {
            refreshFromConfig();
            if (apiKey == null || apiKey.isEmpty()) {
                log.error("Gemini API key not configured");
                sendError(sseEmitter, "Gemini API key not configured");
                return "";
            }
        }

        List<Content> contents = convertMessagesToContents(messages);
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("Retrying Gemini streaming request (attempt {}/{})", attempt + 1, MAX_RETRIES + 1);
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                Client client = getOrCreateClient();

                log.info("Starting Gemini STREAMING request - Model: {}, Messages: {}, Attempt: {}",
                    model, messages.size(), attempt + 1);

                ResponseStream<GenerateContentResponse> responseStream =
                    client.models.generateContentStream(model, contents, null);

                StringBuilder fullResponse = new StringBuilder();
                int chunkCount = 0;

                for (GenerateContentResponse response : responseStream) {
                    String chunkText = response.text();

                    if (chunkText != null && !chunkText.isEmpty()) {
                        chunkCount++;
                        fullResponse.append(chunkText);

                        String jsonResponse = String.format("{\"content\":\"%s\"}", escapeJson(chunkText));
                        sseEmitter.send(jsonResponse);

                        log.debug("Sent chunk #{}: {} chars", chunkCount, chunkText.length());
                    }
                }

                log.info("Gemini streaming complete - Total chunks: {}, Total chars: {}",
                    chunkCount, fullResponse.length());

                responseStream.close();

                // Do NOT send [DONE] or complete here - let the caller handle SSE completion
                // so it can send proper named events that the frontend expects
                return fullResponse.toString();

            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                boolean isRetryable = msg.contains("Failed to execute HTTP request")
                    || msg.contains("timeout") || msg.contains("Timeout")
                    || msg.contains("connection") || msg.contains("Connection");

                if (!isRetryable || attempt >= MAX_RETRIES) {
                    log.error("Gemini streaming failed (attempt {}): {}", attempt + 1, msg, e);
                    break;
                }
                log.warn("Gemini streaming failed (attempt {}), will retry: {}", attempt + 1, msg);
            }
        }

        sendError(sseEmitter, "Error: " + (lastException != null ? lastException.getMessage() : "Unknown error"));
        return "";
    }

    /**
     * Convert messages to Gemini Content format
     */
    private List<Content> convertMessagesToContents(List<Map<String, String>> messages) {
        List<Content> contents = new ArrayList<>();
        
        for (Map<String, String> msg : messages) {
            String role = msg.get("role");
            String text = msg.get("content");
            
            if (text == null || text.isEmpty()) {
                continue;
            }
            
            // Map roles: "assistant" -> "model", "user" -> "user"
            String geminiRole = "assistant".equals(role) ? "model" : "user";
            
            // Create Part with text
            Part part = Part.builder().text(text).build();
            
            Content content = Content.builder()
                .role(geminiRole)
                .parts(List.of(part))
                .build();
            
            contents.add(content);
        }
        
        return contents;
    }

    private void sendError(SseEmitter sseEmitter, String error) {
        try {
            sseEmitter.send("{\"error\":\"" + escapeJson(error) + "\"}");
            sseEmitter.complete();
        } catch (IOException e) {
            log.error("Error sending error message: {}", e.getMessage());
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Stream chat completion with callback (for token-by-token streaming)
     *
     * @param messages List of messages
     * @param tokenConsumer Consumer to receive each token as it arrives
     * @return Full response text
     */
    public String streamChatCompletionWithCallback(List<Map<String, String>> messages, java.util.function.Consumer<String> tokenConsumer) {
        return streamChatCompletionWithCallback(messages, tokenConsumer, null);
    }

    public String streamChatCompletionWithCallback(List<Map<String, String>> messages, java.util.function.Consumer<String> tokenConsumer, String modelOverride) {
        if (apiKey == null || apiKey.isEmpty()) {
            refreshFromConfig();
            if (apiKey == null || apiKey.isEmpty()) {
                log.error("Gemini API key not configured");
                return "Error: Gemini API key not configured";
            }
        }

        String effectiveModel = (modelOverride != null && !modelOverride.isEmpty()) ? modelOverride : this.model;
        boolean streaming = tokenConsumer != null;
        log.info("Streaming: {}, Model: {}", streaming, effectiveModel);

        List<Content> contents = convertMessagesToContents(messages);
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("Retrying Gemini API call (attempt {}/{})", attempt + 1, MAX_RETRIES + 1);
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                Client client = getOrCreateClient();
                StringBuilder fullResponse = new StringBuilder();

                if (streaming) {
                    ResponseStream<GenerateContentResponse> responseStream =
                        client.models.generateContentStream(effectiveModel, contents, null);

                    int chunkCount = 0;
                    for (GenerateContentResponse response : responseStream) {
                        String chunkText = response.text();

                        if (chunkText != null && !chunkText.isEmpty()) {
                            chunkCount++;
                            log.debug("[Gemini Streaming] Chunk #{}: {} chars", chunkCount, chunkText.length());
                            fullResponse.append(chunkText);
                            tokenConsumer.accept(chunkText);
                        }
                    }
                    log.info("[Gemini Streaming] Total chunks sent: {}", chunkCount);
                    responseStream.close();
                } else {
                    GenerateContentResponse response = client.models.generateContent(effectiveModel, contents, null);
                    String text = response.text();
                    if (text != null) {
                        fullResponse.append(text);
                    }
                }

                return fullResponse.toString();

            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                boolean isRetryable = msg.contains("Failed to execute HTTP request")
                    || msg.contains("timeout") || msg.contains("Timeout")
                    || msg.contains("connection") || msg.contains("Connection");

                if (!isRetryable || attempt >= MAX_RETRIES) {
                    log.error("Gemini API call failed (attempt {}): {}", attempt + 1, msg, e);
                    break;
                }
                log.warn("Gemini API call failed (attempt {}), will retry: {}", attempt + 1, msg);
            }
        }

        return "Error: " + (lastException != null ? lastException.getMessage() : "Unknown error");
    }

    /**
     * Generate embeddings using Gemini text-embedding-004 model
     * Much faster than OpenAI from Asia regions
     * 
     * @param input Text to embed
     * @param outputDimensionality Target dimensions (default 512 to match Pinecone)
     * @return List of BigDecimal embedding values
     */
    public List<BigDecimal> generateEmbedding(String input, int outputDimensionality) {
        if (apiKey == null || apiKey.isEmpty()) {
            // Try refreshing from config in case settings were added after startup
            refreshFromConfig();
            if (apiKey == null || apiKey.isEmpty()) {
                log.error("Gemini API key is not configured for embeddings");
                throw new IllegalStateException("Gemini API key is not configured");
            }
        }

        long startTime = System.currentTimeMillis();
        
        try {
            // Build request body for Gemini embeddings
            Map<String, Object> contentPart = new HashMap<>();
            contentPart.put("text", input);
            
            Map<String, Object> content = new HashMap<>();
            content.put("parts", List.of(contentPart));
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "models/gemini-embedding-001");
            requestBody.put("content", content);
            requestBody.put("outputDimensionality", outputDimensionality);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // Build HTTP request
            String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=%s", apiKey);
            
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();
            
            // Execute request
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error body";
                    log.error("Gemini embeddings API failed: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Gemini embeddings API failed: " + response.code());
                }
                
                String responseBody = response.body().string();
                
                // Parse response
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                Map<String, Object> embedding = (Map<String, Object>) responseMap.get("embedding");
                
                if (embedding == null) {
                    throw new RuntimeException("No embedding data in Gemini response");
                }
                
                List<Double> values = (List<Double>) embedding.get("values");
                if (values == null || values.isEmpty()) {
                    throw new RuntimeException("No embedding values in Gemini response");
                }
                
                // Convert to BigDecimal list
                List<BigDecimal> embeddingBigDecimal = new ArrayList<>();
                for (Double d : values) {
                    embeddingBigDecimal.add(BigDecimal.valueOf(d));
                }
                
                long duration = System.currentTimeMillis() - startTime;
                log.info("Gemini embedding generated in {}ms - {} dimensions", duration, embeddingBigDecimal.size());
                
                return embeddingBigDecimal;
            }
            
        } catch (Exception e) {
            log.error("Failed to call Gemini embeddings API", e);
            throw new RuntimeException("Failed to call Gemini embeddings API: " + e.getMessage(), e);
        }
    }

    /**
     * Generate embeddings with default 512 dimensions (for Pinecone)
     */
    public List<BigDecimal> generateEmbedding(String input) {
        return generateEmbedding(input, 512);
    }
}
