package ai.inquery.server.web.api.controller.redis.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version TableVO.java, v 0.1 September 16, 2022 17:16 moji Exp $
 */
@Data
public class KeyCreateRequest extends DataSourceBaseRequest {

    /**
     * key name
     */
    @NotNull
    private String name;

    /**
     * key value
     */
    private Object value;

    /**
     * Expiration
     */
    private Long ttl;
}
