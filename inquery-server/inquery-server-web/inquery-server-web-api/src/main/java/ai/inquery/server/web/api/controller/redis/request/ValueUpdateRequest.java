package ai.inquery.server.web.api.controller.redis.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version ConnectionQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class ValueUpdateRequest extends DataSourceBaseRequest {

    /**
     * key name
     */
    @NotNull
    private String key;

    /**
     * Original key value
     */
    @NotNull
    private Object originalValue;

    /**
     * Key value after update
     */
    @NotNull
    private Object updateValue;

}
