
package ai.inquery.server.web.api.controller.data.source.converter;

import ai.inquery.spi.model.SSHInfo;
import ai.inquery.server.web.api.controller.data.source.request.SSHTestRequest;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 */
@Mapper(componentModel = "spring")
public abstract class SSHWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "RHost", ignore = true),
        @Mapping(target = "RPort", ignore = true),
    })
    public abstract SSHInfo toInfo(SSHTestRequest request);
}