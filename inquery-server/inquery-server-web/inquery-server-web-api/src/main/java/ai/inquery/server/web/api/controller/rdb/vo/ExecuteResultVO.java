package ai.inquery.server.web.api.controller.rdb.vo;

import java.util.List;


import ai.inquery.spi.model.Header;
import lombok.Data;

/**
 * @version ExecuteResultVO.java, v 0.1 October 23, 2022 11:20 moji Exp $
 */
@Data
public class ExecuteResultVO {

    /**
     * executed sql
     */
    private String sql;

    /**
     * Original SQL without pagination
     */
    private String originalSql;

    /**
     * description
     */
    private String description;

    /**
     * Failure message prompt
     */
    private String message;

    /**
     * success flag
     */
    private Boolean success;

    /**
     * Modify the number of rows and query sql will not return
     */
    private Integer updateCount;

    /**
     * List of display headers
     */
    private List<Header> headerList;

    /**
     * list of data
     */
    private List<List<String>> dataList;

    /**
     * sql type
     *
     */
    private String sqlType;

    /**
     * Whether there is a next page
     * Only available for select statements
     */
    private Boolean hasNextPage;

    /**
     * Page coding
     * Only available for select statements
     */
    private Integer pageNo;

    /**
     * Paging Size
     * Only available for select statements
     */
    private Integer pageSize;

    /**
     * Total number of fuzzy rows
     * Only select statements have
     */
    private String fuzzyTotal;

    /**
     * execution duration
     */
    private Long duration;

    /**
     * Whether the returned result can be edited
     */
    private boolean canEdit;

    /**
     * Table Name
     */
    private String tableName;
}
