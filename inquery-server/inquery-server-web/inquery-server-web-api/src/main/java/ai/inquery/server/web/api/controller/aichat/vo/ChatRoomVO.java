package ai.inquery.server.web.api.controller.aichat.vo;

import lombok.Data;

import java.util.Date;

/**
 * Chat Room VO
 */
@Data
public class ChatRoomVO {

    /**
     * Chat room ID
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



