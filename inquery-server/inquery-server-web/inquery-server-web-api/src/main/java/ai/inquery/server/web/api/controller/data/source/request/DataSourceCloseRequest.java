package ai.inquery.server.web.api.controller.data.source.request;


import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * @version ConnectionCreateRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DataSourceCloseRequest {

    /**
     * primary key id
     */
    @NotNull
    private Long id;

}
