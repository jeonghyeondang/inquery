package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.model.AiChatMessage;
import ai.inquery.server.domain.api.param.AiChatMessageCreateParam;
import ai.inquery.server.domain.repository.entity.AiChatMessageDO;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

import java.util.List;

@Slf4j
@Mapper(componentModel = "spring")
public abstract class AiChatMessageConverter {

    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract AiChatMessageDO toDO(AiChatMessageCreateParam param);

    public abstract AiChatMessage toModel(AiChatMessageDO param);

    public abstract List<AiChatMessage> toModel(List<AiChatMessageDO> param);
}



