package ai.inquery.server.web.api.controller.data.source.request;


import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * @version ConnectionCreateRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DataSourceAttachRequest implements DataSourceBaseRequestInfo{

    /**
     * primary key id
     */
    @NotNull
    private Long id;

    @Override
    public Long getDataSourceId() {
        return id;
    }

    @Override
    public String getDatabaseName() {
        return null;
    }

    @Override
    public String getSchemaName() {
        return null;
    }
}
