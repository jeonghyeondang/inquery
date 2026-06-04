package ai.inquery.server.domain.core.impl;

import lombok.Data;

@Data
public class JiraSearchResult {
    private String issueKey;
    private String summary;
    private String description;
    private String url;
    private String issueType;
    private String status;
    
    public JiraSearchResult() {}
    
    public JiraSearchResult(String issueKey, String summary, String description, String url, String issueType, String status) {
        this.issueKey = issueKey;
        this.summary = summary;
        this.description = description;
        this.url = url;
        this.issueType = issueType;
        this.status = status;
    }
}







