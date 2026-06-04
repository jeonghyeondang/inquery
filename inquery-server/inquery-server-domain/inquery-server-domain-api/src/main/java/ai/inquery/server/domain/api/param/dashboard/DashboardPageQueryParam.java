package ai.inquery.server.domain.api.param.dashboard;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;

import lombok.Data;

/**
 * @version UserSavedDdlPageQueryParam.java, v 0.1 September 25, 2022 14:05 moji Exp $
 */
@Data
public class DashboardPageQueryParam extends PageQueryParam {

    /**
     * search keyword
     */
    private String searchKey;

    /**
     * user id
     */
    private Long userId;

}
