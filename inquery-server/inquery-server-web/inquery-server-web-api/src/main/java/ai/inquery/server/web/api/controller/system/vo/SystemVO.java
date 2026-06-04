package ai.inquery.server.web.api.controller.system.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * system
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SystemVO {
    /**
     * The unique ID of the  system
     */
    private String systemUuid;
}
