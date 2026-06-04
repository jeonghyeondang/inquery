package ai.inquery.server.domain.api.param.datasource.access;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Data Source Access
 *
 */
@Data
public class DataSourceAccessPageQueryParam extends PageQueryParam {
    /**
     * Data source id
     */
    @NotNull
    private Long dataSourceId;

    /**
     * Authorization type
     *
     * @see AccessObjectTypeEnum
     */
    @NotNull
    private String accessObjectType;

    /**
     * Authorization ID, distinguish whether it is a user or a team according to the type
     */
    @NotNull
    private Long accessObjectId;
}
