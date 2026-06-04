package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence REST API service for search and page creation.
 * Used by AI collection (DataCatalogServiceImpl) and agent chat (QueryProcessingServiceImpl).
 */
public class ConfluenceService extends AbstractSearchService {

    private final String baseUrl;
    private final String username;
    private final String apiToken;

    public ConfluenceService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl(userConfig.getConfluenceBaseUrl() != null ? userConfig.getConfluenceBaseUrl() : "")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build());
        this.baseUrl = userConfig.getConfluenceBaseUrl();
        this.username = userConfig.getConfluenceUsername();
        this.apiToken = userConfig.getConfluenceApiToken();
    }

    public ConfluenceService(UserAIConfigSaveParam userConfig) {
        super(WebClient.builder()
            .baseUrl(userConfig.getConfluenceBaseUrl() != null ? userConfig.getConfluenceBaseUrl() : "")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build());
        this.baseUrl = userConfig.getConfluenceBaseUrl();
        this.username = userConfig.getConfluenceUsername();
        this.apiToken = userConfig.getConfluenceApiToken();
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiToken != null && !apiToken.isBlank();
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Search Confluence pages using siteSearch CQL via /wiki/rest/api/search.
     * This uses the same Elasticsearch-based search engine as the Confluence UI,
     * providing fuzzy matching, underscore tokenization, and relevance ranking.
     */
    public List<WikiSearchResult> searchPages(String query, int maxResults) {
        List<WikiSearchResult> results = new ArrayList<>();

        if (!isConfigured()) {
            logger.warn("Confluence credentials not configured");
            return results;
        }

        try {
            logger.info("Confluence search started: query='{}'", query);

            String cql = "type=page AND siteSearch ~ \"" + escapeCql(query) + "\"";
            logger.info("Confluence siteSearch CQL: {}", cql);

            results = searchBySiteSearch(cql, maxResults);
            logger.info("Confluence siteSearch: {} result(s)", results.size());
        } catch (Exception e) {
            logger.error("Confluence search failed", e);
        }

        return results;
    }

    /**
     * Search using /wiki/rest/api/search (siteSearch).
     * Uses the same Elasticsearch engine as the Confluence UI.
     * Response structure: results[].content (page info) + results[].excerpt (text snippet).
     */
    private List<WikiSearchResult> searchBySiteSearch(String cql, int limit) {
        List<WikiSearchResult> results = new ArrayList<>();
        try {
            WebClient client = buildAuthenticatedClient();
            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wiki/rest/api/search")
                            .queryParam("cql", cql)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleErrorResponse)
                    .bodyToMono(String.class)
                    .transform(this::withTimeoutAndRetry)
                    .onErrorResume(e -> {
                        logger.error("Confluence siteSearch API call failed: {}", e.getMessage());
                        return Mono.just("{\"results\":[]}");
                    })
                    .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);
                for (JsonNode item : root.path("results")) {
                    JsonNode content = item.path("content");
                    if (content.isMissingNode()) continue;

                    String pageId = content.path("id").asText("");
                    String title = content.path("title").asText("");
                    String relativeUrl = content.path("_links").path("webui").asText("");

                    String url = relativeUrl;
                    if (!relativeUrl.isEmpty() && !relativeUrl.startsWith("http")) {
                        url = baseUrl + (relativeUrl.startsWith("/wiki") ? "" : "/wiki") + relativeUrl;
                    }

                    // Use excerpt from search result (HTML snippet)
                    String excerpt = item.path("excerpt").asText("");
                    if (!excerpt.isEmpty()) {
                        excerpt = excerpt.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    }

                    results.add(new WikiSearchResult(pageId, title, excerpt, url));
                }
            }
        } catch (Exception e) {
            logger.error("Confluence siteSearch failed: cql='{}', error={}", cql, e.getMessage());
        }
        return results;
    }

    /**
     * Search using raw CQL query with body expansion.
     */
    private List<WikiSearchResult> searchByCql(String cql, int limit) {
        List<WikiSearchResult> results = new ArrayList<>();
        try {
            WebClient client = buildAuthenticatedClient();
            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wiki/rest/api/content/search")
                            .queryParam("cql", cql)
                            .queryParam("limit", limit)
                            .queryParam("expand", "body.storage,body.view,version")
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleErrorResponse)
                    .bodyToMono(String.class)
                    .transform(this::withTimeoutAndRetry)
                    .onErrorResume(e -> {
                        logger.error("Confluence API call failed: {}", e.getMessage());
                        return Mono.just("{\"results\":[]}");
                    })
                    .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);
                for (JsonNode item : root.path("results")) {
                    results.add(parseSearchResult(item));
                }
            }
        } catch (Exception e) {
            logger.error("Confluence CQL search failed: cql='{}', error={}", cql, e.getMessage());
        }
        return results;
    }

    // ── Fetch page content ───────────────────────────────────────────────

    /**
     * Fetch full page content by page ID using v2 API.
     * Returns content as markdown (converted from HTML storage format).
     */
    public String fetchPageContent(String pageId) {
        if (!isConfigured() || pageId == null || pageId.isBlank()) {
            return "";
        }

        try {
            WebClient client = buildAuthenticatedClient();
            String response = client.get()
                    .uri("/wiki/api/v2/pages/" + pageId + "?body-format=storage")
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleErrorResponse)
                    .bodyToMono(String.class)
                    .transform(this::withTimeoutAndRetry)
                    .onErrorResume(e -> {
                        logger.error("Confluence page fetch failed: pageId={}, error={}", pageId, e.getMessage());
                        return Mono.just("{}");
                    })
                    .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);
                String html = root.path("body").path("storage").path("value").asText("");
                if (!html.isEmpty()) {
                    String content = convertHtmlToMarkdown(html);
                    // Truncate to avoid overwhelming LLM context
                    if (content.length() > 5000) {
                        content = content.substring(0, 5000) + "\n\n...(truncated)";
                    }
                    return content;
                }
            }
        } catch (Exception e) {
            logger.error("Confluence page fetch failed: pageId={}", pageId, e);
        }
        return "";
    }

    // ── Create page ──────────────────────────────────────────────────────

    /**
     * Create a new Confluence page using v2 API.
     *
     * @param spaceId   numeric space ID
     * @param title     page title
     * @param htmlBody  page content in Confluence storage format (HTML)
     * @return created page URL, or null on failure
     */
    public String createPage(String spaceId, String title, String htmlBody, String parentId) {
        if (!isConfigured()) {
            logger.warn("Confluence credentials not configured for page creation");
            return null;
        }

        try {
            java.util.LinkedHashMap<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("spaceId", spaceId);
            bodyMap.put("status", "current");
            bodyMap.put("title", title);
            if (parentId != null && !parentId.isBlank()) {
                bodyMap.put("parentId", parentId);
            }
            bodyMap.put("body", new java.util.LinkedHashMap<String, Object>() {{
                put("representation", "storage");
                put("value", htmlBody);
            }});
            String bodyJson = objectMapper.writeValueAsString(bodyMap);

            WebClient client = buildAuthenticatedClient();
            String response = client.post()
                    .uri("/wiki/api/v2/pages")
                    .bodyValue(bodyJson)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> {
                                int statusCode = clientResponse.statusCode().value();
                                return clientResponse.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            logger.error("Confluence page creation failed (HTTP {}): {}", statusCode, errorBody);
                                            return Mono.error(new RuntimeException("Page creation failed: HTTP " + statusCode + " - " + errorBody));
                                        });
                            })
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                String pageId = root.path("id").asText("");
                String pageTitle = root.path("title").asText(title);
                String webUrl = root.path("_links").path("webui").asText("");

                String fullUrl = webUrl;
                if (!webUrl.isEmpty() && !webUrl.startsWith("http")) {
                    // Confluence API returns paths like /spaces/KEY/... without /wiki prefix
                    String prefix = webUrl.startsWith("/wiki") ? "" : "/wiki";
                    fullUrl = baseUrl + prefix + webUrl;
                }

                logger.info("Confluence page created: id={}, title='{}', url={}", pageId, pageTitle, fullUrl);
                return fullUrl;
            }
        } catch (Exception e) {
            logger.error("Confluence page creation failed", e);
        }
        return null;
    }

    /**
     * Resolve a Space Key to a numeric Space ID via Confluence REST API.
     */
    public String resolveSpaceId(String spaceKey) {
        if (!isConfigured() || spaceKey == null || spaceKey.isBlank()) return null;
        try {
            WebClient client = buildAuthenticatedClient();
            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wiki/api/v2/spaces")
                            .queryParam("keys", spaceKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();
            if (response != null) {
                JsonNode results = objectMapper.readTree(response).path("results");
                if (results.isArray() && results.size() > 0) {
                    String id = results.get(0).path("id").asText();
                    logger.info("Resolved spaceKey '{}' → spaceId '{}'", spaceKey, id);
                    return id;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve space key '{}': {}", spaceKey, e.getMessage());
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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

    private WikiSearchResult parseSearchResult(JsonNode item) {
        String pageId = item.path("id").asText("");
        String title = item.path("title").asText("");
        String relativeUrl = item.path("_links").path("webui").asText("");

        String url = relativeUrl;
        if (!relativeUrl.isEmpty() && !relativeUrl.startsWith("http")) {
            url = baseUrl + (relativeUrl.startsWith("/wiki") ? "" : "/wiki") + relativeUrl;
        }

        String content = "";
        if (item.has("body")) {
            JsonNode bodyNode = item.path("body");
            if (bodyNode.has("storage")) {
                String htmlContent = bodyNode.path("storage").path("value").asText("");
                if (!htmlContent.isEmpty()) {
                    content = convertHtmlToMarkdown(htmlContent);
                }
            }
            if (content.isEmpty() && bodyNode.has("view")) {
                String viewContent = bodyNode.path("view").path("value").asText("");
                if (!viewContent.isEmpty()) {
                    content = convertHtmlToMarkdown(viewContent);
                }
            }
        }

        // Truncate content for search results
        if (content.length() > 3000) {
            content = content.substring(0, 3000) + "\n\n...(truncated)";
        }

        return new WikiSearchResult(pageId, title, url, content);
    }

    private String escapeCql(String value) {
        if (value == null) return "";
        return value.replace("\"", "\\\"").replace("'", "\\'");
    }

    private String convertHtmlToMarkdown(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        String markdown = html;

        Pattern macroPattern = Pattern.compile(
            "<ac:structured-macro[^>]*>.*?<ac:rich-text-body>(.*?)</ac:rich-text-body>.*?</ac:structured-macro>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        Matcher macroMatcher = macroPattern.matcher(markdown);
        StringBuffer macroReplacement = new StringBuffer();
        while (macroMatcher.find()) {
            String bodyContent = macroMatcher.group(1);
            macroMatcher.appendReplacement(macroReplacement, Matcher.quoteReplacement(bodyContent));
        }
        macroMatcher.appendTail(macroReplacement);
        markdown = macroReplacement.toString();

        markdown = markdown.replaceAll("<ac:parameter[^>]*>", "");
        markdown = markdown.replaceAll("</ac:parameter>", "");
        markdown = markdown.replaceAll("<ac:link[^>]*>", "");
        markdown = markdown.replaceAll("</ac:link>", "");
        markdown = markdown.replaceAll("<ac:image[^>]*>.*?</ac:image>", "");
        markdown = markdown.replaceAll("<ac:[^>]+>", "");
        markdown = markdown.replaceAll("</ac:[^>]+>", "");

        markdown = markdown.replaceAll("<h1[^>]*>(.*?)</h1>", "# $1\n\n");
        markdown = markdown.replaceAll("<h2[^>]*>(.*?)</h2>", "## $1\n\n");
        markdown = markdown.replaceAll("<h3[^>]*>(.*?)</h3>", "### $1\n\n");
        markdown = markdown.replaceAll("<h4[^>]*>(.*?)</h4>", "#### $1\n\n");
        markdown = markdown.replaceAll("<h5[^>]*>(.*?)</h5>", "##### $1\n\n");
        markdown = markdown.replaceAll("<h6[^>]*>(.*?)</h6>", "###### $1\n\n");

        markdown = markdown.replaceAll("<strong[^>]*>(.*?)</strong>", "**$1**");
        markdown = markdown.replaceAll("<b[^>]*>(.*?)</b>", "**$1**");

        markdown = markdown.replaceAll("<em[^>]*>(.*?)</em>", "*$1*");
        markdown = markdown.replaceAll("<i[^>]*>(.*?)</i>", "*$1*");

        markdown = markdown.replaceAll("<code[^>]*>(.*?)</code>", "`$1`");
        markdown = markdown.replaceAll("<tt[^>]*>(.*?)</tt>", "`$1`");

        markdown = markdown.replaceAll("<pre[^>]*>(.*?)</pre>", "```\n$1\n```");

        markdown = markdown.replaceAll("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");

        markdown = markdown.replaceAll("<li[^>]*>(.*?)</li>", "- $1\n");
        markdown = markdown.replaceAll("<ul[^>]*>", "");
        markdown = markdown.replaceAll("</ul>", "\n");
        markdown = markdown.replaceAll("<ol[^>]*>", "");
        markdown = markdown.replaceAll("</ol>", "\n");

        markdown = convertTableToMarkdown(markdown);

        markdown = markdown.replaceAll("<br[^>]*/?>", "\n");
        markdown = markdown.replaceAll("<p[^>]*>", "");
        markdown = markdown.replaceAll("</p>", "\n\n");

        markdown = markdown.replaceAll("<blockquote[^>]*>(.*?)</blockquote>", "> $1\n");

        markdown = markdown.replaceAll("<hr[^>]*/?>", "\n---\n");

        markdown = markdown.replaceAll("<[^>]+>", "");

        markdown = markdown.replace("&nbsp;", " ");
        markdown = markdown.replace("&amp;", "&");
        markdown = markdown.replace("&lt;", "<");
        markdown = markdown.replace("&gt;", ">");
        markdown = markdown.replace("&quot;", "\"");
        markdown = markdown.replace("&#39;", "'");

        markdown = markdown.replaceAll("[ \\t]+", " ");
        markdown = markdown.replaceAll("\n{3,}", "\n\n");
        markdown = markdown.trim();

        return markdown;
    }

    private String convertTableToMarkdown(String html) {
        if (html == null || !html.contains("<table")) {
            return html;
        }

        StringBuilder result = new StringBuilder();
        int lastIndex = 0;

        Pattern tablePattern = Pattern.compile(
            "<table[^>]*>.*?</table>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = tablePattern.matcher(html);

        while (matcher.find()) {
            result.append(html, lastIndex, matcher.start());

            String tableHtml = matcher.group();
            StringBuilder tableMarkdown = new StringBuilder("\n\n");

            Pattern rowPattern = Pattern.compile(
                "<tr[^>]*>(.*?)</tr>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            Matcher rowMatcher = rowPattern.matcher(tableHtml);

            List<List<String>> allRows = new ArrayList<>();
            int maxColumns = 0;

            while (rowMatcher.find()) {
                String row = rowMatcher.group(1);
                List<String> cells = new ArrayList<>();

                Pattern cellPattern = Pattern.compile(
                    "<t[dh][^>]*>(.*?)</t[dh]>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE
                );
                Matcher cellMatcher = cellPattern.matcher(row);

                while (cellMatcher.find()) {
                    String cellContent = cellMatcher.group(1);
                    cellContent = cleanCellContent(cellContent);
                    cells.add(cellContent);
                }

                if (!cells.isEmpty()) {
                    allRows.add(cells);
                    maxColumns = Math.max(maxColumns, cells.size());
                }
            }

            if (!allRows.isEmpty()) {
                List<String> headers = allRows.get(0);

                tableMarkdown.append("| ");
                for (int i = 0; i < maxColumns; i++) {
                    String header = (i < headers.size()) ? headers.get(i) : "";
                    tableMarkdown.append(header).append(" | ");
                }
                tableMarkdown.append("\n");

                tableMarkdown.append("| ");
                for (int i = 0; i < maxColumns; i++) {
                    tableMarkdown.append("--- | ");
                }
                tableMarkdown.append("\n");

                for (int rowIdx = 1; rowIdx < allRows.size(); rowIdx++) {
                    List<String> cells = allRows.get(rowIdx);
                    tableMarkdown.append("| ");
                    for (int i = 0; i < maxColumns; i++) {
                        String cell = (i < cells.size()) ? cells.get(i) : "";
                        tableMarkdown.append(cell).append(" | ");
                    }
                    tableMarkdown.append("\n");
                }
            }

            tableMarkdown.append("\n");
            result.append(tableMarkdown);

            lastIndex = matcher.end();
        }

        result.append(html.substring(lastIndex));

        return result.toString();
    }

    private String cleanCellContent(String content) {
        if (content == null) {
            return "";
        }

        content = content.replaceAll("<br[^>]*/?>", " ");
        content = content.replaceAll("<p[^>]*>", " ");
        content = content.replaceAll("</p>", " ");

        content = content.replaceAll("<[^>]+>", "");

        content = content.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'");

        content = content.replaceAll("\\s+", " ").trim();

        return content;
    }
}
