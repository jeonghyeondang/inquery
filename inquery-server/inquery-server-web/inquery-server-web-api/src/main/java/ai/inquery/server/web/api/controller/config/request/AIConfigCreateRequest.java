
package ai.inquery.server.web.api.controller.config.request;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 */
@Data
public class AIConfigCreateRequest {

    /**
     * APIKEY
     */
    private String apiKey;

    /**
     * SECRETKEY
     */
    private String secretKey;

    /**
     * APIHOST
     */
    private String apiHost;

    /**
     * api http proxy host
     */
    private String httpProxyHost;

    /**
     * api http proxy port
     */
    private String httpProxyPort;

    /**
     * @see AiSqlSourceEnum
     */
    @NotNull
    private String aiSqlSource;

    /**
     * return data stream
     * Optional, default value is TRUE
     */
    private Boolean stream = Boolean.TRUE;

    /**
     * deployed model, default gpt-3.5-turbo
     */
    private String model;

    /**
     * Whether this provider is allowed to be picked by the runtime router.
     * Nullable: a missing value is interpreted as "no change" so existing
     * clients that don't send the flag don't accidentally disable a key.
     * The persisted system_config default for absent rows is "enabled".
     */
    private Boolean enabled;
}
