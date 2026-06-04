package ai.inquery.server.admin.api.controller.datasource.converter;

import ai.inquery.server.admin.api.controller.datasource.request.DataSourceCreateRequest;
import ai.inquery.server.admin.api.controller.datasource.request.DataSourceUpdateRequest;
import ai.inquery.server.admin.api.controller.datasource.vo.DataSourcePageQueryVO;
import ai.inquery.server.common.api.controller.request.CommonPageQueryRequest;
import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.datasource.DataSourceCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePageQueryParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class DataSourceAdminConverter {

    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "kind", expression = "java(DataSourceKindEnum.SHARED.getCode())"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract DataSourcePageQueryParam request2param(CommonPageQueryRequest request);

    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "kind", ignore = true),
    })
    public abstract DataSourcePageQueryParam request2paramAccess(CommonPageQueryRequest request);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract DataSourcePageQueryVO dto2vo(DataSource dto);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "user", target = "userName"),
        @Mapping(target = "kind", expression = "java(DataSourceKindEnum.SHARED.getCode())"),
        @Mapping(target = "envType", ignore = true),
    })
    public abstract DataSourceCreateParam createReq2param(DataSourceCreateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "user", target = "userName")
    })
    public abstract DataSourceUpdateParam updateReq2param(DataSourceUpdateRequest request);
}
