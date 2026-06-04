package ai.inquery.server.domain.core.lineage;

import java.util.List;
import java.util.Map;

/**
 * Abstraction for executing SQL queries against a user's database and returning results.
 * Decouples LineageDetector implementations from DlTemplateService / Spring specifics.
 */
@FunctionalInterface
public interface LineageSqlExecutor {

    /**
     * Execute a SQL query and return results as a list of column-name → value maps.
     *
     * @param sql the SQL query to execute
     * @return list of row maps
     */
    List<Map<String, String>> execute(String sql) throws Exception;
}
