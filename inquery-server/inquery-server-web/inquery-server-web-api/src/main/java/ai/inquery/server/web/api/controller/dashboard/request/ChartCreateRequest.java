package ai.inquery.server.web.api.controller.dashboard.request;

import lombok.Data;

/**
 * @version ChartCreateParam.java, v 0.1 June 9, 2023 15:38 moji Exp $
 */
@Data
public class ChartCreateRequest {


    /**
     * Chart name
     */
    private String name;

    /**
     * description
     */
    private String description;

    /**
     * chart information
     */
    private String schema;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Database type
     */
    private String type;

    /**
     * DB name
     */
    private String databaseName;

    /**
     * schema name
     */
    private String schemaName;

    /**
     * ddl content
     */
    private String ddl;

    /**
     * Source type: AI_CHAT, WORKSPACE, DASHBOARD
     */
    private String sourceType;

}
