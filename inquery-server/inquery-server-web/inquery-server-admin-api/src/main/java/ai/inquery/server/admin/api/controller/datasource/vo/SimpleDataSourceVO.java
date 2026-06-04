
package ai.inquery.server.admin.api.controller.datasource.vo;

import ai.inquery.server.common.api.controller.vo.SimpleEnvironmentVO;
import lombok.Data;

/**
 * Data Source
 *
 */
@Data
public class SimpleDataSourceVO {

    /**
     * primary key id
     */
    private Long id;

    /**
     * Connection alias
     */
    private String alias;

    /**
     * connection address
     */
    private String url;

    /**
     * Database type (e.g. MYSQL, SNOWFLAKE, POSTGRESQL)
     */
    private String type;

    /**
     * environment id
     */
    private Long environmentId;

    /**
     * environment
     */
    private SimpleEnvironmentVO environment;
}
