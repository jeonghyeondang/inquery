package ai.inquery.server.web.api.controller.redis.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version ConnectionQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class KeyQueryRequest extends DataSourceBaseRequest {

    /**
     * Cache key name
     */
    private String keyName;

    /**
     * search keyword
     */
    private String searchKey;
}
