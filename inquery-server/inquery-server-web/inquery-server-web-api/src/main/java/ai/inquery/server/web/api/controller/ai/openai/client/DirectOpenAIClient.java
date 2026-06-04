package ai.inquery.server.web.api.controller.ai.openai.client;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Direct OpenAI API Client for embeddings.
 * Chat completions are handled by OfficialOpenAIClient.
 */
@Slf4j
public class DirectOpenAIClient {

    private static final String OPENAI_KEY = "chatgpt.apiKey";
    private static final String OPENAI_HOST = "chatgpt.apiHost";
    private static final String PROXY_HOST = "chatgpt.proxy.host";
    private static final String PROXY_PORT = "chatgpt.proxy.port";

    private static final String DEFAULT_API_HOST = "https://api.openai.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static DirectOpenAIClient instance;
    private static OkHttpClient httpClient;
    private static String apiKey;
    private static String apiHost;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private DirectOpenAIClient() {
    }

    public static DirectOpenAIClient getInstance() {
        if (instance == null) {
            synchronized (DirectOpenAIClient.class) {
                if (instance == null) {
                    instance = new DirectOpenAIClient();
                    refresh();
                }
            }
        }
        return instance;
    }

    public static void refresh() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);

        // Get API Key
        Config apiKeyConfig = configService.find(OPENAI_KEY).getData();
        if (apiKeyConfig != null) {
            apiKey = apiKeyConfig.getContent();
        } else {
            apiKey = ApplicationContextUtil.getProperty(OPENAI_KEY);
        }

        // Get API Host
        Config apiHostConfig = configService.find(OPENAI_HOST).getData();
        if (apiHostConfig != null) {
            apiHost = apiHostConfig.getContent();
        } else {
            apiHost = ApplicationContextUtil.getProperty(OPENAI_HOST);
        }
        if (StringUtils.isBlank(apiHost)) {
            apiHost = DEFAULT_API_HOST;
        }
        // Remove trailing slash
        if (apiHost.endsWith("/")) {
            apiHost = apiHost.substring(0, apiHost.length() - 1);
        }

        // Get Proxy settings
        String proxyHost = null;
        Integer proxyPort = null;

        Config hostConfig = configService.find(PROXY_HOST).getData();
        if (hostConfig != null) {
            proxyHost = hostConfig.getContent();
        } else {
            proxyHost = System.getProperty("http.proxyHost");
        }

        Config portConfig = configService.find(PROXY_PORT).getData();
        if (portConfig != null && StringUtils.isNotBlank(portConfig.getContent())) {
            proxyPort = Integer.valueOf(portConfig.getContent());
        } else if (Objects.nonNull(System.getProperty("http.proxyPort"))) {
            proxyPort = Integer.valueOf(System.getProperty("http.proxyPort"));
        }

        // Build OkHttpClient
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(5))
            .writeTimeout(Duration.ofMinutes(5));

        if (Objects.nonNull(proxyHost) && Objects.nonNull(proxyPort)) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
            log.info("OpenAI proxy configured: {}:{}", proxyHost, proxyPort);
        }

        httpClient = builder.build();

        log.info("DirectOpenAIClient refreshed - apiHost: {}, apiKey: {}",
            apiHost, maskApiKey(apiKey));
    }

    /**
     * Generate embeddings using OpenAI API
     *
     * @param input Text to embed
     * @return FastChatEmbeddingResponse with embedding vector
     */
    public ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse embeddings(String input) {
        if (StringUtils.isBlank(apiKey)) {
            log.error("OpenAI API key is not configured");
            throw new IllegalStateException("OpenAI API key is not configured");
        }

        try {
            // Build request body for embeddings
            // text-embedding-3-small supports dimensions parameter (default: 1536, can be reduced to 512)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "text-embedding-3-small");
            requestBody.put("input", input);
            requestBody.put("dimensions", 512); // Match Pinecone index dimensions

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            log.debug("OpenAI Embeddings API Request - Input length: {}", input.length());

            // Build HTTP request
            Request request = new Request.Builder()
                .url(apiHost + "/v1/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();

            // Execute request
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("OpenAI embeddings API failed: {}", response.code());
                    throw new RuntimeException("OpenAI embeddings API failed: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("OpenAI Embeddings API Response received");

                // Parse response
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) responseMap.get("data");
                if (data == null || data.isEmpty()) {
                    throw new RuntimeException("No embedding data in response");
                }

                Map<String, Object> firstItem = data.get(0);
                List<Double> embedding = (List<Double>) firstItem.get("embedding");

                // Convert to BigDecimal list
                List<java.math.BigDecimal> embeddingBigDecimal = new java.util.ArrayList<>();
                for (Double d : embedding) {
                    embeddingBigDecimal.add(java.math.BigDecimal.valueOf(d));
                }

                // Create response object
                ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatItem item =
                    ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatItem.builder()
                        .embedding(embeddingBigDecimal)
                        .build();

                List<ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatItem> items =
                    new java.util.ArrayList<>();
                items.add(item);

                return ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse.builder()
                    .data(items)
                    .build();
            }

        } catch (Exception e) {
            log.error("Failed to call OpenAI embeddings API", e);
            throw new RuntimeException("Failed to call OpenAI embeddings API: " + e.getMessage(), e);
        }
    }

    private static String maskApiKey(String input) {
        if (input == null || input.length() < 8) {
            return "***";
        }
        StringBuilder maskedString = new StringBuilder(input);
        for (int i = input.length() / 4; i < input.length() / 2; i++) {
            maskedString.setCharAt(i, '*');
        }
        return maskedString.toString();
    }
}
