package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Data catalog save request
 */
@Data
public class DataCatalogSaveRequest extends DataSourceBaseRequest {

    /**
     * Table name
     */
    @NotNull
    private String tableName;

    /**
     * Table description
     */
    private String tableDescription;

    /**
     * Column catalog list
     */
    private List<DataCatalogColumnSaveRequest> columns;
}














