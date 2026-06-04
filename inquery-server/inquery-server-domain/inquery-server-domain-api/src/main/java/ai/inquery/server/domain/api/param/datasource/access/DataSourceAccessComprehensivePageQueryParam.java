package ai.inquery.server.domain.api.param.datasource.access;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import lombok.Data;

/**
 * Data Source Access
 *
 */
@Data
public class DataSourceAccessComprehensivePageQueryParam extends PageQueryParam {
    /**
     * Data source id
     */
    private Long dataSourceId;

    /**
     * Authorization type
     *
     * @see AccessObjectTypeEnum
     */
    private String accessObjectType;

    /**
     * Authorization ID, distinguish whether it is a user or a team according to the type
     */
    private Long accessObjectId;

    /**
     * Query keywords for users or teams
     */
    private String userOrTeamSearchKey;

    /**
     * Query keywords for data source
     */
    private String dataSourceSearchKey;
}
