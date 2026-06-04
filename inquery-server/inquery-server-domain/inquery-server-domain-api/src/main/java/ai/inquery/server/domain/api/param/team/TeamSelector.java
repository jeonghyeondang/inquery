package ai.inquery.server.domain.api.param.team;

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
public class TeamSelector {
    /**
     * Modifier user
     */
    private Boolean modifiedUser;

}
