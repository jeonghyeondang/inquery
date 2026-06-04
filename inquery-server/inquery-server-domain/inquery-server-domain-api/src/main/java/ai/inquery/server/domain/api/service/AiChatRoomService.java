package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.AiChatRoom;
import ai.inquery.server.domain.api.param.AiChatRoomCreateParam;
import ai.inquery.server.domain.api.param.AiChatRoomUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;

/**
 * AI Chat Room Service
 */
public interface AiChatRoomService {

    /**
     * Create chat room
     *
     * @param param chat room param
     * @return chat room id
     */
    DataResult<Long> create(AiChatRoomCreateParam param);

    /**
     * Update chat room
     *
     * @param param chat room param
     * @return action result
     */
    ActionResult update(AiChatRoomUpdateParam param);

    /**
     * Delete chat room (soft delete)
     *
     * @param id chat room id
     * @return action result
     */
    ActionResult delete(Long id);

    /**
     * Get chat room by id
     *
     * @param id chat room id
     * @return chat room
     */
    DataResult<AiChatRoom> get(Long id);

    /**
     * Get chat room by conversation id
     *
     * @param conversationId conversation id
     * @return chat room
     */
    DataResult<AiChatRoom> getByConversationId(String conversationId);

    /**
     * List chat rooms by user id
     *
     * @param userId user id
     * @return chat room list
     */
    ListResult<AiChatRoom> listByUserId(Long userId);
}



