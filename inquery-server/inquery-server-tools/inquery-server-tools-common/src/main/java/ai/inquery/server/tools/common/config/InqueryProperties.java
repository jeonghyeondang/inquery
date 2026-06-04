package ai.inquery.server.tools.common.config;

import ai.inquery.server.tools.common.enums.ModeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @version SystemProperties.java, v 0.1 November 13, 2022 14:28 moji Exp $
 */
@Configuration
@ConfigurationProperties(prefix = "inquery")
@Data
public class InqueryProperties {

    /**
     * version
     */
    private String version;

    /**
     * gateway
     */
    private GatewayProperties gateway;

    /**
     * mode
     */
    private ModeEnum mode;

    @Data
    public static class GatewayProperties {

        private String baseUrl;
        private String modelBaseUrl;

    }
}
