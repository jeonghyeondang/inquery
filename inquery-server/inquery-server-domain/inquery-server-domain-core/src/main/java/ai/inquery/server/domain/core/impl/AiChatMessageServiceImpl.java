package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.model.AiChatMessage;
import ai.inquery.server.domain.api.param.AiChatMessageCreateParam;
import ai.inquery.server.domain.api.param.AiChatMessageUpdateParam;
import ai.inquery.server.domain.api.service.AiChatMessageService;
import ai.inquery.server.domain.core.converter.AiChatMessageConverter;
import ai.inquery.server.domain.repository.MapperUtils;
import ai.inquery.server.domain.repository.entity.AiChatMessageDO;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI Chat Message Service Implementation
 */
@Service
public class AiChatMessageServiceImpl implements AiChatMessageService {

    @Autowired
    private AiChatMessageConverter aiChatMessageConverter;

    @Override
    public DataResult<Long> create(AiChatMessageCreateParam param) {
        AiChatMessageDO messageDO = aiChatMessageConverter.toDO(param);
        MapperUtils.getAiChatMessageMapper().insert(messageDO);
        return DataResult.of(messageDO.getId());
    }

    @Override
    public ListResult<AiChatMessage> listByChatRoomId(Long chatRoomId) {
        QueryWrapper<AiChatMessageDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chat_room_id", chatRoomId);
        queryWrapper.orderByAsc("gmt_create");
        List<AiChatMessageDO> messageDOList = MapperUtils.getAiChatMessageMapper().selectList(queryWrapper);
        return ListResult.of(aiChatMessageConverter.toModel(messageDOList));
    }

    @Override
    public DataResult<AiChatMessage> get(Long id) {
        AiChatMessageDO messageDO = MapperUtils.getAiChatMessageMapper().selectById(id);
        return DataResult.of(aiChatMessageConverter.toModel(messageDO));
    }

    @Override
    public ActionResult update(AiChatMessageUpdateParam param) {
        AiChatMessageDO messageDO = MapperUtils.getAiChatMessageMapper().selectById(param.getId());
        if (messageDO == null) {
            // Message not found - return success (idempotent operation)
            return ActionResult.isSuccess();
        }
        messageDO.setContent(param.getContent());
        MapperUtils.getAiChatMessageMapper().updateById(messageDO);
        return ActionResult.isSuccess();
    }
}



