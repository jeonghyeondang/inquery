package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @date: 2024-03-24 12:36
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DatabaseExportDataRequest extends DataSourceBaseRequest {
    @NotNull
    private String exportType;
    @NotEmpty
    private List<String> tableNames;
    /**
     * single: single-row insert, multi: multi-row insert, update: update statement
     */
    private String sqyType;
    private Boolean containsHeader;
}