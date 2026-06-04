package ai.inquery.spi.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleTable implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * Table Name
     */
    @JsonAlias({"TABLE_NAME"})
    private String name;

    /**
     * description
     */
    @JsonAlias({"REMARKS"})
    private String comment;

    /**
     * Database name
     */
    @JsonAlias("TABLE_CAT")
    private String databaseName;

    /**
     * Schema name
     */
    @JsonAlias({"TABLE_SCHEM"})
    private String schemaName;
}
