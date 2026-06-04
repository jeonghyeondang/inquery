package ai.inquery.server.domain.api.param.user;

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
public class UserSelector {
    /**
     * Modifier user
     */
    private Boolean modifiedUser;

}
