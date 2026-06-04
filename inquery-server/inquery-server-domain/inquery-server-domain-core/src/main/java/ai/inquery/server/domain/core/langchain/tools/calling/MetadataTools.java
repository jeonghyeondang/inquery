package ai.inquery.server.domain.core.langchain.tools.calling;

import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.core.query.SchemaSearcher;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.TableLineageDO;
import ai.inquery.server.domain.repository.mapper.TableLineageMapper;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.model.Header;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Schema/metadata helper tools for the root agent.
 *
 * <p>Metadata and verification tools for the root agent:
 * <ul>
 *   <li>{@code lookup_table_metadata} — column/DDL context via vector search
 *       plus lineage/source-query hints from the auto-detected
 *       {@code table_lineage} table (upstream/downstream/source_query).</li>
 *   <li>{@code run_readonly_sql} — guarded read-only SQL for verification
 *       probes (INFORMATION_SCHEMA, SELECT DISTINCT, COUNT). NOT a replacement
 *       for {@code query_data} —
 *       metric/KPI answers still flow through the markdown-streaming
 *       pipeline so the Svelte UX (overview + useful query option(s) + chart)
 *       remains consistent.</li>
 *   <li>{@code probe_column_values} — structured value/domain probe. The
 *       LLM supplies table/column/filter parts and this class builds the
 *       dialect-aware SQL, avoiding ad-hoc DISTINCT syntax generation.</li>
 *   <li>{@code trace_table_lineage} / {@code explain_metric_source} —
 *       source-query-focused tools for tracing how a table, metric, or
 *       column is produced from {@code table_lineage.source_query}.</li>
 * </ul>
 *
 * <p>One instance per agent call; not thread-safe.
 */
@Slf4j
public class MetadataTools {

    private static final int MAX_TABLES_PER_LOOKUP = 5;
    private static final int MAX_ROWS_FOR_LLM = 30;
    private static final int MAX_DOMAIN_VALUES_FOR_LLM = 30;
    private static final int MAX_QUALITY_COLUMNS = 8;
    private static final int MAX_PROFILE_COLUMNS = 10;
    private static final int MAX_PROFILE_SAMPLE_VALUES = 5;
    private static final int MAX_SOURCE_QUERY_CHARS = 6000;
    private static final long SMALL_TABLE_MAX_ROWS = 100_000L;
    private static final long MEDIUM_TABLE_MAX_ROWS = 1_000_000L;
    private static final long LARGE_TABLE_MAX_ROWS = 10_000_000L;
    /**
     * Upper bound for {@link #checkDataVolumeBatch} input size. Aligned with
     * {@code SchemaSearcher.DEFAULT_TOP_K} (15) so the LLM can pass every
     * vector-search candidate in a single tool call. Larger inputs are
     * silently truncated; the LLM does not need extra negotiation.
     */
    private static final int MAX_VOLUME_BATCH_TABLES = 15;
    /**
     * Hard cap on {@code run_readonly_sql} executions per agent run.
     * The tool is for verification only — INFORMATION_SCHEMA/COUNT/DISTINCT
     * probes — and the LLM has a strong tendency to re-probe with slightly
     * tweaked WHERE clauses when results are empty, which burns through
     * the LangChain4j sequential-tool-call budget without producing a
     * better answer. After the cap the tool returns a STOP message
     * containing the prior probe's SQL so the LLM is forced to surface
     * what it already has to the user.
     */
    private static final int MAX_READONLY_SQL_PROBES = 1;
    private static final int MAX_COLUMN_VALUE_PROBES = 2;
    private static final String IDENTIFIER_PART = "[A-Za-z_][A-Za-z0-9_$]*";
    private static final String QUALIFIED_IDENTIFIER =
            IDENTIFIER_PART + "(\\." + IDENTIFIER_PART + "){0,2}";
    private static final List<String> ALLOWED_SQL_PREFIXES =
            List.of("SELECT", "WITH", "SHOW", "DESCRIBE", "EXPLAIN");
    private static final List<String> BLOCKED_SQL_KEYWORDS = List.of(
            "DROP", "DELETE", "TRUNCATE", "ALTER", "INSERT", "UPDATE",
            "CREATE", "GRANT", "REVOKE", "RENAME", "REPLACE", "MERGE",
            "CALL", "EXEC", "EXECUTE", "INTO"
    );

    private final SchemaSearcher schemaSearcher;
    private final DlTemplateService dlTemplateService;
    private final Long dataSourceId;
    private final String databaseName;
    private final String schemaName;
    /**
     * Connected dialect (POSTGRESQL, SNOWFLAKE, MYSQL, BIGQUERY, ...).
     * Surfaced in tool responses so the LLM picks the correct vendor
     * syntax for follow-up {@code run_readonly_sql} probes. Optional —
     * if null, dialect-specific suggestions are omitted.
     */
    private final String dbType;
    /**
     * Per-agent-run probe counter. New {@code MetadataTools} instance
     * is created on each chat request so this resets naturally.
     */
    private final AtomicInteger readonlySqlInvocations = new AtomicInteger(0);
    private final AtomicInteger columnValueProbeInvocations = new AtomicInteger(0);
    /** SQL of the most recent successful {@link #runReadOnlySql} call, surfaced in the STOP message. */
    private volatile String lastProbeSql;
    /** Result of the most recent successful {@link #runReadOnlySql}, surfaced in the STOP message. */
    private volatile String lastProbeResult;
    private final Consumer<String> progressCallback;

    public MetadataTools(SchemaSearcher schemaSearcher,
                         DlTemplateService dlTemplateService,
                         Long dataSourceId,
                         String databaseName,
                         String schemaName,
                         String dbType,
                         Consumer<String> progressCallback) {
        this.schemaSearcher = schemaSearcher;
        this.dlTemplateService = dlTemplateService;
        this.dataSourceId = dataSourceId;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.dbType = dbType;
        this.progressCallback = progressCallback;
    }

    @Tool("""
        Search the data catalog for candidate tables or datasets that may
        contain a requested kind of data. Use before lookup_table_metadata
        when the user does not know the exact table name, asks "what tables
        have X?", "is there data about X?", or asks whether a dataset exists.
        Returns candidate table names with compact schema snippets. If no
        candidate is found, you MUST tell the user no matching catalog data
        was found; do NOT call query_data.
        """)
    public String searchDataCatalog(
            @P("Natural-language data/table search query. Use concise keywords, e.g. 'product sales revenue category' or 'customer reviews rating'.") String query,
            @P("Maximum candidate tables to return. Use 5 by default, max 10.") Integer limit
    ) {
        if (query == null || query.isBlank()) {
            return "search_data_catalog: 'query' is required.";
        }

        int topK = limit == null ? 5 : Math.max(1, Math.min(limit, 10));
        emitProgress("metadata.search");
        log.info("[MetadataTools] searchDataCatalog topK={}: {}", topK, query);

        List<String> matches;
        try {
            matches = schemaSearcher.searchSchema(query, null, dataSourceId, databaseName, schemaName);
        } catch (Exception e) {
            log.warn("[MetadataTools] catalog search failed: {}", e.getMessage());
            matches = new ArrayList<>();
        }

        if (matches == null || matches.isEmpty()) {
            return "No matching catalog data found for: " + query + "\n\n"
                    + "DO NOT call query_data. Tell the user that no matching table/dataset "
                    + "is currently catalogued for this database. If useful, ask them for "
                    + "another keyword or the exact table name.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found candidate catalog data for: ").append(query).append("\n\n");
        int count = Math.min(matches.size(), topK);
        for (int i = 0; i < count; i++) {
            sb.append(formatCatalogCandidate(i + 1, matches.get(i))).append("\n");
        }
        sb.append("\nUse lookup_table_metadata for a candidate if the user asks for columns, structure, or lineage. ")
                .append("Use query_data only if the user asks to compute metrics or return actual data.");
        return sb.toString();
    }

