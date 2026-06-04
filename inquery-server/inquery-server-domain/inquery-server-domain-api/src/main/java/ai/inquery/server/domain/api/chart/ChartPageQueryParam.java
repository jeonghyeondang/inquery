package ai.inquery.server.domain.api.chart;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;

import lombok.Data;

/**
 * @version UserSavedDdlPageQueryParam.java, v 0.1 September 25, 2022 14:05 moji Exp $
 */
@Data
public class ChartPageQueryParam extends PageQueryParam {

    /**
     * Report ID
     */
    private Long dashboardId;

    /**
     * search keyword
     */
    private String searchKey;

}
