package ai.inquery.server.domain.api.param.team;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * update
 *
 */
@Data
public class TeamUpdateParam {
    /**
     * primary key
     */
    @NotNull
    private Long id;

    /**
     * Team Name
     */
    private String name;

    /**
     * Team status
     *
     * @see ai.inquery.server.domain.api.enums.ValidStatusEnum
     */
    private String status;

    /**
     * role coding
     *
     * @see ai.inquery.server.domain.api.enums.RoleCodeEnum
     */
    private String roleCode;

    /**
     * Team description
     */
    private String description;
}
