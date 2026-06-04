package ai.inquery.server.domain.core.lineage.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DB2 lineage detector using SYSCAT.VIEWDEP + SYSCAT.VIEWS.
 */
@Slf4j
@Component
public class DB2LineageDetector extends AbstractViewBasedDetector {

    @Override
    public boolean supports(String dbType) {
        return "DB2".equalsIgnoreCase(dbType);
    }

    @Override
    protected String getDbType() {
        return "DB2";
    }

    @Override
    protected String getViewDefinitionsSql() {
        return """
                SELECT
                    CURRENT SERVER AS VIEW_DATABASE,
                    v.VIEWSCHEMA AS VIEW_SCHEMA,
                    v.VIEWNAME AS VIEW_NAME,
                    v.TEXT AS VIEW_DEFINITION
                FROM SYSCAT.VIEWS v
                WHERE v.VIEWSCHEMA NOT LIKE 'SYS%'
                  AND v.VALID = 'Y'
                """;
    }
}
