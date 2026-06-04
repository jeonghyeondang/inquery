package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.GitHubSearchResult;
import ai.inquery.server.domain.core.impl.GitHubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class GitHubSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "github";
    }

    @Override
    public String getDisplayName() {
        return "GitHub";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new GitHubService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            GitHubService service = new GitHubService(config);
            List<GitHubSearchResult> results = service.searchCode(keyword, maxResults);
            for (GitHubSearchResult r : results) {
                String key = (r.getRepository() == null ? "" : r.getRepository()) + "::" + r.getTitle();
                hits.add(SearchHit.builder()
                        .dedupKey(key)
                        .title(r.getTitle())
                        .url(r.getUrl())
                        .text(r.getBody())
                        .build());
            }
        } catch (Exception e) {
            log.warn("GitHub search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle()).append("\n");
        if (hit.getUrl() != null && !hit.getUrl().isBlank()) {
            sb.append("URL: ").append(hit.getUrl()).append("\n");
        }
        if (hit.getText() != null && !hit.getText().isBlank()) {
            String snippet = hit.getText();
            if (snippet.length() > 1500) {
                snippet = snippet.substring(0, 1500) + "...";
            }
            sb.append("```\n").append(snippet).append("\n```\n\n");
        }
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching code found in GitHub.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "GitHub token is not configured.";
    }
}
