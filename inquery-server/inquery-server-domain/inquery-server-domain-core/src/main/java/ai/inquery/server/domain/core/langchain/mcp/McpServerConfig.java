package ai.inquery.server.domain.core.langchain.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Configuration for a single MCP server connection.
 */
@Data
@Builder
public class McpServerConfig {

    /**
     * Unique key to identify this MCP server (e.g., "slack", "confluence", "jira").
     */
    private String key;

    /**
     * Display name for UI (e.g., "Slack", "Confluence", "Jira").
     */
    private String displayName;

    /**
     * Command to start the MCP server as a subprocess.
     * e.g., ["npx", "-y", "@modelcontextprotocol/server-slack"]
     */
    private java.util.List<String> command;

    /**
     * Environment variables to pass to the MCP server (credentials, config).
     * e.g., {"SLACK_BOT_TOKEN": "xoxb-..."}
     */
    private Map<String, String> environment;
}
