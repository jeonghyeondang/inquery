
package ai.inquery.server.admin.api.controller.team.vo;

import java.util.Date;

import ai.inquery.server.common.api.controller.vo.SimpleUserVO;
import lombok.Data;

/**
 * Pagination query
 *
 */
@Data
public class TeamPageQueryVO {
    /**
     * primary key
     */
    private Long id;

    /**
     * team coding
     */
    private String code;

    /**
     * Team Name
     */
    private String name;

    /**
     * Team status
     *
     * @see ai.inquery.server.domain.api.enums.ValidStatusEnum
     */
    private String status;

    /**
     * Team description
     */
    private String description;

    /**
     * Change the time
     */
    private Date gmtModified;

    /**
     * Modifier user id
     */
    private Long modifiedUserId;

    /**
     * Modifier user
     */
    private SimpleUserVO modifiedUser;
}
