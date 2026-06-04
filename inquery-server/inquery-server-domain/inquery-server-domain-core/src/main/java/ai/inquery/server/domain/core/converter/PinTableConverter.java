package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.param.PinTableParam;
import ai.inquery.server.domain.api.param.TablePageQueryParam;
import ai.inquery.server.domain.repository.entity.PinTableDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public abstract class PinTableConverter {

    /**
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract PinTableDO param2do(PinTableParam param);

    @Mapping(target = "userId", ignore = true)
    public abstract PinTableParam toPinTableParam (TablePageQueryParam param);
}
