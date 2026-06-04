package ai.inquery.server.domain.core.lineage.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SQLite lineage detector using sqlite_master.
 */
@Slf4j
@Component
public class SQLiteLineageDetector extends AbstractViewBasedDetector {

    @Override
    public boolean supports(String dbType) {
        return "SQLITE".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "SQLITE";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    '' AS VIEW_DATABASE,
                    '' AS VIEW_SCHEMA,
                    name AS VIEW_NAME,
                    sql AS VIEW_DEFINITION
                FROM sqlite_master
                WHERE type = 'view'
                """;
    }
}
