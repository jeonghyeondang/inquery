package ai.inquery.server.domain.core.monitoring;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * AI Call Context - Thread-local context for tracking AI API calls
 * Used for monitoring and auditing AI model invocations
 */
@Slf4j
public class AICallContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> START_TIME = new ThreadLocal<>();

    /**
     * Start a new AI request tracking context
     * @return Generated request ID
     */
    public static String startRequest() {
        String requestId = UUID.randomUUID().toString();
        REQUEST_ID.set(requestId);
        START_TIME.set(System.currentTimeMillis());
        log.debug("Started AI call tracking with requestId: {}", requestId);
        return requestId;
    }

    /**
     * Set the user ID for the current request
     * @param userId User ID
     */
    public static void setUserId(String userId) {
        if (userId != null) {
            USER_ID.set(userId);
        }
    }

    /**
     * Get the current request ID
     * @return Request ID or null if not set
     */
    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    /**
     * Get the current user ID
     * @return User ID or null if not set
     */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * Get the elapsed time since request start
     * @return Elapsed time in milliseconds, or 0 if not started
     */
    public static long getElapsedTime() {
        Long startTime = START_TIME.get();
        if (startTime != null) {
            return System.currentTimeMillis() - startTime;
        }
        return 0;
    }

    /**
     * Clear the current context (call this in finally block or at end of request)
     */
    public static void clear() {
        REQUEST_ID.remove();
        USER_ID.remove();
        START_TIME.remove();
        log.debug("Cleared AI call tracking context");
    }
}
