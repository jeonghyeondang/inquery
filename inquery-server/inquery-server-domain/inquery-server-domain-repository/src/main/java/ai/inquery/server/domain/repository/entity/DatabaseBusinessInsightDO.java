package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Database Business Insight Entity
 */
@Data
@TableName("database_business_insight")
public class DatabaseBusinessInsightDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Data Source ID
     */
    private Long dataSourceId;

    /**
     * Database Name
     */
    private String databaseName;

    /**
     * Google Play Store Link
     */
    private String playStoreLink;

    /**
     * Apple App Store Link
     */
    private String appStoreLink;

    /**
     * Web Site Link
     */
    private String webLink;

    /**
     * Business Insight Content (Markdown)
     */
    private String insightContent;

    /**
     * Reference Links (JSON string)
     */
    private String referenceLinks;

    /**
     * Create Time
     */
    private LocalDateTime createTime;

    /**
     * Update Time
     */
    private LocalDateTime updateTime;
}
