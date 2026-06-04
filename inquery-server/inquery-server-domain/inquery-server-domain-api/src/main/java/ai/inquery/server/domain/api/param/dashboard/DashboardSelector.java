package ai.inquery.server.domain.api.param.dashboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * selectro
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSelector {

    /**
     * Chart ID list
     */
    private Boolean chartIds;
}
