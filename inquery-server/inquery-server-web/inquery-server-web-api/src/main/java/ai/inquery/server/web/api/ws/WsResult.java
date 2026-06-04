package ai.inquery.server.web.api.ws;


import ai.inquery.server.tools.base.wrapper.Result;
import lombok.Data;

@Data
public class WsResult {
    /**
     * message id
     */
    private String uuid;

    /**
     * message content
     */
    private Result message;

    /**
     * message type
     */
    private String actionType;
}
