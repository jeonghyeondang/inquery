package ai.inquery.server.domain.core.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Normalized representation of one external-search hit.
 * Each {@link ExternalSearchHandler} maps its native result type
 * (WikiSearchResult / SlackSearchResult / JiraSearchResult) into this
 * structure so the orchestrator can dedup and render uniformly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchHit {

    /** Stable identifier for dedup (pageId / channel:ts / issueKey). */
    private String dedupKey;

    /** Title or heading shown in the rendered context block. */
    private String title;

    /** Optional secondary heading (channel name, user, issue type/status). */
    private String subtitle;

    /** Canonical URL pointing back to the source. */
    private String url;

    /** Primary text body (page content / message text / issue description). */
    private String text;
}
