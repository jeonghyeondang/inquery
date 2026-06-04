package ai.inquery.server.domain.core.langchain.tools;

import lombok.Data;

import java.util.Map;

/**
 * Response from frontend when user approves or denies a tool execution.
 */
@Data
public class ToolApprovalResponse {

    private String requestId;
    private boolean approved;

    /**
     * Modified parameters from the user (may differ from original values).
     * Key: parameter name, Value: user-modified value.
     */
    private Map<String, String> parameters;
}
