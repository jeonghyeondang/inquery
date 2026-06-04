package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI Chat Room
 */
@Data
public class AiChatRoom implements Serializable {
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
     * Conversation ID (UUID for backend context)
     */
    private String conversationId;

    /**
     * Chat room title
     */
    private String title;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Whether it has been deleted, y means deleted, n means not deleted
     */
    private String deleted;
}



