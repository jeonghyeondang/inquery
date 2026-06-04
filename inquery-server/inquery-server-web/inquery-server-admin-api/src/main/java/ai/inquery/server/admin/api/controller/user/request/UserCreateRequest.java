package ai.inquery.server.admin.api.controller.user.request;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.enums.ValidStatusEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * create
 */
@Data
public class UserCreateRequest {
    /**
     * userName
     */
    @NotNull
    private String userName;

    /**
     * password (initial password set by the admin; the user can change it
     * later via the self change-password flow). Bcrypt-hashed server-side.
     */
    @NotNull
    @Size(min = 6, max = 64, message = "password must be between 6 and 64 characters")
    private String password;

    /**
     * Nick name
     */
    @NotNull
    private String nickName;

    /**
     * email
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
