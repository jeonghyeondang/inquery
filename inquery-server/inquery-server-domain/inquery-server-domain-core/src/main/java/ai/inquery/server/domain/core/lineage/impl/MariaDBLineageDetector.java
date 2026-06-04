package ai.inquery.server.domain.core.lineage.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MariaDB lineage detector using information_schema.VIEWS.
 */
@Slf4j
@Component
public class MariaDBLineageDetector extends AbstractViewBasedDetector {

    @Override
    public boolean supports(String dbType) {
        return "MARIADB".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "MARIADB";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    TABLE_SCHEMA AS VIEW_DATABASE,
                    TABLE_SCHEMA AS VIEW_SCHEMA,
                    TABLE_NAME AS VIEW_NAME,
                    VIEW_DEFINITION AS VIEW_DEFINITION
                FROM information_schema.VIEWS
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                """;
    }
}
