package ai.inquery.server.web.api.controller.aichat.request;

import lombok.Data;

/**
 * Chat Room Update Request
 */
@Data
public class ChatRoomUpdateRequest {

    /**
     * Chat room ID
     */
    private Long id;

    /**
     * Chat room title
     */
    private String title;
}



