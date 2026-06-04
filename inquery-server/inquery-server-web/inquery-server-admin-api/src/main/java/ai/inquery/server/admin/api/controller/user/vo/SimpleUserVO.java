package ai.inquery.server.admin.api.controller.user.vo;

import ai.inquery.server.domain.api.enums.ValidStatusEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * user
 *
 */
@Data
public class SimpleUserVO {
    /**
     * primary key
     */
    @NotNull
    private Long id;

    /**
     * userName
     */
    @NotNull
    private String userName;

    /**
     * Nick name
     */
    @NotNull
    private String nickName;

    /**
     * user status
     *
     * @see ValidStatusEnum
     */
    private String status;
}
