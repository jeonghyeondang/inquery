
package ai.inquery.server.admin.api.controller.datasource.vo;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class DataSourceAccessPageQueryVO {

    /**
     * primary key
     */
    @NotNull
    private Long id;
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

    /**
     * Authorization object
     */
    @NotNull
    private DataSourceAccessObjectVO accessObject;
}
