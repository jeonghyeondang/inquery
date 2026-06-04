package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.core.impl.JiraSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira service for search and issue creation via direct REST API.
 */
public class JiraService extends AbstractSearchService {

    private final String baseUrl;
    private final String username;
    private final String apiToken;

    public JiraService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl(userConfig.getJiraBaseUrl() != null ? userConfig.getJiraBaseUrl() : "")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build());
        this.baseUrl = userConfig.getJiraBaseUrl();
        this.username = userConfig.getJiraUsername();
        this.apiToken = userConfig.getJiraApiToken();
    }

    public JiraService(UserAIConfigSaveParam config) {
        super(WebClient.builder()
            .baseUrl(config.getJiraBaseUrl() != null ? config.getJiraBaseUrl() : "")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build());
        this.baseUrl = config.getJiraBaseUrl();
        this.username = config.getJiraUsername();
        this.apiToken = config.getJiraApiToken();
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiToken != null && !apiToken.isBlank();
    }

    private WebClient buildAuthenticatedClient() {
        String authHeader = (username != null && !username.isEmpty())
                ? username + ":" + apiToken
                : ":" + apiToken;
        String auth = Base64.getEncoder().encodeToString(authHeader.getBytes(StandardCharsets.UTF_8));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Create a Jira issue.
     * @return issue URL (e.g., https://site.atlassian.net/browse/PROJ-123), or null on failure
     */
    public String createIssue(String projectKey, String issueType, String summary,
                               String description, String assigneeId) {
        if (!isConfigured()) {
            logger.warn("Jira credentials not configured for issue creation");
            return null;
        }
        try {
            java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("project", Map.of("key", projectKey));
            fields.put("issuetype", Map.of("name", issueType));
            fields.put("summary", summary);
            if (description != null && !description.isBlank()) {
                fields.put("description", buildAdfDescription(description));
            }
            if (assigneeId != null && !assigneeId.isBlank()) {
                fields.put("assignee", Map.of("id", assigneeId));
            }

            String bodyJson = objectMapper.writeValueAsString(Map.of("fields", fields));
            logger.info("[JiraWrite] Creating issue: project={}, type={}, summary='{}'", projectKey, issueType, summary);

            WebClient client = buildAuthenticatedClient();
            String response = client.post()
                    .uri("/rest/api/3/issue")
                    .bodyValue(bodyJson)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(errorBody -> {
                                        logger.error("Jira issue creation failed (HTTP {}): {}", clientResponse.statusCode().value(), errorBody);
                                        return Mono.error(new RuntimeException("Issue creation failed: " + errorBody));
                                    }))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                String issueKey = root.path("key").asText("");
                String issueUrl = baseUrl + "/browse/" + issueKey;
                logger.info("[JiraWrite] Issue created: {}", issueUrl);
                return issueUrl;
            }
        } catch (Exception e) {
            logger.error("Jira issue creation failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Build ADF (Atlassian Document Format) description from plain text.
     */
    private Map<String, Object> buildAdfDescription(String text) {
        List<Map<String, Object>> contentNodes = new ArrayList<>();
        for (String line : text.split("\n")) {
            contentNodes.add(Map.of(
                    "type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", line))
            ));
        }
        return Map.of("type", "doc", "version", 1, "content", contentNodes);
    }

    /**
     * Search Jira issues by keyword (for chat flow).
     */
    public List<JiraSearchResult> searchByKeyword(String keyword, int maxResults) {
        if (!isConfigured()) return new ArrayList<>();
        try {
            String jql = String.format("summary ~ \"%s\" OR description ~ \"%s\"", keyword, keyword);
            return searchByJql(jql, maxResults);
        } catch (Exception e) {
            logger.error("Jira keyword search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<JiraSearchResult> searchByJql(String jql, int maxResults) {
        List<JiraSearchResult> results = new ArrayList<>();
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("jql", jql);
            requestBody.put("maxResults", maxResults);
            requestBody.put("fields", List.of("summary", "description", "issuetype", "status"));
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            WebClient client = buildAuthenticatedClient();
            String response = client.post()
                    .uri("/rest/api/3/search/jql")
                    .bodyValue(jsonBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(errorBody -> {
                                        logger.error("Jira search failed (HTTP {}): {}", clientResponse.statusCode().value(), errorBody);
                                        return Mono.error(new RuntimeException("Jira search failed"));
                                    }))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode issuesNode = root.path("issues");
                for (JsonNode issue : issuesNode) {
                    String issueKey = issue.path("key").asText("");
                    String summary = issue.path("fields").path("summary").asText("");
                    String desc = "";
                    JsonNode descNode = issue.path("fields").path("description");
                    if (descNode.isTextual()) desc = descNode.asText("");
                    else if (descNode.isObject()) desc = descNode.toString();
                    desc = desc.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    String issueTypeStr = issue.path("fields").path("issuetype").path("name").asText("");
                    String status = issue.path("fields").path("status").path("name").asText("");
                    String url = baseUrl + "/browse/" + issueKey;
                    results.add(new JiraSearchResult(issueKey, summary, desc, url, issueTypeStr, status));
                }
            }
        } catch (Exception e) {
            logger.error("Jira JQL search failed: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Search issues by table name (for AI collection flow).
     * Delegates to searchByKeyword internally.
     */
    public List<JiraSearchResult> searchIssues(String tableName, int maxResults) {
        if (!isConfigured()) {
            logger.warn("Jira credentials not configured for search");
            return new ArrayList<>();
        }
        String searchQuery = extractTableName(tableName);
        logger.info("Jira search started: {} (query: {})", tableName, searchQuery);
        List<JiraSearchResult> results = searchByKeyword(searchQuery, maxResults);
        logger.info("Jira search completed: {} results", results.size());
        return results;
    }
}







