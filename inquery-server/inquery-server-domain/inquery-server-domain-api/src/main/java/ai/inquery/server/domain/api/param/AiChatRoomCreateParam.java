package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Chat Room Create Param
 */
@Data
public class AiChatRoomCreateParam implements Serializable {

    private static final long serialVersionUID = 1L;

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
}



