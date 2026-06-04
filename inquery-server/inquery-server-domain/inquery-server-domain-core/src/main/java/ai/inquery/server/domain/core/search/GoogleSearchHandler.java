package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.GoogleDriveSearchResult;
import ai.inquery.server.domain.core.impl.GoogleDriveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class GoogleSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "google";
    }

    @Override
    public String getDisplayName() {
        return "Google Drive";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new GoogleDriveService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            GoogleDriveService service = new GoogleDriveService(config);
            List<GoogleDriveSearchResult> results = service.searchFiles(keyword, maxResults);
            for (GoogleDriveSearchResult r : results) {
                hits.add(SearchHit.builder()
                        .dedupKey(r.getFileId())
                        .title(r.getTitle())
                        .subtitle(describeMime(r.getMimeType()))
                        .url(r.getUrl())
                        .text(r.getContent())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Google Drive search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public void enrich(List<SearchHit> hits, int topN, UserAIConfigSaveParam config) {
        GoogleDriveService service = new GoogleDriveService(config);
        int fetchCount = Math.min(topN, hits.size());
        for (int i = 0; i < fetchCount; i++) {
            SearchHit hit = hits.get(i);
            if (hit.getText() == null || hit.getText().isBlank()) {
                String mime = "Google Sheet".equals(hit.getSubtitle())
                        ? "application/vnd.google-apps.spreadsheet"
                        : "application/vnd.google-apps.document";
                try {
                    hit.setText(service.fetchContent(hit.getDedupKey(), mime));
                } catch (Exception e) {
                    log.warn("Google Drive content fetch failed for fileId={}: {}",
                            hit.getDedupKey(), e.getMessage());
                }
            }
        }
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle());
        if (hit.getSubtitle() != null && !hit.getSubtitle().isBlank()) {
            sb.append(" (").append(hit.getSubtitle()).append(")");
        }
        sb.append("\n");
        sb.append("URL: ").append(hit.getUrl()).append("\n");
        if (hit.getText() != null && !hit.getText().isBlank()) {
            sb.append(hit.getText()).append("\n\n");
        }
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching Google Docs or Sheets found in Google Drive.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "Google Drive is not connected. Connect it in Settings > Integrations.";
    }

    private String describeMime(String mimeType) {
        if ("application/vnd.google-apps.spreadsheet".equals(mimeType)) {
            return "Google Sheet";
        }
        if ("application/vnd.google-apps.document".equals(mimeType)) {
            return "Google Doc";
        }
        return "Google Drive";
    }
}
