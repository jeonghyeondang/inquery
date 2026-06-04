
package ai.inquery.server.domain.api.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 */
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class SchemaOperationParam {
    String databaseName;
    String schemaName;
    String newSchemaName;
}