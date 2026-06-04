package ai.inquery.server.domain.api.chart;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * query
 *
 */
@Data
@NoArgsConstructor
public class ChartListQueryParam {

    /**
     * primary key list (for listByIds)
     */
    private List<Long> idList;

    /**
     * user id
     */
    private Long userId;

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
