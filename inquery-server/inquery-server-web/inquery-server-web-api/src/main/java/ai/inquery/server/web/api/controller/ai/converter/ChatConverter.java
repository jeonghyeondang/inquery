package ai.inquery.server.web.api.controller.ai.converter;

import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatItem;
import ai.inquery.server.web.api.controller.ai.fastchat.model.FastChatCompletionsUsage;
import ai.inquery.server.web.api.controller.ai.request.ChatQueryRequest;

import com.unfbx.chatgpt.entity.common.Usage;
import com.unfbx.chatgpt.entity.embeddings.EmbeddingResponse;
import com.unfbx.chatgpt.entity.embeddings.Item;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version ChatConverter.java, v 0.1 April 2, 2023 13:31 moji Exp $
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class ChatConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "tableName", ignore = true),
        @Mapping(target = "catalogTableName", ignore = true),
    })
    public abstract TableQueryParam chat2tableQuery(ChatQueryRequest request);

    /**
     * chat convert
     *
     * @param item
     * @return
     */
    public abstract FastChatItem item2ChatItem(Item item);

    /**
     * usage convert
     *
     * @param usage
     * @return
     */
    public abstract FastChatCompletionsUsage usage2usage(Usage usage);

    /**
     * response convert
     *
     * @param embeddingResponse
     * @return
     */
    public abstract FastChatEmbeddingResponse response2response(EmbeddingResponse embeddingResponse);
}
