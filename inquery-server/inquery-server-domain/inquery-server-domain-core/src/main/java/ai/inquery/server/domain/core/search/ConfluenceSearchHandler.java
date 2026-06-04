package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.ConfluenceService;
import ai.inquery.server.domain.core.impl.WikiSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ConfluenceSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "confluence";
    }

    @Override
    public String getDisplayName() {
        return "Confluence";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new ConfluenceService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            ConfluenceService service = new ConfluenceService(config);
            List<WikiSearchResult> results = service.searchPages(keyword, maxResults);
            for (WikiSearchResult wr : results) {
                hits.add(SearchHit.builder()
                        .dedupKey(wr.getPageId())
                        .title(wr.getTitle())
                        .url(wr.getUrl())
                        .text(wr.getContent())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Confluence search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public void enrich(List<SearchHit> hits, int topN, UserAIConfigSaveParam config) {
        ConfluenceService service = new ConfluenceService(config);
        int fetchCount = Math.min(topN, hits.size());
        for (int i = 0; i < fetchCount; i++) {
            SearchHit hit = hits.get(i);
            if (hit.getText() == null || hit.getText().isBlank()) {
                try {
                    String fullContent = service.fetchPageContent(hit.getDedupKey());
                    hit.setText(fullContent);
                } catch (Exception e) {
                    log.warn("Confluence page content fetch failed for pageId={}: {}",
                            hit.getDedupKey(), e.getMessage());
                }
            }
        }
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle()).append("\n");
        sb.append("URL: ").append(hit.getUrl()).append("\n");
        if (hit.getText() != null && !hit.getText().isBlank()) {
            sb.append(hit.getText()).append("\n\n");
        }
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching pages found in Confluence.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "Confluence credentials are not configured.";
    }
}
