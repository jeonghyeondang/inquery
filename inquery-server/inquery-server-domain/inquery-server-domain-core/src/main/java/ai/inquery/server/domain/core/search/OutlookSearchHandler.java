package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.impl.OutlookSearchResult;
import ai.inquery.server.domain.core.impl.OutlookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OutlookSearchHandler implements ExternalSearchHandler {

    @Override
    public String getServiceName() {
        return "outlook";
    }

    @Override
    public String getDisplayName() {
        return "Outlook";
    }

    @Override
    public boolean isConfigured(UserAIConfigSaveParam config) {
        return new OutlookService(config).isConfigured();
    }

    @Override
    public List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            OutlookService service = new OutlookService(config);
            List<OutlookSearchResult> results = service.searchEmails(keyword, maxResults);
            for (OutlookSearchResult er : results) {
                hits.add(SearchHit.builder()
                        .dedupKey(er.getMessageId())
                        .title(er.getSubject() != null && !er.getSubject().isBlank()
                                ? er.getSubject() : "(no subject)")
                        .subtitle(formatSubtitle(er))
                        .url(er.getWebLink())
                        .text(er.getBody())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Outlook search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return hits;
    }

    @Override
    public String buildContextEntry(SearchHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(hit.getTitle());
        if (hit.getSubtitle() != null && !hit.getSubtitle().isBlank()) {
            sb.append(" — ").append(hit.getSubtitle());
        }
        sb.append("\n");
        if (hit.getUrl() != null && !hit.getUrl().isBlank()) {
            sb.append("URL: ").append(hit.getUrl()).append("\n");
        }
        if (hit.getText() != null && !hit.getText().isBlank()) {
            sb.append(hit.getText()).append("\n\n");
        }
        return sb.toString();
    }

    @Override
    public String getNoResultsMessage() {
        return "No matching emails found in Outlook.";
    }

    @Override
    public String getNotConfiguredReason() {
        return "Outlook is not connected. Connect it in Settings > Integrations (Tenant ID, Client ID, and Connect).";
    }

    private static String formatSubtitle(OutlookSearchResult er) {
        StringBuilder sb = new StringBuilder();
        if (er.getFrom() != null && !er.getFrom().isBlank()) {
            sb.append(er.getFrom());
        }
        if (er.getReceivedDateTime() != null && !er.getReceivedDateTime().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(er.getReceivedDateTime());
        }
        return sb.toString();
    }
}
