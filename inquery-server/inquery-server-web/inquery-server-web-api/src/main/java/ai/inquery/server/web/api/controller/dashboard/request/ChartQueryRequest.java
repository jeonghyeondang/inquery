package ai.inquery.server.web.api.controller.dashboard.request;

import java.util.List;

import lombok.Data;

/**
 * @version ChartQueryRequest.java, v 0.1 June 9, 2023 17:46 moji Exp $
 */
@Data
public class ChartQueryRequest {

    /**
     * Chart ID list
     */
    private List<Long> ids;
}
