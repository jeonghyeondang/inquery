package ai.inquery.server.web.api.controller.redis.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version TableManageRequest.java, v 0.1 September 16, 2022 17:55 moji Exp $
 */
@Data
public class KeyValueManageRequest extends DataSourceBaseRequest {

    /**
     * redis ddl statement
     */
    @NotNull
    private String ddl;
}
