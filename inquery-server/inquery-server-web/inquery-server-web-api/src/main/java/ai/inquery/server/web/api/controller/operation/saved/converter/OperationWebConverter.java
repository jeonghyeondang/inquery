package ai.inquery.server.web.api.controller.operation.saved.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.Operation;
import ai.inquery.server.domain.api.param.operation.OperationPageQueryParam;
import ai.inquery.server.domain.api.param.operation.OperationSavedParam;
import ai.inquery.server.domain.api.param.operation.OperationUpdateParam;
import ai.inquery.server.web.api.controller.operation.saved.request.OperationCreateRequest;
import ai.inquery.server.web.api.controller.operation.saved.request.OperationQueryRequest;
import ai.inquery.server.web.api.controller.operation.saved.request.OperationUpdateRequest;
import ai.inquery.server.web.api.controller.operation.saved.vo.OperationVO;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version DdlManageWebConverter.java, v 0.1 September 26, 2022 10:08 moji Exp $
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class OperationWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract OperationSavedParam req2param(OperationCreateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract OperationUpdateParam updateReq2param(OperationUpdateRequest request);

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
    })
    public abstract OperationPageQueryParam queryReq2param(OperationQueryRequest request, Long userId);

    /**
     * Model conversion
     *
     * @param ddlDTO
     * @return
     */
    @Mappings({
        @Mapping(target = "connectable", expression = "java(ddlDTO.getDataSourceName() != null)"),
    })
    public abstract OperationVO dto2vo(Operation ddlDTO);

    /**
     * Model conversion
     *
     * @param ddlDTOS
     * @return
     */
    public abstract List<OperationVO> dto2vo(List<Operation> ddlDTOS);
}
