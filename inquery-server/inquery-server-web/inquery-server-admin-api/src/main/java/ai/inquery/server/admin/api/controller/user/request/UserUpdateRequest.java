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
public class UserUpdateRequest {
    /**
     * primary key
     */
    @NotNull
    private Long id;

    /**
     * password. Optional on update — if null, the existing password is kept.
     * If provided, must be 6–64 characters; bcrypt-hashed server-side.
     *
     * <p>Note: clients must omit (or set null) instead of sending an empty string
     * when "no change" is intended; an empty string would violate {@link Size#min()}.
     */
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
    private String roleCode;

    /**
     * user status
     *
     * @see ValidStatusEnum
     */
    @NotNull
    private String status;
}
