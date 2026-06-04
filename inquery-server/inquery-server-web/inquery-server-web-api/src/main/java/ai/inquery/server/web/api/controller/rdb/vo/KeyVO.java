package ai.inquery.server.web.api.controller.rdb.vo;

import java.util.List;

import lombok.Data;

/**
 * @version IndexVO.java, v 0.1 September 16, 2022 17:47 moji Exp $
 */
@Data
public class KeyVO {

    /**
     * Contains columns
     */
    private String columns;

    /**
     * Index name
     */
    private String name;

    /**
     * Comment
     */
    private String comment;

    /**
     * Columns included in the index
     */
    private List<ColumnVO> columnList;
}
