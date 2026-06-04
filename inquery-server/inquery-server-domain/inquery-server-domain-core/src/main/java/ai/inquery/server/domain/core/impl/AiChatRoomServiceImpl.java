package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.enums.DeletedTypeEnum;
import ai.inquery.server.domain.api.model.AiChatRoom;
import ai.inquery.server.domain.api.param.AiChatRoomCreateParam;
import ai.inquery.server.domain.api.param.AiChatRoomUpdateParam;
import ai.inquery.server.domain.api.service.AiChatRoomService;
import ai.inquery.server.domain.core.converter.AiChatRoomConverter;
import ai.inquery.server.domain.repository.MapperUtils;
import ai.inquery.server.domain.repository.entity.AiChatRoomDO;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI Chat Room Service Implementation
 */
@Service
public class AiChatRoomServiceImpl implements AiChatRoomService {

    @Autowired
    private AiChatRoomConverter aiChatRoomConverter;

    @Override
    public DataResult<Long> create(AiChatRoomCreateParam param) {
        AiChatRoomDO chatRoomDO = aiChatRoomConverter.toDO(param);
        chatRoomDO.setDeleted(DeletedTypeEnum.N.name());
        MapperUtils.getAiChatRoomMapper().insert(chatRoomDO);
        return DataResult.of(chatRoomDO.getId());
    }

    @Override
    public ActionResult update(AiChatRoomUpdateParam param) {
        AiChatRoomDO chatRoomDO = new AiChatRoomDO();
        chatRoomDO.setId(param.getId());
        chatRoomDO.setTitle(param.getTitle());
        MapperUtils.getAiChatRoomMapper().updateById(chatRoomDO);
        return ActionResult.isSuccess();
    }

    @Override
    public ActionResult delete(Long id) {
        AiChatRoomDO chatRoomDO = new AiChatRoomDO();
        chatRoomDO.setId(id);
        chatRoomDO.setDeleted(DeletedTypeEnum.Y.name());
        MapperUtils.getAiChatRoomMapper().updateById(chatRoomDO);
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<AiChatRoom> get(Long id) {
        AiChatRoomDO chatRoomDO = MapperUtils.getAiChatRoomMapper().selectById(id);
        return DataResult.of(aiChatRoomConverter.toModel(chatRoomDO));
    }

    @Override
    public DataResult<AiChatRoom> getByConversationId(String conversationId) {
        QueryWrapper<AiChatRoomDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("conversation_id", conversationId);
        queryWrapper.eq("deleted", DeletedTypeEnum.N.name());
        AiChatRoomDO chatRoomDO = MapperUtils.getAiChatRoomMapper().selectOne(queryWrapper);
        return DataResult.of(aiChatRoomConverter.toModel(chatRoomDO));
    }

    @Override
    public ListResult<AiChatRoom> listByUserId(Long userId) {
        QueryWrapper<AiChatRoomDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq("deleted", DeletedTypeEnum.N.name());
        queryWrapper.orderByDesc("gmt_modified");
        List<AiChatRoomDO> chatRoomDOList = MapperUtils.getAiChatRoomMapper().selectList(queryWrapper);
        return ListResult.of(aiChatRoomConverter.toModel(chatRoomDOList));
    }
}



