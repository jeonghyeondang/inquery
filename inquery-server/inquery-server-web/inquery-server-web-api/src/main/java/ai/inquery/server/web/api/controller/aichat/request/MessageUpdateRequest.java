package ai.inquery.server.web.api.controller.aichat.request;

import lombok.Data;

/**
 * Message Update Request
 */
@Data
public class MessageUpdateRequest {

    /**
     * Message ID
     */
    private Long id;

    /**
     * Message content
     */
    private String content;
}
