package ai.inquery.server.web.api.controller.rdb.converter;

import ai.inquery.server.web.api.controller.rdb.request.FunctionUpdateRequest;
import ai.inquery.spi.model.Function;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 */
@Mapper(componentModel = "spring")
public abstract class FunctionConverter {
    @Mappings({
        @Mapping(target = "remarks", ignore = true),
        @Mapping(target = "functionType", ignore = true),
        @Mapping(target = "specificName", ignore = true),
    })
    public abstract Function request2param(FunctionUpdateRequest request);

}
