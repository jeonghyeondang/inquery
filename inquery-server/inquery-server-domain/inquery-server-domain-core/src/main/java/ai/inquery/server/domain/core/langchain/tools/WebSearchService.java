package ai.inquery.server.domain.core.langchain.tools;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Web Search Service for Deep Research.
 * Uses each LLM's native web search API (Gemini Google Search, OpenAI web_search, Claude web_search).
 */
@Slf4j
@Service
public class WebSearchService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    @Autowired
    private ConfigService configService;

    private final HttpClient httpClient;

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // Per-provider defaults used only when the caller doesn't pass a
    // concrete model name (e.g. just "openai" / "claude" / "gemini" as a
    // hint). Kept in sync with ChatController / DeepResearchController so
    // every entry-point that triggers a native web search ends up on the
    // same default model family.
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.4-mini";
    private static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6";

    /**
     * Search the web using the LLM's native web search capability.
     * The LLM searches, synthesizes, and returns sources in a single call.
     *
     * <p>{@code modelName} is used both to pick the provider (substring
     * match on {@code "gemini"} / {@code "claude"}, default → OpenAI) and
     * as the actual model passed to that provider's API. If the caller
     * hands us a bare provider hint (e.g. just {@code "openai"}) the
     * per-provider {@code DEFAULT_*_MODEL} fallback kicks in.
     */
    public WebSearchResponse searchWithLLM(String question, String modelName) {
        log.info("Native LLM web search - model: {}, question: {}", modelName, question);

        try {
            if (modelName != null && modelName.toLowerCase().contains("gemini")) {
                return searchWithGemini(question, modelName);
            }
            if (modelName != null && modelName.toLowerCase().contains("claude")) {
                return searchWithClaude(question, modelName);
            }
            return searchWithOpenAI(question, modelName);
        } catch (Exception e) {
            log.error("Native LLM web search failed: {}", e.getMessage(), e);
            WebSearchResponse fallback = new WebSearchResponse();
            fallback.setSynthesizedText("Web search failed: " + e.getMessage());
            fallback.setSources(List.of());
            return fallback;
        }
    }

    /**
     * Check if web search is available (any LLM API key configured).
     */
    public boolean isAvailable() {
        return getConfigValue(LangChainModelProvider.GEMINI_KEY) != null
                || getConfigValue(LangChainModelProvider.OPENAI_KEY) != null
                || getConfigValue(LangChainModelProvider.CLAUDE_KEY) != null;
    }

    // --- Native LLM Web Search Implementations ---

    private WebSearchResponse searchWithGemini(String question, String requestedModel) throws Exception {
        String apiKey = getConfigValue(LangChainModelProvider.GEMINI_KEY);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("Gemini API key not configured");
        }

        // Honor whatever Gemini model the caller passed in (typically the
        // user's pinned `gemini.model`). Bare provider hints like "gemini"
        // or null fall back to the platform default. Empty string is
        // treated the same as null because the Gemini REST URL would 404
        // otherwise.
        String modelToUse;
        if (requestedModel != null && !requestedModel.isBlank()
                && !requestedModel.equalsIgnoreCase("gemini")) {
            modelToUse = requestedModel;
        } else {
            modelToUse = ModelMapper.getDefaultPrimaryModel();
        }

        log.info("Searching with Gemini native Google Search grounding (model={})", modelToUse);

        JSONObject requestBody = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject part = new JSONObject();
        // Prompt repetition only helps fast non-reasoning models (see
        // ModelMapper#optimizePrompt). Pass the actual model so the
        // optimizer can decide instead of unconditionally repeating for
        // a fixed default — repeating on a reasoning model is wasted
        // input tokens with no quality gain.
        String searchPrompt = "Search the web and provide a comprehensive answer with sources for: " + question;
        searchPrompt = ModelMapper.optimizePrompt(searchPrompt, modelToUse);
        part.put("text", searchPrompt);
        parts.add(part);
        content.put("parts", parts);
        content.put("role", "user");
        contents.add(content);
        requestBody.put("contents", contents);

        JSONArray tools = new JSONArray();
        JSONObject googleSearchTool = new JSONObject();
        googleSearchTool.put("google_search", new JSONObject());
        tools.add(googleSearchTool);
        requestBody.put("tools", tools);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelToUse + ":generateContent?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API error: " + response.statusCode() + " - " + response.body());
        }

        JSONObject json = JSON.parseObject(response.body());
        return parseGeminiResponse(json);
    }

    private WebSearchResponse searchWithOpenAI(String question, String requestedModel) throws Exception {
        String apiKey = getConfigValue(LangChainModelProvider.OPENAI_KEY);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OpenAI API key not configured");
        }

        // Use the caller-supplied model (e.g. user's pinned `chatgpt.model`)
        // instead of hard-coding gpt-4o. The Responses API web_search tool
        // is supported on the entire gpt-5* family plus gpt-4o as of
        // 2026-05 (developers.openai.com/api/docs/guides/tools-web-search).
        // Strings that are clearly bare provider hints ("openai" / null /
        // empty) collapse to the platform default.
        String modelToUse;
        if (requestedModel != null && !requestedModel.isBlank()
                && !requestedModel.equalsIgnoreCase("openai")
                && !requestedModel.equalsIgnoreCase("gpt")) {
            modelToUse = requestedModel;
        } else {
            modelToUse = DEFAULT_OPENAI_MODEL;
        }

        log.info("Searching with OpenAI native web search (model={})", modelToUse);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelToUse);
        requestBody.put("input", "Search the web and provide a comprehensive answer with sources for: " + question);

        // Enable web search tool
        JSONArray tools = new JSONArray();
        JSONObject webSearchTool = new JSONObject();
        webSearchTool.put("type", "web_search_preview");
        tools.add(webSearchTool);
        requestBody.put("tools", tools);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }

        JSONObject json = JSON.parseObject(response.body());
        return parseOpenAIResponse(json);
    }

    private WebSearchResponse searchWithClaude(String question, String requestedModel) throws Exception {
        String apiKey = getConfigValue(LangChainModelProvider.CLAUDE_KEY);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("Claude API key not configured");
        }

        // Honor the caller-supplied Claude model (typically `claude.model`).
        // Bare provider hint "claude" or empty/null falls back to the
        // platform default to preserve previous behavior.
        String modelToUse;
        if (requestedModel != null && !requestedModel.isBlank()
                && !requestedModel.equalsIgnoreCase("claude")) {
            modelToUse = requestedModel;
        } else {
            modelToUse = DEFAULT_CLAUDE_MODEL;
        }

        log.info("Searching with Claude native web search (model={})", modelToUse);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelToUse);
        requestBody.put("max_tokens", 4096);

        // Enable web search tool
        JSONArray tools = new JSONArray();
        JSONObject webSearchTool = new JSONObject();
        webSearchTool.put("type", "web_search_20250305");
        webSearchTool.put("name", "web_search");
        webSearchTool.put("max_uses", 5);
        tools.add(webSearchTool);
        requestBody.put("tools", tools);

        // Messages
        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", "Search the web and provide a comprehensive answer with sources for: " + question);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "web-search-2025-03-05")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Claude API error: " + response.statusCode() + " - " + response.body());
        }

        JSONObject json = JSON.parseObject(response.body());
        return parseClaudeResponse(json);
    }

    // --- Response Parsers ---

    private WebSearchResponse parseGeminiResponse(JSONObject json) {
        WebSearchResponse result = new WebSearchResponse();
        List<SearchResult> sources = new ArrayList<>();

        // Extract text from candidates
        StringBuilder text = new StringBuilder();
        JSONArray candidates = json.getJSONArray("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.getJSONObject("content");
            if (content != null) {
                JSONArray parts = content.getJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.size(); i++) {
                        String partText = parts.getJSONObject(i).getString("text");
                        if (partText != null) {
                            text.append(partText);
                        }
                    }
                }
            }

            // Extract sources from groundingMetadata
            JSONObject groundingMetadata = candidate.getJSONObject("groundingMetadata");
            if (groundingMetadata != null) {
                JSONArray groundingChunks = groundingMetadata.getJSONArray("groundingChunks");
                if (groundingChunks != null) {
                    for (int i = 0; i < groundingChunks.size(); i++) {
                        JSONObject chunk = groundingChunks.getJSONObject(i);
                        JSONObject web = chunk.getJSONObject("web");
                        if (web != null) {
                            SearchResult sr = new SearchResult();
                            sr.setTitle(web.getString("title"));
                            sr.setUrl(web.getString("uri"));
                            sr.setSource(extractDomain(web.getString("uri")));
                            sr.setContent("");
                            sr.setScore(1.0 - (i * 0.1));
                            sources.add(sr);
                        }
                    }
                }
            }
        }

        result.setSynthesizedText(text.toString());
        result.setSources(sources);
        return result;
    }

    private WebSearchResponse parseOpenAIResponse(JSONObject json) {
        WebSearchResponse result = new WebSearchResponse();
        List<SearchResult> sources = new ArrayList<>();

        StringBuilder text = new StringBuilder();
        JSONArray output = json.getJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                String type = item.getString("type");

                if ("message".equals(type)) {
                    JSONArray contentArr = item.getJSONArray("content");
                    if (contentArr != null) {
                        for (int j = 0; j < contentArr.size(); j++) {
                            JSONObject contentItem = contentArr.getJSONObject(j);
                            if ("output_text".equals(contentItem.getString("type"))) {
                                text.append(contentItem.getString("text"));

                                // Extract annotations (sources)
                                JSONArray annotations = contentItem.getJSONArray("annotations");
                                if (annotations != null) {
                                    for (int k = 0; k < annotations.size(); k++) {
                                        JSONObject ann = annotations.getJSONObject(k);
                                        if ("url_citation".equals(ann.getString("type"))) {
                                            SearchResult sr = new SearchResult();
                                            sr.setTitle(ann.getString("title"));
                                            sr.setUrl(ann.getString("url"));
                                            sr.setSource(extractDomain(ann.getString("url")));
                                            sr.setContent("");
                                            sr.setScore(1.0 - (k * 0.05));
                                            // Deduplicate by URL
                                            if (sources.stream().noneMatch(s -> s.getUrl() != null && s.getUrl().equals(sr.getUrl()))) {
                                                sources.add(sr);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        result.setSynthesizedText(text.toString());
        result.setSources(sources);
        return result;
    }

    private WebSearchResponse parseClaudeResponse(JSONObject json) {
        WebSearchResponse result = new WebSearchResponse();
        List<SearchResult> sources = new ArrayList<>();

        StringBuilder text = new StringBuilder();
        JSONArray content = json.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.getJSONObject(i);
                String type = block.getString("type");

                if ("text".equals(type)) {
                    text.append(block.getString("text"));
                } else if ("web_search_tool_result".equals(type)) {
                    // Extract sources from web search results
                    JSONArray searchContent = block.getJSONArray("content");
                    if (searchContent != null) {
                        for (int j = 0; j < searchContent.size(); j++) {
                            JSONObject item = searchContent.getJSONObject(j);
                            if ("web_search_result".equals(item.getString("type"))) {
                                SearchResult sr = new SearchResult();
                                sr.setTitle(item.getString("title"));
                                sr.setUrl(item.getString("url"));
                                sr.setSource(extractDomain(item.getString("url")));
                                sr.setContent("");
                                sr.setScore(1.0 - (j * 0.05));
                                // Deduplicate by URL
                                if (sources.stream().noneMatch(s -> s.getUrl() != null && s.getUrl().equals(sr.getUrl()))) {
                                    sources.add(sr);
                                }
                            }
                        }
                    }
                }
            }
        }

        result.setSynthesizedText(text.toString());
        result.setSources(sources);
        return result;
    }

    // --- Helper methods ---

    private String getConfigValue(String key) {
        try {
            DataResult<Config> result = configService.find(key);
            if (result != null && result.success() && result.getData() != null) {
                return result.getData().getContent();
            }
        } catch (Exception e) {
            log.debug("Config not found: {}", key);
        }
        return null;
    }

    private String extractDomain(String url) {
        if (url == null || url.isEmpty()) return "Unknown";
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                return host.replaceFirst("^www\\.", "");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }

    // --- Data classes ---

    @Data
    public static class WebSearchResponse {
        private String synthesizedText;
        private List<SearchResult> sources;
    }

    @Data
    public static class SearchResult {
        private String title;
        private String content;
        private String url;
        private String source;
        private double score;

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("### ").append(title != null ? title : "Result").append("\n");
            if (source != null && !source.isEmpty()) {
                sb.append("*Source: ").append(source).append("*\n\n");
            }
            if (content != null && !content.isEmpty()) {
                sb.append(content).append("\n\n");
            }
            if (url != null && !url.isEmpty()) {
                sb.append("[Read more](").append(url).append(")\n");
            }
            return sb.toString();
        }
    }
}
