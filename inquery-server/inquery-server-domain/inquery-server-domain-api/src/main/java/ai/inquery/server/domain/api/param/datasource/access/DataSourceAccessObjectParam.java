
package ai.inquery.server.domain.api.param.datasource.access;

import java.io.Serial;
import java.io.Serializable;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DataSource Access Object
 * It could be a user or a team
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceAccessObjectParam implements Serializable {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    /**
     * Authorization ID, distinguish whether it is a user or a team according to the type
     */
    private Long id;

    /**
     * Authorization type
     *
     * @see AccessObjectTypeEnum
     */
    private String type;

}
