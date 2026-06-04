package ai.inquery.server.web.api.controller.erd.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Table information for ERD visualization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ERDTableVO {

    /**
     * Table name
     */
    private String name;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * Table comment
     */
    private String comment;

    /**
     * Table type (e.g., BASE TABLE, VIEW, MATERIALIZED VIEW)
     */
    private String type;

    /**
     * List of columns in this table
     */
    private List<ERDColumnVO> columns;
}

