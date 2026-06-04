package ai.inquery.server.domain.core.langchain.tools.calling;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.api.service.ReferenceDocumentSearchService;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService;
import ai.inquery.server.domain.core.search.ExternalSearchHandler;
import ai.inquery.server.domain.core.search.SearchHit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Read-only external search tools exposed to top-level LLM agents.
 *
 * <p>One {@code @Tool} per service so the LLM picks routing based on the tool
 * descriptions — one tool per external service. Each tool returns a
 * single Markdown block (already deduped) for direct inclusion in the agent's
 * conversation.
 *
 * <p>Stateful per call: caller owns lifetime, passing in the user's config and
 * a fast model name (used by {@link WebSearchService}).
 */
@Slf4j
public class SearchTools {

    private static final int MAX_RESULTS_PER_KEYWORD = 5;
    private static final int CONFLUENCE_ENRICH_TOP_N = 5;

    private final Map<String, ExternalSearchHandler> handlersByService;
    private final WebSearchService webSearchService;
    private final ReferenceDocumentSearchService referenceDocumentSearchService;
    private final Long userId;
    private final UserAIConfigSaveParam userConfig;
    private final String webSearchModelName;
    private final Consumer<String> progressCallback;
    private final BiConsumer<String, String> resultCollector;

    public SearchTools(List<ExternalSearchHandler> handlers,
                       WebSearchService webSearchService,
                       ReferenceDocumentSearchService referenceDocumentSearchService,
                       Long userId,
                       UserAIConfigSaveParam userConfig,
                       String webSearchModelName,
                       Consumer<String> progressCallback,
                       BiConsumer<String, String> resultCollector) {
        Map<String, ExternalSearchHandler> map = new HashMap<>();
        if (handlers != null) {
            for (ExternalSearchHandler h : handlers) {
                map.put(h.getServiceName().toLowerCase(), h);
            }
        }
        this.handlersByService = map;
        this.webSearchService = webSearchService;
        this.referenceDocumentSearchService = referenceDocumentSearchService;
        this.userId = userId;
        this.userConfig = userConfig;
        this.webSearchModelName = webSearchModelName;
        this.progressCallback = progressCallback;
        this.resultCollector = resultCollector;
    }

    @Tool("Search Confluence: wiki pages, documentation, knowledge base, team knowledge. Returns Markdown excerpts with URLs.")
    public String searchConfluence(
            @P("Topic keywords only; do NOT include words like 'wiki' or 'confluence'") String keyword
    ) {
        return runSearch("confluence", keyword);
    }

    @Tool("Search Slack: messages, channels, notifications, past team discussions. Returns Markdown excerpts with channel and author.")
    public String searchSlack(
            @P("Topic keywords only; do NOT include words like 'slack'") String keyword
    ) {
        return runSearch("slack", keyword);
    }

    @Tool("Search Jira: tickets, issues, sprints, boards. Returns Markdown excerpts with issue key, status, summary.")
    public String searchJira(
            @P("Topic keywords only; do NOT include words like 'jira' or 'ticket'") String keyword
    ) {
        return runSearch("jira", keyword);
    }

    @Tool("Search GitHub: repos, pull requests, issues, commits, source code, DDL, ORM models. Returns Markdown excerpts with repository, file path, and code snippet.")
    public String searchGithub(
            @P("Table name or identifier to look up (e.g. 'orders', 'user_events'); do NOT include words like 'github'") String keyword
    ) {
        return runSearch("github", keyword);
    }

    @Tool("Search Google Drive: Google Docs and Sheets, specs, planning docs, spreadsheets, shared team documents. Returns Markdown excerpts with document title and URL.")
    public String searchGoogleDrive(
            @P("Topic keywords only; do NOT include words like 'google', 'drive', 'docs' or 'sheets'") String keyword
    ) {
        return runSearch("google", keyword);
    }

    @Tool("Search Outlook email: messages, threads, notifications, inbox history. Returns Markdown excerpts with subject, sender, date, and link.")
    public String searchOutlook(
            @P("Topic keywords only; do NOT include words like 'outlook', 'email', or 'mail'") String keyword
    ) {
        return runSearch("outlook", keyword);
    }

