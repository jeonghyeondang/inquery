package ai.inquery.server.domain.api.param.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @date: 2024-02-27 22:08
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DatabaseExportParam {
    /**
     * DB name
     */
    private String databaseName;

    private String schemaName;

    private Boolean containData;

}
