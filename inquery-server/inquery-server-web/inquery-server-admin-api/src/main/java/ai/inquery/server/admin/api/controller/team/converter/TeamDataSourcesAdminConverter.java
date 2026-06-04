package ai.inquery.server.admin.api.controller.team.converter;

import ai.inquery.server.admin.api.controller.datasource.request.DataSourceAccessBatchCreateRequest;
import ai.inquery.server.admin.api.controller.team.request.TeamPageCommonQueryRequest;
import ai.inquery.server.admin.api.controller.team.vo.TeamDataSourcePageQueryVO;
import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
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
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class, AccessObjectTypeEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class TeamDataSourcesAdminConverter {

    /**
     * convert
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "accessObjectId", source = "teamId"),
        @Mapping(target = "accessObjectType", expression = "java(AccessObjectTypeEnum.TEAM.name())"),
        @Mapping(source = "searchKey", target = "dataSourceSearchKey"),
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "dataSourceId", ignore = true),
        @Mapping(target = "userOrTeamSearchKey", ignore = true),
    })
    public abstract DataSourceAccessComprehensivePageQueryParam request2param(TeamPageCommonQueryRequest request);

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
    @Mappings({
        @Mapping(target = "teamId", source = "accessObjectId"),
    })
    public abstract TeamDataSourcePageQueryVO dto2vo(DataSourceAccess dto);

}
