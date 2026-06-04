package ai.inquery.server.admin.api.controller.team.converter;

import ai.inquery.server.admin.api.controller.team.request.TeamCreateRequest;
import ai.inquery.server.admin.api.controller.team.request.TeamUpdateRequest;
import ai.inquery.server.admin.api.controller.team.vo.TeamPageQueryVO;
import ai.inquery.server.common.api.controller.request.CommonPageQueryRequest;
import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.Team;
import ai.inquery.server.domain.api.param.team.TeamCreateParam;
import ai.inquery.server.domain.api.param.team.TeamPageQueryParam;
import ai.inquery.server.domain.api.param.team.TeamUpdateParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class TeamAdminConverter {


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
    })
    public abstract TeamPageQueryParam request2param(CommonPageQueryRequest request);


    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract TeamPageQueryVO dto2vo(Team dto);


    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "roleCode", ignore = true),
    })
    public abstract TeamCreateParam request2param(TeamCreateRequest request);


    /**
     * conversion
     *
     * @param request
     * @return
     */
    public abstract TeamUpdateParam request2param(TeamUpdateRequest request);
}
