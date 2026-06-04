package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL Parser Service
 * Parses DDL statements to extract table and column metadata
 */
@Slf4j
@Service
public class DDLParserService {

    // CREATE TABLE or CREATE OR REPLACE TABLE support
    // TRANSIENT, TEMPORARY keywords support
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "(?i)CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TRANSIENT\\s+|TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`?([\\w.]+)`?|([\\w.]+))",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    public List<TableMetadata> parseDDL(String ddl) {
        List<TableMetadata> tables = new ArrayList<>();
        
        if (ddl == null || ddl.trim().isEmpty()) {
            log.warn("DDL is empty.");
            return tables;
        }

        log.debug("DDL parsing started. DDL length: {}", ddl.length());
        
        // Find CREATE TABLE statements
        Matcher tableMatcher = CREATE_TABLE_PATTERN.matcher(ddl);
        
        if (!tableMatcher.find()) {
            log.warn("Cannot find CREATE TABLE statement in DDL. DDL content: {}", ddl.substring(0, Math.min(500, ddl.length())));
            return tables;
        }
        
        // Reset to start from beginning
        tableMatcher.reset();
        
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1) != null ? tableMatcher.group(1) : tableMatcher.group(2);
            log.info("Table found: {}", tableName);
            
            // Find end position of this table
            int tableStart = tableMatcher.end();
            // CLUSTER BY clause may exist, so find column definition start parenthesis
            int columnStart = findColumnDefinitionStart(ddl, tableStart);
            int tableEnd = findTableEnd(ddl, columnStart);
            
            String tableDefinition = ddl.substring(columnStart, tableEnd);
            log.debug("Table definition extracted. Length: {}", tableDefinition.length());
            
            TableMetadata table = new TableMetadata(tableName);
            parseColumns(tableDefinition, table);
            log.info("Table {} parsing completed. Column count: {}", tableName, table.getColumns().size());
            tables.add(table);
        }
        
        log.info("Total {} tables parsed.", tables.size());
        return tables;
    }

    /**
     * Find column definition start parenthesis (skip CLUSTER BY clause)
     */
    private int findColumnDefinitionStart(String ddl, int start) {
        // CLUSTER BY clause may exist, so find column definition start parenthesis
        // Example: "cluster by (dt)(" -> first ( is CLUSTER BY, second ( is column definition
        int depth = 0;
        boolean foundClusterBy = false;
        
        for (int i = start; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            
            if (c == '(') {
                // Check if it's CLUSTER BY
                String before = ddl.substring(Math.max(0, i - 30), i).toUpperCase().trim();
                if (before.endsWith("CLUSTER BY") || before.endsWith("CLUSTER")) {
                    foundClusterBy = true;
                    depth = 1; // CLUSTER BY parenthesis start
                    continue;
                }
                
                // After CLUSTER BY parenthesis closes, next parenthesis is column definition start
                if (foundClusterBy && depth == 0) {
                    return i;
                }
                
                // Normal case (no CLUSTER BY)
                if (!foundClusterBy) {
                    return i;
                }
                
                depth++;
            } else if (c == ')') {
                depth--;
                if (foundClusterBy && depth == 0) {
                    // CLUSTER BY parenthesis closed, next parenthesis is column definition
                    continue;
                }
            }
        }
        return start;
    }

    private int findTableEnd(String ddl, int start) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = start; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            
            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar) {
                inString = false;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        
        return ddl.length();
    }

    private void parseColumns(String tableDefinition, TableMetadata table) {
        // Extract only content inside parentheses
        Pattern contentPattern = Pattern.compile("\\(([\\s\\S]+)\\)");
        Matcher contentMatcher = contentPattern.matcher(tableDefinition);
        
        if (!contentMatcher.find()) {
            log.warn("Cannot find content inside parentheses in table definition.");
            return;
        }
        
        String columnsContent = contentMatcher.group(1);
        log.debug("Column content extracted. Length: {}", columnsContent.length());
        
        // Find each column line - also handle COMMENT
        String[] lines = columnsContent.split(",\\s*(?=\\w|`)");
        
        for (String line : lines) {
            line = line.trim();
            
            if (line.isEmpty()) {
                continue;
            }
            
            // Skip CONSTRAINT, KEY, CLUSTER BY etc.
            String upperLine = line.toUpperCase().trim();
            if (upperLine.startsWith("CONSTRAINT") ||
                upperLine.startsWith("PRIMARY KEY") ||
                upperLine.startsWith("FOREIGN KEY") ||
                upperLine.startsWith("UNIQUE") ||
                upperLine.startsWith("INDEX") ||
                upperLine.startsWith("KEY") ||
                upperLine.startsWith("CLUSTER BY")) {
                log.debug("Skipping constraint/option: {}", line.substring(0, Math.min(50, line.length())));
                continue;
            }
            
            // Extract column name and data type - also handle COMMENT
            // Example: COLUMN_NAME VARCHAR(16777216) COMMENT 'comment text'
            // Or: COLUMN_NAME VARIANT COMMENT 'comment text'
            Pattern columnPattern = Pattern.compile(
                "`?([\\w]+)`?\\s+([\\w\\(\\)]+(?:\\s*\\([^)]+\\))?)",
                Pattern.CASE_INSENSITIVE
            );
            
            Matcher columnMatcher = columnPattern.matcher(line);
            if (columnMatcher.find()) {
                String columnName = columnMatcher.group(1);
                String dataType = columnMatcher.group(2);
                boolean nullable = !line.toUpperCase().contains("NOT NULL");
                
                // Remove COMMENT (after data type extraction)
                if (dataType.contains("COMMENT")) {
                    int commentIndex = dataType.toUpperCase().indexOf("COMMENT");
                    if (commentIndex > 0) {
                        dataType = dataType.substring(0, commentIndex).trim();
                    }
                }
                
                ColumnMetadata column = new ColumnMetadata(columnName, dataType.trim());
                column.setNullable(nullable);
                table.getColumns().add(column);
                log.debug("Column added: {} ({})", columnName, dataType);
            } else {
                log.debug("Column parsing failed: {}", line.substring(0, Math.min(100, line.length())));
            }
        }
    }
}







