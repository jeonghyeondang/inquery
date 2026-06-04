package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.SlackSearchResult;
import ai.inquery.server.domain.core.impl.SlackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SlackSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "slack";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new SlackService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            SlackService service = new SlackService(config);
            List<SlackSearchResult> results = service.searchByKeyword(keyword, maxResults);
            for (SlackSearchResult sr : results) {
                hits.add(SearchHit.builder()
                        .dedupKey(sr.getChannelId() + ":" + sr.getMessageTs())
                        .title("#" + nullSafe(sr.getChannelName()))
                        .subtitle(sr.getUserName())
                        .url(sr.getMessageUrl())
                        .text(sr.getMessageText())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Slack search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle());
        if (hit.getSubtitle() != null && !hit.getSubtitle().isBlank()) {
            sb.append(" - ").append(hit.getSubtitle());
        }
        sb.append("\n");
        if (hit.getUrl() != null) {
            sb.append("URL: ").append(hit.getUrl()).append("\n");
        }
        if (hit.getText() != null && !hit.getText().isBlank()) {
            sb.append(hit.getText()).append("\n\n");
        }
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching messages found in Slack.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "Slack token is not configured.";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
