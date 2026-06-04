package ai.inquery.server.domain.api.param.user;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.enums.ValidStatusEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * create
 *
 */
@Data
public class UserCreateParam {
    /**
     * userName
     */
    @NotNull
    private String userName;

    /**
     * password
     */
    @NotNull
    private String password;

    /**
     * Nick name
     */
    @NotNull
    private String nickName;

    /**
     * Mail
     */
    @NotNull
    private String email;

    /**
     * role coding
     *
     * @see RoleCodeEnum
     */
    @NotNull
    private String roleCode;

    /**
     * user status
     *
     * @see ValidStatusEnum
     */
    @NotNull
    private String status;
}
