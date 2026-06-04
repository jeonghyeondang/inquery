package ai.inquery.server.web.api.controller.aichat.request;

import lombok.Data;

/**
 * Chat Room Create Request
 */
@Data
public class ChatRoomCreateRequest {

    /**
     * Conversation ID (UUID)
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



