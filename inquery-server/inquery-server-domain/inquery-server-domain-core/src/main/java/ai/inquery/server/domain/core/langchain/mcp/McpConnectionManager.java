package ai.inquery.server.domain.core.langchain.mcp;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages MCP server connections based on user's integration credentials.
 * Creates MCP clients for configured services (Slack, Confluence, Jira)
 * and provides a unified ToolProvider for the agent.
 */
@Slf4j
@Component
public class McpConnectionManager {

    /**
     * Build MCP server configs based on user's configured credentials.
     * Only creates configs for services that have valid credentials.
     */
    public List<McpServerConfig> buildConfigs(UserAIConfigSaveParam userConfig) {
        List<McpServerConfig> configs = new ArrayList<>();

        // Slack: handled via direct REST API in SlackService (not MCP)
        // Confluence: handled via direct REST API in ConfluenceService (not MCP)
        // Jira: handled via direct REST API in JiraService (not MCP)


        return configs;
    }

    /**
     * Validate credentials for all configured services by calling lightweight APIs.
     * Returns a map of service key → error message for services that failed validation.
     * Services not configured are not included in the result.
     */
    public Map<String, String> validateCredentials(UserAIConfigSaveParam userConfig) {
        Map<String, String> failures = new LinkedHashMap<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ObjectMapper mapper = new ObjectMapper();

        // Slack: auth.test
        if (userConfig.getSlackUserToken() != null && !userConfig.getSlackUserToken().isBlank()) {
            try {
                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://slack.com/api/auth.test"))
                                .header("Authorization", "Bearer " + userConfig.getSlackUserToken())
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(resp.body());
                if (!root.path("ok").asBoolean(false)) {
                    String error = root.path("error").asText("unknown");
                    failures.put("slack", "Slack authentication failed: " + error
                            + ". Please update your Slack User Token in Settings > Integrations.");
                    log.warn("Slack credential validation failed: {}", error);
                } else {
                    // Check available scopes from response header
                    String scopes = resp.headers().firstValue("x-oauth-scopes").orElse("");
                    log.info("Slack token validated. Scopes: {}", scopes);
                }
            } catch (Exception e) {
                failures.put("slack", "Slack connection failed: " + e.getMessage());
                log.warn("Slack credential validation error: {}", e.getMessage());
            }
        }

        // Confluence: validated directly in ConfluenceService (not MCP)

        // Jira: GET /rest/api/3/myself
        if (userConfig.getJiraApiToken() != null && !userConfig.getJiraApiToken().isBlank()
                && userConfig.getJiraBaseUrl() != null && !userConfig.getJiraBaseUrl().isBlank()) {
            try {
                String baseUrl = userConfig.getJiraBaseUrl().replaceAll("/+$", "");
                String auth = java.util.Base64.getEncoder().encodeToString(
                        (userConfig.getJiraUsername() + ":" + userConfig.getJiraApiToken()).getBytes());
                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/rest/api/3/myself"))
                                .header("Authorization", "Basic " + auth)
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    failures.put("jira", "Jira authentication failed (HTTP " + resp.statusCode()
                            + "). Please check your API token and username in Settings > Integrations.");
                    log.warn("Jira credential validation failed: HTTP {}", resp.statusCode());
                }
            } catch (Exception e) {
                failures.put("jira", "Jira connection failed: " + e.getMessage());
                log.warn("Jira credential validation error: {}", e.getMessage());
            }
        }


        if (failures.isEmpty()) {
            log.info("All configured service credentials validated successfully");
        } else {
            log.warn("Credential validation failures: {}", failures.keySet());
        }

