package ai.inquery.server.domain.core.langchain.tools;

import ai.inquery.server.domain.core.impl.ConfluenceService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j tool for creating Confluence pages with user approval.
 * The agent calls this tool, which triggers the approval UI before executing.
 */
@Slf4j
public class ConfluenceWriteTool {

    private final ConfluenceService confluenceService;
    private final ToolApprovalCallback approvalCallback;
    private final String confluenceBaseUrl;
    private final String confluenceUsername;
    private final String confluenceApiToken;

    public ConfluenceWriteTool(ConfluenceService confluenceService,
                               ToolApprovalCallback approvalCallback,
                               String confluenceBaseUrl,
                               String confluenceUsername,
                               String confluenceApiToken) {
        this.confluenceService = confluenceService;
        this.approvalCallback = approvalCallback;
        this.confluenceBaseUrl = confluenceBaseUrl;
        this.confluenceUsername = confluenceUsername;
        this.confluenceApiToken = confluenceApiToken;
    }

    @Tool("Create a new page in Confluence wiki. Content must be in HTML format (Confluence storage format). Use spaceId '1' as placeholder — the user will specify the correct space in the approval UI.")
    public String createConfluencePage(
            @P("Page title") String title,
            @P("Page content in HTML format (Confluence storage format)") String htmlContent) {

        log.info("[ConfluenceWrite] Agent requested page creation: title='{}'", title);

        // Build approval request with editable parameters
        // Default parent page URL: base space URL (user can paste any page URL to create as child)
        String defaultParentUrl = (confluenceBaseUrl != null && !confluenceBaseUrl.isEmpty())
                ? confluenceBaseUrl + "/wiki/spaces/"
                : "";

        ToolApprovalRequest approvalRequest = ToolApprovalRequest.builder()
                .toolName("createConfluencePage")
                .toolDisplayName("Create Confluence Page")
                .description("Create a new page in Confluence")
                .target(confluenceBaseUrl != null ? extractHost(confluenceBaseUrl) : "Confluence")
                .parameters(List.of(
                        ToolApprovalRequest.ToolParameter.builder()
                                .name("parentUrl")
                                .displayName("Parent Page URL")
                                .type("text")
                                .value(defaultParentUrl)
                                .required(true)
                                .build(),
                        ToolApprovalRequest.ToolParameter.builder()
                                .name("title")
                                .displayName("Page Title")
                                .type("text")
                                .value(title)
                                .required(true)
                                .build(),
                        ToolApprovalRequest.ToolParameter.builder()
                                .name("content")
                                .displayName("Content Preview")
                                .type("html")
                                .value(htmlContent)
                                .required(true)
                                .build()
                ))
                .build();

        try {
            ToolApprovalResponse response = approvalCallback.requestApproval(approvalRequest);

            // Apply user modifications
            Map<String, String> params = response.getParameters();
            String finalTitle = params != null && params.containsKey("title") ? params.get("title") : title;
            String finalContent = params != null && params.containsKey("content") ? params.get("content") : htmlContent;
            String finalParentUrl = params != null && params.containsKey("parentUrl") ? params.get("parentUrl") : defaultParentUrl;

            // Extract parent page ID and space key from URL
            String parentId = extractPageId(finalParentUrl);
            String spaceId = resolveSpaceId(finalParentUrl);
            if (spaceId == null) {
                String error = "Could not resolve Space ID from URL: " + finalParentUrl;
                log.warn("[ConfluenceWrite] {}", error);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return "Failed to create page: " + error;
            }

            log.info("[ConfluenceWrite] Creating page: spaceId={}, parentId={}, title='{}'", spaceId, parentId, finalTitle);

            String pageUrl = confluenceService.createPage(spaceId, finalTitle, finalContent, parentId);

            if (pageUrl != null) {
                log.info("[ConfluenceWrite] Page created successfully: {}", pageUrl);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), true, null);
                return "Page created successfully: " + pageUrl;
            } else {
                String error = "Page creation API returned null";
                log.error("[ConfluenceWrite] {}", error);
                approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, error);
                return "Failed to create page. Please check your Confluence credentials and permissions.";
            }

        } catch (ToolApprovalManager.ToolApprovalException e) {
            log.info("[ConfluenceWrite] User denied page creation: {}", e.getMessage());
            return "Page creation was cancelled by the user.";
        } catch (Exception e) {
            log.error("[ConfluenceWrite] Unexpected error: {}", e.getMessage(), e);
            return "Failed to create page: " + e.getMessage();
        }
    }

    /**
     * Resolve a Space URL or Space Key to a numeric Space ID.
     * Accepts: full URL, /wiki/spaces/KEY, or plain space key.
     */
    private String resolveSpaceId(String spaceUrl) {
        if (spaceUrl == null || spaceUrl.isBlank()) return null;

        String spaceKey = null;

        // Try: /wiki/spaces/{SpaceKey}/pages/{PageId}
        Pattern fullPattern = Pattern.compile("/wiki/spaces/([^/]+)/pages/(\\d+)");
        Matcher mFull = fullPattern.matcher(spaceUrl);
        if (mFull.find()) {
            spaceKey = mFull.group(1);
        } else {
            // Try: /wiki/spaces/{SpaceKey}
            Pattern spacePattern = Pattern.compile("/wiki/spaces/([^/]+)");
            Matcher mSpace = spacePattern.matcher(spaceUrl);
            if (mSpace.find()) {
                spaceKey = mSpace.group(1);
            } else if (!spaceUrl.contains("/") && !spaceUrl.contains(".")) {
                spaceKey = spaceUrl.trim();
            }
        }

        if (spaceKey == null) {
            log.warn("[ConfluenceWrite] Cannot parse space key from URL: {}", spaceUrl);
            return null;
        }

        // Resolve Space Key → Space ID via Confluence REST API
        if (confluenceBaseUrl == null || confluenceUsername == null || confluenceApiToken == null) {
            log.warn("[ConfluenceWrite] Confluence credentials not available for space resolution");
            return null;
        }

        try {
            String apiUrl = confluenceBaseUrl + "/wiki/api/v2/spaces?keys=" + spaceKey;
            String auth = Base64.getEncoder().encodeToString(
                    (confluenceUsername + ":" + confluenceApiToken).getBytes());

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(httpResponse.body());
                com.fasterxml.jackson.databind.JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    String resolvedId = results.get(0).path("id").asText();
                    log.info("[ConfluenceWrite] Resolved spaceKey '{}' → spaceId '{}'", spaceKey, resolvedId);
                    return resolvedId;
                }
            }
            log.warn("[ConfluenceWrite] Space resolution failed: HTTP {}", httpResponse.statusCode());
        } catch (Exception e) {
            log.warn("[ConfluenceWrite] Failed to resolve space key '{}': {}", spaceKey, e.getMessage());
        }
        return null;
    }

    /**
     * Extract page ID from a Confluence URL.
     * e.g., /wiki/spaces/KEY/pages/231309342/Page+Title → "231309342"
     * Returns null if no page ID found (space-level URL).
     */
    private String extractPageId(String url) {
        if (url == null || url.isBlank()) return null;
        Pattern pageIdPattern = Pattern.compile("/pages/(\\d+)");
        Matcher m = pageIdPattern.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
