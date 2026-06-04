package ai.inquery.server.domain.core.impl;

import lombok.Data;

@Data
public class WikiSearchResult {
    private String pageId;
    private String title;
    private String url;
    private String content;
    
    public WikiSearchResult() {}
    
    public WikiSearchResult(String pageId, String title, String url, String content) {
        this.pageId = pageId;
        this.title = title;
        this.url = url;
        this.content = content;
    }
}







