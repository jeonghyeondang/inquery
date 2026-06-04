package ai.inquery.server.web.api.controller.dashboard.request;

import java.util.List;

import lombok.Data;

/**
 * @version DashboardSaveParam.java, v 0.1 June 9, 2023 15:29 moji Exp $
 */
@Data
public class DashboardUpdateRequest {

    /**
     * primary key
     */
    private Long id;


    /**
     * Dashboard name
     */
    private String name;

    /**
     * Dashboard layout information
     */
    private String schema;

    /**
     * Chart ID list
     */
    private List<Long> chartIds;

    /**
     * Dashboard description
     */
    private String description;

    /**
     * Refresh rule: NONE, 1MIN, 10MIN, 1HOUR, 1DAY
     */
    private String refreshRule;
}