    @Tool("""
        Build an analysis plan for a vague business problem when the user
        does not know what analysis to ask for. Use for prompts like
        "help me understand why revenue is not growing", "how can we reduce
        churn?", "find the cause of delivery delays", or "analyze this
        business problem".

        This tool does NOT scan every table and does NOT execute SQL. It
        searches the catalog for a small set of relevant candidate datasets
        and returns only evidence needed for the root agent to design the
        analysis. It does not contain business-domain templates and does not
        decide the analysis steps itself. If candidate data is found and the
        user asked to analyze/diagnose the problem, the root agent should
        create the first queryData question from the user's goal plus the
        returned table/column evidence. If no candidate data is found, do NOT
        call queryData; ask the user for a table, metric, or better keyword.
        """)
    public String planAnalysis(
            @P("The user's business problem in natural language. Keep the user's words and goal.") String businessProblem,
            @P("Optional known context from the conversation, such as prior tables, metrics, filters, date ranges, or business assumptions. Pass empty string if none.") String knownContext,
            @P("Optional constraints such as target metric, segment, period, or department. Pass empty string if none.") String constraints
    ) {
        if (businessProblem == null || businessProblem.isBlank()) {
            return "plan_analysis: 'businessProblem' is required.";
        }

        emitProgress("metadata.plan");
        String problem = businessProblem.trim();
        String context = knownContext == null ? "" : knownContext.trim();
        String userConstraints = constraints == null ? "" : constraints.trim();
        String catalogQuery = String.join(" ", List.of(problem, context, userConstraints)).trim();
        log.info("[MetadataTools] planAnalysis: {}", problem);

        List<String> matches;
        try {
            matches = schemaSearcher.searchSchema(catalogQuery, null, dataSourceId, databaseName, schemaName);
        } catch (Exception e) {
            log.warn("[MetadataTools] analysis planning catalog lookup failed: {}", e.getMessage());
            matches = new ArrayList<>();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ANALYSIS_PLAN_READY\n\n");
        sb.append("Problem: ").append(problem).append("\n");
        if (!context.isBlank()) {
            sb.append("Known context: ").append(context).append("\n");
        }
        if (!userConstraints.isBlank()) {
            sb.append("Constraints: ").append(userConstraints).append("\n");
        }

        if (matches == null || matches.isEmpty()) {
            sb.append("\nCandidate data: none found in the current catalog.\n\n");
            sb.append("DO_NOT_CALL_QUERY_DATA\n");
            sb.append("Ask the user, in their language, for the smallest missing scope: a relevant table, metric, period, department, or domain keyword.");
            return sb.toString();
        }

        int candidateCount = Math.min(matches.size(), 5);
        sb.append("\nCandidate data:\n");
        for (int i = 0; i < candidateCount; i++) {
            sb.append(formatCatalogCandidate(i + 1, matches.get(i))).append("\n");
        }

        sb.append("\nPlanning instructions for the root agent:\n");
        sb.append("- Use the user's goal, constraints, business context, and candidate table/column evidence above to design the analysis in the user's language.\n");
        sb.append("- Prefer a first queryData question that validates the main measurable outcome over time or by the most relevant grain visible in the candidate columns.\n");
        sb.append("- Do not claim a metric, segment, date column, or domain rule exists unless it is present in the candidate evidence or prior context.\n");
        sb.append("- If the candidate evidence is too weak to form a concrete first query, ask for the smallest missing scope instead of calling queryData.\n\n");
        sb.append("NEXT_ACTION: If there is enough candidate evidence and the user asked to analyze or diagnose this problem now, call queryData next. ")
                .append("Write the queryData question yourself from the user's goal and candidate evidence, and pass this whole planning evidence in conversation_context.");
        return sb.toString();
    }

    @Tool("""
        Return columns/DDL and lineage (upstream / downstream / source query)
        for one or more known tables. Use when the user asks for table
        structure, column lists, types, or where a table's data comes
        from / flows to. Does NOT generate SQL and does NOT execute
        anything. For metric/KPI/data answers use query_data instead.
        """)
    public String lookupTableMetadata(
            @P("Comma-separated table names (max 5). Bare table names or fully-qualified db.schema.table both accepted.") String tableNames
    ) {
        if (tableNames == null || tableNames.isBlank()) {
            return "lookup_table_metadata: 'tableNames' is required (comma-separated).";
        }
        List<String> names = Arrays.stream(tableNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_TABLES_PER_LOOKUP)
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            return "lookup_table_metadata: parsed table names list was empty.";
        }

        emitProgress("metadata.lookup");
        log.info("[MetadataTools] lookupTableMetadata: {}", names);

        // Step 1: vector DB metadata (DDL with columns + types + comments)
        List<String> ddlBlocks;
        try {
            ddlBlocks = schemaSearcher.searchSchemaByTableNames(names);
        } catch (Exception e) {
            log.warn("[MetadataTools] vector DB lookup failed: {}", e.getMessage());
            ddlBlocks = new ArrayList<>();
        }

        // Step 2: lineage rows from auto-detected table_lineage
        Map<String, LineageInfo> lineageByTable = new LinkedHashMap<>();
        try {
            emitProgress("metadata.lineage");
            TableLineageMapper mapper = Dbutils.getMapper(TableLineageMapper.class);
            for (String raw : names) {
                String shortName = raw.contains(".")
                        ? raw.substring(raw.lastIndexOf('.') + 1)
                        : raw;

                LambdaQueryWrapper<TableLineageDO> upstream = new LambdaQueryWrapper<>();
                upstream.eq(TableLineageDO::getDataSourceId, dataSourceId)
                        .eq(TableLineageDO::getTableName, shortName);
                if (databaseName != null) upstream.eq(TableLineageDO::getDatabaseName, databaseName);
                if (schemaName != null) upstream.eq(TableLineageDO::getSchemaName, schemaName);
                TableLineageDO up = mapper.selectOne(upstream);

                LambdaQueryWrapper<TableLineageDO> downstream = new LambdaQueryWrapper<>();
                downstream.eq(TableLineageDO::getDataSourceId, dataSourceId)
                        .like(TableLineageDO::getSourceTables, shortName);
                if (databaseName != null) downstream.eq(TableLineageDO::getDatabaseName, databaseName);
                if (schemaName != null) downstream.eq(TableLineageDO::getSchemaName, schemaName);
                List<TableLineageDO> downRows = mapper.selectList(downstream);

                if (up != null || (downRows != null && !downRows.isEmpty())) {
                    lineageByTable.put(raw, new LineageInfo(up, downRows));
                }
            }
        } catch (Exception e) {
            log.warn("[MetadataTools] lineage lookup failed: {}", e.getMessage());
        }

        if (ddlBlocks.isEmpty() && lineageByTable.isEmpty()) {
            return "No metadata or lineage found for: " + String.join(", ", names)
                    + ". The table(s) may not exist or may not be catalogued yet.\n\n"
                    + "DO NOT call query_data — generating SQL against a non-existent table "
                    + "will produce a misleading answer based on a different table. "
                    + "Instead, either (a) ask the user for the exact table name in their "
                    + "language, or (b) call run_readonly_sql ONCE to suggest similar names, "
                    + "then STOP and present those candidates to the user.\n\n"
                    + "Suggested probe for the connected dialect:\n"
                    + suggestedTableLookupSql(names.get(0));
        }

        StringBuilder sb = new StringBuilder();
        if (!ddlBlocks.isEmpty()) {
            sb.append("## Columns\n");
            for (String ddl : ddlBlocks) {
                sb.append(ddl).append("\n---\n");
            }
        }
        if (!lineageByTable.isEmpty()) {
            sb.append("\n## Lineage\n");
            for (Map.Entry<String, LineageInfo> e : lineageByTable.entrySet()) {
                sb.append("### ").append(e.getKey()).append("\n");
                LineageInfo info = e.getValue();
                if (info.upstream != null) {
                    String sources = info.upstream.getSourceTables();
                    if (sources != null && !sources.isBlank()) {
                        sb.append("- Upstream (source tables): ").append(sources).append("\n");
                    }
                }
                if (info.downstream != null && !info.downstream.isEmpty()) {
                    String down = info.downstream.stream()
                            .map(d -> qualified(d.getDatabaseName(), d.getSchemaName(), d.getTableName()))
                            .distinct()
                            .collect(Collectors.joining(", "));
                    sb.append("- Downstream (used by): ").append(down).append("\n");
                }
                if (info.upstream != null && info.upstream.getSourceQuery() != null
                        && !info.upstream.getSourceQuery().isBlank()) {
                    sb.append("- Source Query:\n```sql\n")
                            .append(info.upstream.getSourceQuery().trim())
                            .append("\n```\n");
                }
            }
        }
        return sb.toString();
    }

    @Tool("""
        Trace lineage for a known table using table_lineage metadata.
        Use when the user asks where a table comes from, what upstream tables
        feed it, what downstream tables depend on it, or wants the source SQL
        that creates/populates the table. This is source-logic focused; use
        lookup_table_metadata for simple column/type/table-info questions.
        """)
    public String traceTableLineage(
            @P("Known table name. Bare table, schema.table, or database.schema.table are accepted.") String tableName,
            @P("Whether to include the source_query SQL. Use true when the user asks how it is built or asks for logic/source SQL.") Boolean includeSourceQuery
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "trace_table_lineage: 'tableName' is required.";
        }
        String cleanTable = tableName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        emitProgress("metadata.lineage");
        String shortName = shortTableName(cleanTable);
        TableLineageDO upstream = findLineageRow(shortName);
        List<TableLineageDO> downstream = findDownstreamRows(shortName);

        if (upstream == null && downstream.isEmpty()) {
            return "No lineage/source query found for: " + cleanTable + ". "
                    + "Tell the user this table is not currently registered in table_lineage. "
                    + "Do NOT invent upstream tables or source SQL.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Lineage trace for ").append(cleanTable).append("\n\n");
        if (upstream != null) {
            sb.append("## Target\n");
            sb.append("- Table: ").append(qualified(upstream.getDatabaseName(), upstream.getSchemaName(), upstream.getTableName())).append("\n");
            if (upstream.getDescription() != null && !upstream.getDescription().isBlank()) {
                sb.append("- Description: ").append(upstream.getDescription()).append("\n");
            }
            if (upstream.getSourceTables() != null && !upstream.getSourceTables().isBlank()) {
                sb.append("- Upstream source tables: ").append(upstream.getSourceTables()).append("\n");
            } else {
                sb.append("- Upstream source tables: not recorded\n");
            }
            if (Boolean.TRUE.equals(includeSourceQuery)) {
                appendSourceQuery(sb, upstream.getSourceQuery());
            }
        }

        if (!downstream.isEmpty()) {
            sb.append("\n## Downstream\n");
            for (TableLineageDO row : downstream) {
                sb.append("- ").append(qualified(row.getDatabaseName(), row.getSchemaName(), row.getTableName()));
                if (row.getDescription() != null && !row.getDescription().isBlank()) {
                    sb.append(": ").append(row.getDescription());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @Tool("""
        Explain how a metric or column is produced from table_lineage.source_query.
        Use when the user asks how a metric/column is calculated, where a field
        comes from, or whether the source SQL references a specific field.
        If columnName is provided, this returns matching source-query snippets
        around that column. If no match exists, say it was not found in the
        registered source query; do not guess a formula.
        """)
    public String explainMetricSource(
            @P("Known target table name. Bare table, schema.table, or database.schema.table are accepted.") String tableName,
            @P("Optional metric/column name to find in source_query, e.g. total_revenue, revenue, category. Pass empty string to explain the table source query overall.") String columnName
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "explain_metric_source: 'tableName' is required.";
        }
        String cleanTable = tableName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        String cleanColumn = columnName == null ? "" : columnName.trim();
        if (!cleanColumn.isBlank()) {
            identifierError = validateIdentifier(cleanColumn, "columnName");
            if (identifierError != null) return "BLOCKED: " + identifierError;
        }

        emitProgress("metadata.lineage");
        String shortName = shortTableName(cleanTable);
        TableLineageDO row = findLineageRow(shortName);
        if (row == null || row.getSourceQuery() == null || row.getSourceQuery().isBlank()) {
            return "No source_query found for: " + cleanTable + ". "
                    + "Tell the user this metric/column logic is not currently registered. "
                    + "Do NOT invent a calculation.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Metric/source explanation for ").append(cleanTable);
        if (!cleanColumn.isBlank()) {
            sb.append(".").append(cleanColumn);
        }
        sb.append("\n\n");
        if (row.getDescription() != null && !row.getDescription().isBlank()) {
            sb.append("- Table description: ").append(row.getDescription()).append("\n");
        }
        if (row.getSourceTables() != null && !row.getSourceTables().isBlank()) {
            sb.append("- Upstream source tables: ").append(row.getSourceTables()).append("\n");
        }

        if (cleanColumn.isBlank()) {
            appendSourceQuery(sb, row.getSourceQuery());
            return sb.toString();
        }

        String snippets = sourceQuerySnippets(row.getSourceQuery(), cleanColumn);
        if (snippets.isBlank()) {
            sb.append("\nNo direct reference to `").append(cleanColumn)
                    .append("` was found in the registered source_query.\n")
                    .append("Do NOT guess the formula. Tell the user the column may be renamed upstream, derived indirectly, or not registered in lineage metadata.");
        } else {
            sb.append("\nMatching source-query snippets for `").append(cleanColumn).append("`:\n")
                    .append("```sql\n").append(snippets).append("\n```\n")
                    .append("\nExplain only what is supported by these snippets and the recorded upstream tables.");
        }
        return sb.toString();
    }

    @Tool("""
        Return registered catalog and lineage evidence for explaining a
        metric or column definition. Use when the user asks what a metric
        means business-wise, how to interpret it, what it is used for, or
        caveats such as discount/tax/refund inclusion. This tool does NOT
        write the final metric dictionary entry and does NOT invent business
        meaning. The root agent must use the returned evidence to explain the
        metric in the user's language. This is NOT source SQL explanation;
        use explain_metric_source when the user asks how it is calculated in
        SQL. Must not create SQL editor, chart, or query_data payload UX.
        """)
    public String explainMetricDefinition(
            @P("Metric or column name to define, e.g. total_revenue, total_units_sold, average_order_value.") String metricName,
            @P("Optional known table containing the metric. Bare table, schema.table, or database.schema.table accepted. Pass empty string if unknown.") String tableName
    ) {
        if (metricName == null || metricName.isBlank()) {
            return "explain_metric_definition: 'metricName' is required.";
        }
        String cleanMetric = metricName.trim();
        String identifierError = validateIdentifier(cleanMetric, "metricName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        String cleanTable = tableName == null ? "" : tableName.trim();
        if (!cleanTable.isBlank()) {
            identifierError = validateIdentifier(cleanTable, "tableName");
            if (identifierError != null) return "BLOCKED: " + identifierError;
        }

        emitProgress("metadata.definition");
        log.info("[MetadataTools] explainMetricDefinition metric={}, table={}", cleanMetric, cleanTable);

        List<String> catalogBlocks = new ArrayList<>();
        try {
            if (!cleanTable.isBlank()) {
                catalogBlocks = schemaSearcher.searchSchemaByTableNames(List.of(cleanTable));
            } else {
                catalogBlocks = schemaSearcher.searchSchema(cleanMetric, null, dataSourceId, databaseName, schemaName);
            }
        } catch (Exception e) {
            log.warn("[MetadataTools] metric definition catalog lookup failed: {}", e.getMessage());
        }

        String tableForLineage = !cleanTable.isBlank()
                ? shortTableName(cleanTable)
                : inferTableNameFromCatalog(catalogBlocks);
        TableLineageDO lineage = tableForLineage == null || tableForLineage.isBlank()
                ? null
                : findLineageRow(shortTableName(tableForLineage));

        String catalogEvidence = catalogMetricEvidence(catalogBlocks, cleanMetric);
        String sourceSnippet = lineage == null ? "" : sourceQuerySnippets(lineage.getSourceQuery(), cleanMetric);
        String tableDescription = lineage != null ? lineage.getDescription() : null;
        String sourceTables = lineage != null ? lineage.getSourceTables() : null;

        if ((catalogBlocks == null || catalogBlocks.isEmpty()) && lineage == null) {
            return "No catalog or lineage definition found for metric `" + cleanMetric + "`. "
                    + "Tell the user this metric is not currently registered in the catalog. "
                    + "Do not invent business meaning or calculation details.";
        }

        return formatMetricDefinitionEvidence(cleanMetric, cleanTable, catalogEvidence,
                tableDescription, sourceTables, sourceSnippet);
    }

    @Tool("""
        Probe the actual values/domain of one column using structured inputs.
        Use when the user asks what values/codes/categories/statuses exist,
        whether a specific dimension value appears, or before query_data when
        the exact filter value is unclear. This tool builds dialect-aware SQL;
        do NOT pass raw SELECT SQL. It returns top distinct values with counts.
        NOT for metric/KPI answers — call query_data for those.
        """)
    public String probeColumnValues(
            @P("Known table name to probe. Use bare table, schema.table, or database.schema.table from catalog metadata.") String tableName,
            @P("Known column name to inspect. Must be a real column from metadata/catalog.") String columnName,
            @P("Optional SQL WHERE condition without the WHERE keyword, e.g. \"order_date >= '2026-01-01'\". Use empty string unless the user gave a clear filter. DML/DDL and semicolons are rejected.") String whereCondition,
            @P("Maximum values to return. Use 20 by default, max 30.") Integer limit
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "probe_column_values: 'tableName' is required.";
        }
        if (columnName == null || columnName.isBlank()) {
            return "probe_column_values: 'columnName' is required.";
        }

        String cleanTable = tableName.trim();
        String cleanColumn = columnName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;
        identifierError = validateIdentifier(cleanColumn, "columnName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        String cleanWhere = whereCondition == null ? "" : whereCondition.trim();
        String whereError = validateWhereCondition(cleanWhere);
        if (whereError != null) return "BLOCKED: " + whereError;

        int valueLimit = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_DOMAIN_VALUES_FOR_LLM));
        int attempt = columnValueProbeInvocations.incrementAndGet();
        if (attempt > MAX_COLUMN_VALUE_PROBES) {
            return "STOP: probe_column_values is limited to "
                    + MAX_COLUMN_VALUE_PROBES
                    + " calls per turn. DO NOT call more tools. Reply with the values already found, "
                    + "or ask the user for a more specific table/column/filter.";
        }

        emitProgress("metadata.probe");
        String sql = buildColumnValueProbeSql(cleanTable, cleanColumn, cleanWhere, valueLimit);
        log.info("[MetadataTools] probeColumnValues (attempt {}/{}): {}",
                attempt, MAX_COLUMN_VALUE_PROBES, sql);

        try {
            DlExecuteParam param = new DlExecuteParam();
            param.setSql(sql);
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(databaseName);
            param.setSchemaName(schemaName);
            param.setConsoleId(0L);

            ListResult<ExecuteResult> result = dlTemplateService.execute(param);
            StringBuilder response = new StringBuilder();
            response.append("Column value probe\n")
                    .append("- Dialect: ").append(dbType == null ? "unknown" : dbType).append("\n")
                    .append("- Table: ").append(cleanTable).append("\n")
                    .append("- Column: ").append(cleanColumn).append("\n");
            if (!cleanWhere.isBlank()) {
                response.append("- Filter: ").append(cleanWhere).append("\n");
            }
            response.append("- SQL used:\n```sql\n").append(sql).append("\n```\n\n");

            if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                response.append(formatResultForLLM(result.getData().get(0)));
            } else {
                response.append("No values returned")
                        .append(result.getErrorMessage() != null ? ": " + result.getErrorMessage() : ".")
                        .append("\n\nDo NOT guess values. Tell the user no matching values were found for this column/filter.");
            }
            return response.toString();
        } catch (Exception e) {
            log.warn("[MetadataTools] probeColumnValues failed: {}", e.getMessage());
            return "Column value probe failed. Do NOT expose raw errors to the user. "
                    + "Tell the user the values could not be verified for this table/column and ask for a narrower filter.";
        }
    }

