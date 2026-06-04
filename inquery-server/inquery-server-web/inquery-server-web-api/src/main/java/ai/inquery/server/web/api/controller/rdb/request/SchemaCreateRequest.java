package ai.inquery.server.web.api.controller.rdb.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class SchemaCreateRequest extends DataSourceBaseRequest {

    /**
     * Data name
     */
    @JsonAlias({"TABLE_SCHEM"})
    private String name;


    private String comment;


    private String owner;
}
