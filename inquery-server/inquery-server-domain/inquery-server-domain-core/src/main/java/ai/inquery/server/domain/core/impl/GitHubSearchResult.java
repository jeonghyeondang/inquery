package ai.inquery.server.domain.core.impl;

import lombok.Data;

@Data
public class GitHubSearchResult {
    private String type; // "issue", "pr", "code"
    private String title;
    private String body;
    private String url;
    private String repository;
    private String author;
    private String state; // "open", "closed" (issues/PRs only)
    private String createdAt;
    
    public GitHubSearchResult() {}
    
    public GitHubSearchResult(String type, String title, String body, String url, 
                             String repository, String author, String state, String createdAt) {
        this.type = type;
        this.title = title;
        this.body = body;
        this.url = url;
        this.repository = repository;
        this.author = author;
        this.state = state;
        this.createdAt = createdAt;
    }
}







