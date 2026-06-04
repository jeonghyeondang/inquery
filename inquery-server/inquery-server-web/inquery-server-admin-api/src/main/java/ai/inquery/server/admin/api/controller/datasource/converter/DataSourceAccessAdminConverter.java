package ai.inquery.server.admin.api.controller.datasource.converter;

import ai.inquery.server.admin.api.controller.datasource.request.DataSourceAccessBatchCreateRequest;
import ai.inquery.server.admin.api.controller.datasource.request.DataSourceAccessPageQueryRequest;
import ai.inquery.server.admin.api.controller.datasource.vo.DataSourceAccessPageQueryVO;
import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.DataSourceAccess;
import ai.inquery.server.domain.api.param.datasource.access.DataSourceAccessBatchCreatParam;
import ai.inquery.server.domain.api.param.datasource.access.DataSourceAccessComprehensivePageQueryParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class DataSourceAccessAdminConverter {

    /**
     * convert
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "searchKey", target = "userOrTeamSearchKey"),
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "accessObjectType", ignore = true),
        @Mapping(target = "accessObjectId", ignore = true),
        @Mapping(target = "dataSourceSearchKey", ignore = true),
    })
    public abstract DataSourceAccessComprehensivePageQueryParam request2param(DataSourceAccessPageQueryRequest request);

    /**
     * convert
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "pageNo", ignore = true),
        @Mapping(target = "pageSize", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract DataSourceAccessBatchCreatParam request2param(DataSourceAccessBatchCreateRequest request);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract DataSourceAccessPageQueryVO dto2vo(DataSourceAccess dto);

}
