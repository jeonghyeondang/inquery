package ai.inquery.server.web.api.controller.rdb.converter;

import ai.inquery.server.web.api.controller.rdb.request.ProcedureUpdateRequest;
import ai.inquery.spi.model.Procedure;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @date: February 24, 2024 13:39
 */
@Mapper(componentModel = "spring")
public abstract class ProcedureConverter {

    @Mappings({
        @Mapping(target = "remarks", ignore = true),
        @Mapping(target = "procedureType", ignore = true),
        @Mapping(target = "specificName", ignore = true),
    })
    public abstract Procedure request2param(ProcedureUpdateRequest request);
}
