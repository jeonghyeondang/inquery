package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.AiChatMessage;
import ai.inquery.server.domain.api.param.AiChatMessageCreateParam;
import ai.inquery.server.domain.api.param.AiChatMessageUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;

/**
 * AI Chat Message Service
 */
public interface AiChatMessageService {

    /**
     * Create chat message
     *
     * @param param chat message param
     * @return message id
     */
    DataResult<Long> create(AiChatMessageCreateParam param);

    /**
     * List messages by chat room id
     *
     * @param chatRoomId chat room id
     * @return message list
     */
    ListResult<AiChatMessage> listByChatRoomId(Long chatRoomId);

    /**
     * Get message by id
     *
     * @param id message id
     * @return message
     */
    DataResult<AiChatMessage> get(Long id);

    /**
     * Update chat message content
     *
     * @param param update param with id and content
     * @return action result
     */
    ActionResult update(AiChatMessageUpdateParam param);
}



