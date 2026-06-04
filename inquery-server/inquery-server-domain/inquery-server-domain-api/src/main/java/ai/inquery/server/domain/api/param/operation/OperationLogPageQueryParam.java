package ai.inquery.server.domain.api.param.operation;

import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;

import lombok.Data;

/**
 * @version UserExecutedDdlPageQueryParam.java, v 0.1 September 25, 2022 14:05 moji Exp $
 */
@Data
public class OperationLogPageQueryParam extends PageQueryParam {

    /**
     * user id
     */
    private Long userId;

    /**
     * search keyword
     */
    private String searchKey;

    /**
     * Data source id
     */
    private Long dataSourceId;

    /**
     * database name
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
