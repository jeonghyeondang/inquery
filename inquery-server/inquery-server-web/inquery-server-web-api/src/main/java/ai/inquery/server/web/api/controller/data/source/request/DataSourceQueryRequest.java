package ai.inquery.server.web.api.controller.data.source.request;

import ai.inquery.server.tools.base.wrapper.request.PageQueryRequest;
import lombok.Data;

/**
 * @version ConnectionQueryRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DataSourceQueryRequest extends PageQueryRequest {

    /**
     * Alias fuzzy search terms
     */
    private String searchKey;
    /**
     * Connection Type
     *
     * @see ai.inquery.server.domain.api.enums.DataSourceKindEnum
     */
    private String kind;
}
