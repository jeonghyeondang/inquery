package ai.inquery.server.web.api.controller.erd.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Column information for ERD visualization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ERDColumnVO {

    /**
     * Column name
     */
    private String name;

    /**
     * Column data type (e.g., VARCHAR(255), NUMBER(38,0))
     */
    private String dataType;

    /**
     * Whether this column is a primary key
     */
    private Boolean isPrimaryKey;

    /**
     * Whether this column is nullable
     */
    private Boolean isNullable;

    /**
     * Foreign key information (if this column has FK)
     */
    private ERDForeignKeyVO foreignKey;

    /**
     * Column comment
     */
    private String comment;

    /**
     * Column position in table
     */
    private Integer ordinalPosition;
}



