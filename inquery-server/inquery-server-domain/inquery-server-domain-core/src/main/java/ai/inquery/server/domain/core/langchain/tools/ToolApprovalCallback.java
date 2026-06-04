package ai.inquery.server.domain.core.langchain.tools;

/**
 * Callback interface for tools that require user approval before execution.
 * Implemented by the SSE streaming layer to send approval requests to the frontend
 * and block until the user responds.
 */
public interface ToolApprovalCallback {

    /**
     * Request user approval for a tool action.
     * This method blocks until the user approves, denies, or times out.
     *
     * @param request the approval request with tool details and parameters
     * @return the user's response with possibly modified parameters
     * @throws ToolApprovalManager.ToolApprovalException if denied, timed out, or error
     */
    ToolApprovalResponse requestApproval(ToolApprovalRequest request)
            throws ToolApprovalManager.ToolApprovalException;

    /**
     * Notify the frontend about the tool execution result (success or failure).
     * Called after the tool executes so the approval card can show the outcome.
     *
     * @param requestId the approval request ID
     * @param success whether the tool execution succeeded
     * @param error error message if failed, null if succeeded
     */
    default void notifyToolResult(String requestId, boolean success, String error) {
        // Default no-op for backward compatibility
    }
}
