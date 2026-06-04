package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.domain.api.enums.ExportSizeEnum;
import ai.inquery.server.domain.api.enums.ExportTypeEnum;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @version ConnectionQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DataExportRequest extends DataSourceBaseRequest {
    /**
     * Executed SQL
     */
    private String sql;

    /**
     * Original SQL without pagination
     */
    private String originalSql;

    private Integer pageNo;
    private Integer pageSize;

    /**
     * export type
     *
     * @see ExportTypeEnum
     */
    @NotNull
    private String exportType;

    /**
     * How much data is currently needed at the beginning
     *
     * @see ExportSizeEnum
     */
    @NotNull
    private String exportSize;
}