        return failures;
    }

    /**
     * Create MCP clients for all configured services.
     * Used by SlackChatService / DeepAgent where all tools may be needed.
     */
    public McpConnectionResult connect(UserAIConfigSaveParam userConfig) {
        return connect(userConfig, null);
    }

    /**
     * Create MCP clients and return a unified ToolProvider.
     * When targetService is specified, only that service is connected.
     *
     * @param userConfig     user's integration credentials
     * @param targetService  which service to connect ("slack", "confluence", "jira", "github"), or null for all
     * @return MCP connection result with ToolProvider and clients to close
     */
    public McpConnectionResult connect(UserAIConfigSaveParam userConfig, String targetService) {
        List<McpServerConfig> configs = buildConfigs(userConfig);

        // Filter to only the target service if specified
        if (targetService != null && !targetService.isBlank()) {
            configs = configs.stream()
                    .filter(c -> c.getKey().equalsIgnoreCase(targetService))
                    .toList();
            log.info("Filtered MCP configs to target service '{}': {} config(s)", targetService, configs.size());
        }

        if (configs.isEmpty()) {
            log.info("No MCP servers configured for user");
            return new McpConnectionResult(null, List.of());
        }

        List<McpClient> clients = new ArrayList<>();

        for (McpServerConfig config : configs) {
            try {
                McpTransport transport = new StdioMcpTransport.Builder()
                        .command(config.getCommand())
                        .environment(config.getEnvironment())
                        .logEvents(false)
                        .build();

                McpClient client = new DefaultMcpClient.Builder()
                        .key(config.getKey())
                        .transport(transport)
                        .build();

                clients.add(client);
                log.info("MCP server connected: {}", config.getDisplayName());
            } catch (Exception e) {
                log.warn("Failed to connect MCP server {}: {}", config.getKey(), e.getMessage());
            }
        }

        if (clients.isEmpty()) {
            return new McpConnectionResult(null, List.of());
        }

        var mcpBuilder = McpToolProvider.builder()
                .mcpClients(clients);

        // Filter MCP tools to only expose safe read + write operations.
        // Each MCP server uses different naming conventions:
        // - Atlassian (Confluence/Jira): tool_name_get, tool_name_post
        // - Slack: slack_post_message, slack_list_channels, slack_get_channel_history
        // - GitHub: various patterns
        // We allow read and create operations, block update/delete/patch.
        mcpBuilder.filter((client, tool) -> {
            String name = tool.name().toLowerCase();
            // Atlassian naming: allow _get (read) and _post (create)
            if (name.endsWith("_get") || name.endsWith("_post")) {
                return true;
            }
            // Slack naming: allow specific tools
            if (name.startsWith("slack_")) {
                // Allow: post_message, list_channels, get_channel_history, get_thread_replies,
                //        get_users, get_user_profile, search_messages, add_reaction
                // Block: update_message, delete_message, etc.
                return !name.contains("update") && !name.contains("delete");
            }
            return false;
        });

        ToolProvider toolProvider = mcpBuilder.build();

        return new McpConnectionResult(toolProvider, clients);
    }

    /**
     * Extract site name from Atlassian URL.
     * e.g., "https://mycompany.atlassian.net" → "mycompany"
     */
    private String extractSiteName(String baseUrl) {
        if (baseUrl == null) return "";
        String url = baseUrl.replaceFirst("https?://", "");
        int dot = url.indexOf('.');
        return dot > 0 ? url.substring(0, dot) : url;
    }

    /**
     * Result of MCP connection, containing the ToolProvider and clients to close.
     */
    public record McpConnectionResult(
            ToolProvider toolProvider,
            List<McpClient> clients
    ) {
        public boolean hasTools() {
            return toolProvider != null && !clients.isEmpty();
        }

        /**
         * Get the names of all available tools from connected MCP servers.
         * Used to inform the LLM exactly which tools are available.
         */
        public List<String> getToolNames() {
            List<String> names = new java.util.ArrayList<>();
            for (McpClient client : clients) {
                try {
                    client.listTools().stream()
                            .filter(spec -> {
                                String n = spec.name().toLowerCase();
                                // Must match the same filter logic as McpToolProvider.filter()
                                if (n.endsWith("_get") || n.endsWith("_post")) return true;
                                if (n.startsWith("slack_")) return !n.contains("update") && !n.contains("delete");
                                return false;
                            })
                            .forEach(spec -> names.add(spec.name()));
                } catch (Exception e) {
                    // ignore — tool listing may not be supported
                }
            }
            return names;
        }

        public void close() {
            for (McpClient client : clients) {
                try {
                    client.close();
                } catch (Exception e) {
                    // ignore close errors
                }
            }
        }
    }
}
