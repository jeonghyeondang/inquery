package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * Custom dashboard
 * </p>
 *
 * @since 2023-09-09
 */
@Getter
@Setter
@TableName("chart")
public class ChartDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * creation time
     */
    private Date gmtCreate;

    /**
     * modified time
     */
    private Date gmtModified;

    /**
     * Chart name
     */
    private String name;

    /**
     * Chart description
     */
    private String description;

    /**
     * chart information
     */
    private String schema;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Database type
     */
    private String type;

    /**
     * DB name
     */
    private String databaseName;


    private String schemaName;

    /**
     * DDL content
     */
    private String ddl;

    /**
     * Whether it has been deleted, 'Y' means deleted, 'N' means not deleted
     */
    private String deleted;

    /**
     * user id
     */
    private Long userId;

    /**
     * Source type: AI_CHAT, WORKSPACE, DASHBOARD
     */
    private String sourceType;
}
