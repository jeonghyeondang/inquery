package ai.inquery.server.web.api.controller.dashboard.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * @version Dashboard.java, v 0.1 June 9, 2023 15:32 moji Exp $
 */
@Data
public class DashboardVO {

    /**
     * primary key
     */
    private Long id;

    /**
     * creation time
     */
    private Date gmtCreate;

    /**
     * modified time
     */
    private Date gmtModified;

    /**
     * Dashboard name
     */
    private String name;

    /**
     * Dashboard description
     */
    private String description;

    /**
     * Dashboard layout information
     */
    private String schema;

    /**
     * Chart ID list
     */
    private List<Long> chartIds;

    /**
     * Refresh rule: NONE, 1MIN, 10MIN, 1HOUR, 1DAY
     */
    private String refreshRule;

    /**
     * Unique token for public sharing
     */
    private String shareToken;

    /**
     * Whether dashboard is publicly accessible
     */
    private Boolean isPublic;
}
