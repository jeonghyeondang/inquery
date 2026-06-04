package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @date: February 27, 2024 22:03
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DatabaseExportRequest extends DataSourceBaseRequest {
    private Boolean containData;
}
