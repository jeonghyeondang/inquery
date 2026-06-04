package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.AiFeedbackCreateParam;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;

import java.util.List;

/**
 * AI Feedback Service
 * Handles user feedback for AI-generated content (SQL, interpretations, research)
 */
public interface AiFeedbackService {

    /**
     * Create feedback
     *
     * @param param feedback param
     * @return feedback id
     */
    DataResult<Long> create(AiFeedbackCreateParam param);

    /**
     * Check if positive feedback exists for a given question and data source
     * Used for retrieving successful query patterns
     *
     * @param question user question
     * @param dataSourceId data source id
     * @return true if positive feedback exists
     */
    DataResult<Boolean> hasPositiveFeedback(String question, Long dataSourceId);

    /**
     * Get successful query patterns (Few-shot examples) for a data source.
     * Returns top N patterns with positive feedback, ordered by recency.
     *
     * @param dataSourceId data source id
     * @param limit maximum number of patterns to return
     * @return list of successful query patterns (question + SQL pairs)
     */
    ListResult<QueryPattern> getSuccessfulPatterns(Long dataSourceId, int limit);

    /**
     * Query Pattern model for Few-shot learning
     */
    class QueryPattern {
        private String question;
        private String sql;

        public QueryPattern() {}

        public QueryPattern(String question, String sql) {
            this.question = question;
            this.sql = sql;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }
}
