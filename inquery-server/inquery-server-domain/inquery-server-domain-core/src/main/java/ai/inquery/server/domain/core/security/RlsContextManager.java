package ai.inquery.server.domain.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Manages Row-Level Security (RLS) context injection as per system_architecture.md section 7.2
 * Injects user context into database session using GETVARIABLE pattern
 */
@Slf4j
@Component
public class RlsContextManager {

    /**
     * Generate SQL to set session variable for RLS
     * @param userId User identifier to inject into session
     * @return SQL statement to set session variable
     */
    public String getSetSessionVariableSql(String userId) {
        // Sanitize userId to prevent injection
        String sanitizedUserId = sanitize(userId);
        return String.format("ALTER SESSION SET APP_USER_ID = '%s'", sanitizedUserId);
    }

    /**
     * Generate SQL to unset session variable
     * CRITICAL: Must be called when returning connection to pool to prevent session bleeding
     * @return SQL statement to unset session variable
     */
    public String getUnsetSessionVariableSql() {
        return "UNSET APP_USER_ID";
    }
    
    /**
     * Inject user context into database connection
     * Should be called before executing user queries
     * @param connection Database connection
     * @param userId User identifier
     */
    public void injectUserContext(Connection connection, String userId) {
        if (connection == null || userId == null) {
            log.warn("Cannot inject user context: connection or userId is null");
            return;
        }
        
        try (Statement stmt = connection.createStatement()) {
            String sql = getSetSessionVariableSql(userId);
            stmt.execute(sql);
            log.info("User context injected: userId={}", userId);
        } catch (Exception e) {
            log.error("Failed to inject user context for userId={}", userId, e);
            throw new RuntimeException("Failed to set RLS context", e);
        }
    }
    
    /**
     * Clear user context from database connection
     * MUST be called before returning connection to pool
     * @param connection Database connection
     */
    public void clearUserContext(Connection connection) {
        if (connection == null) {
            return;
        }
        
        try (Statement stmt = connection.createStatement()) {
            String sql = getUnsetSessionVariableSql();
            stmt.execute(sql);
            log.debug("User context cleared from connection");
        } catch (Exception e) {
            log.error("Failed to clear user context", e);
            // Don't throw - connection pool cleanup should continue
        }
    }
    
    /**
     * Execute query with RLS context
     * Automatically handles context injection and cleanup
     * @param connection Database connection
     * @param userId User identifier
     * @param sqlQuery SQL query to execute
     * @param executor Query executor function
     * @param <T> Return type
     * @return Query result
     */
    public <T> T executeWithRlsContext(Connection connection, String userId, 
                                       String sqlQuery, QueryExecutor<T> executor) {
        try {
            // Inject user context
            injectUserContext(connection, userId);
            
            // Execute query
            return executor.execute(connection, sqlQuery);
            
        } catch (Exception e) {
            log.error("Failed to execute query with RLS context", e);
            throw new RuntimeException("Query execution with RLS failed", e);
        } finally {
            // Always clear context (prevent session bleeding)
            clearUserContext(connection);
        }
    }
    
    /**
     * Sanitize user ID to prevent SQL injection
     * @param userId Raw user ID
     * @return Sanitized user ID
     */
    private String sanitize(String userId) {
        if (userId == null) {
            return "";
        }
        // Allow only alphanumeric, underscore, hyphen
        return userId.replaceAll("[^a-zA-Z0-9_-]", "");
    }
    
    @FunctionalInterface
    public interface QueryExecutor<T> {
        T execute(Connection connection, String sql) throws Exception;
    }
}
