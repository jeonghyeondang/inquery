package ai.inquery.server.web.api.controller.rdb.request;

import lombok.Data;

/**
 * Data catalog column save request
 */
@Data
public class DataCatalogColumnSaveRequest {

    /**
     * Column name
     */
    private String columnName;

    /**
     * Column description
     */
    private String columnDescription;

    /**
     * Schema information (JSON format)
     */
    private String schemaInfo;

    /**
     * Example values (JSON array)
     */
    private String exampleValues;
}














