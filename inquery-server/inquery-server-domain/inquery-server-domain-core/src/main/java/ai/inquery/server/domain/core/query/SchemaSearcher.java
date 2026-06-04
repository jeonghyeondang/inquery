package ai.inquery.server.domain.core.query;

import ai.inquery.server.domain.api.service.VectorSearchService;
import ai.inquery.server.domain.repository.entity.ColumnMetaCacheDO;
import ai.inquery.server.domain.repository.entity.TableSearchResultDO;
import ai.inquery.server.domain.repository.mapper.ColumnMetaCacheMapper;
import ai.inquery.server.domain.repository.mapper.TableMetaCacheMapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Searches for relevant schema information.
 * Primary: Vector DB semantic search.
 * Fallback: Keyword-based search on cached table metadata in PostgreSQL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaSearcher {

    private final VectorSearchService vectorSearchService;
    private final TableMetaCacheMapper tableMetaCacheMapper;
    private final ColumnMetaCacheMapper columnMetaCacheMapper;

    private static final int DEFAULT_TOP_K = 15;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "must",
            "how", "many", "much", "what", "which", "who", "whom", "where", "when",
            "why", "that", "this", "these", "those", "it", "its",
            "show", "me", "get", "find", "list", "give", "tell", "display",
            "all", "each", "every", "any", "some", "no", "not", "only",
            "and", "or", "but", "if", "then", "else", "so", "because",
            "in", "on", "at", "to", "for", "of", "with", "by", "from", "about",
            "into", "through", "between", "after", "before", "above", "below",
            "i", "you", "we", "they", "he", "she", "my", "your", "our", "their",
            "select", "group", "order", "limit", "count",
            "sum", "avg", "min", "max", "total", "number", "data", "table", "query"
    );

    /**
     * Pre-warm Vector DB connection in background.
     */
    public void warmUp() {
        try {
            vectorSearchService.ensureConnection();
        } catch (Exception e) {
            log.debug("Vector DB warmup failed (non-fatal): {}", e.getMessage());
        }
    }

    public List<String> searchSchema(String query) {
        return searchSchema(query, null);
    }

    public List<String> searchSchema(String query, List<String> excludedTables) {
        return searchSchema(query, excludedTables, DEFAULT_TOP_K);
    }

    public List<String> searchSchema(String query, List<String> excludedTables, int topK) {
        try {
            List<Double> queryVector = vectorSearchService.generateEmbedding(query);
            return vectorSearchService.search(queryVector, query, topK, excludedTables);
        } catch (Exception e) {
            log.warn("Vector DB search failed (this is OK if Vector DB is not set up): {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Search schema with keyword-based fallback when vector DB returns empty.
     * Tries vector search first; if empty, falls back to keyword matching on cached table metadata.
     */
    public List<String> searchSchema(String query, List<String> excludedTables,
                                      Long dataSourceId, String databaseName, String schemaName) {
        // Primary: vector search
        List<String> results = searchSchema(query, excludedTables);
        if (!results.isEmpty()) {
            return results;
        }

        // Fallback: keyword-based search on cached metadata
        if (dataSourceId == null) {
            return results;
        }

        log.info("Vector search returned empty, falling back to keyword-based search for dataSourceId={}", dataSourceId);
        try {
            return keywordFallbackSearch(query, excludedTables, dataSourceId, databaseName, schemaName);
        } catch (Exception e) {
            log.warn("Keyword fallback search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Search schema by explicit table names using Pinecone metadata filter.
     * Used when table names are known (e.g., parsed from selected SQL).
     */
    public List<String> searchSchemaByTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            log.info("Searching schema by explicit table names: {}", tableNames);
            return vectorSearchService.searchByTableNames(tableNames);
        } catch (Exception e) {
            log.warn("Vector DB search by table names failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Keyword-based fallback: search cached table metadata in PostgreSQL.
     * Extracts keywords from query, scores tables by keyword match, builds DDL for top matches.
     */
    private List<String> keywordFallbackSearch(String query, List<String> excludedTables,
                                                Long dataSourceId, String databaseName, String schemaName) {
        Long userId = ContextUtils.getUserId();

        // Get cached tables
        List<TableSearchResultDO> allTables;
        if (databaseName != null && !databaseName.isEmpty()) {
            allTables = tableMetaCacheMapper.findByDataSourceAndSchema(dataSourceId, databaseName, schemaName, userId);
        } else {
            allTables = tableMetaCacheMapper.findAllByDataSourceId(dataSourceId, userId);
        }

        if (allTables == null || allTables.isEmpty()) {
            log.info("No cached tables found for keyword fallback (dataSourceId={})", dataSourceId);
            return new ArrayList<>();
        }

        // Filter excluded tables
        Set<String> excluded = excludedTables != null
                ? excludedTables.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : Set.of();
        List<TableSearchResultDO> filteredTables = allTables.stream()
                .filter(t -> !excluded.contains(t.getTableName().toUpperCase()))
                .collect(Collectors.toList());

        // Extract keywords from query
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            log.info("No meaningful keywords extracted from query: {}", query);
            return new ArrayList<>();
        }

        log.info("Keyword fallback: extracted keywords={} from query, searching {} cached tables",
                keywords, filteredTables.size());

        // Score and rank tables
        List<ScoredTable> scored = filteredTables.stream()
                .map(table -> new ScoredTable(table, scoreTable(table, keywords)))
                .filter(st -> st.score > 0)
                .sorted(Comparator.comparingInt(ScoredTable::score).reversed())
                .limit(DEFAULT_TOP_K)
                .collect(Collectors.toList());

        if (scored.isEmpty()) {
            log.info("No tables matched keywords: {}", keywords);
            return new ArrayList<>();
        }

        log.info("Keyword fallback matched {} tables (top: {})",
                scored.size(), scored.get(0).table.getTableName());

        // Build DDL for matched tables
        List<String> results = new ArrayList<>();
        for (ScoredTable st : scored) {
            String ddl = buildTableDDL(st.table);
            if (ddl != null) {
                results.add(ddl);
            }
        }
        return results;
    }

    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.toLowerCase().split("[\\s_,.;:!?()\\[\\]{}\"']+"))
                .filter(w -> w.length() > 1)
                .filter(w -> !STOP_WORDS.contains(w))
                .distinct()
                .collect(Collectors.toList());
    }

    private int scoreTable(TableSearchResultDO table, List<String> keywords) {
        String tableLower = table.getTableName().toLowerCase();
        String commentLower = table.getComment() != null ? table.getComment().toLowerCase() : "";
        // Split table name by underscores for token matching (e.g., "order_items" -> ["order", "items"])
        Set<String> tableTokens = Set.of(tableLower.split("_"));

        int score = 0;
        for (String keyword : keywords) {
            // Exact token match in table name (highest weight)
            if (tableTokens.contains(keyword)) {
                score += 3;
            }
            // Substring match in table name
            else if (tableLower.contains(keyword) || keyword.contains(tableLower)) {
                score += 2;
            }
            // Match in table comment
            if (commentLower.contains(keyword)) {
                score += 1;
            }
        }
        return score;
    }

    private String buildTableDDL(TableSearchResultDO table) {
        List<ColumnMetaCacheDO> columns = columnMetaCacheMapper.findByTableCacheId(table.getId());
        if (columns == null || columns.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // Header: [Table Path: db.schema.tableName]
        sb.append("[Table Path: ");
        if (table.getDatabaseName() != null) {
            sb.append(table.getDatabaseName()).append(".");
        }
        if (table.getSchemaName() != null) {
            sb.append(table.getSchemaName()).append(".");
        }
        sb.append(table.getTableName()).append("] ");

        // DDL
        sb.append("CREATE TABLE ").append(table.getTableName()).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetaCacheDO col = columns.get(i);
            sb.append("    ").append(col.getColumnName());
            if (col.getDataType() != null) {
                sb.append(" ").append(col.getDataType());
            }
            if (col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1) {
                sb.append(" PRIMARY KEY");
            }
            if (col.getIsNullable() != null && col.getIsNullable() == 0) {
                sb.append(" NOT NULL");
            }
            if (col.getComment() != null && !col.getComment().isBlank()) {
                sb.append(" COMMENT '").append(col.getComment().replace("'", "''")).append("'");
            }
            if (i < columns.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(");");

        if (table.getComment() != null && !table.getComment().isBlank()) {
            sb.append(" -- ").append(table.getComment());
        }

        return sb.toString();
    }

    private record ScoredTable(TableSearchResultDO table, int score) {}
}
