package ai.inquery.server.web.api.controller.rdb.request;


import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Query sequence detail request
 *
 */
@Data
public class SequenceDetailQueryRequest extends DataSourceBaseRequest {

    /**
     * Sequence Name
     */
    @NotNull
    private String sequenceName;

}
