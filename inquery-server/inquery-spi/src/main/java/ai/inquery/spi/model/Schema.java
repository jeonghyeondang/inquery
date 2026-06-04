
package ai.inquery.spi.model;

import java.io.Serializable;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Schema implements Serializable {
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    /**
     * databaseName
     */
    @JsonAlias({"TABLE_CATALOG","table_catalog"})
    private String databaseName;
    /**
     * Data name
     */
    @JsonAlias({"TABLE_SCHEM","table_schem"})
    private String name;


    private String comment;


    private String owner;
}