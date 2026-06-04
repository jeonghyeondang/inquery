
package ai.inquery.server.domain.api.model;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 */
@Data
public class AIConfig {

    /**
     * APIKEY
     */
    private String apiKey = "";

    /**
     * SECRETKEY
     */
    private String secretKey = "";

    /**
     * APIHOST
     */
    private String apiHost = "";

    /**
     * api http proxy host
     */
    private String httpProxyHost = "";

    /**
     * api http proxy port
     */
    private String httpProxyPort = "";

    /**
     * @see AiSqlSourceEnum
     */
    @NotNull
    private String aiSqlSource = "";

    /**
     * return data stream
     * Optional, default value is TRUE
     */
    private Boolean stream = Boolean.TRUE;

    /**
     * deployed model, default gpt-3.5-turbo
     */
    private String model = "";

    /**
     * Whether this provider is selectable by the runtime router. Defaults to
     * true so providers configured before this flag existed remain active.
     */
    private Boolean enabled = Boolean.TRUE;
}