    @Tool("""
        Execute a read-only SQL probe for verification only — INFORMATION_SCHEMA
        lookups, SELECT DISTINCT to confirm a value/event exists, or COUNT
        sanity checks. Always include a partition filter (e.g. dt >=) on
        large tables. NOT for answering metric/KPI/data questions — for
        those, call query_data so the user sees the standard
        overview + useful query option(s) + chart UX.

        DIALECT — match the [Database dialect: ...] header at the top of
        the conversation. Prefer the standard INFORMATION_SCHEMA path
        which works on PostgreSQL, MySQL, Snowflake, Redshift, BigQuery
        (with `region-*`. prefix), and most others:
          SELECT table_schema, table_name FROM information_schema.tables
          WHERE table_name ILIKE '%keyword%' LIMIT 20
        Vendor-specific shortcuts like `SHOW TABLES IN db.schema` are
        Snowflake/Databricks-only — never use them on PostgreSQL or MySQL.
        Use ILIKE on PostgreSQL, LIKE everywhere else.
        """)
    public String runReadOnlySql(
            @P("A single SELECT/WITH/SHOW/DESCRIBE/EXPLAIN statement in the connected dialect's syntax. DML/DDL is rejected.") String sql
    ) {
        if (sql == null || sql.isBlank()) {
            return "run_readonly_sql: 'sql' is empty.";
        }
        String rejection = validateSqlSafety(sql);
        if (rejection != null) {
            return "BLOCKED: " + rejection;
        }
        emitProgress("metadata.probe");
        // Per-run probe budget. Without this the LLM tends to keep
        // reissuing slightly-different INFORMATION_SCHEMA queries on
        // empty results until LangChain4j's sequential-tool-call cap
        // trips and the user sees a raw runtime error.
        int attempt = readonlySqlInvocations.incrementAndGet();
        if (attempt > MAX_READONLY_SQL_PROBES) {
            log.info("[MetadataTools] runReadOnlySql HARD-STOP at attempt {} (cap={}). Rejecting SQL: {}",
                    attempt, MAX_READONLY_SQL_PROBES,
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);
            StringBuilder stop = new StringBuilder();
            stop.append("STOP: run_readonly_sql is limited to ")
                    .append(MAX_READONLY_SQL_PROBES)
                    .append(" probe per turn (verification only, not exploration).\n");
            if (lastProbeSql != null) {
                stop.append("\nYou already ran:\n```sql\n").append(lastProbeSql).append("\n```\n");
            }
            if (lastProbeResult != null) {
                stop.append("\nIts result was:\n").append(lastProbeResult).append("\n");
            }
            stop.append("\nDO NOT call any more tools. ")
                    .append("Reply to the user now: present the candidate names from the result above ")
                    .append("(or, if it was empty, tell them no matching table was found and ask for the exact name).");
            return stop.toString();
        }

        log.info("[MetadataTools] runReadOnlySql (attempt {}/{}): {}",
                attempt, MAX_READONLY_SQL_PROBES,
                sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);
        try {
            DlExecuteParam param = new DlExecuteParam();
            param.setSql(sql);
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(databaseName);
            param.setSchemaName(schemaName);
            param.setConsoleId(0L);

            ListResult<ExecuteResult> result = dlTemplateService.execute(param);
            String response;
            if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                response = formatResultForLLM(result.getData().get(0));
            } else {
                response = "Error: " + (result.getErrorMessage() != null
                        ? result.getErrorMessage() : "(no error message)");
            }
            this.lastProbeSql = sql;
            this.lastProbeResult = response;
            return response;
        } catch (Exception e) {
            log.warn("[MetadataTools] runReadOnlySql failed: {}", e.getMessage());
            String response = "Error executing SQL: " + e.getMessage();
            this.lastProbeSql = sql;
            this.lastProbeResult = response;
            return response;
        }
    }

    @Tool("""
        Validate lightweight data quality for a known table. Use when the
        user asks whether a table is trustworthy, fresh, duplicated, missing
        values, or safe to use before BI/analysis. This tool executes guarded
        read-only checks internally and returns a markdown quality report only;
        it must NOT produce SQL editor, chart, or query_data payload UX.
        """)
    public String validateDataQuality(
            @P("Known table name. Bare table, schema.table, or database.schema.table are accepted.") String tableName,
            @P("Optional comma-separated key columns for duplicate checks. Pass empty string if unknown.") String keyColumns,
            @P("Optional date/timestamp column for freshness checks. Pass empty string if unknown.") String dateColumn,
            @P("Optional comma-separated important columns for null/cardinality checks. Pass empty string to infer a few columns.") String requiredColumns
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "validate_data_quality: 'tableName' is required.";
        }
        String cleanTable = tableName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        List<String> keys = parseIdentifierList(keyColumns, "keyColumns");
        if (keys == null) return "BLOCKED: keyColumns must be comma-separated unquoted identifiers.";
        List<String> required = parseIdentifierList(requiredColumns, "requiredColumns");
        if (required == null) return "BLOCKED: requiredColumns must be comma-separated unquoted identifiers.";
        String cleanDateColumn = dateColumn == null ? "" : dateColumn.trim();
        if (!cleanDateColumn.isBlank()) {
            identifierError = validateIdentifier(cleanDateColumn, "dateColumn");
            if (identifierError != null) return "BLOCKED: " + identifierError;
        }

        emitProgress("metadata.quality");
        log.info("[MetadataTools] validateDataQuality table={}, keys={}, dateColumn={}, required={}",
                cleanTable, keys, cleanDateColumn, required);

        try {
            List<String> sampledColumns = sampleColumnNames(cleanTable);
            if (required.isEmpty()) {
                required = sampledColumns.stream()
                        .filter(c -> !keys.contains(c))
                        .limit(MAX_QUALITY_COLUMNS)
                        .collect(Collectors.toList());
            }
            if (cleanDateColumn.isBlank()) {
                cleanDateColumn = inferDateColumn(sampledColumns);
            }

            Long rowCount = executeSingleLong("SELECT COUNT(*) AS row_count FROM " + cleanTable);
            List<QualityCheck> checks = new ArrayList<>();
            List<String> limitations = new ArrayList<>();

            if (rowCount == null) {
                checks.add(new QualityCheck("Row count", "FAIL", "unknown",
                        "Could not count rows.", "high"));
            } else if (rowCount == 0) {
                checks.add(new QualityCheck("Row count", "FAIL", "0 rows",
                        "The table is empty.", "high"));
            } else {
                checks.add(new QualityCheck("Row count", "PASS", rowCount + " rows",
                        "The table contains data.", "low"));
            }

            if (!keys.isEmpty() && rowCount != null && rowCount > 0) {
                Long duplicateGroups = executeSingleLong(buildDuplicateGroupsSql(cleanTable, keys));
                if (duplicateGroups == null) {
                    checks.add(new QualityCheck("Duplicate keys", "WARNING", "unknown",
                            "Could not run duplicate check for `" + String.join(", ", keys) + "`.", "medium"));
                } else if (duplicateGroups > 0) {
                    checks.add(new QualityCheck("Duplicate keys", "FAIL", duplicateGroups + " duplicate group(s)",
                            "Duplicate key groups found for `" + String.join(", ", keys) + "`.", "high"));
                } else {
                    checks.add(new QualityCheck("Duplicate keys", "PASS", "0 duplicate groups",
                            "No duplicate key groups found for `" + String.join(", ", keys) + "`.", "low"));
                }
            } else {
                checks.add(new QualityCheck("Duplicate keys", "WARNING", "skipped",
                        "No key columns were provided.", "medium"));
                limitations.add("Duplicate checks need known key columns.");
            }

            if (!required.isEmpty() && rowCount != null && rowCount > 0) {
                Map<String, Long> nullCounts = executeNullCounts(cleanTable, required);
                long maxNulls = 0L;
                String worstColumn = null;
                for (String col : required) {
                    long nulls = nullCounts.getOrDefault(col, 0L);
                    if (nulls > maxNulls) {
                        maxNulls = nulls;
                        worstColumn = col;
                    }
                }
                if (maxNulls == 0) {
                    checks.add(new QualityCheck("Nulls", "PASS", "0 nulls in checked columns",
                            "Checked columns: `" + String.join(", ", required) + "`.", "low"));
                } else {
                    double ratio = rowCount == 0 ? 0.0 : (double) maxNulls / rowCount * 100.0;
                    checks.add(new QualityCheck("Nulls", "WARNING", worstColumn + " " + formatPercent(ratio),
                            "Highest null count is `" + worstColumn + "` with " + maxNulls + " null row(s).", "medium"));
                }
            } else {
                checks.add(new QualityCheck("Nulls", "WARNING", "skipped",
                        "No columns were available for null checks.", "medium"));
            }

            if (!cleanDateColumn.isBlank() && rowCount != null && rowCount > 0) {
                DateRange range = executeDateRange(cleanTable, cleanDateColumn);
                if (range == null || (range.min == null && range.max == null)) {
                    checks.add(new QualityCheck("Freshness", "WARNING", "unknown",
                            "Could not inspect `" + cleanDateColumn + "`.", "medium"));
                } else {
                    checks.add(new QualityCheck("Freshness", "PASS", "max " + nullSafe(range.max),
                            "Date range on `" + cleanDateColumn + "`: " + nullSafe(range.min) + " to " + nullSafe(range.max) + ".", "low"));
                }
            } else {
                checks.add(new QualityCheck("Freshness", "WARNING", "skipped",
                        "No date/time column was provided or inferred.", "medium"));
                limitations.add("Freshness checks need a date or timestamp column.");
            }

            if (!required.isEmpty() && rowCount != null && rowCount > 0) {
                Map<String, Long> distinctCounts = executeDistinctCounts(cleanTable, required.stream()
                        .limit(Math.min(5, required.size()))
                        .collect(Collectors.toList()));
                if (!distinctCounts.isEmpty()) {
                    String summary = distinctCounts.entrySet().stream()
                            .map(e -> "`" + e.getKey() + "`=" + e.getValue())
                            .collect(Collectors.joining(", "));
                    checks.add(new QualityCheck("Cardinality", "PASS", summary,
                            "Distinct counts were calculated for representative checked columns.", "low"));
                }
            }

            String overall = overallStatus(checks);
            return formatQualityReport(cleanTable, overall, checks, limitations);
        } catch (Exception e) {
            log.warn("[MetadataTools] validateDataQuality failed: {}", e.getMessage());
            return "Data quality check failed for `" + cleanTable + "`. "
                    + "Ask the user to confirm the table name, key columns, or date column. "
                    + "Do not expose raw database errors.";
        }
    }

    @Tool("""
        Return lightweight data-volume evidence for a known table before
        running a potentially expensive data query. Use when a data request
        may scan a large fact/event/detail table and the user did not provide
        a time range. This tool does NOT decide whether to ask for a date
        range and does NOT generate analysis SQL. It returns row count,
        date-column evidence, date range, optional recent 30/90 day row
        counts, and fixed threshold bands so the root agent can decide
        whether to call request_date_range or proceed with queryData.
        Do NOT use for metadata-only questions, metric definitions, lineage,
        or small known summary tables unless scan cost is uncertain.
        """)
    public String checkDataVolume(
            @P("Known table name. Bare table, schema.table, or database.schema.table are accepted.") String tableName,
            @P("Optional date/timestamp column to inspect. Pass empty string to infer from table columns.") String dateColumn
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "check_data_volume: 'tableName' is required.";
        }
        String cleanTable = tableName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        String cleanDateColumn = dateColumn == null ? "" : dateColumn.trim();
        if (!cleanDateColumn.isBlank()) {
            identifierError = validateIdentifier(cleanDateColumn, "dateColumn");
            if (identifierError != null) return "BLOCKED: " + identifierError;
        }

        emitProgress("metadata.volume");
        log.info("[MetadataTools] checkDataVolume table={}, dateColumn={}", cleanTable, cleanDateColumn);

        try {
            List<ColumnProfile> sampledColumns = sampleColumnProfiles(cleanTable);
            List<String> columnNames = sampledColumns.stream()
                    .map(ColumnProfile::name)
                    .collect(Collectors.toList());
            if (cleanDateColumn.isBlank()) {
                cleanDateColumn = inferDateColumn(columnNames);
            }

            Long rowCount = executeSingleLong("SELECT COUNT(*) AS row_count FROM " + cleanTable);
            DateRange dateRange = cleanDateColumn.isBlank() ? null : executeDateRange(cleanTable, cleanDateColumn);
            Long recent30 = cleanDateColumn.isBlank() ? null : executeRecentRowCount(cleanTable, cleanDateColumn, 30);
            Long recent90 = cleanDateColumn.isBlank() ? null : executeRecentRowCount(cleanTable, cleanDateColumn, 90);

            return formatDataVolumeEvidence(cleanTable, rowCount, sampledColumns, cleanDateColumn,
                    dateRange, recent30, recent90);
        } catch (Exception e) {
            log.warn("[MetadataTools] checkDataVolume failed: {}", e.getMessage());
            return "DATA_VOLUME_EVIDENCE\n\n"
                    + "Table: `" + cleanTable + "`\n"
                    + "Volume check failed. Ask the user to confirm the table name, or proceed only with a clearly bounded aggregate query. "
                    + "Do not expose raw database errors.";
        }
    }

    @Tool("""
        Fast batch row-count probe for vector-search candidate tables. Use
        AFTER search_data_catalog / plan_analysis returns multiple candidates
        and BEFORE deciding whether to ask the user for a date range. Returns
        row count and volume band (small / medium / large / very_large) for
        every supplied table in a single round-trip.

        Implementation: queries the connected dialect's statistics catalog
        (PostgreSQL pg_class, MySQL/MariaDB information_schema.TABLES,
        Snowflake INFORMATION_SCHEMA.TABLES.ROW_COUNT, BigQuery __TABLES__,
        SQL Server sys.partitions, Oracle ALL_TABLES.NUM_ROWS, DB2 SYSCAT,
        Redshift svv_table_info, ClickHouse system.tables, DuckDB
        duckdb_tables). Falls back to COUNT(*) for tables that have not
        been analyzed or for dialects without a statistics catalog. The
        result table includes a 'Source' column so you can see which path
        produced each row.

        Decision rule for the root agent:
        - Pick the single most relevant candidate for the user's question.
        - If that table is small or medium, call queryData directly with a
          bounded SQL. Do NOT call request_date_range based on the word
          "trend"/"over time" alone — volume is the only valid trigger.
        - If that table is large or very_large AND the user did NOT specify
          a time range, call checkDataVolume on that ONE table to fetch
          date-column evidence before deciding whether request_date_range
          is actually needed.

        This tool intentionally does NOT compute date ranges or recent-window
        counts. It is the cheap pre-check that replaces guessing-then-asking.
        Use checkDataVolume for the deeper single-table evidence on the
        chosen candidate only.
        """)
    public String checkDataVolumeBatch(
            @P("Comma-separated table names from search_data_catalog / plan_analysis candidates. Max 15. Bare table, schema.table, or database.schema.table all accepted.") String tableNames
    ) {
        if (tableNames == null || tableNames.isBlank()) {
            return "check_data_volume_batch: 'tableNames' is required (comma-separated).";
        }

        List<String> raw = Arrays.stream(tableNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(MAX_VOLUME_BATCH_TABLES)
                .collect(Collectors.toList());
        if (raw.isEmpty()) {
            return "check_data_volume_batch: parsed table names list was empty.";
        }

        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String name : raw) {
            if (name.matches(QUALIFIED_IDENTIFIER)) {
                valid.add(name);
            } else {
                invalid.add(name);
            }
        }
        if (valid.isEmpty()) {
            return "BLOCKED: no valid identifiers in tableNames. Use bare table, schema.table, or database.schema.table.";
        }

        emitProgress("metadata.volume.batch");
        String dialect = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
        log.info("[MetadataTools] checkDataVolumeBatch dialect={}, tables={}", dialect, valid);

        Map<String, Long> rowCounts = new LinkedHashMap<>();
        Set<String> estimateSources = new HashSet<>();

        // Step 1: dialect-aware statistics-catalog lookup. Single round-trip,
        // typically ms even on multi-TB tables because no row scan happens.
        // Returns NULL/zero for tables that have never been analyzed; those
        // fall through to the exact COUNT(*) fallback below.
        String estimateSql = buildEstimateRowCountSql(dialect, valid);
        if (estimateSql != null) {
            try {
                ListResult<ExecuteResult> result = executeSql(estimateSql);
                Map<String, Long> byShortName = parseEstimateResults(result);
                if (!byShortName.isEmpty()) {
                    for (String original : valid) {
                        if (rowCounts.containsKey(original)) continue;
                        String shortName = shortTableName(original);
                        Long count = lookupCaseInsensitive(byShortName, shortName);
                        if (count != null && count > 0L) {
                            rowCounts.put(original, count);
                            estimateSources.add(original);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MetadataTools] estimate row count failed for dialect={}: {}", dialect, e.getMessage());
            }
        }

        // Step 2: portable UNION ALL exact-count round-trip for whatever the
        // statistics catalog didn't cover (empty/new tables, unsupported
        // dialects, missing permissions on system catalogs).
        List<String> remainder = valid.stream()
                .filter(name -> !rowCounts.containsKey(name))
                .collect(Collectors.toList());
        if (!remainder.isEmpty()) {
            try {
                String unionSql = remainder.stream()
                        .map(t -> "SELECT '" + escapeLiteral(t) + "' AS table_name, COUNT(*) AS row_count FROM " + t)
                        .collect(Collectors.joining("\nUNION ALL\n"));
                ListResult<ExecuteResult> result = executeSql(unionSql);
                if (result != null && result.success() && result.getData() != null && !result.getData().isEmpty()) {
                    ExecuteResult data = result.getData().get(0);
                    if (data.getDataList() != null) {
                        for (List<String> row : data.getDataList()) {
                            if (row == null || row.size() < 2) continue;
                            int offset = row.size() >= 3 ? 1 : 0;
                            String name = row.get(offset);
                            String count = row.get(offset + 1);
                            if (name == null || count == null) continue;
                            try {
                                rowCounts.put(name.trim(), Math.round(Double.parseDouble(count.replace(",", "").trim())));
                            } catch (NumberFormatException ignored) {
                                // skip malformed value, per-table fallback will retry below
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MetadataTools] checkDataVolumeBatch UNION ALL fallback failed, dropping to per-table: {}", e.getMessage());
            }
        }

        // Step 3: per-table fallback for anything still missing (single-table
        // permission scoping, UNION ALL type mismatch on exotic dialects).
        List<String> failed = new ArrayList<>();
        for (String name : valid) {
            if (rowCounts.containsKey(name)) continue;
            try {
                Long c = executeSingleLong("SELECT COUNT(*) AS row_count FROM " + name);
                if (c != null) {
                    rowCounts.put(name, c);
                } else {
                    failed.add(name);
                }
            } catch (Exception e) {
                log.debug("[MetadataTools] per-table row count failed for {}: {}", name, e.getMessage());
                failed.add(name);
            }
        }

        return formatBatchVolumeEvidence(valid, rowCounts, estimateSources, failed, invalid, dialect);
    }

    @Tool("""
        Profile a known table's actual data distribution. Use when the user
        asks for table profiling, column distribution, null ratio, distinct
        counts, sample values, min/max, or date ranges. This is deeper than
        lookup_table_metadata and broader than validateDataQuality. It returns
        a markdown profile report only; it must NOT produce SQL editor, chart,
        or query_data payload UX.
        """)
    public String profileTable(
            @P("Known table name. Bare table, schema.table, or database.schema.table are accepted.") String tableName,
            @P("Optional comma-separated columns to profile. Pass empty string to infer the first representative columns.") String columns,
            @P("Maximum columns to profile. Use 8 by default, max 10.") Integer limit
    ) {
        if (tableName == null || tableName.isBlank()) {
            return "profile_table: 'tableName' is required.";
        }
        String cleanTable = tableName.trim();
        String identifierError = validateIdentifier(cleanTable, "tableName");
        if (identifierError != null) return "BLOCKED: " + identifierError;

        int columnLimit = limit == null ? 8 : Math.max(1, Math.min(limit, MAX_PROFILE_COLUMNS));
        List<String> requestedColumns = parseIdentifierList(columns, "columns");
        if (requestedColumns == null) return "BLOCKED: columns must be comma-separated unquoted identifiers.";

        emitProgress("metadata.profile");
        log.info("[MetadataTools] profileTable table={}, columns={}, limit={}", cleanTable, requestedColumns, columnLimit);

        try {
            List<ColumnProfile> detected = sampleColumnProfiles(cleanTable);
            List<String> targetColumns = requestedColumns.isEmpty()
                    ? detected.stream().map(ColumnProfile::name).limit(columnLimit).collect(Collectors.toList())
                    : requestedColumns.stream().limit(columnLimit).collect(Collectors.toList());
            if (targetColumns.isEmpty()) {
                return "No columns could be profiled for `" + cleanTable + "`. "
                        + "The table may be empty, inaccessible, or not supported by the connected dialect.";
            }

            Map<String, String> typeByColumn = detected.stream()
                    .collect(Collectors.toMap(ColumnProfile::name, ColumnProfile::dataType, (a, b) -> a, LinkedHashMap::new));
            Long rowCount = executeSingleLong("SELECT COUNT(*) AS row_count FROM " + cleanTable);
            Map<String, Long> nullCounts = executeNullCounts(cleanTable, targetColumns);
            Map<String, Long> distinctCounts = executeDistinctCounts(cleanTable, targetColumns);
            Map<String, MinMax> minMaxByColumn = executeMinMax(cleanTable, targetColumns);
            Map<String, List<String>> sampleValues = executeSampleValues(cleanTable, targetColumns);

            List<ColumnProfile> profiles = new ArrayList<>();
            for (String column : targetColumns) {
                long nulls = nullCounts.getOrDefault(column, 0L);
                double nullRatio = rowCount == null || rowCount == 0 ? 0.0 : (double) nulls / rowCount * 100.0;
                profiles.add(new ColumnProfile(
                        column,
                        typeByColumn.getOrDefault(column, "unknown"),
                        nulls,
                        nullRatio,
                        distinctCounts.getOrDefault(column, null),
                        minMaxByColumn.get(column),
                        sampleValues.getOrDefault(column, List.of())
                ));
            }

            return formatProfileReport(cleanTable, rowCount, profiles, requestedColumns.isEmpty());
        } catch (Exception e) {
            log.warn("[MetadataTools] profileTable failed: {}", e.getMessage());
            return "Table profiling failed for `" + cleanTable + "`. "
                    + "Ask the user to confirm the table name or provide a smaller set of columns. "
                    + "Do not expose raw database errors.";
        }
    }

    private String validateSqlSafety(String sql) {
        String cleaned = sql.replaceAll("--[^\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ").trim();
        String upper = cleaned.toUpperCase();
        boolean allowed = ALLOWED_SQL_PREFIXES.stream().anyMatch(upper::startsWith);
        if (!allowed) return "Query must start with SELECT / WITH / SHOW / DESCRIBE / EXPLAIN.";
        String[] tokens = upper.split("\\s+|\\(|\\)|;|,");
        for (String t : tokens) {
            if (BLOCKED_SQL_KEYWORDS.contains(t)) {
                return "Forbidden keyword: " + t;
            }
        }
        String withoutTrailingSemi = cleaned.replaceAll(";\\s*$", "");
        if (withoutTrailingSemi.contains(";")) return "Multiple statements are not allowed.";
        return null;
    }

    private List<String> parseIdentifierList(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        List<String> values = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_QUALITY_COLUMNS)
                .collect(Collectors.toList());
        for (String value : values) {
            if (validateIdentifier(value, fieldName) != null) return null;
        }
        return values;
    }

    private List<String> sampleColumnNames(String tableName) {
        String sql = buildSampleSql(tableName, 1);
        try {
            ListResult<ExecuteResult> result = executeSql(sql);
            if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                ExecuteResult data = result.getData().get(0);
                if (data.getHeaderList() != null) {
                    return data.getHeaderList().stream()
                            .map(Header::getName)
                            .filter(name -> name != null && !name.isBlank())
                            .filter(name -> !"Row Number".equalsIgnoreCase(name))
                            .limit(MAX_QUALITY_COLUMNS)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("[MetadataTools] sampleColumnNames failed: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private List<ColumnProfile> sampleColumnProfiles(String tableName) {
        String sql = buildSampleSql(tableName, 1);
        try {
            ListResult<ExecuteResult> result = executeSql(sql);
            if (result.success() && result.getData() != null && !result.getData().isEmpty()) {
                ExecuteResult data = result.getData().get(0);
                if (data.getHeaderList() != null) {
                    return data.getHeaderList().stream()
                            .filter(header -> header.getName() != null && !header.getName().isBlank())
                            .filter(header -> !"Row Number".equalsIgnoreCase(header.getName()))
                            .filter(header -> validateIdentifier(header.getName(), "column") == null)
                            .limit(MAX_PROFILE_COLUMNS)
                            .map(header -> new ColumnProfile(
                                    header.getName(),
                                    header.getDataType() == null ? "unknown" : header.getDataType(),
                                    0L,
                                    0.0,
                                    null,
                                    null,
                                    List.of()
                            ))
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("[MetadataTools] sampleColumnProfiles failed: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private String buildSampleSql(String tableName, int limit) {
        String dialect = dbType == null ? "" : dbType.toUpperCase();
        return switch (dialect) {
            case "SQLSERVER" -> "SELECT TOP " + limit + " * FROM " + tableName;
            case "ORACLE", "DB2" -> "SELECT * FROM " + tableName + " FETCH FIRST " + limit + " ROWS ONLY";
            default -> "SELECT * FROM " + tableName + " LIMIT " + limit;
        };
    }

    private String inferDateColumn(List<String> columns) {
        if (columns == null) return "";
        return columns.stream()
                .filter(c -> {
                    String lower = c.toLowerCase();
                    return lower.contains("date")
                            || lower.contains("time")
                            || lower.contains("created")
                            || lower.contains("updated")
                            || lower.endsWith("_at")
                            || lower.endsWith("dt");
                })
                .findFirst()
                .orElse("");
    }

    private ListResult<ExecuteResult> executeSql(String sql) {
        DlExecuteParam param = new DlExecuteParam();
        param.setSql(sql);
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        param.setConsoleId(0L);
        return dlTemplateService.execute(param);
    }

    private Long executeSingleLong(String sql) {
        ListResult<ExecuteResult> result = executeSql(sql);
        String value = firstValue(result);
        if (value == null || value.isBlank()) return null;
        try {
            return Math.round(Double.parseDouble(value.replace(",", "").trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Long> executeNullCounts(String tableName, List<String> columns) {
        if (columns == null || columns.isEmpty()) return new LinkedHashMap<>();
        String select = columns.stream()
                .map(col -> "SUM(CASE WHEN " + col + " IS NULL THEN 1 ELSE 0 END) AS " + col + "_nulls")
                .collect(Collectors.joining(", "));
        ListResult<ExecuteResult> result = executeSql("SELECT " + select + " FROM " + tableName);
        return rowAsLongMap(result, columns, "_nulls");
    }

    private Map<String, Long> executeDistinctCounts(String tableName, List<String> columns) {
        if (columns == null || columns.isEmpty()) return new LinkedHashMap<>();
        String select = columns.stream()
                .map(col -> "COUNT(DISTINCT " + col + ") AS " + col + "_distinct")
                .collect(Collectors.joining(", "));
        ListResult<ExecuteResult> result = executeSql("SELECT " + select + " FROM " + tableName);
        return rowAsLongMap(result, columns, "_distinct");
    }

    private Map<String, MinMax> executeMinMax(String tableName, List<String> columns) {
        Map<String, MinMax> values = new LinkedHashMap<>();
        if (columns == null || columns.isEmpty()) return values;
        String select = columns.stream()
                .map(col -> "MIN(" + col + ") AS " + col + "_min, MAX(" + col + ") AS " + col + "_max")
                .collect(Collectors.joining(", "));
        try {
            ListResult<ExecuteResult> result = executeSql("SELECT " + select + " FROM " + tableName);
            if (result == null || !result.success() || result.getData() == null || result.getData().isEmpty()) {
                return values;
            }
            ExecuteResult data = result.getData().get(0);
            if (data.getDataList() == null || data.getDataList().isEmpty()) return values;
            List<String> row = data.getDataList().get(0);
            int offset = row.size() == columns.size() * 2 + 1 ? 1 : 0;
            for (int i = 0; i < columns.size(); i++) {
                int minIdx = offset + (i * 2);
                int maxIdx = minIdx + 1;
                if (maxIdx < row.size()) {
                    values.put(columns.get(i), new MinMax(row.get(minIdx), row.get(maxIdx)));
                }
            }
        } catch (Exception e) {
            log.warn("[MetadataTools] executeMinMax failed: {}", e.getMessage());
        }
        return values;
    }

    private Map<String, List<String>> executeSampleValues(String tableName, List<String> columns) {
        Map<String, List<String>> samples = new LinkedHashMap<>();
        if (columns == null || columns.isEmpty()) return samples;
        for (String column : columns) {
            try {
                String sql = buildColumnValueProbeSql(tableName, column, "", MAX_PROFILE_SAMPLE_VALUES);
                ListResult<ExecuteResult> result = executeSql(sql);
                if (result == null || !result.success() || result.getData() == null || result.getData().isEmpty()) {
                    samples.put(column, List.of());
                    continue;
                }
                ExecuteResult data = result.getData().get(0);
                if (data.getDataList() == null) {
                    samples.put(column, List.of());
                    continue;
                }
                samples.put(column, data.getDataList().stream()
                        .filter(row -> row != null && !row.isEmpty())
                        .map(row -> row.get(row.size() == 3 ? 1 : 0))
                        .filter(value -> value != null && !value.isBlank())
                        .limit(MAX_PROFILE_SAMPLE_VALUES)
                        .collect(Collectors.toList()));
            } catch (Exception e) {
                log.warn("[MetadataTools] executeSampleValues failed for {}: {}", column, e.getMessage());
                samples.put(column, List.of());
            }
        }
        return samples;
    }

    private DateRange executeDateRange(String tableName, String dateColumn) {
        ListResult<ExecuteResult> result = executeSql("SELECT MIN(" + dateColumn + ") AS min_value, MAX(" + dateColumn + ") AS max_value FROM " + tableName);
        if (!result.success() || result.getData() == null || result.getData().isEmpty()) return null;
        ExecuteResult data = result.getData().get(0);
        if (data.getDataList() == null || data.getDataList().isEmpty()) return null;
        List<String> row = data.getDataList().get(0);
        return new DateRange(row.size() > 0 ? row.get(0) : null, row.size() > 1 ? row.get(1) : null);
    }

    private Long executeRecentRowCount(String tableName, String dateColumn, int days) {
        String sql = buildRecentRowCountSql(tableName, dateColumn, days);
        if (sql == null || sql.isBlank()) return null;
        try {
            return executeSingleLong(sql);
        } catch (Exception e) {
            log.debug("[MetadataTools] recent row count failed for {} days: {}", days, e.getMessage());
            return null;
        }
    }

    private String buildRecentRowCountSql(String tableName, String dateColumn, int days) {
        String dialect = dbType == null ? "" : dbType.toUpperCase();
        return switch (dialect) {
            case "SQLSERVER" -> "SELECT COUNT(*) AS row_count FROM " + tableName
                    + " WHERE " + dateColumn + " >= DATEADD(day, -" + days + ", GETDATE())";
            case "ORACLE", "DB2" -> "SELECT COUNT(*) AS row_count FROM " + tableName
                    + " WHERE " + dateColumn + " >= CURRENT_DATE - " + days + " DAYS";
            case "BIGQUERY" -> "SELECT COUNT(*) AS row_count FROM " + tableName
                    + " WHERE " + dateColumn + " >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL " + days + " DAY)";
            case "SNOWFLAKE", "DATABRICKS" -> "SELECT COUNT(*) AS row_count FROM " + tableName
                    + " WHERE " + dateColumn + " >= DATEADD(day, -" + days + ", CURRENT_TIMESTAMP())";
            default -> "SELECT COUNT(*) AS row_count FROM " + tableName
                    + " WHERE " + dateColumn + " >= CURRENT_DATE - INTERVAL '" + days + " days'";
        };
    }

    private String firstValue(ListResult<ExecuteResult> result) {
        if (result == null || !result.success() || result.getData() == null || result.getData().isEmpty()) return null;
        ExecuteResult data = result.getData().get(0);
        if (data.getDataList() == null || data.getDataList().isEmpty()) return null;
        List<String> row = data.getDataList().get(0);
        return row == null || row.isEmpty() ? null : row.get(row.size() - 1);
    }

    private Map<String, Long> rowAsLongMap(ListResult<ExecuteResult> result, List<String> columns, String suffix) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (result == null || !result.success() || result.getData() == null || result.getData().isEmpty()) {
            return values;
        }
        ExecuteResult data = result.getData().get(0);
        if (data.getDataList() == null || data.getDataList().isEmpty()) return values;
        List<String> row = data.getDataList().get(0);
        int offset = row.size() == columns.size() + 1 ? 1 : 0;
        for (int i = 0; i < columns.size() && i + offset < row.size(); i++) {
            try {
                values.put(columns.get(i), Math.round(Double.parseDouble(row.get(i + offset).replace(",", "").trim())));
            } catch (Exception ignored) {
                values.put(columns.get(i), 0L);
            }
        }
        return values;
    }

    private String buildDuplicateGroupsSql(String tableName, List<String> keys) {
        String joinedKeys = String.join(", ", keys);
        return "SELECT COUNT(*) AS duplicate_key_groups FROM (\n"
                + "  SELECT " + joinedKeys + ", COUNT(*) AS row_count\n"
                + "  FROM " + tableName + "\n"
                + "  GROUP BY " + joinedKeys + "\n"
                + "  HAVING COUNT(*) > 1\n"
                + ") dq";
    }

    private String overallStatus(List<QualityCheck> checks) {
        boolean hasFail = checks.stream().anyMatch(c -> "FAIL".equals(c.status));
        if (hasFail) return "FAIL";
        boolean hasWarning = checks.stream().anyMatch(c -> "WARNING".equals(c.status));
        return hasWarning ? "WARNING" : "PASS";
    }

    private String formatQualityReport(String tableName, String overall,
                                       List<QualityCheck> checks, List<String> limitations) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Data Quality Check: `").append(tableName).append("`\n\n");
        sb.append("**Overall:** ").append(overall).append("\n\n");
        sb.append("**Checks**\n");
        for (QualityCheck check : checks) {
            sb.append("- **").append(check.name).append(":** ")
                    .append(check.value).append(" — ")
                    .append(check.status)
                    .append(". ")
                    .append(check.detail)
                    .append(" Severity: ")
                    .append(check.severity)
                    .append(".\n");
        }
        if (limitations != null && !limitations.isEmpty()) {
            sb.append("\n**Limitations**\n");
            limitations.stream().distinct().forEach(item -> sb.append("- ").append(item).append("\n"));
        }
        sb.append("\n**Next step**\n");
        sb.append("- Use `profile_table` for deeper column distributions, samples, and min/max profiling.\n");
        return sb.toString();
    }

    private String formatProfileReport(String tableName, Long rowCount,
                                       List<ColumnProfile> profiles,
                                       boolean inferredColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Table Profile: `").append(tableName).append("`\n\n");
        sb.append("**Overview**\n");
        sb.append("- Row count: ").append(rowCount == null ? "unknown" : rowCount).append("\n");
        sb.append("- Profiled columns: ").append(profiles.size()).append("\n");
        if (inferredColumns) {
            sb.append("- Column selection: inferred representative columns from the table sample.\n");
        }
        sb.append("\n**Column Profiles**\n");
        for (ColumnProfile profile : profiles) {
            sb.append("- `").append(profile.name()).append("`");
            sb.append(" (").append(profile.dataType()).append(")");
            sb.append(": nulls=").append(profile.nullCount());
            sb.append(" (").append(formatPercent(profile.nullRatio())).append(")");
            if (profile.distinctCount() != null) {
                sb.append(", distinct=").append(profile.distinctCount());
            }
            if (profile.minMax() != null) {
                sb.append(", min=").append(nullSafe(profile.minMax().min()));
                sb.append(", max=").append(nullSafe(profile.minMax().max()));
            }
            if (profile.sampleValues() != null && !profile.sampleValues().isEmpty()) {
                sb.append(", sample values=[")
                        .append(profile.sampleValues().stream()
                                .map(value -> "`" + value + "`")
                                .collect(Collectors.joining(", ")))
                        .append("]");
            }
            sb.append("\n");
        }
        sb.append("\n**Limitations**\n");
        sb.append("- This is a lightweight profile over capped columns and sample values, not a full statistical distribution.\n");
        sb.append("- Use `validate_data_quality` when the goal is pass/warning/fail trust checks.\n");
        return sb.toString();
    }

    private String formatDataVolumeEvidence(String tableName,
                                            Long rowCount,
                                            List<ColumnProfile> sampledColumns,
                                            String dateColumn,
                                            DateRange dateRange,
                                            Long recent30,
                                            Long recent90) {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA_VOLUME_EVIDENCE\n\n");
        sb.append("Table: `").append(tableName).append("`\n");
        sb.append("Total row count: ").append(rowCount == null ? "unknown" : rowCount).append("\n");
        sb.append("Volume band: ").append(volumeBand(rowCount)).append("\n");
        sb.append("Thresholds: small <= ").append(SMALL_TABLE_MAX_ROWS)
                .append(", medium <= ").append(MEDIUM_TABLE_MAX_ROWS)
                .append(", large <= ").append(LARGE_TABLE_MAX_ROWS)
                .append(", very_large > ").append(LARGE_TABLE_MAX_ROWS)
                .append("\n");

        if (sampledColumns != null && !sampledColumns.isEmpty()) {
            sb.append("Sampled columns: ")
                    .append(sampledColumns.stream()
                            .map(profile -> "`" + profile.name() + "`(" + profile.dataType() + ")")
                            .collect(Collectors.joining(", ")))
                    .append("\n");
        }

        if (dateColumn != null && !dateColumn.isBlank()) {
            sb.append("Date column candidate: `").append(dateColumn).append("`\n");
            if (dateRange != null) {
                sb.append("Date range: ").append(nullSafe(dateRange.min))
                        .append(" to ")
                        .append(nullSafe(dateRange.max))
                        .append("\n");
            } else {
                sb.append("Date range: unknown\n");
            }
            if (recent30 != null) {
                sb.append("Rows in recent 30 days: ").append(recent30).append("\n");
            }
            if (recent90 != null) {
                sb.append("Rows in recent 90 days: ").append(recent90).append("\n");
            }
        } else {
            sb.append("Date column candidate: none inferred from sampled columns\n");
        }

        sb.append("\nInstructions for the root agent:\n");
        sb.append("- Treat this as scan-cost evidence only; the tool does not decide the final action.\n");
        sb.append("- If row count is large/very_large and the user did not specify a time range, prefer request_date_range before queryData unless the next query is clearly bounded, aggregated, or already limited.\n");
        sb.append("- If row count is small or the table appears to be a summary/aggregate table, queryData can proceed without a date picker when the SQL will stay bounded.\n");
        sb.append("- If the user explicitly asked for all-time/overall analysis, prefer aggregate SQL over raw/detail scans.\n");
        return sb.toString();
    }

    private String volumeBand(Long rowCount) {
        if (rowCount == null) return "unknown";
        if (rowCount <= SMALL_TABLE_MAX_ROWS) return "small";
        if (rowCount <= MEDIUM_TABLE_MAX_ROWS) return "medium";
        if (rowCount <= LARGE_TABLE_MAX_ROWS) return "large";
        return "very_large";
    }

    private String formatBatchVolumeEvidence(List<String> requested,
                                             Map<String, Long> rowCounts,
                                             Set<String> estimateSources,
                                             List<String> failed,
                                             List<String> invalid,
                                             String dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA_VOLUME_BATCH_EVIDENCE\n\n");
        sb.append("Dialect: ").append(dialect == null || dialect.isBlank() ? "unknown" : dialect).append("\n");
        sb.append("Thresholds: small <= ").append(SMALL_TABLE_MAX_ROWS)
                .append(", medium <= ").append(MEDIUM_TABLE_MAX_ROWS)
                .append(", large <= ").append(LARGE_TABLE_MAX_ROWS)
                .append(", very_large > ").append(LARGE_TABLE_MAX_ROWS)
                .append("\n\n");

        sb.append("| Table | Row count | Volume band | Source |\n");
        sb.append("|-------|-----------|-------------|--------|\n");
        boolean anyRow = false;
        for (String name : requested) {
            Long count = rowCounts.get(name);
            if (count == null) continue;
            anyRow = true;
            String source = estimateSources != null && estimateSources.contains(name)
                    ? "estimate (catalog stats)"
                    : "exact (COUNT(*))";
            sb.append("| `").append(name).append("` | ")
                    .append(count).append(" | ")
                    .append(volumeBand(count)).append(" | ")
                    .append(source).append(" |\n");
        }
        if (!anyRow) {
            sb.append("| _no probe succeeded_ | - | - | - |\n");
        }

        if (failed != null && !failed.isEmpty()) {
            sb.append("\nProbe failed (table may not exist, be inaccessible, or rejected by the dialect): ")
                    .append(failed.stream().map(n -> "`" + n + "`").collect(Collectors.joining(", ")))
                    .append("\n");
        }
        if (invalid != null && !invalid.isEmpty()) {
            sb.append("\nIgnored invalid identifiers: ")
                    .append(invalid.stream().map(n -> "`" + n + "`").collect(Collectors.joining(", ")))
                    .append("\n");
        }

        sb.append("\nInstructions for the root agent:\n");
        sb.append("- 'Source' shows how the count was obtained. 'estimate (catalog stats)' is fast and may be slightly stale (depends on ANALYZE/auto-stats); 'exact (COUNT(*))' is precise but full-scans the table.\n");
        sb.append("- Pick the single most relevant candidate for the user's question (judge by table name + the prior catalog candidate descriptions).\n");
        sb.append("- If the chosen table is small or medium, call queryData directly with a bounded SQL. Do NOT call request_date_range.\n");
        sb.append("- If the chosen table is large or very_large AND the user did NOT specify a time range, call checkDataVolume on that ONE table to fetch date evidence before deciding whether to call request_date_range.\n");
        sb.append("- The wording 'trend' / 'over time' / 'by month' alone is NOT a reason to ask for a date range. Only volume band + missing user-specified time range justifies request_date_range.\n");
        return sb.toString();
    }

    /**
     * Build a single dialect-aware SQL that returns {@code (table_name, row_count)}
     * for every requested table using the connected dialect's statistics
     * catalog. Returns {@code null} when the dialect is unknown or cannot be
     * queried in one round-trip; the caller then falls back to portable
     * {@code COUNT(*)} probes.
     *
     * <p>Estimates may be stale (PostgreSQL/Oracle: needs ANALYZE; MySQL
     * InnoDB: sampled). Counts ≤ 0 are treated as "missing" so the caller
     * recomputes them via the exact path. Volume-band classification
     * (small/medium/large/very_large) tolerates these inaccuracies.
     */
    private String buildEstimateRowCountSql(String dialect, List<String> tableNames) {
        if (dialect == null || dialect.isBlank() || tableNames == null || tableNames.isEmpty()) {
            return null;
        }
        List<String> shortNames = tableNames.stream()
                .map(this::shortTableName)
                .distinct()
                .collect(Collectors.toList());
        String inClauseLower = inClauseLiterals(shortNames, false);
        String inClauseUpper = inClauseLiterals(shortNames, true);

        switch (dialect) {
            case "POSTGRESQL":
            case "POSTGRES":
            case "GREENPLUM": {
                String schemaPredicate = (schemaName != null && !schemaName.isBlank())
                        ? " AND n.nspname = '" + escapeLiteral(schemaName) + "'"
                        : "";
                return "SELECT c.relname AS table_name, c.reltuples::bigint AS row_count "
                        + "FROM pg_class c "
                        + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                        + "WHERE c.relkind IN ('r','p') AND c.relname IN (" + inClauseLower + ")"
                        + schemaPredicate;
            }
            case "REDSHIFT": {
                String schemaPredicate = (schemaName != null && !schemaName.isBlank())
                        ? " AND \"schema\" = '" + escapeLiteral(schemaName) + "'"
                        : "";
                return "SELECT \"table\" AS table_name, tbl_rows AS row_count "
                        + "FROM svv_table_info "
                        + "WHERE \"table\" IN (" + inClauseLower + ")"
                        + schemaPredicate;
            }
            case "MYSQL":
            case "MARIADB":
            case "TIDB": {
                String schema = schemaName != null && !schemaName.isBlank()
                        ? schemaName
                        : (databaseName != null && !databaseName.isBlank() ? databaseName : null);
                String schemaPredicate = schema != null
                        ? " AND TABLE_SCHEMA = '" + escapeLiteral(schema) + "'"
                        : "";
                return "SELECT TABLE_NAME AS table_name, TABLE_ROWS AS row_count "
                        + "FROM information_schema.TABLES "
                        + "WHERE TABLE_NAME IN (" + inClauseLower + ")"
                        + schemaPredicate;
            }
            case "SNOWFLAKE": {
                String schemaPredicate = (schemaName != null && !schemaName.isBlank())
                        ? " AND TABLE_SCHEMA = '" + escapeLiteral(schemaName.toUpperCase(Locale.ROOT)) + "'"
                        : "";
                return "SELECT TABLE_NAME AS table_name, ROW_COUNT AS row_count "
                        + "FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME IN (" + inClauseUpper + ")"
                        + schemaPredicate;
            }
            case "BIGQUERY": {
                // __TABLES__ pseudo-table needs the dataset (databaseName) qualifier.
                String dataset = (schemaName != null && !schemaName.isBlank())
                        ? schemaName
                        : (databaseName != null && !databaseName.isBlank() ? databaseName : null);
                if (dataset == null) return null;
                return "SELECT table_id AS table_name, row_count "
                        + "FROM `" + dataset + ".__TABLES__` "
                        + "WHERE table_id IN (" + inClauseLower + ")";
            }
            case "SQLSERVER":
            case "MSSQL": {
                String schemaPredicate = (schemaName != null && !schemaName.isBlank())
                        ? " AND s.name = '" + escapeLiteral(schemaName) + "'"
                        : "";
                return "SELECT t.name AS table_name, SUM(p.rows) AS row_count "
                        + "FROM sys.tables t "
                        + "JOIN sys.partitions p ON t.object_id = p.object_id "
                        + "JOIN sys.schemas s ON t.schema_id = s.schema_id "
                        + "WHERE p.index_id IN (0, 1) AND t.name IN (" + inClauseLower + ")"
                        + schemaPredicate
                        + " GROUP BY t.name";
            }
            case "ORACLE": {
                if (schemaName != null && !schemaName.isBlank()) {
                    return "SELECT TABLE_NAME AS table_name, NUM_ROWS AS row_count "
                            + "FROM ALL_TABLES "
                            + "WHERE OWNER = '" + escapeLiteral(schemaName.toUpperCase(Locale.ROOT)) + "' "
                            + "AND TABLE_NAME IN (" + inClauseUpper + ")";
                }
                return "SELECT TABLE_NAME AS table_name, NUM_ROWS AS row_count "
                        + "FROM USER_TABLES WHERE TABLE_NAME IN (" + inClauseUpper + ")";
            }
            case "DB2": {
                String schemaPredicate = (schemaName != null && !schemaName.isBlank())
                        ? " AND TABSCHEMA = '" + escapeLiteral(schemaName.toUpperCase(Locale.ROOT)) + "'"
                        : "";
                return "SELECT TABNAME AS table_name, CARD AS row_count "
                        + "FROM SYSCAT.TABLES "
                        + "WHERE TYPE = 'T' AND TABNAME IN (" + inClauseUpper + ")"
                        + schemaPredicate;
            }
            case "CLICKHOUSE": {
                String db = (schemaName != null && !schemaName.isBlank())
                        ? schemaName
                        : (databaseName != null && !databaseName.isBlank() ? databaseName : null);
                String schemaPredicate = db != null
                        ? " AND database = '" + escapeLiteral(db) + "'"
                        : "";
                return "SELECT name AS table_name, total_rows AS row_count "
                        + "FROM system.tables "
                        + "WHERE name IN (" + inClauseLower + ")"
                        + schemaPredicate;
            }
            case "DUCKDB": {
                return "SELECT table_name, estimated_size AS row_count "
                        + "FROM duckdb_tables() "
                        + "WHERE table_name IN (" + inClauseLower + ")";
            }
            // SQLite, Trino/Presto, and other dialects fall through — they
            // either have no row-count statistic in INFORMATION_SCHEMA or
            // intentionally hide it. The portable COUNT(*) fallback handles
            // them.
            default:
                return null;
        }
    }

    private String inClauseLiterals(List<String> values, boolean upperCase) {
        return values.stream()
                .map(v -> upperCase ? v.toUpperCase(Locale.ROOT) : v)
                .map(v -> "'" + escapeLiteral(v) + "'")
                .collect(Collectors.joining(", "));
    }

    private String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * Parse the {@code (table_name, row_count)} rows returned by an estimate
     * SQL into a map keyed by raw table name. {@code COUNT(*)} columns may
     * arrive as decimals (e.g. {@code reltuples} is a double) — we round
     * defensively.
     */
    private Map<String, Long> parseEstimateResults(ListResult<ExecuteResult> result) {
        Map<String, Long> byName = new LinkedHashMap<>();
        if (result == null || !result.success() || result.getData() == null || result.getData().isEmpty()) {
            return byName;
        }
        ExecuteResult data = result.getData().get(0);
        if (data.getDataList() == null) return byName;
        for (List<String> row : data.getDataList()) {
            if (row == null || row.size() < 2) continue;
            int offset = row.size() >= 3 ? 1 : 0;
            String name = row.get(offset);
            String count = row.get(offset + 1);
            if (name == null || count == null || name.isBlank()) continue;
            try {
                long parsed = Math.round(Double.parseDouble(count.replace(",", "").trim()));
                byName.put(name.trim(), parsed);
            } catch (NumberFormatException ignored) {
                // skip malformed value
            }
        }
        return byName;
    }

    /**
     * Catalogs differ in case-folding (PostgreSQL lowercases, Oracle/Snowflake
     * uppercase). We always probe with the requested casing first, then both
     * lower and upper forms.
     */
    private Long lookupCaseInsensitive(Map<String, Long> map, String key) {
        if (map == null || map.isEmpty() || key == null) return null;
        Long hit = map.get(key);
        if (hit != null) return hit;
        hit = map.get(key.toLowerCase(Locale.ROOT));
        if (hit != null) return hit;
        return map.get(key.toUpperCase(Locale.ROOT));
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.US, "%.2f%%", value);
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String validateIdentifier(String value, String fieldName) {
        if (!value.matches(QUALIFIED_IDENTIFIER)) {
            return fieldName + " must be an unquoted identifier path like table, schema.table, or database.schema.table.";
        }
        return null;
    }

    private String validateWhereCondition(String whereCondition) {
        if (whereCondition == null || whereCondition.isBlank()) return null;
        String cleaned = whereCondition.replaceAll("--[^\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ").trim();
        if (cleaned.toUpperCase().startsWith("WHERE ")) {
            return "whereCondition must not include the WHERE keyword.";
        }
        if (cleaned.contains(";")) {
            return "whereCondition must not contain semicolons or multiple statements.";
        }
        String upper = cleaned.toUpperCase();
        String[] tokens = upper.split("\\s+|\\(|\\)|;|,");
        for (String t : tokens) {
            if (BLOCKED_SQL_KEYWORDS.contains(t) || ALLOWED_SQL_PREFIXES.contains(t)) {
                return "Forbidden keyword in whereCondition: " + t;
            }
        }
        return null;
    }

    private String buildColumnValueProbeSql(String tableName, String columnName,
                                            String whereCondition, int limit) {
        String baseWhere = columnName + " IS NOT NULL";
        if (whereCondition != null && !whereCondition.isBlank()) {
            baseWhere = baseWhere + " AND (" + whereCondition + ")";
        }

        String dialect = dbType == null ? "" : dbType.toUpperCase();
        switch (dialect) {
            case "SQLSERVER":
                return "SELECT TOP " + limit + " " + columnName + " AS value, COUNT(*) AS row_count\n"
                        + "FROM " + tableName + "\n"
                        + "WHERE " + baseWhere + "\n"
                        + "GROUP BY " + columnName + "\n"
                        + "ORDER BY row_count DESC";
            case "ORACLE":
            case "DB2":
                return "SELECT " + columnName + " AS value, COUNT(*) AS row_count\n"
                        + "FROM " + tableName + "\n"
                        + "WHERE " + baseWhere + "\n"
                        + "GROUP BY " + columnName + "\n"
                        + "ORDER BY row_count DESC\n"
                        + "FETCH FIRST " + limit + " ROWS ONLY";
            default:
                return "SELECT " + columnName + " AS value, COUNT(*) AS row_count\n"
                        + "FROM " + tableName + "\n"
                        + "WHERE " + baseWhere + "\n"
                        + "GROUP BY " + columnName + "\n"
                        + "ORDER BY row_count DESC\n"
                        + "LIMIT " + limit;
        }
    }

    private TableLineageDO findLineageRow(String shortTableName) {
        try {
            TableLineageMapper mapper = Dbutils.getMapper(TableLineageMapper.class);
            LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TableLineageDO::getDataSourceId, dataSourceId)
                    .eq(TableLineageDO::getTableName, shortTableName);
            if (databaseName != null) wrapper.eq(TableLineageDO::getDatabaseName, databaseName);
            if (schemaName != null) wrapper.eq(TableLineageDO::getSchemaName, schemaName);
            List<TableLineageDO> rows = mapper.selectList(wrapper);
            return rows == null || rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[MetadataTools] findLineageRow failed: {}", e.getMessage());
            return null;
        }
    }

    private List<TableLineageDO> findDownstreamRows(String shortTableName) {
        try {
            TableLineageMapper mapper = Dbutils.getMapper(TableLineageMapper.class);
            LambdaQueryWrapper<TableLineageDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TableLineageDO::getDataSourceId, dataSourceId)
                    .like(TableLineageDO::getSourceTables, shortTableName);
            if (databaseName != null) wrapper.eq(TableLineageDO::getDatabaseName, databaseName);
            if (schemaName != null) wrapper.eq(TableLineageDO::getSchemaName, schemaName);
            List<TableLineageDO> rows = mapper.selectList(wrapper);
            return rows != null ? rows : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[MetadataTools] findDownstreamRows failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String shortTableName(String tableName) {
        int idx = tableName.lastIndexOf('.');
        return idx >= 0 ? tableName.substring(idx + 1) : tableName;
    }

    private void appendSourceQuery(StringBuilder sb, String sourceQuery) {
        if (sourceQuery == null || sourceQuery.isBlank()) {
            sb.append("\n## Source Query\nNot recorded\n");
            return;
        }
        sb.append("\n## Source Query\n```sql\n")
                .append(truncateSourceQuery(sourceQuery.trim()))
                .append("\n```\n");
    }

    private String truncateSourceQuery(String sourceQuery) {
        if (sourceQuery.length() <= MAX_SOURCE_QUERY_CHARS) {
            return sourceQuery;
        }
        return sourceQuery.substring(0, MAX_SOURCE_QUERY_CHARS)
                + "\n-- truncated: source_query is longer than "
                + MAX_SOURCE_QUERY_CHARS + " characters";
    }

    private String sourceQuerySnippets(String sourceQuery, String columnName) {
        if (sourceQuery == null || sourceQuery.isBlank() || columnName == null || columnName.isBlank()) {
            return "";
        }
        String[] lines = sourceQuery.split("\\R");
        String needle = columnName.toLowerCase();
        List<String> snippets = new ArrayList<>();
        int lastIncluded = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].toLowerCase().contains(needle)) {
                continue;
            }
            int start = Math.max(0, i - 2);
            int end = Math.min(lines.length - 1, i + 2);
            if (start <= lastIncluded) {
                start = lastIncluded + 1;
            }
            if (!snippets.isEmpty() && start <= end) {
                snippets.add("-- ...");
            }
            for (int j = start; j <= end; j++) {
                snippets.add(lines[j]);
            }
            lastIncluded = end;
            if (String.join("\n", snippets).length() > MAX_SOURCE_QUERY_CHARS) {
                snippets.add("-- truncated: more matching snippets omitted");
                break;
            }
        }
        return String.join("\n", snippets).trim();
    }

    private String catalogMetricEvidence(List<String> catalogBlocks, String metricName) {
        if (catalogBlocks == null || catalogBlocks.isEmpty() || metricName == null || metricName.isBlank()) {
            return "";
        }
        String needle = metricName.toLowerCase();
        List<String> evidence = new ArrayList<>();
        for (String block : catalogBlocks) {
            if (block == null || block.isBlank()) continue;
            String[] lines = block.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].toLowerCase().contains(needle)) continue;
                int start = Math.max(0, i - 1);
                int end = Math.min(lines.length - 1, i + 1);
                if (!evidence.isEmpty()) evidence.add("---");
                String tablePath = extractTablePath(block);
                if (tablePath != null && !tablePath.isBlank()) {
                    evidence.add("Table: " + tablePath);
                }
                for (int j = start; j <= end; j++) {
                    String trimmed = lines[j].trim();
                    if (!trimmed.isBlank()) evidence.add(trimmed);
                }
                if (String.join("\n", evidence).length() > 2500) {
                    evidence.add("... truncated");
                    return String.join("\n", evidence);
                }
            }
        }
        if (!evidence.isEmpty()) return String.join("\n", evidence);

        return catalogBlocks.stream()
                .filter(block -> block != null && !block.isBlank())
                .map(this::formatCatalogCandidateForDefinition)
                .limit(3)
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatCatalogCandidateForDefinition(String ddl) {
        StringBuilder sb = new StringBuilder();
        String tablePath = extractTablePath(ddl);
        if (tablePath != null && !tablePath.isBlank()) {
            sb.append("Table: ").append(tablePath).append("\n");
        }
        String description = extractTableDescription(ddl);
        if (description != null && !description.isBlank()) {
            sb.append("Description: ").append(description).append("\n");
        }
        List<String> columns = extractColumnPreview(ddl, 8);
        if (!columns.isEmpty()) {
            sb.append("Columns: ").append(String.join(", ", columns));
        }
        return sb.toString().trim();
    }

    private String inferTableNameFromCatalog(List<String> catalogBlocks) {
        if (catalogBlocks == null || catalogBlocks.isEmpty()) return null;
        for (String block : catalogBlocks) {
            String tablePath = extractTablePath(block);
            if (tablePath != null && !tablePath.isBlank()) {
                return shortTableName(tablePath);
            }
        }
        return null;
    }

    private String formatMetricDefinitionEvidence(String metricName,
                                                  String tableName,
                                                  String catalogEvidence,
                                                  String tableDescription,
                                                  String sourceTables,
                                                  String sourceSnippet) {
        StringBuilder sb = new StringBuilder();
        sb.append("METRIC_DEFINITION_EVIDENCE\n\n");
        sb.append("Metric: `").append(metricName).append("`\n");
        if (tableName != null && !tableName.isBlank()) {
            sb.append("Requested table: `").append(tableName).append("`\n");
        }
        if (tableDescription != null && !tableDescription.isBlank()) {
            sb.append("Registered table description: ").append(tableDescription).append("\n");
        }

        sb.append("\nRegistered lineage evidence:\n");
        if (sourceTables != null && !sourceTables.isBlank()) {
            sb.append("- Upstream sources: ").append(sourceTables).append("\n");
        } else {
            sb.append("- Upstream sources: not registered.\n");
        }
        if (sourceSnippet != null && !sourceSnippet.isBlank()) {
            sb.append("- Source-query snippets mentioning `").append(metricName).append("`:\n")
                    .append("```sql\n")
                    .append(sourceSnippet)
                    .append("\n```\n");
        } else {
            sb.append("- No direct source-query snippet for `").append(metricName).append("` was found in registered lineage.\n");
        }

        sb.append("\nCatalog evidence:\n");
        if (catalogEvidence != null && !catalogEvidence.isBlank()) {
            sb.append("```text\n").append(truncateSourceQuery(catalogEvidence)).append("\n```\n");
        } else {
            sb.append("- No direct catalog line for this metric was found.\n");
        }

        sb.append("\nInstructions for the root agent:\n");
        sb.append("- Write the final metric explanation in the user's language.\n");
        sb.append("- Use only the registered evidence above; do not invent inclusion/exclusion rules, grain, source tables, discounts, tax, shipping, refunds, cancellations, or currency treatment.\n");
        sb.append("- If evidence is incomplete, say that the metric is only partially defined in the current catalog.\n");
        sb.append("- If the user needs exact SQL calculation logic, call explain_metric_source in a follow-up turn or mention it as the next step.\n");
        return sb.toString();
    }

    private void emitProgress(String key) {
        if (progressCallback == null || key == null || key.isBlank()) return;
        try {
            progressCallback.accept(key);
        } catch (Exception e) {
            log.debug("[MetadataTools] progress callback failed: {}", e.getMessage());
        }
    }

    private String formatCatalogCandidate(int index, String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return index + ". (empty catalog entry)";
        }
        String tablePath = extractTablePath(ddl);
        List<String> columns = extractColumnPreview(ddl, 8);
        String description = extractTableDescription(ddl);

        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". ");
        if (tablePath != null && !tablePath.isBlank()) {
            sb.append(tablePath);
        } else {
            sb.append(firstLine(ddl));
        }
        if (description != null && !description.isBlank()) {
            sb.append("\n   Description: ").append(description);
        }
        if (!columns.isEmpty()) {
            sb.append("\n   Columns: ").append(String.join(", ", columns));
        }
        return sb.toString();
    }

    private String extractTablePath(String ddl) {
        String marker = "[Table Path:";
        int start = ddl.indexOf(marker);
        if (start >= 0) {
            int end = ddl.indexOf("]", start);
            if (end > start) {
                return ddl.substring(start + marker.length(), end).trim();
            }
        }

        String upper = ddl.toUpperCase();
        int createIdx = upper.indexOf("CREATE TABLE ");
        if (createIdx >= 0) {
            int nameStart = createIdx + "CREATE TABLE ".length();
            int nameEnd = ddl.indexOf("(", nameStart);
            if (nameEnd > nameStart) {
                return ddl.substring(nameStart, nameEnd).trim();
            }
        }
        return null;
    }

    private List<String> extractColumnPreview(String ddl, int limit) {
        List<String> columns = new ArrayList<>();
        String[] lines = ddl.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("CREATE TABLE")
                    || trimmed.startsWith(");") || trimmed.equals("(")) {
                continue;
            }
            if (trimmed.startsWith("--")) continue;
            String cleaned = trimmed.replaceAll(",$", "");
            String[] parts = cleaned.split("\\s+");
            if (parts.length == 0) continue;
            String column = parts[0].replace("\"", "").replace("`", "");
            if (column.equalsIgnoreCase("CONSTRAINT") || column.equalsIgnoreCase("PRIMARY")
                    || column.equalsIgnoreCase("FOREIGN") || column.equalsIgnoreCase("KEY")) {
                continue;
            }
            columns.add(column);
            if (columns.size() >= limit) break;
        }
        return columns;
    }

    private String extractTableDescription(String ddl) {
        int commentIdx = ddl.lastIndexOf(" -- ");
        if (commentIdx >= 0 && commentIdx + 4 < ddl.length()) {
            return ddl.substring(commentIdx + 4).trim();
        }
        return null;
    }

    private String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline > 0 ? text.substring(0, newline) : text).trim();
    }

    private String formatResultForLLM(ExecuteResult result) {
        if (result == null || result.getDataList() == null) return "No data returned.";
        List<List<String>> data = result.getDataList();
        List<Header> headers = result.getHeaderList();
        if (data.isEmpty()) return "Query returned 0 rows.";

        StringBuilder sb = new StringBuilder();
        sb.append("Returned ").append(data.size()).append(" row(s).\n");
        if (headers != null && !headers.isEmpty()) {
            sb.append("Columns: ").append(headers.stream()
                    .map(Header::getName).collect(Collectors.joining(", "))).append("\n\n");
        }
        int rowLimit = Math.min(data.size(), MAX_ROWS_FOR_LLM);
        for (int i = 0; i < rowLimit; i++) {
            List<String> row = data.get(i);
            sb.append("Row ").append(i + 1).append(": ");
            if (headers != null && headers.size() == row.size()) {
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(headers.get(j).getName()).append("=").append(row.get(j));
                }
            } else {
                sb.append(String.join(", ", row));
            }
            sb.append("\n");
        }
        if (data.size() > rowLimit) {
            sb.append("... and ").append(data.size() - rowLimit).append(" more row(s)");
        }
        return sb.toString();
    }

    /**
     * Builds a one-shot {@code information_schema} probe in the connected
     * dialect's syntax. Returned as a fenced SQL block the LLM can copy
     * into a {@code run_readonly_sql} call. Conservative defaults:
     * INFORMATION_SCHEMA + LIMIT works on PostgreSQL, MySQL, Snowflake,
     * Redshift, etc. — only BigQuery and unknown dialects get a tailored
     * fallback. ILIKE is PostgreSQL-only; LIKE elsewhere.
     */
    private String suggestedTableLookupSql(String hintTableName) {
        String keyword = hintTableName == null ? "" : hintTableName.replace("'", "");
        String dialect = dbType == null ? "" : dbType.toUpperCase();
        String matchOp = "POSTGRESQL".equals(dialect) ? "ILIKE" : "LIKE";
        String pattern = "'%" + keyword + "%'";

        String body;
        switch (dialect) {
            case "BIGQUERY":
                body = "SELECT table_schema, table_name\n"
                        + "FROM `region-us`.INFORMATION_SCHEMA.TABLES\n"
                        + "WHERE LOWER(table_name) LIKE LOWER(" + pattern + ")\n"
                        + "LIMIT 20";
                break;
            case "SNOWFLAKE":
            case "DATABRICKS":
                body = "SHOW TABLES LIKE " + pattern
                        + (databaseName != null && schemaName != null
                            ? " IN " + databaseName + "." + schemaName : "");
                break;
            default:
                body = "SELECT table_schema, table_name\n"
                        + "FROM information_schema.tables\n"
                        + "WHERE table_name " + matchOp + " " + pattern + "\n"
                        + "LIMIT 20";
        }
        return "```sql\n" + body + "\n```";
    }

    private static String qualified(String db, String schema, String table) {
        StringBuilder sb = new StringBuilder();
        if (db != null && !db.isBlank()) sb.append(db).append(".");
        if (schema != null && !schema.isBlank()) sb.append(schema).append(".");
        sb.append(table);
        return sb.toString();
    }

    private record LineageInfo(TableLineageDO upstream, List<TableLineageDO> downstream) {}
    private record QualityCheck(String name, String status, String value, String detail, String severity) {}
    private record DateRange(String min, String max) {}
    private record MinMax(String min, String max) {}
    private record ColumnProfile(String name,
                                 String dataType,
                                 Long nullCount,
                                 double nullRatio,
                                 Long distinctCount,
                                 MinMax minMax,
                                 List<String> sampleValues) {}
}
