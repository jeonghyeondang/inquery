package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.core.impl.GitHubSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub search service
 * Adapted from data-category project
 */
public class GitHubService extends AbstractSearchService {

    private final UserAIConfigDO userConfig;

    public GitHubService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl(userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json")
            .build());
        this.userConfig = userConfig;
    }

    public GitHubService(UserAIConfigSaveParam config) {
        this(toDO(config));
    }

    private static UserAIConfigDO toDO(UserAIConfigSaveParam config) {
        UserAIConfigDO d = new UserAIConfigDO();
        d.setGithubToken(config.getGithubToken());
        d.setGithubBaseUrl(config.getGithubBaseUrl());
        d.setGithubOrganization(config.getGithubOrganization());
        return d;
    }

    public boolean isConfigured() {
        return userConfig != null
                && userConfig.getGithubToken() != null
                && !userConfig.getGithubToken().isBlank();
    }

    public List<GitHubSearchResult> searchCode(String tableName, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        if (userConfig.getGithubToken() == null || userConfig.getGithubToken().isEmpty()) {
            logger.warn("GitHub token is not configured.");
            return results;
        }

        try {
            String tableNameOnly = extractTableName(tableName);
            logger.info("GitHub code search started: {} (original: {}, query: {})", tableName, tableNameOnly, tableNameOnly);

            // Pass 1: filename-based search (filename: query) - top 1 only
            logger.info("GitHub pass 1: filename search (filename:{})", tableNameOnly);
            results = searchCodeByFilename(tableNameOnly, 1);
            logger.info("GitHub pass 1 (filename) done: {} results", results.size());
            
            // Pass 2: code content search (fill remaining slots)
            int remainingResults = maxResults - results.size();
            if (remainingResults > 0) {
                logger.info("GitHub pass 2: code search (query: {}, max: {})", tableNameOnly, remainingResults);
                List<GitHubSearchResult> codeResults = searchCodeInternal(tableNameOnly, remainingResults);
                results.addAll(codeResults);
                logger.info("GitHub pass 2 (code) done: {} added", codeResults.size());
            }

            logger.info("GitHub code search complete: {} results", results.size());
        } catch (Exception e) {
            logger.error("Error during GitHub code search", e);
        }

        return results;
    }

    /**
     * Search GitHub code by file name.
     * Uses a filename: query to find files whose names contain the table name.
     */
    private List<GitHubSearchResult> searchCodeByFilename(String tableName, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        try {
            // Build filename search query (filename:)
            StringBuilder query = new StringBuilder();
            query.append("filename:").append(tableName);
            
            if (userConfig.getGithubOrganization() != null && !userConfig.getGithubOrganization().isEmpty()) {
                query.append(" org:").append(userConfig.getGithubOrganization());
            }

            logger.info("GitHub filename search query: {}", query.toString());

            String baseUrl = userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com";

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search/code")
                    .queryParam("q", query.toString())
                    .queryParam("per_page", maxResults)
                    .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> {
                                logger.error("GitHub filename search API error (HTTP {}): {}", statusCode, errorBody);
                                return Mono.error(new RuntimeException("GitHub filename search API failed: " + statusCode));
                            });
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorResume(e -> {
                    logger.error("GitHub filename search API call failed: {}", e.getMessage());
                    return Mono.just("{\"items\":[]}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);

                int totalCount = root.path("total_count").asInt(0);
                logger.info("GitHub filename search response: total_count={}", totalCount);

                JsonNode items = root.path("items");

                for (JsonNode item : items) {
                    String name = item.path("name").asText("");
                    String path = item.path("path").asText("");
                    String htmlUrl = item.path("html_url").asText("");

                    JsonNode repo = item.path("repository");
                    String repository = repo.path("full_name").asText("");

                    String snippet = "";
                    logger.debug("Fetching file content directly (filename search): {}", path);
                    snippet = fetchFileContent(repository, path, baseUrl);

                    if (snippet != null) {
                        snippet = snippet.replaceAll("<[^>]+>", "");
                        snippet = snippet.replaceAll("[ \\t]+", " ");
                        snippet = snippet.replaceAll("\n{3,}", "\n\n");
                        snippet = snippet.trim();
                    }

                    GitHubSearchResult result = new GitHubSearchResult(
                        "code", path + " (" + name + ")", snippet, htmlUrl, repository, "", "", ""
                    );
                    results.add(result);
                }
            }
        } catch (Exception e) {
            logger.error("Error during GitHub filename search", e);
        }

        return results;
    }

    private List<GitHubSearchResult> searchCodeInternal(String searchQuery, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        try {
            String query = buildQuery(searchQuery, "code");

            logger.info("GitHub code search query: {}", query);

            String baseUrl = userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com";

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search/code")
                    .queryParam("q", query)
                    .queryParam("per_page", maxResults)
                    .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> {
                                logger.error("GitHub Code API error (HTTP {}): {}", statusCode, errorBody);
                                return Mono.error(new RuntimeException("GitHub Code API failed: " + statusCode));
                            });
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorResume(e -> {
                    logger.error("GitHub Code API call failed: {}", e.getMessage());
                    return Mono.just("{\"items\":[]}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);

                int totalCount = root.path("total_count").asInt(0);
                logger.info("GitHub code search response: total_count={}", totalCount);

                JsonNode items = root.path("items");
                logger.info("GitHub code search response: items array size={}", items.size());

                if (items.size() == 0 && totalCount > 0) {
                    logger.warn("GitHub code search: total_count={} but items empty. Response snippet: {}",
                        totalCount, response.substring(0, Math.min(500, response.length())));
                }

                for (JsonNode item : items) {
                    String name = item.path("name").asText("");
                    String path = item.path("path").asText("");
                    String htmlUrl = item.path("html_url").asText("");

                    JsonNode repo = item.path("repository");
                    String repository = repo.path("full_name").asText("");

                    String snippet = "";
                    JsonNode textMatches = item.path("text_matches");

                    logger.debug("Fetching file content directly: {}", path);
                    snippet = fetchFileContent(repository, path, baseUrl);

                    if ((snippet == null || snippet.isEmpty()) && textMatches.isArray() && textMatches.size() > 0) {
                        snippet = textMatches.get(0).path("fragment").asText("");
                        logger.debug("Using snippet (fallback): {} (length: {})", path, snippet.length());
                    }

                    if (snippet != null) {
                        snippet = snippet.replaceAll("<[^>]+>", "");
                        snippet = snippet.replaceAll("[ \\t]+", " ");
                        snippet = snippet.replaceAll("\n{3,}", "\n\n");
                        snippet = snippet.trim();
                    }

                    GitHubSearchResult result = new GitHubSearchResult(
                        "code", path + " (" + name + ")", snippet, htmlUrl, repository, "", "", ""
                    );
                    results.add(result);
                }
            }
        } catch (Exception e) {
            logger.error("Error during GitHub code search", e);
        }

        return results;
    }

    private String fetchFileContent(String repository, String path, String baseUrl) {
        try {
            String url = "/repos/" + repository + "/contents/" + path;

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        logger.debug("Failed to fetch file content (HTTP {}): {}", statusCode, path);
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(new RuntimeException("Failed to fetch file content: " + statusCode)));
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    logger.debug("Failed to fetch file content: {}", e.getMessage());
                    return Mono.just("{\"content\":\"\"}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                try {
                    JsonNode root = objectMapper.readTree(response);

                    String encoding = root.path("encoding").asText("");
                    String content = root.path("content").asText("");

                    if (!content.isEmpty()) {
                        if ("base64".equalsIgnoreCase(encoding)) {
                            String cleanContent = content.replaceAll("\\s+", "");
                            byte[] decoded = java.util.Base64.getDecoder().decode(cleanContent);
                            return new String(decoded, StandardCharsets.UTF_8);
                        } else {
                            logger.debug("File content is not base64 (encoding: {}): {}", encoding, path);
                            return content;
                        }
                    }
                } catch (IllegalArgumentException e) {
                    logger.debug("Base64 decode failed (invalid format): {} - {}", path, e.getMessage());
                } catch (Exception e) {
                    logger.debug("File content decode failed: {} - {}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.debug("Error fetching file content: {}", e.getMessage());
        }

        return "";
    }

    private String buildQuery(String searchQuery, String type) {
        StringBuilder query = new StringBuilder();
        query.append(searchQuery);

        if (userConfig.getGithubOrganization() != null && !userConfig.getGithubOrganization().isEmpty()) {
            query.append(" org:").append(userConfig.getGithubOrganization());
        }

        return query.toString();
    }

    private String normalizeSearchQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }

        String normalized = query.replace("_", " ").replace(".", " ");
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized.trim();
    }
}







