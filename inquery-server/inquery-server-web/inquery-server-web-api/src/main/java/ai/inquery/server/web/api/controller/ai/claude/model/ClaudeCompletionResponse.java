package ai.inquery.server.web.api.controller.ai.claude.model;

import com.unfbx.chatgpt.entity.common.Usage;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 */
@Data
public class ClaudeCompletionResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 4968922211204353592L;
    private String log_id;
    private String stop_reason;
    private String stop;
    private String model;
    private String completion;
    private Usage usage;
    private ClaudeMessageLimit messageLimit;
}