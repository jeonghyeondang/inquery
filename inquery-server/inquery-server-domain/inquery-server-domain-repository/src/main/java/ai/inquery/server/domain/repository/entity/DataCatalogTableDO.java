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
 * <p>
 * Data catalog table information
 * </p>
 *
 * @since 2025-01-27
 */
@Getter
@Setter
@TableName("data_catalog_table")
public class DataCatalogTableDO implements Serializable {

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
     * Table name
     */
    private String tableName;

    /**
     * Table description
     */
    @TableField(value = "table_description", insertStrategy = FieldStrategy.IGNORED, updateStrategy = FieldStrategy.IGNORED)
    private String tableDescription;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Whether this table is active for AI queries (0=inactive, 1=active)
     * Default: 1 (active)
     */
    @TableField(value = "active")
    private Short active;
}









