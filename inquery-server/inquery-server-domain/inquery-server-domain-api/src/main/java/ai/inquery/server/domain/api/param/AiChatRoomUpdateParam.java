package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Chat Room Update Param
 */
@Data
public class AiChatRoomUpdateParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Chat room ID
     */
    private Long id;

    /**
     * Chat room title
     */
    private String title;
}



