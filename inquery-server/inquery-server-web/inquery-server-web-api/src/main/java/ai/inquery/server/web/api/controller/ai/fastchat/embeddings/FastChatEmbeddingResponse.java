package ai.inquery.server.web.api.controller.ai.fastchat.embeddings;

import com.unfbx.chatgpt.entity.common.Usage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * description: 
 *
 *  2023-02-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FastChatEmbeddingResponse implements Serializable {

    private String object;
    private List<FastChatItem> data;
    private String model;
    private Usage usage;
}
