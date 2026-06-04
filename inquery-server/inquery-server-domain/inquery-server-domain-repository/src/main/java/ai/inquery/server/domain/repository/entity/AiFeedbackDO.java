package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * AI Feedback Entity
 * Stores user feedback (positive/negative) for AI-generated content
 * Used for learning and improving AI responses
 */
@Getter
@Setter
@TableName("ai_feedback")
public class AiFeedbackDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Date gmtCreate;

    private Date gmtModified;

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
     * User who gave feedback
     */
    private Long userId;
}
