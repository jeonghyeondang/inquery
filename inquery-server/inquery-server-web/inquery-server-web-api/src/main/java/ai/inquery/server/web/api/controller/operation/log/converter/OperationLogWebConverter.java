package ai.inquery.server.web.api.controller.operation.log.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.OperationLog;
import ai.inquery.server.domain.api.param.operation.OperationLogCreateParam;
import ai.inquery.server.domain.api.param.operation.OperationLogPageQueryParam;
import ai.inquery.server.web.api.controller.operation.log.request.OperationLogCreateRequest;
import ai.inquery.server.web.api.controller.operation.log.request.OperationLogQueryRequest;
import ai.inquery.server.web.api.controller.operation.log.vo.OperationLogVO;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version HistoryWebConverter.java, v 0.1 September 25, 2022 16:53 moji Exp $
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class OperationLogWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "status", ignore = true),
        @Mapping(target = "operationRows", ignore = true),
        @Mapping(target = "useTime", ignore = true),
        @Mapping(target = "extendInfo", ignore = true),
        @Mapping(target = "userId", ignore = true),
        @Mapping(target = "source", ignore = true),
    })
    public abstract OperationLogCreateParam createReq2param(OperationLogCreateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "userId", ignore = true),
    })
    public abstract OperationLogPageQueryParam req2param(OperationLogQueryRequest request);

    /**
     * Model conversion
     *
     * @param ddlDTO
     * @return
     */
    @Mappings({
        @Mapping(source = "ddl", target = "name"),
        @Mapping(target = "connectable", expression = "java(ddlDTO.getDataSourceName() != null)"),
    })
    public abstract OperationLogVO dto2vo(OperationLog ddlDTO);

    /**
     * Model conversion
     *
     * @param ddlDTOS
     * @return
     */
    public abstract List<OperationLogVO> dto2vo(List<OperationLog> ddlDTOS);
}
