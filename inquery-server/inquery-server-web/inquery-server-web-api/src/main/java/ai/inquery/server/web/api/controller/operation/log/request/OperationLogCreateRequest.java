package ai.inquery.server.web.api.controller.operation.log.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version DdlCreateRequest.java, v 0.1 September 18, 2022 11:13 moji Exp $
 */
@Data
public class OperationLogCreateRequest extends DataSourceBaseRequest {

    /**
     * file alias
     */
    private String name;

    /**
     * ddl type
     */
    @NotNull
    private String type;

    /**
     * ddl content
     */
    @NotNull
    private String ddl;
}
