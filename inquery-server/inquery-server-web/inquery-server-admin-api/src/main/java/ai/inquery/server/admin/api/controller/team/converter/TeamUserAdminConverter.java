package ai.inquery.server.admin.api.controller.team.converter;

import ai.inquery.server.admin.api.controller.datasource.request.DataSourceAccessBatchCreateRequest;
import ai.inquery.server.admin.api.controller.team.request.TeamPageCommonQueryRequest;
import ai.inquery.server.admin.api.controller.team.vo.TeamUserPageQueryVO;
import ai.inquery.server.domain.api.model.TeamUser;
import ai.inquery.server.domain.api.param.datasource.access.DataSourceAccessBatchCreatParam;
import ai.inquery.server.domain.api.param.team.user.TeamUserComprehensivePageQueryParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class TeamUserAdminConverter {

    /**
     * convert
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "searchKey", target = "userSearchKey"),
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "userId", ignore = true),
        @Mapping(target = "teamSearchKey", ignore = true),
    })
    public abstract TeamUserComprehensivePageQueryParam request2param(TeamPageCommonQueryRequest request);

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
    public abstract TeamUserPageQueryVO dto2vo(TeamUser dto);

}
