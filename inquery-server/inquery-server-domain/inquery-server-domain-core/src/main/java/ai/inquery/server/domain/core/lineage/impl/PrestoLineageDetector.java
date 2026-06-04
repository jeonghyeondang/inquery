package ai.inquery.server.domain.core.lineage.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Presto / Trino lineage detector using information_schema.views.
 */
@Slf4j
@Component
public class PrestoLineageDetector extends AbstractViewBasedDetector {

    @Override
    public boolean supports(String dbType) {
        return "PRESTO".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "PRESTO";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    table_catalog AS VIEW_DATABASE,
                    table_schema AS VIEW_SCHEMA,
                    table_name AS VIEW_NAME,
                    '' AS VIEW_DEFINITION
                FROM information_schema.views
                WHERE table_schema NOT IN ('information_schema')
                """;
    }
}
