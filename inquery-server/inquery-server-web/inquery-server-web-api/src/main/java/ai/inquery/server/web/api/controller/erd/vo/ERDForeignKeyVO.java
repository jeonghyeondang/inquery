package ai.inquery.server.web.api.controller.erd.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Foreign key information for ERD visualization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ERDForeignKeyVO {

    /**
     * Constraint name
     */
    private String constraintName;

    /**
     * Referenced schema name
     */
    private String referencedSchema;

    /**
     * Referenced table name
     */
    private String referencedTable;

    /**
     * Referenced column name
     */
    private String referencedColumn;

    /**
     * Whether the relationship was inferred from naming conventions instead of an explicit FK constraint.
     */
    private Boolean inferred;
}



