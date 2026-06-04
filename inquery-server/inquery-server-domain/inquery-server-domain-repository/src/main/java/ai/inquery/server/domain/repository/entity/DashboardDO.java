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
 * @since 2023-09-02
 */
@Getter
@Setter
@TableName("dashboard")
public class DashboardDO implements Serializable {

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
     * Dashboard name
     */
    private String name;

    /**
     * Dashboard description
     */
    private String description;

    /**
     * Dashboard layout information
     */
    private String schema;

    /**
     * Whether it has been deleted, y means deleted, n means not deleted
     */
    private String deleted;

    /**
     * user id
     */
    private Long userId;

    /**
     * Refresh rule: NONE, 1MIN, 10MIN, 1HOUR, 1DAY
     */
    private String refreshRule;

    /**
     * Unique token for public sharing (UUID)
     */
    private String shareToken;

    /**
     * Whether dashboard is publicly accessible (y/n)
     */
    private String isPublic;
}
