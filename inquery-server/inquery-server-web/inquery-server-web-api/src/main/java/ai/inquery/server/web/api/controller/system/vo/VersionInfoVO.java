package ai.inquery.server.web.api.controller.system.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Version information
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VersionInfoVO {
    /**
     * Backend version
     */
    private String backendVersion;

    /**
     * Frontend version
     */
    private String frontendVersion;

    /**
     * Build time (timestamp)
     */
    private Long buildTime;

    /**
     * Environment (dev, prod, etc.)
     */
    private String environment;
}



