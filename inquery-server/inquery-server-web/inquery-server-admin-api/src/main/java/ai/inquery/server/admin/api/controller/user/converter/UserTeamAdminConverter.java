package ai.inquery.server.admin.api.controller.user.converter;

import ai.inquery.server.admin.api.controller.user.request.UserPageCommonQueryRequest;
import ai.inquery.server.admin.api.controller.user.vo.UserTeamPageQueryVO;
import ai.inquery.server.domain.api.model.TeamUser;
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
public abstract class UserTeamAdminConverter {

    /**
     * convert
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "searchKey", target = "teamSearchKey"),
        @Mapping(target = "enableReturnCount", expression = "java(true)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "teamId", ignore = true),
        @Mapping(target = "userSearchKey", ignore = true),
    })
    public abstract TeamUserComprehensivePageQueryParam request2param(UserPageCommonQueryRequest request);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract UserTeamPageQueryVO dto2vo(TeamUser dto);
}
