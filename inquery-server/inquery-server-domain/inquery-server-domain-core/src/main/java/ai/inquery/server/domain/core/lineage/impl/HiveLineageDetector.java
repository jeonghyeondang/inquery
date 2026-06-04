package ai.inquery.server.domain.core.lineage.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hive lineage detector using information_schema.views (Hive 3+).
 */
@Slf4j
@Component
public class HiveLineageDetector extends AbstractViewBasedDetector {

    @Override
    public boolean supports(String dbType) {
        return "HIVE".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "HIVE";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    table_schema AS VIEW_DATABASE,
                    table_schema AS VIEW_SCHEMA,
                    table_name AS VIEW_NAME,
                    view_definition AS VIEW_DEFINITION
                FROM information_schema.views
                WHERE table_schema NOT IN ('default', 'information_schema')
                """;
    }
}
