package ai.inquery.server.domain.core.langchain.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages pending tool approval requests.
 * Tools block on waitForApproval(), frontend submits via submitApproval().
 */
@Slf4j
@Component
public class ToolApprovalManager {

    private static final long APPROVAL_TIMEOUT_SECONDS = 300; // 5 minutes — user needs time to review and edit params

    private final ConcurrentHashMap<String, CompletableFuture<ToolApprovalResponse>> pendingApprovals
            = new ConcurrentHashMap<>();

    /**
     * Wait for user approval of a tool execution.
     * Blocks the calling thread until the user responds or timeout occurs.
     *
     * @param requestId unique ID matching the ToolApprovalRequest sent to frontend
     * @return user's approval response with possibly modified parameters
     * @throws ToolApprovalException if timeout, denied, or interrupted
     */
    public ToolApprovalResponse waitForApproval(String requestId) throws ToolApprovalException {
        CompletableFuture<ToolApprovalResponse> future = new CompletableFuture<>();
        pendingApprovals.put(requestId, future);

        try {
            ToolApprovalResponse response = future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!response.isApproved()) {
                throw new ToolApprovalException("User denied the action");
            }

            return response;
        } catch (TimeoutException e) {
            throw new ToolApprovalException("Approval timed out after " + APPROVAL_TIMEOUT_SECONDS + " seconds");
        } catch (ToolApprovalException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolApprovalException("Error waiting for approval: " + e.getMessage());
        } finally {
            pendingApprovals.remove(requestId);
        }
    }

    /**
     * Submit user's approval decision. Called from the REST endpoint.
     *
     * @param response user's approval response
     * @return true if a pending request was found and completed
     */
    public boolean submitApproval(ToolApprovalResponse response) {
        CompletableFuture<ToolApprovalResponse> future = pendingApprovals.get(response.getRequestId());
        if (future == null) {
            log.warn("No pending approval found for requestId: {}", response.getRequestId());
            return false;
        }

        future.complete(response);
        log.info("Approval submitted for requestId: {}, approved: {}", response.getRequestId(), response.isApproved());
        return true;
    }

    /**
     * Cancel a pending approval (e.g., when the SSE connection is closed).
     * Completes the future exceptionally so the blocked thread unblocks immediately.
     */
    public void cancelApproval(String requestId) {
        CompletableFuture<ToolApprovalResponse> future = pendingApprovals.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new ToolApprovalException("SSE connection closed by client"));
            log.info("Cancelled pending approval for requestId: {}", requestId);
        }
    }

    /**
     * Cancel all pending approvals (e.g., when stream is shutting down).
     */
    public void cancelAll(java.util.Set<String> requestIds) {
        for (String requestId : requestIds) {
            cancelApproval(requestId);
        }
    }

    public static class ToolApprovalException extends Exception {
        public ToolApprovalException(String message) {
            super(message);
        }
    }
}
