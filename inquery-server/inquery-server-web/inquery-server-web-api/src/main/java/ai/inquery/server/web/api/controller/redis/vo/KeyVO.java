package ai.inquery.server.web.api.controller.redis.vo;

import lombok.Data;

/**
 * @version Table.java, v 0.1 September 16, 2022 17:16 moji Exp $
 */
@Data
public class KeyVO {

    /**
     * key name
     */
    private String name;

    /**
     * key value
     */
    private Object value;

    /**
     * key type
     */
    private String type;

    /**
     * Expiration
     */
    private Long ttl;
}
