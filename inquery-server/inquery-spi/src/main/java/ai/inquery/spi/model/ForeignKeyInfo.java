package ai.inquery.spi.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Foreign Key information for ERD visualization
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeignKeyInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Constraint name
     */
    @JsonAlias({"CONSTRAINT_NAME", "constraint_name"})
    private String constraintName;

    /**
     * Source database name (containing the FK column)
     */
    @JsonAlias({"FK_TABLE_CATALOG", "fk_table_catalog"})
    private String fkDatabaseName;

    /**
     * Source schema name (containing the FK column)
     */
    @JsonAlias({"FK_TABLE_SCHEMA", "fk_table_schema"})
    private String fkSchemaName;

    /**
     * Source table name (containing the FK column)
     */
    @JsonAlias({"FK_TABLE_NAME", "fk_table_name"})
    private String fkTableName;

    /**
     * Source column name (the FK column)
     */
    @JsonAlias({"FK_COLUMN_NAME", "fk_column_name"})
    private String fkColumnName;

    /**
     * Referenced database name (containing the PK/unique column)
     */
    @JsonAlias({"PK_TABLE_CATALOG", "pk_table_catalog"})
    private String pkDatabaseName;

    /**
     * Referenced schema name (containing the PK/unique column)
     */
    @JsonAlias({"PK_TABLE_SCHEMA", "pk_table_schema"})
    private String pkSchemaName;

    /**
     * Referenced table name (containing the PK/unique column)
     */
    @JsonAlias({"PK_TABLE_NAME", "pk_table_name"})
    private String pkTableName;

    /**
     * Referenced column name (the PK/unique column)
     */
    @JsonAlias({"PK_COLUMN_NAME", "pk_column_name"})
    private String pkColumnName;

    /**
     * Key sequence number (for composite keys)
     */
    @JsonAlias({"KEY_SEQ", "key_seq"})
    private Integer keySequence;

    /**
     * Update rule (CASCADE, SET NULL, NO ACTION, etc.)
     */
    private String updateRule;

    /**
     * Delete rule (CASCADE, SET NULL, NO ACTION, etc.)
     */
    private String deleteRule;
}



