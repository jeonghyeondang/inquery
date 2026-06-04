package ai.inquery.server.domain.core.lineage;

import ai.inquery.server.domain.core.dbt.LineageGraph;

/**
 * Strategy interface for detecting table-level lineage from different database systems.
 * Each implementation knows how to extract lineage information from a specific DB type.
 */
public interface LineageDetector {

    /**
     * Whether this detector supports the given database type.
     */
    boolean supports(String dbType);

    /**
     * Detect lineage by executing queries against the user's database.
     * Caller is responsible for setting up InqueryContext with ConnectInfo before calling.
     *
     * @param dataSourceId the data source connection ID
     * @param executor     abstraction for executing SQL and getting results
     * @return detected lineage graph (nodes + edges)
     */
    LineageGraph detectLineage(Long dataSourceId, LineageSqlExecutor executor) throws Exception;
}
