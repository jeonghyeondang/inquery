package ai.inquery.server.domain.api.param.datasource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * @version DataSourceSelector.java, v 0.1 September 23, 2022 15:28 moji Exp $
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceSelector {

    /**
     * environment id
     */
    private Boolean environment;
}
