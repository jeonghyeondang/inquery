package ai.inquery.server.domain.core.monitoring;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * User activity tracking service as per system_architecture.md section 6.1
 * Logs all query processing activities for monitoring and improvement
 */
@Slf4j
@Service
public class QueryAuditService {

    // In-memory storage for demo (replace with DB in production)
    private final ConcurrentLinkedQueue<QueryAuditLog> auditLogs = new ConcurrentLinkedQueue<>();
    private static final int MAX_LOGS = 10000; // Limit memory usage

    public void logQuery(QueryAuditLog auditLog) {
        auditLogs.offer(auditLog);

        // Prevent unbounded growth
        while (auditLogs.size() > MAX_LOGS) {
            auditLogs.poll();
        }

        log.info("Query audit logged: user={}, query='{}', executionTime={}ms, success={}",
            auditLog.getUserId(),
            auditLog.getQuestionOriginal(),
            auditLog.getExecutionTimeMs(),
            auditLog.isSuccess());
    }

    public List<QueryAuditLog> getRecentLogs(int limit) {
        List<QueryAuditLog> recent = new ArrayList<>();
        int count = 0;
        for (QueryAuditLog log : auditLogs) {
            if (count++ >= limit) break;
            recent.add(log);
        }
        return recent;
    }

    public List<QueryAuditLog> getFailedQueries() {
        List<QueryAuditLog> failed = new ArrayList<>();
        for (QueryAuditLog log : auditLogs) {
            if (!log.isSuccess()) {
                failed.add(log);
            }
        }
        return failed;
    }

    /**
     * Query audit log data structure
     */
    @Data
    public static class QueryAuditLog {
        private String requestId;  // Unique ID for tracking
        private LocalDateTime timestamp;
        private String userId;
        private String questionOriginal;
        private String questionTranslated;
        private List<String> searchKeywords;  // Search augmentation keywords
        private String generatedSql;
        private long executionTimeMs;
        private boolean success;
        private String errorMessage;
        private String errorType;
        private int resultRowCount;
        private String thoughtProcess; // CoT logs
        private String recommendedChart;
        
        // Step-by-step processing info
        private String queryType;  // CHAT, TABLE, CHART
        private String schemaContext;  // Schema info used for SQL generation
        private String modelUsed;  // LLM model used
        private Long translateTimeMs;  // Translation step time
        private Long schemaSearchTimeMs;  // Schema search step time
        private Long sqlGenerationTimeMs;  // SQL generation step time
        private Long sqlExecutionTimeMs;  // SQL execution step time
        private Long chartRecommendTimeMs;  // Chart recommendation step time
        
        // LLM Request/Response logs
        private String classificationPrompt;  // Prompt sent for query classification
        private String classificationResponse;  // LLM response for classification
        private String translatePrompt;  // Prompt sent for translation
        private String translateResponse;  // LLM response for translation
        private String sqlGenerationPrompt;  // Prompt sent for SQL generation
        private String sqlGenerationResponse;  // LLM raw response for SQL
        private String chartRecommendPrompt;  // Prompt sent for chart recommendation
        private String chartRecommendResponse;  // LLM response for chart recommendation
        private String chartRecommendReason;  // Reason for chart recommendation
        private String interpretPrompt;  // Prompt sent for result interpretation
        private String interpretResponse;  // LLM response for interpretation
        private String interpretMarkdownTable;  // Markdown table sent to LLM

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final QueryAuditLog log = new QueryAuditLog();

            public Builder timestamp(LocalDateTime timestamp) {
                log.timestamp = timestamp;
                return this;
            }

            public Builder userId(String userId) {
                log.userId = userId;
                return this;
            }

            public Builder questionOriginal(String question) {
                log.questionOriginal = question;
                return this;
            }

            public Builder questionTranslated(String translated) {
                log.questionTranslated = translated;
                return this;
            }

            public Builder searchKeywords(List<String> keywords) {
                log.searchKeywords = keywords;
                return this;
            }

            public Builder generatedSql(String sql) {
                log.generatedSql = sql;
                return this;
            }

            public Builder executionTimeMs(long timeMs) {
                log.executionTimeMs = timeMs;
                return this;
            }

            public Builder success(boolean success) {
                log.success = success;
                return this;
            }

            public Builder errorMessage(String error) {
                log.errorMessage = error;
                return this;
            }

            public Builder errorType(String type) {
                log.errorType = type;
                return this;
            }

            public Builder resultRowCount(int count) {
                log.resultRowCount = count;
                return this;
            }

            public Builder thoughtProcess(String cot) {
                log.thoughtProcess = cot;
                return this;
            }

            public Builder recommendedChart(String chart) {
                log.recommendedChart = chart;
                return this;
            }

            public Builder requestId(String requestId) {
                log.requestId = requestId;
                return this;
            }

            public Builder queryType(String queryType) {
                log.queryType = queryType;
                return this;
            }

            public Builder schemaContext(String schemaContext) {
                log.schemaContext = schemaContext;
                return this;
            }

            public Builder modelUsed(String model) {
                log.modelUsed = model;
                return this;
            }

            public Builder translateTimeMs(Long timeMs) {
                log.translateTimeMs = timeMs;
                return this;
            }

            public Builder schemaSearchTimeMs(Long timeMs) {
                log.schemaSearchTimeMs = timeMs;
                return this;
            }

            public Builder sqlGenerationTimeMs(Long timeMs) {
                log.sqlGenerationTimeMs = timeMs;
                return this;
            }

            public Builder sqlExecutionTimeMs(Long timeMs) {
                log.sqlExecutionTimeMs = timeMs;
                return this;
            }

            public Builder chartRecommendTimeMs(Long timeMs) {
                log.chartRecommendTimeMs = timeMs;
                return this;
            }

            public Builder classificationPrompt(String prompt) {
                log.classificationPrompt = prompt;
                return this;
            }

            public Builder classificationResponse(String response) {
                log.classificationResponse = response;
                return this;
            }

            public Builder translatePrompt(String prompt) {
                log.translatePrompt = prompt;
                return this;
            }

            public Builder translateResponse(String response) {
                log.translateResponse = response;
                return this;
            }

            public Builder sqlGenerationPrompt(String prompt) {
                log.sqlGenerationPrompt = prompt;
                return this;
            }

            public Builder sqlGenerationResponse(String response) {
                log.sqlGenerationResponse = response;
                return this;
            }

            public Builder chartRecommendPrompt(String prompt) {
                log.chartRecommendPrompt = prompt;
                return this;
            }

            public Builder chartRecommendResponse(String response) {
                log.chartRecommendResponse = response;
                return this;
            }

            public Builder chartRecommendReason(String reason) {
                log.chartRecommendReason = reason;
                return this;
            }

            public Builder interpretPrompt(String prompt) {
                log.interpretPrompt = prompt;
                return this;
            }

            public Builder interpretResponse(String response) {
                log.interpretResponse = response;
                return this;
            }

            public Builder interpretMarkdownTable(String table) {
                log.interpretMarkdownTable = table;
                return this;
            }

            public QueryAuditLog build() {
                if (log.timestamp == null) {
                    log.timestamp = LocalDateTime.now();
                }
                if (log.requestId == null) {
                    log.requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
                }
                return log;
            }
        }
    }
}
