package ai.inquery.server.web.api.controller.aichat.vo;

import ai.inquery.server.web.api.controller.ai.attachment.AttachmentMetaDTO;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Message VO
 */
@Data
public class MessageVO {

    /**
     * Message ID
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

    /**
     * Attachments linked to this message (hydrated by the controller
     * via a bulk join, ordered by position). Empty when the message
     * has none.
     */
    private List<AttachmentMetaDTO> attachments;
}



