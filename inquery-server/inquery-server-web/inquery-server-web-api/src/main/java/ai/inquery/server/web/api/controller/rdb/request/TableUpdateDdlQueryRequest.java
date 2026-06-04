package ai.inquery.server.web.api.controller.rdb.request;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * @version TableUpdateDdlQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class TableUpdateDdlQueryRequest {

    /**
     * DB type
     * @see ai.inquery.server.domain.support.enums.DbTypeEnum
     */
    @NotNull
    private String dbType;


}
