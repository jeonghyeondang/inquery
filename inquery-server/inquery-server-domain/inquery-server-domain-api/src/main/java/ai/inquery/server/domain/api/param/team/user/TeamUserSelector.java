package ai.inquery.server.domain.api.param.team.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * select
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TeamUserSelector {
    /**
     * Team
     */
    private Boolean team;

    /**
     * User
     */
    private Boolean user;
}
