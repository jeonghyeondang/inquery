package ai.inquery.server.web.api.controller.aichat.request;

import lombok.Data;

import java.util.List;

/**
 * Message Save Request
 */
@Data
public class MessageSaveRequest {

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
     * Optional attachment ids (image / pdf / text) linked to this
     * message. The attachments themselves are uploaded separately via
     * {@code POST /api/ai/attachments} and stored against the
     * authenticated user.
     */
    private List<Long> attachmentIds;
}



