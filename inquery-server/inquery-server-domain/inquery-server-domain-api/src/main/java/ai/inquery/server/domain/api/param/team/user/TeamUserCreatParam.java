package ai.inquery.server.domain.api.param.team.user;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Team User
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TeamUserCreatParam {
    /**
     * team id
     */
    @NotNull
    private Long teamId;

    /**
     * user id
     */
    private Long userId;
}
