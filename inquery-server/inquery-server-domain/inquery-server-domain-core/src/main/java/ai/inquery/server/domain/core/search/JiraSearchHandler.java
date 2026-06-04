package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.JiraSearchResult;
import ai.inquery.server.domain.core.impl.JiraService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class JiraSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "jira";
    }

    @Override
    public String getDisplayName() {
        return "Jira";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new JiraService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            JiraService service = new JiraService(config);
            List<JiraSearchResult> results = service.searchByKeyword(keyword, maxResults);
            for (JiraSearchResult jr : results) {
                hits.add(SearchHit.builder()
                        .dedupKey(jr.getIssueKey())
                        .title(jr.getIssueKey() + ": " + nullSafe(jr.getSummary()))
                        .subtitle("Type: " + nullSafe(jr.getIssueType()) + " | Status: " + nullSafe(jr.getStatus()))
                        .url(jr.getUrl())
                        .text(jr.getDescription())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Jira search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle()).append("\n");
        if (hit.getUrl() != null) {
            sb.append("URL: ").append(hit.getUrl()).append("\n");
        }
        if (hit.getSubtitle() != null && !hit.getSubtitle().isBlank()) {
            sb.append(hit.getSubtitle()).append("\n");
        }
        if (hit.getText() != null && !hit.getText().isBlank()) {
            sb.append(hit.getText()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching issues found in Jira.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "Jira credentials are not configured.";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
