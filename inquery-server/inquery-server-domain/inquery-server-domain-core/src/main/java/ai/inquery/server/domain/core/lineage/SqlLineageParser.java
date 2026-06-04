package ai.inquery.server.domain.core.lineage;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to extract source table references from SQL text (VIEW definitions, queries).
 * Parses FROM and JOIN clauses to find table references.
 * Not a full SQL parser, but sufficient for most VIEW definitions.
 */
public class SqlLineageParser {

    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?:FROM|JOIN|INNER\\s+JOIN|LEFT\\s+(?:OUTER\\s+)?JOIN|RIGHT\\s+(?:OUTER\\s+)?JOIN|FULL\\s+(?:OUTER\\s+)?JOIN|CROSS\\s+JOIN)"
                    + "\\s+"
                    + "([\"`\\[]?[\\w]+[\"`\\]]?"
                    + "(?:\\s*\\.\\s*[\"`\\[]?[\\w]+[\"`\\]]?){0,2})",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION",
            "EXCEPT", "INTERSECT", "VALUES", "SET", "INTO", "AS", "ON",
            "CASE", "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT",
            "NULL", "TRUE", "FALSE", "LATERAL", "UNNEST", "DUAL"
    );

    /**
     * Extract source table names from a SQL statement (typically a VIEW definition).
     *
     * @param sql the SQL statement
     * @return set of unique table references (unquoted, uppercase)
     */
    public static Set<String> extractSourceTables(String sql) {
        if (sql == null || sql.isBlank()) return Collections.emptySet();

        String cleaned = removeComments(sql);

        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(cleaned);

        while (matcher.find()) {
            String tableRef = matcher.group(1).trim();
            tableRef = unquote(tableRef);

            if (tableRef.isEmpty()) continue;
            String upperName = tableRef.toUpperCase();
            String simpleName = upperName.contains(".") ? upperName.substring(upperName.lastIndexOf('.') + 1) : upperName;
            if (SQL_KEYWORDS.contains(simpleName)) continue;
            if (simpleName.startsWith("(")) continue;

            tables.add(upperName);
        }

        return tables;
    }

    private static String removeComments(String sql) {
        String noBlockComments = sql.replaceAll("/\\*.*?\\*/", " ");
        return noBlockComments.replaceAll("--[^\n]*", " ");
    }

    private static String unquote(String name) {
        return name.replaceAll("[\"'`\\[\\]]", "").trim();
    }
}
