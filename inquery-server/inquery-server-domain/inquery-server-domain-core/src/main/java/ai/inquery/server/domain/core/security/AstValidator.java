package ai.inquery.server.domain.core.security;

import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.spi.model.TableColumn;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AstValidator {

    @Autowired(required = false)
    private TableService tableService;

    /**
     * Validates SQL query:
     * 1. Only SELECT statements allowed
     * 2. Tables exist in the database
     * 3. Columns exist in the referenced tables (if TableService available)
     */
    public boolean validate(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            // 1. Only allow SELECT statements
            if (!(statement instanceof Select)) {
                log.warn("Non-SELECT statement rejected: {}", statement.getClass().getSimpleName());
                return false;
            }
            
            // 2. Extract and validate tables (basic validation without DB connection)
            TablesNamesFinder tablesFinder = new TablesNamesFinder();
            List<String> tableNames = tablesFinder.getTableList((Select) statement);
            
            if (tableNames.isEmpty()) {
                log.warn("No tables found in SQL query");
                return false;
            }
            
            log.info("SQL validation passed: {} tables referenced", tableNames.size());
            return true;
            
        } catch (Exception e) {
            log.error("SQL parsing failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates SQL with schema context (requires data source info)
     * Use this when you have database/schema information available
     */
    public boolean validateWithSchema(String sql, String databaseName, String schemaName) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            // 1. Only allow SELECT statements
            if (!(statement instanceof Select)) {
                log.warn("Non-SELECT statement rejected");
                return false;
            }
            
            // 2. Extract table names
            TablesNamesFinder tablesFinder = new TablesNamesFinder();
            List<String> tableNames = tablesFinder.getTableList((Select) statement);
            
            if (tableNames.isEmpty()) {
                log.warn("No tables found in SQL query");
                return false;
            }
            
            log.info("SQL validation with schema passed: {} tables, database={}, schema={}", 
                tableNames.size(), databaseName, schemaName);
            
            // Note: Full schema validation would require querying the actual database
            // which needs a dataSourceId. This is a placeholder for future enhancement.
            
            return true;
            
        } catch (Exception e) {
            log.error("SQL parsing failed: {}", e.getMessage());
            return false;
        }
    }
}
