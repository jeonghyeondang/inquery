package ai.inquery.server.domain.core.converter;

import java.util.List;
import java.util.Map;

import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.user.UserCreateParam;
import ai.inquery.server.domain.api.param.user.UserUpdateParam;
import ai.inquery.server.domain.api.service.UserService;
import ai.inquery.server.domain.repository.entity.InqueryUserDO;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.springframework.context.annotation.Lazy;

/**
 * converter
 *
 */
@Mapper(componentModel = "spring")
public abstract class UserConverter {

    @Resource
    @Lazy
    private UserService userService;

    /**
     * Convert
     *
     * @param data
     * @return
     */
    @Mappings({
        @Mapping(target = "modifiedUser.id", source = "modifiedUserId"),
    })
    public abstract User do2dto(InqueryUserDO data);

    /**
     * Convert
     *
     * @param datas
     * @return
     */
    public abstract List<User> do2dto(List<InqueryUserDO> datas);

    /**
     *
     * @param user
     * @return
     */
    @Mappings({
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "createUserId", ignore = true),
    })
    public abstract InqueryUserDO dto2do(User user);

    /**
     *
     * @param user
     * @return
     */
    @Mappings({
        @Mapping(target = "createUserId", source = "userId"),
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract InqueryUserDO param2do(UserCreateParam user, Long userId);

    /**
     *
     * @param user
     * @return
     */
    @Mappings({
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "userName", ignore = true),
        @Mapping(target = "createUserId", ignore = true),
    })
    public abstract InqueryUserDO param2do(UserUpdateParam user, Long userId);

    /**
     * Fill in detailed information
     *
     * @param list
     */
    public void fillDetail(List<User> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        List<Long> idList = EasyCollectionUtils.toList(list, User::getId);
        List<User> queryList = userService.listQuery(idList).getData();
        Map<Long, User> queryMap = EasyCollectionUtils.toIdentityMap(queryList, User::getId);
        for (User data : list) {
            if (data == null || data.getId() == null) {
                continue;
            }
            User query = queryMap.get(data.getId());
            add(data, query);
        }
    }

    @Mappings({
        @Mapping(target = "id", ignore = true),
    })
    public abstract void add(@MappingTarget User target, User source);
}
