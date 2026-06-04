package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * Data catalog column save param
 */
@Data
public class DataCatalogColumnSaveParam implements Serializable {

    private static final long serialVersionUID = 1L;

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

    /**
     * Column ordinal position (DDL order)
     */
    private Integer ordinalPosition;
}














