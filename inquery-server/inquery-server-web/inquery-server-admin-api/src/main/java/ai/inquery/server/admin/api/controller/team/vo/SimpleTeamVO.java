
package ai.inquery.server.admin.api.controller.team.vo;

import lombok.Data;

/**
 * team
 *
 */
@Data
public class SimpleTeamVO {

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

}
