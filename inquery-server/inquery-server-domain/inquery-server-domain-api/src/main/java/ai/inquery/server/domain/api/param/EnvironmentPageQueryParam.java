package ai.inquery.server.domain.api.param;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import lombok.Data;

/**
 * environment
 *
 */
@Data
public class EnvironmentPageQueryParam extends PageQueryParam {

    /**
     * search keyword
     */
    private String searchKey;
}
