package ai.inquery.server.domain.api.param.datasource.access;

import ai.inquery.server.domain.api.param.datasource.DataSourceSelector;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * slecetor
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceAccessSelector {

    /**
     * Authorization object
     */
    private Boolean accessObject;

    /**
     * data source
     */
    private Boolean dataSource;

    /**
     * data source
     */
    private DataSourceSelector dataSourceSelector;
}
