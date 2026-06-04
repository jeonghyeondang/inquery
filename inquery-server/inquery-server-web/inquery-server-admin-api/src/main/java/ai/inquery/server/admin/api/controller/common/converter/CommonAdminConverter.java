package ai.inquery.server.admin.api.controller.common.converter;

import ai.inquery.server.admin.api.controller.common.vo.TeamUserListVO;
import ai.inquery.server.admin.api.controller.datasource.vo.SimpleDataSourceVO;
import ai.inquery.server.admin.api.controller.team.vo.SimpleTeamVO;
import ai.inquery.server.admin.api.controller.user.vo.SimpleUserVO;
import ai.inquery.server.common.api.controller.request.CommonQueryRequest;
import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.model.Team;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.datasource.DataSourcePageQueryParam;
import ai.inquery.server.domain.api.param.team.TeamPageQueryParam;
import ai.inquery.server.domain.api.param.user.UserPageQueryParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", imports = {AccessObjectTypeEnum.class, DataSourceKindEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class CommonAdminConverter {

    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "pageSize", expression = "java(10)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "pageNo", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract TeamPageQueryParam request2paramTeam(CommonQueryRequest request);

    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "pageSize", expression = "java(10)"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "pageNo", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract UserPageQueryParam request2paramUser(CommonQueryRequest request);

    /**
     * conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "pageSize", expression = "java(10)"),
        @Mapping(target = "kind", expression = "java(DataSourceKindEnum.SHARED.getCode())"),
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "pageNo", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract DataSourcePageQueryParam request2paramDataSource(CommonQueryRequest request);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract SimpleTeamVO dto2voTeam(Team dto);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract SimpleDataSourceVO dto2voDataSource(DataSource dto);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract SimpleUserVO dto2voUser(User dto);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(target = "type", expression = "java(AccessObjectTypeEnum.TEAM.getCode())"),
        @Mapping(target = "code", source = "code"),
        @Mapping(target = "name", source = "name"),
    })
    public abstract TeamUserListVO dto2voTeamUser(Team dto);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(target = "type", expression = "java(AccessObjectTypeEnum.USER.getCode())"),
        @Mapping(target = "code", source = "userName"),
        @Mapping(target = "name", source = "nickName"),
    })
    public abstract TeamUserListVO dto2voTeamUser(User dto);
}
