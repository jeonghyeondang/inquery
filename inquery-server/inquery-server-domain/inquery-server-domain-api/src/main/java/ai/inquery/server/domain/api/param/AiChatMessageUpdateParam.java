package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Chat Message Update Param
 */
@Data
public class AiChatMessageUpdateParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Message ID
     */
    private Long id;

    /**
     * Message content
     */
    private String content;
}
