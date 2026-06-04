package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI Chat Message
 */
@Data
public class AiChatMessage implements Serializable {
    /**
     * Primary key
     */
    private Long id;

    /**
     * Creation time
     */
    private Date gmtCreate;

    /**
     * Modified time
     */
    private Date gmtModified;

    /**
     * Chat room ID
     */
    private Long chatRoomId;

    /**
     * Role (user/assistant)
     */
    private String role;

    /**
     * Message content
     */
    private String content;

    /**
     * User ID
     */
    private Long userId;
}



