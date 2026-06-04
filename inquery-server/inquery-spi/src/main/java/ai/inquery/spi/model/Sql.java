package ai.inquery.spi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * sql object
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Sql  implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * sql
     */
    private String sql;

}
