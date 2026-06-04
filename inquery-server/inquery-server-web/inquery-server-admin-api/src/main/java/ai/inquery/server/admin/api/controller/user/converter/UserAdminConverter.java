package ai.inquery.server.admin.api.controller.user.converter;

import ai.inquery.server.admin.api.controller.user.request.UserCreateRequest;
import ai.inquery.server.admin.api.controller.user.request.UserUpdateRequest;
import ai.inquery.server.admin.api.controller.user.vo.UserPageQueryVO;
import ai.inquery.server.common.api.controller.request.CommonPageQueryRequest;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.user.UserCreateParam;
import ai.inquery.server.domain.api.param.user.UserPageQueryParam;
import ai.inquery.server.domain.api.param.user.UserUpdateParam;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class UserAdminConverter {

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
    public abstract UserPageQueryParam request2param(CommonPageQueryRequest request);

    /**
     * conversion
     *
     * @param dto
     * @return
     */
    public abstract UserPageQueryVO dto2vo(User dto);

    /**
     * conversion
     *
     * @param request
     * @return
     */
    public abstract UserCreateParam request2param(UserCreateRequest request);

    /**
     * conversion
     *
     * @param request
     * @return
     */
    public abstract UserUpdateParam request2param(UserUpdateRequest request);
}
