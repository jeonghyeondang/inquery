package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Feedback Create Param
 * Used to submit user feedback (positive/negative) for AI-generated content
 */
@Data
public class AiFeedbackCreateParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Feedback type: POSITIVE or NEGATIVE
     */
    private String feedbackType;

    /**
     * Response type: SQL_GENERATION, RESULT_INTERPRETATION, DEEP_RESEARCH
     */
    private String responseType;

    /**
     * Chat room ID (for AI Chat messages)
     */
    private Long chatRoomId;

    /**
     * Message ID in chat
     */
    private Long messageId;

    /**
     * Original user question
     */
    private String question;

    /**
     * Generated SQL or interpretation text
     */
    private String generatedContent;

    /**
     * Deep Research session ID
     */
    private Long researchSessionId;

    /**
     * Data source ID
     */
    private Long dataSourceId;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * User ID
     */
    private Long userId;
}
