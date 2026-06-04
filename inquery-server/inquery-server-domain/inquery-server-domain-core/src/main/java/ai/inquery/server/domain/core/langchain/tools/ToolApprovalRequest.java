package ai.inquery.server.domain.core.langchain.tools;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Represents a tool execution that requires user approval before proceeding.
 * Sent to frontend via SSE "tool_approval" event. The frontend renders a form
 * for the user to review, modify parameters, and approve/deny the action.
 */
@Data
@Builder
public class ToolApprovalRequest {

    /**
     * Unique ID to correlate approval response back to the waiting tool.
     */
    @Builder.Default
    private String requestId = UUID.randomUUID().toString();

    /**
     * Internal tool method name (e.g., "sendSlackMessage").
     */
    private String toolName;

    /**
     * Human-readable label for the UI (e.g., "Send Slack Message").
     */
    private String toolDisplayName;

    /**
     * Human-readable description of the action (e.g., "Update page 'login_uuid' in Confluence").
     */
    private String description;

    /**
     * Target destination (e.g., space name, channel name, repo name).
     * Shown prominently in the approval UI so the user knows WHERE the action goes.
     */
    private String target;

    /**
     * Parameters that the user can review and modify before approving.
     */
    private List<ToolParameter> parameters;

    @Data
    @Builder
    public static class ToolParameter {
        private String name;
        private String displayName;

        /**
         * UI input type: "text", "textarea", "dropdown", "autocomplete"
         * - autocomplete: text input with optional API-backed suggestions.
         *   Falls back to plain text if the API returns empty results (e.g., missing scopes).
         */
        private String type;

        /**
         * Pre-filled value from the LLM agent.
         */
        private String value;

        /**
         * For dropdown type: available options.
         */
        private List<OptionItem> options;

        /**
         * For dropdown type: endpoint to fetch options dynamically.
         * e.g., "/api/ai/tools/slack/channels"
         */
        private String optionsEndpoint;

        private boolean required;
    }

    @Data
    @Builder
    public static class OptionItem {
        private String label;
        private String value;
    }
}
