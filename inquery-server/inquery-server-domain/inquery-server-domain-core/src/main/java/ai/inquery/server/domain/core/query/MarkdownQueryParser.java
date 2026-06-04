package ai.inquery.server.domain.core.query;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the SQL-generation markdown produced by
 * {@link SqlGenerator#buildStreamingPrompt} into a structured
 * {@link ParsedMarkdown} (overview + per-query title/sql/explanation/suggestion).
 *
 * <p>Lifted verbatim from the deleted {@code QueryProcessingServiceImpl} so the
 * Manual-mode UX (overview + query options + per-query explanation +
 * suggestions) stays compatible after migrating the chat path through
 * the root tool-calling agent.
 */
@Slf4j
public final class MarkdownQueryParser {

    private MarkdownQueryParser() {}

    public static final class ParsedMarkdown {
        public String overview;
        public final List<QueryProcessingResult.QueryItem> queries = new ArrayList<>();
    }

    /** Parse a full markdown response (overview + multiple `## Title` query sections). */
    public static ParsedMarkdown parse(String markdown) {
        ParsedMarkdown result = new ParsedMarkdown();
        if (markdown == null || markdown.isEmpty()) return result;

        String[] sections = markdown.split("(?=## )");
        String pendingSuggestion = null;

        for (int i = 0; i < sections.length; i++) {
            String section = sections[i];

            if (i == 0 && !section.startsWith("## ")) {
                int separatorIdx = section.indexOf("\n---");
                if (separatorIdx > 0) {
                    result.overview = section.substring(0, separatorIdx).trim();
                    String afterSeparator = section.substring(separatorIdx + 4).trim();
                    if (!afterSeparator.isEmpty() && !afterSeparator.startsWith("## ")) {
                        pendingSuggestion = afterSeparator.split("\n## ")[0].trim();
                    }
                } else {
                    result.overview = section.trim();
                }
                continue;
            }

            if (!section.startsWith("## ")) continue;

            int titleEnd = section.indexOf("\n");
            String title = titleEnd > 0 ? section.substring(0, titleEnd).trim() : section.trim();

            String sql = extractSqlFromSection(section.substring(titleEnd > 0 ? titleEnd : 0));
            String explanation = extractExplanationFromSection(section);

            if (!title.isEmpty() && sql != null && !sql.isEmpty()) {
                QueryProcessingResult.QueryItem item = new QueryProcessingResult.QueryItem();
                item.setTitle(title);
                item.setSql(sql);
                item.setExplanation(explanation);
                if (pendingSuggestion != null && !result.queries.isEmpty()) {
                    item.setSuggestion(pendingSuggestion);
                }
                result.queries.add(item);
                pendingSuggestion = null;
            }

            int separatorIdx = section.indexOf("\n---");
            if (separatorIdx > 0) {
                String afterSeparator = section.substring(separatorIdx + 4).trim();
                int nextTitleIdx = afterSeparator.indexOf("## ");
                if (nextTitleIdx > 0) {
                    pendingSuggestion = afterSeparator.substring(0, nextTitleIdx).trim();
                } else if (!afterSeparator.isEmpty()) {
                    pendingSuggestion = afterSeparator;
                }
            }
        }

        log.info("Parsed {} queries from markdown (overview: {}, suggestions: {})",
                result.queries.size(),
                result.overview != null && !result.overview.isEmpty(),
                result.queries.stream().filter(q -> q.getSuggestion() != null).count());
        return result;
    }

    /**
     * Extract the first executable SQL statement from a streamed markdown
     * response. Supports both ```sql code blocks and naked SELECT/WITH
     * legacy formats.
     */
    public static String extractFirstSql(String response) {
        if (response == null || response.isEmpty()) return null;

        int sqlBlockStart = response.indexOf("```sql");
        if (sqlBlockStart >= 0) {
            int sqlContentStart = response.indexOf("\n", sqlBlockStart);
            if (sqlContentStart >= 0) {
                int sqlEnd = response.indexOf("```", sqlContentStart);
                if (sqlEnd > sqlContentStart) {
                    return response.substring(sqlContentStart + 1, sqlEnd).trim();
                }
            }
        }

        String upper = response.toUpperCase();
        int selectIdx = upper.indexOf("SELECT");
        int withIdx = upper.indexOf("WITH");
        int sqlStart = -1;
        if (selectIdx >= 0 && withIdx >= 0) sqlStart = Math.min(selectIdx, withIdx);
        else if (selectIdx >= 0) sqlStart = selectIdx;
        else if (withIdx >= 0) sqlStart = withIdx;
        if (sqlStart < 0) return null;

        String sqlPart = response.substring(sqlStart);
        int endMarker = sqlPart.indexOf("-- END SQL --");
        int bulletStart = sqlPart.indexOf("\n-");
        int separator = sqlPart.indexOf("\n---");
        int endIdx = sqlPart.length();
        if (endMarker > 0) endIdx = Math.min(endIdx, endMarker);
        if (bulletStart > 0) endIdx = Math.min(endIdx, bulletStart);
        if (separator > 0) endIdx = Math.min(endIdx, separator);
        return cleanSql(sqlPart.substring(0, endIdx).trim());
    }

    /** Looks-like-SQL guard. */
    public static boolean looksLikeSql(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String cleaned = text.trim();
        while (cleaned.startsWith("--")) {
            int nl = cleaned.indexOf('\n');
            if (nl == -1) break;
            cleaned = cleaned.substring(nl + 1).trim();
        }
        String upper = cleaned.toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("WITH")
                || upper.startsWith("INSERT") || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE") || upper.startsWith("CREATE")
                || upper.startsWith("ALTER") || upper.startsWith("DROP")
                || upper.startsWith("TRUNCATE") || upper.startsWith("MERGE")
                || (upper.contains("SELECT") && upper.contains("FROM"));
    }

    private static String extractSqlFromSection(String section) {
        String fencedSql = extractFencedSql(section);
        if (fencedSql != null && !fencedSql.isEmpty()) {
            return fencedSql;
        }

        String upper = section.toUpperCase();
        int selectIdx = upper.indexOf("SELECT");
        int withIdx = upper.indexOf("WITH");
        int sqlStart = -1;
        if (selectIdx >= 0 && withIdx >= 0) sqlStart = Math.min(selectIdx, withIdx);
        else if (selectIdx >= 0) sqlStart = selectIdx;
        else if (withIdx >= 0) sqlStart = withIdx;
        if (sqlStart < 0) return null;

        String sqlPart = section.substring(sqlStart);
        int endMarker = sqlPart.indexOf("-- END SQL --");
        int closingFence = sqlPart.indexOf("```");
        int boldHeading = sqlPart.indexOf("\n**");
        int bulletStart = sqlPart.indexOf("\n-");
        int separator = sqlPart.indexOf("\n---");

        int endIdx = sqlPart.length();
        if (endMarker > 0) endIdx = Math.min(endIdx, endMarker);
        if (closingFence > 0) endIdx = Math.min(endIdx, closingFence);
        if (boldHeading > 0) endIdx = Math.min(endIdx, boldHeading);
        if (bulletStart > 0) endIdx = Math.min(endIdx, bulletStart);
        if (separator > 0) endIdx = Math.min(endIdx, separator);

        return cleanSql(sqlPart.substring(0, endIdx).trim());
    }

    private static String extractFencedSql(String section) {
        if (section == null || section.isEmpty()) return null;

        String lower = section.toLowerCase();
        int sqlBlockStart = lower.indexOf("```sql");
        if (sqlBlockStart < 0) {
            sqlBlockStart = section.indexOf("```");
        }
        if (sqlBlockStart < 0) return null;

        int sqlContentStart = section.indexOf("\n", sqlBlockStart);
        if (sqlContentStart < 0) return null;

        int sqlEnd = section.indexOf("```", sqlContentStart + 1);
        if (sqlEnd <= sqlContentStart) return null;

        String candidate = cleanSql(section.substring(sqlContentStart + 1, sqlEnd));
        return looksLikeSql(candidate) ? candidate : null;
    }

    private static String extractExplanationFromSection(String section) {
        int endSqlIdx = section.indexOf("-- END SQL --");
        String afterSql = endSqlIdx > 0 ? section.substring(endSqlIdx + 13) : section;

        int bulletStart = afterSql.indexOf("\n-");
        if (bulletStart < 0) bulletStart = afterSql.indexOf("-");
        if (bulletStart < 0) return "";

        String explanationPart = afterSql.substring(bulletStart);
        int separator = explanationPart.indexOf("\n---");
        if (separator > 0) explanationPart = explanationPart.substring(0, separator);
        return explanationPart.trim();
    }

    private static String cleanSql(String sql) {
        if (sql == null) return null;
        String cleaned = sql.trim();
        if (cleaned.startsWith("```sql")) cleaned = cleaned.substring(6);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.replaceFirst("(?is)\\n\\s*```[\\s\\S]*$", "");
        cleaned = cleaned.replaceFirst("(?is)\\n\\s*\\*\\*[^*\\n]{0,80}:\\s*\\*\\*[\\s\\S]*$", "");
        return cleaned.trim();
    }
}
