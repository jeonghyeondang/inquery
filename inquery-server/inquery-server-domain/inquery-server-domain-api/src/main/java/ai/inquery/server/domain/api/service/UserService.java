package ai.inquery.server.domain.api.service;

import java.util.List;

import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.user.UserCreateParam;
import ai.inquery.server.domain.api.param.user.UserSelector;
import ai.inquery.server.domain.api.param.user.UserPageQueryParam;
import ai.inquery.server.domain.api.param.user.UserUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;

/**
 * User service
 *
 */
public interface UserService {

    /**
     * Query user information
     *
     * @param id
     * @return
     */
    DataResult<User> query(Long id);

    /**
     * gen
     * @param userName
     * @return
     */
    DataResult<User> query(String userName);

    /**
     * List Query Data
     *
     * @param idList
     * @return
     */
    ListResult<User> listQuery(List<Long> idList);

    /**
     * Query user information
     *
     * @param param
     * @return
     */
    PageResult<User> pageQuery(UserPageQueryParam param, UserSelector selector);

    /**
     * Update user information
     * @param user
     * @return
     */
    DataResult<Long> update(UserUpdateParam user);

    /**
     * Update only the password of an existing user. Used for self change-password
     * flows where other fields (nickName/email/role/status) must remain untouched.
     *
     * @param userId          target user id
     * @param bcryptPassword  the already-bcrypt-hashed new password
     */
    DataResult<Boolean> updatePassword(Long userId, String bcryptPassword);

    /**
     * delete users
     * @param id
     * @return
     */
   ActionResult delete(Long id);

    /**
     * Create a user
     * @param user
     * @return
     */
    DataResult<Long> create(UserCreateParam user);
}
