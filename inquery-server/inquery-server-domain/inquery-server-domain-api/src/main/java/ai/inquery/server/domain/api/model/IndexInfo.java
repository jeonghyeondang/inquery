package ai.inquery.server.domain.api.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Index export information
 *
 */
@Data
@Accessors(chain = true)
public class IndexInfo {
    /**
     * Index name
     */
    private String name;
    /**
     * Field
     */
    private String columnName;
    /**
     * Index type
     */
    private String indexType;
    /**
     * Index method
     */
    private String indexMethod;
    /**
     * Comment
     */
    private String comment;
}
