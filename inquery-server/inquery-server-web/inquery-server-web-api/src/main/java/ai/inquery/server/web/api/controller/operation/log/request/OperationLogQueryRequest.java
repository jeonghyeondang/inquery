package ai.inquery.server.web.api.controller.operation.log.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;

import lombok.Data;

/**
 * @version DdlCreateRequest.java, v 0.1 September 18, 2022 11:13 moji Exp $
 */
@Data
public class OperationLogQueryRequest extends PageQueryRequest {

    /**
     * Fuzzy word search
     */
    private String searchKey;

    /**
     * Data source id
     */
    private Long dataSourceId;

    /**
     * Name database
     */
    private String databaseName;

    /**
     * schema name
     */
    private String schemaName;

    /**
     * Query source filter: WORKSPACE or AI_CHAT
     */
    private String source;
}
