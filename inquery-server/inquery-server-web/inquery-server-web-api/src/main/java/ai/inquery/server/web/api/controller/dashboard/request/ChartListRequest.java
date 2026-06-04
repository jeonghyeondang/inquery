package ai.inquery.server.web.api.controller.dashboard.request;

import lombok.Data;

/**
 * Request for listing charts with filters and pagination
 */
@Data
public class ChartListRequest {

    /**
     * Search keyword for chart name
     */
    private String searchKey;

    /**
     * Sort by: recent, source, name, vizType
     */
    private String sortBy;

    /**
     * Only show charts created by the current user
     */
    private Boolean onlyMine;

    /**
     * Page number (1-based)
     */
    private Integer pageNo;

    /**
     * Page size
     */
    private Integer pageSize;

}
