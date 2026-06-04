package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.spi.model.Command;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public abstract class CommandConverter {

    @Mappings({
            @Mapping(target = "script", source = "sql"),
            @Mapping(target = "single", ignore = true),
    })
    public abstract Command param2model(DlExecuteParam param);
}
