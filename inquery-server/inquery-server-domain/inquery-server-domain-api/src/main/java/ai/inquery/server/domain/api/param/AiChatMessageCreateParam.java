package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Chat Message Create Param
 */
@Data
public class AiChatMessageCreateParam implements Serializable {

    private static final long serialVersionUID = 1L;

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



