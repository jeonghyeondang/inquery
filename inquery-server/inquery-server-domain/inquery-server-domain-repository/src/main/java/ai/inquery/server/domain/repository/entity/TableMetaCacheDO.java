package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * Table metadata cache
 */
@Getter
@Setter
@TableName("table_meta_cache")
public class TableMetaCacheDO implements Serializable {

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
     * Schema cache ID
     */
    private Long schemaCacheId;

    /**
     * Table name
     */
    private String tableName;

    /**
     * Table type (BASE TABLE, VIEW, etc.)
     */
    private String tableType;

    /**
     * Table comment
     */
    private String comment;
}



