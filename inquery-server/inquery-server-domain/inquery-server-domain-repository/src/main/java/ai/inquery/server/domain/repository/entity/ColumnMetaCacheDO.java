package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * Column metadata cache
 */
@Getter
@Setter
@TableName("column_meta_cache")
public class ColumnMetaCacheDO implements Serializable {

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
     * Table cache ID
     */
    private Long tableCacheId;

    /**
     * Column name
     */
    private String columnName;

    /**
     * Data type
     */
    private String dataType;

    /**
     * Is primary key (0=false, 1=true)
     */
    private Short isPrimaryKey;

    /**
     * Is nullable (0=false, 1=true)
     */
    private Short isNullable;

    /**
     * Column comment
     */
    private String comment;

    /**
     * Ordinal position
     */
    private Integer ordinalPosition;
}



