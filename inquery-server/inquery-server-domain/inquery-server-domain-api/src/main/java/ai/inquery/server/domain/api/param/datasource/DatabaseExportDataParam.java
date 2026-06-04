package ai.inquery.server.domain.api.param.datasource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @date: 2024-03-24 13:17
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DatabaseExportDataParam {
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String exportType;
    private List<String> tableNames;
    private String sqyType;
    private Boolean containsHeader;

}