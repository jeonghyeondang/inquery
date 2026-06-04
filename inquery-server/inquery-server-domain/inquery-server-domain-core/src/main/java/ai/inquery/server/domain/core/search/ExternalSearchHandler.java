package ai.inquery.server.domain.core.search;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;

import java.util.List;

/**
 * Strategy for a single external service search backend (Confluence/Slack/Jira/...).
 *
 * <p>Each handler owns service-specific concerns (auth check, native search call,
 * Markdown rendering, "not configured" / "no results" copy) while the orchestrator
 * stays service-agnostic.
 */
public interface ExternalSearchHandler {

    /** Lowercase service identifier used by {@code externalServiceTarget} ("confluence", "slack", "jira"). */
    String getServiceName();

    /** Human-readable name for status messages (e.g. {@code "Confluence"}). */
    String getDisplayName();

    /** Whether the user has the credentials needed for this service. */
    boolean isConfigured(UserAIConfigSaveParam config);

    /**
     * Run a single keyword search against the service.
     * Implementations MUST return an empty list on failure (never throw)
     * so the orchestrator can run multiple keywords in parallel safely.
     */
    List<SearchHit> search(String keyword, int maxResults, UserAIConfigSaveParam config);

    /**
     * Hook for handlers that need a post-search enrichment step
     * (e.g. Confluence page content fetch). Default: no-op.
     */
    default void enrich(List<SearchHit> hits, int topN, UserAIConfigSaveParam config) {
        // no-op
    }

    /** Render one hit as a Markdown block to be injected into the LLM prompt. */
    String buildContextEntry(SearchHit hit);

    /** Short message shown when the search returned zero hits. */
    String getNoResultsMessage();

    /** Reason text for the unavailable-service response when {@link #isConfigured} is false. */
    String getNotConfiguredReason();
}
