package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * Table lineage information entity
 * Stores the source query that created/populates a table (for mart tables)
 *
 */
@Getter
@Setter
@TableName("table_lineage")
public class TableLineageDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Creation time
     */
    private Date gmtCreate;

    /**
     * Modified time
     */
    private Date gmtModified;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * Target table name (the mart table)
     */
    private String tableName;

    /**
     * Source query - the full SQL query that creates/populates this table
     */
    @TableField(value = "source_query")
    private String sourceQuery;

    /**
     * Source tables - comma-separated list of source table names (extracted from query)
     * Example: "LOG_PROD.APP.user_events,LOG_PROD.APP.user_sessions"
     */
    @TableField(value = "source_tables")
    private String sourceTables;

    /**
     * Description - user-provided description of what this lineage represents
     */
    @TableField(value = "description", insertStrategy = FieldStrategy.IGNORED, updateStrategy = FieldStrategy.IGNORED)
    private String description;

    /**
     * User ID who created/modified this lineage
     */
    private Long userId;
}

