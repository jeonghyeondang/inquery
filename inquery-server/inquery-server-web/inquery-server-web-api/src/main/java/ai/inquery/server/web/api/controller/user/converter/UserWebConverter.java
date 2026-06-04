
package ai.inquery.server.web.api.controller.user.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.web.api.controller.user.request.UserCreateRequest;
import ai.inquery.server.web.api.controller.user.request.UserUpdateRequest;
import ai.inquery.server.web.api.controller.user.vo.UserVO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 */
@Mapper(componentModel = "spring")
public abstract class UserWebConverter {
    /**
     * Convert
     *
     * @param user
     * @return
     */
    public abstract UserVO dto2vo(User user);

    /**
     *
     * @param user
     * @return
     */
    public abstract List<UserVO> dto2vo(List<User> user);

    /**
     *
     * @param createRequest
     * @return
     */
    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "roleCode", ignore = true),
        @Mapping(target = "status", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "modifiedUserId", ignore = true),
        @Mapping(target = "modifiedUser", ignore = true),
    })
    public abstract User createRequest2dto(UserCreateRequest createRequest);

    /**
     *
     * @param updateRequest
     * @return
     */
    @Mappings({
        @Mapping(target = "roleCode", ignore = true),
        @Mapping(target = "status", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "modifiedUserId", ignore = true),
        @Mapping(target = "modifiedUser", ignore = true),
    })
    public abstract User updateRequest2dto(UserUpdateRequest updateRequest);

}