
package ai.inquery.spi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Trigger  implements Serializable {
    private static final long serialVersionUID = 1L;

    private String databaseName;

    private String schemaName;

    private String triggerName;

    private String eventManipulation;

    private String triggerBody;

}