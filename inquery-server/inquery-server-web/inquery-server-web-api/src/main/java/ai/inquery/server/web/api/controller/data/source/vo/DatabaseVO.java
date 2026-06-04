package ai.inquery.server.web.api.controller.data.source.vo;

import lombok.Data;

/**
 * @version DatabaseVO.java, v 0.1 September 16, 2022 17:24 moji Exp $
 */
@Data
public class DatabaseVO {

    /**
     * DB name
     */
    private String name;

    /**
     * DB description
     */
    private String description;

    /**
     * The number of tables or keys under DB
     */
    private Integer count;
}
