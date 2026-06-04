package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class BusinessInsightDTO implements Serializable {
    private Long id;
    private Long dataSourceId;
    private String databaseName;
    private String playStoreLink;
    private String appStoreLink;
    private String webLink;
    private String insightContent;
    private String referenceLinks;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
