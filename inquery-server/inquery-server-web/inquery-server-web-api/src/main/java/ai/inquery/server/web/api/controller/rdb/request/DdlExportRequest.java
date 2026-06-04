package ai.inquery.server.web.api.controller.rdb.request;

import jakarta.validation.constraints.NotNull;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @version ConnectionQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DdlExportRequest extends DataSourceBaseRequest {

    /**
     * Name
     */
    @NotNull
    private String name;

}
