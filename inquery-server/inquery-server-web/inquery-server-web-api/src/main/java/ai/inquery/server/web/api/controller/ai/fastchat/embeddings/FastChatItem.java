package ai.inquery.server.web.api.controller.ai.fastchat.embeddings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FastChatItem implements Serializable {
    private String object;
    private List<BigDecimal> embedding;
    private Integer index;
}
