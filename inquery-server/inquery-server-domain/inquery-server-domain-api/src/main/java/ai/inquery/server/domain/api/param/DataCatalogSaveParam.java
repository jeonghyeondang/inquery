package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Data catalog save param
 */
@Data
public class DataCatalogSaveParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * Table name
     */
    private String tableName;

    /**
     * Table description
     */
    private String tableDescription;

    /**
     * Column catalog list
     */
    private List<DataCatalogColumnSaveParam> columns;
}














