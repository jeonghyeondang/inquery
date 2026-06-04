package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.model.AiChatRoom;
import ai.inquery.server.domain.api.param.AiChatRoomCreateParam;
import ai.inquery.server.domain.repository.entity.AiChatRoomDO;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

import java.util.List;

@Slf4j
@Mapper(componentModel = "spring")
public abstract class AiChatRoomConverter {

    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "deleted", ignore = true),
    })
    public abstract AiChatRoomDO toDO(AiChatRoomCreateParam param);

    public abstract AiChatRoom toModel(AiChatRoomDO param);

    public abstract List<AiChatRoom> toModel(List<AiChatRoomDO> param);
}



