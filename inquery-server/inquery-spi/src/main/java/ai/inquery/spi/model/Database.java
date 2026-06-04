package ai.inquery.spi.model;

import java.io.Serializable;
import java.util.List;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * database
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Database implements Serializable {
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;
    /**
     * Database name
     */
    @JsonAlias({"TABLE_CAT"})
    private String name;

    /**
     * schema name
     */
    private List<Schema> schemas;


    private String comment;

    private String charset;

    private String collation;

    private String owner;
}