    @Tool("Search uploaded reference documents (PDF, Word, markdown) from Settings → AI Integration. Use for internal specs, RM checklists, data dictionaries, and business rules not in the database schema. Returns excerpts with source filename.")
    public String searchReferenceDocuments(
            @P("Topic keywords related to the table, metric, or business concept; do NOT include words like 'pdf' or 'document'") String keyword
    ) {
        emitProgress("search.reference_documents");
        if (referenceDocumentSearchService == null || userId == null) {
            String result = "Reference document search is not available.";
            collectResult("reference_documents", result);
            return result;
        }
        if (!referenceDocumentSearchService.hasIndexedDocuments(userId)) {
            String result = "No indexed reference documents found. Upload PDF or Word files in Settings → AI Integration → Reference Documents.";
            collectResult("reference_documents", result);
            return result;
        }
        try {
            var hits = referenceDocumentSearchService.search(userId, keyword, MAX_RESULTS_PER_KEYWORD);
            if (hits.isEmpty()) {
                String result = "No matching reference document excerpts found for: " + keyword;
                collectResult("reference_documents", result);
                return result;
            }
            String result = referenceDocumentSearchService.formatAsMarkdown(hits);
            collectResult("reference_documents", result);
            return result;
        } catch (Exception e) {
            log.warn("Reference document search failed for '{}': {}", keyword, e.getMessage());
            String result = "Reference document search failed: " + e.getMessage();
            collectResult("reference_documents", result);
            return result;
        }
    }

    @Tool("Real-time web search: current events, news, weather, stock prices, live scores, recent public information that requires internet access. Returns a synthesized answer with source URLs.")
    public String searchWeb(
            @P("Web search query") String query
    ) {
        emitProgress("search.web");
        if (webSearchService == null || !webSearchService.isAvailable()) {
            String result = "Web search is not available. Please configure an AI model API key in Settings.";
            collectResult("web", result);
            return result;
        }
        try {
            WebSearchService.WebSearchResponse response =
                    webSearchService.searchWithLLM(query, webSearchModelName);
            StringBuilder sb = new StringBuilder();
            if (response.getSynthesizedText() != null && !response.getSynthesizedText().isBlank()) {
                sb.append(response.getSynthesizedText());
            } else {
                sb.append("Web search returned no synthesized answer.");
            }
            if (response.getSources() != null && !response.getSources().isEmpty()) {
                sb.append("\n\nSources:\n");
                for (var source : response.getSources()) {
                    if (source.getUrl() != null && !source.getUrl().isBlank()) {
                        sb.append("- [")
                                .append(source.getTitle() != null ? source.getTitle() : source.getSource())
                                .append("](").append(source.getUrl()).append(")\n");
                    }
                }
            }
            String result = sb.toString();
            collectResult("web", result);
            return result;
        } catch (Exception e) {
            log.warn("Web search failed for query '{}': {}", query, e.getMessage());
            String result = "Web search failed: " + e.getMessage();
            collectResult("web", result);
            return result;
        }
    }

    private String runSearch(String serviceName, String keyword) {
        emitProgress("search." + serviceName);
        ExternalSearchHandler handler = handlersByService.get(serviceName);
        if (handler == null) {
            String result = "No handler is registered for service: " + serviceName;
            collectResult(serviceName, result);
            return result;
        }
        if (!handler.isConfigured(userConfig)) {
            String result = handler.getNotConfiguredReason();
            collectResult(serviceName, result);
            return result;
        }

        log.info("[SearchTools] {} search: keyword='{}'", handler.getDisplayName(), keyword);
        List<SearchHit> hits = handler.search(keyword, MAX_RESULTS_PER_KEYWORD, userConfig);

        Set<String> seen = new HashSet<>();
        hits.removeIf(h -> h.getDedupKey() == null || !seen.add(h.getDedupKey()));

        if (hits.isEmpty()) {
            String result = handler.getNoResultsMessage();
            collectResult(serviceName, result);
            return result;
        }

        handler.enrich(hits, CONFLUENCE_ENRICH_TOP_N, userConfig);

        StringBuilder sb = new StringBuilder();
        for (SearchHit hit : hits) {
            sb.append(handler.buildContextEntry(hit));
        }
        String result = sb.toString();
        collectResult(serviceName, result);
        return result;
    }

    private void collectResult(String serviceName, String result) {
        if (resultCollector == null || result == null || result.isBlank()) return;
        try {
            resultCollector.accept(serviceName, result);
        } catch (Exception e) {
            log.debug("[SearchTools] result collector failed: {}", e.getMessage());
        }
    }

    private void emitProgress(String key) {
        if (progressCallback == null || key == null || key.isBlank()) return;
        try {
            progressCallback.accept(key);
        } catch (Exception e) {
            log.debug("[SearchTools] progress callback failed: {}", e.getMessage());
        }
    }
}
