package ai.inquery.server.web.api.controller.rdb.converter;

import ai.inquery.server.domain.api.param.datasource.DatabaseExportDataParam;
import ai.inquery.server.domain.api.param.datasource.DatabaseExportParam;
import ai.inquery.server.web.api.controller.rdb.request.DatabaseCreateRequest;
import ai.inquery.server.web.api.controller.rdb.request.DatabaseExportDataRequest;
import ai.inquery.server.web.api.controller.rdb.request.DatabaseExportRequest;
import ai.inquery.spi.model.Database;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public abstract class DatabaseConverter {

    @Mappings({
        @Mapping(target = "schemas", ignore = true),
        @Mapping(target = "owner", ignore = true),
    })
    public abstract Database request2param(DatabaseCreateRequest request);

    public abstract DatabaseExportParam request2param(DatabaseExportRequest request);

    public abstract DatabaseExportDataParam request2param(DatabaseExportDataRequest request);
}
