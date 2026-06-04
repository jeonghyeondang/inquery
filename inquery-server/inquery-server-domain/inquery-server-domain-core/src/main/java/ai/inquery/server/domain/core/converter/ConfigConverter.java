
package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.param.SystemConfigParam;
import ai.inquery.server.domain.repository.entity.SystemConfigDO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 */
@Mapper(componentModel = "spring")
public abstract class ConfigConverter {

    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract SystemConfigDO param2do(SystemConfigParam param);

    public abstract Config do2model(SystemConfigDO systemConfigDO);
}