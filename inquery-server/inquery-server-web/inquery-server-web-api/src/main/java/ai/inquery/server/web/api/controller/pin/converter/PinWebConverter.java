package ai.inquery.server.web.api.controller.pin.converter;

import ai.inquery.server.domain.api.param.PinTableParam;
import ai.inquery.server.web.api.controller.pin.request.PinTableRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class PinWebConverter {

    @Mapping(target = "userId", ignore = true)
    public abstract PinTableParam req2param(PinTableRequest request);
}
