package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.repository.entity.JdbcDriverDO;
import ai.inquery.spi.config.DriverConfig;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Slf4j
@Mapper(componentModel = "spring")
public abstract class DriverConfigConverter {
    @Mappings({
        @Mapping(target = "url", ignore = true),
        @Mapping(target = "downloadJdbcDriverUrls", ignore = true),
        @Mapping(target = "custom", ignore = true),
        @Mapping(target = "extendInfo", ignore = true),
        @Mapping(target = "defaultDriver", ignore = true),
    })
    public abstract DriverConfig do2Config(JdbcDriverDO driverDO);

}
