package ai.inquery.server.web.api.controller.erd;

import ai.inquery.server.domain.api.param.TablePageQueryParam;
import ai.inquery.server.domain.api.param.TableSelector;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.erd.request.ERDSchemaRequest;
import ai.inquery.server.web.api.controller.erd.vo.*;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.model.ForeignKeyInfo;
import ai.inquery.spi.model.Table;
import ai.inquery.spi.model.TableColumn;
import ai.inquery.spi.sql.InqueryContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ERD (Entity Relationship Diagram) Controller
 * Provides API for fetching schema information for ERD visualization
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/erd")
@RestController
public class ERDController {

    @Autowired
    private TableService tableService;

    /**
     * Get complete schema information for ERD visualization.
     * Returns all tables with columns and foreign key relationships.
     *
     * @param request ERD schema request
     * @return ERD schema data with tables, columns, and relationships
     */
    @GetMapping("/schema")
    public DataResult<ERDSchemaVO> getSchema(@Valid ERDSchemaRequest request) {
        // Validate required parameters
        if (request.getDatabaseName() == null || request.getDatabaseName().trim().isEmpty()) {
            log.error("databaseName is required for ERD schema");
            return DataResult.error("ERD_INVALID_PARAMS", "Database name is required for ERD visualization");
        }

        try {
            // 1. Get all tables in the schema
            TablePageQueryParam queryParam = TablePageQueryParam.builder()
                    .dataSourceId(request.getDataSourceId())
                    .databaseName(request.getDatabaseName())
                    .schemaName(request.getSchemaName())
                    .refresh(request.isRefresh()) // Pass refresh flag to force cache refresh
                    .pageNo(1)
                    .pageSize(1000) // Get up to 1000 tables
                    .build();

            TableSelector tableSelector = new TableSelector();
            tableSelector.setColumnList(false);  // Don't include columns here - we'll fetch them in batch
            tableSelector.setIndexList(false);   // Skip indexes for performance

            List<Table> tables = tableService.pageQuery(queryParam, tableSelector).getData();

            if (CollectionUtils.isEmpty(tables)) {
                return DataResult.of(ERDSchemaVO.builder()
                        .name(request.getSchemaName() != null ? request.getSchemaName() : request.getDatabaseName())
                        .databaseName(request.getDatabaseName())
                        .tables(Collections.emptyList())
                        .build());
            }

            // 2. Fetch ALL columns for the schema in ONE query (instead of per-table queries)
            Connection connection = InqueryContext.getConnection();
            MetaData metaData = InqueryContext.getMetaData();
            
            // Fetch all columns at once (tableName = null means all tables in schema)
            List<TableColumn> allColumns = Collections.emptyList();
            try {
                allColumns = metaData.columns(
                        connection,
                        request.getDatabaseName(),
                        request.getSchemaName(),
                        null  // null tableName = fetch all columns in schema
                );
            } catch (Exception e) {
                log.warn("Failed to fetch columns in batch: {}", e.getMessage());
            }
            
            // Group columns by table name
            Map<String, List<TableColumn>> columnsByTable = allColumns.stream()
                    .collect(Collectors.groupingBy(TableColumn::getTableName));
            
            // Assign columns to tables (try case-insensitive matching if needed)
            Map<String, List<TableColumn>> columnsByTableLower = allColumns.stream()
                    .collect(Collectors.groupingBy(col -> col.getTableName().toLowerCase()));
            
            for (Table table : tables) {
                // Try exact match first
                List<TableColumn> tableColumns = columnsByTable.get(table.getName());
                
                // If no exact match, try case-insensitive match
                if (tableColumns == null || tableColumns.isEmpty()) {
                    tableColumns = columnsByTableLower.get(table.getName().toLowerCase());
                }
                
                table.setColumnList(tableColumns != null ? tableColumns : Collections.emptyList());
            }

            // 3. Get foreign key information (single query)
            List<ForeignKeyInfo> foreignKeys = Collections.emptyList();
            try {
                foreignKeys = metaData.foreignKeys(
                        connection,
                        request.getDatabaseName(),
                        request.getSchemaName()
                );
            } catch (Exception e) {
                log.warn("Failed to get foreign keys: {}", e.getMessage());
            }

            // 4. Build FK lookup map: "tableName.columnName" -> ForeignKeyInfo
            Map<String, ForeignKeyInfo> fkMap = new HashMap<>();
            for (ForeignKeyInfo fk : foreignKeys) {
                String key = fk.getFkTableName() + "." + fk.getFkColumnName();
                fkMap.put(key, fk);
            }
            inferMissingForeignKeys(tables, fkMap, request.getSchemaName());

            // 5. Convert to ERD VO
            List<ERDTableVO> erdTables = tables.stream()
                    .map(table -> convertToERDTable(table, fkMap))
                    .collect(Collectors.toList());

            ERDSchemaVO result = ERDSchemaVO.builder()
                    .name(request.getSchemaName())
                    .databaseName(request.getDatabaseName())
                    .tables(erdTables)
                    .build();

            return DataResult.of(result);

        } catch (Exception e) {
            log.error("Error getting ERD schema", e);
            return DataResult.error("ERD_SCHEMA_ERROR", "Failed to get ERD schema: " + e.getMessage());
        }
    }

    /**
     * Convert Table to ERDTableVO
     */
    private ERDTableVO convertToERDTable(Table table, Map<String, ForeignKeyInfo> fkMap) {
        List<ERDColumnVO> columns = Collections.emptyList();

        if (CollectionUtils.isNotEmpty(table.getColumnList())) {
            columns = table.getColumnList().stream()
                    .map(col -> convertToERDColumn(table.getName(), col, fkMap))
                    .collect(Collectors.toList());
        }

        return ERDTableVO.builder()
                .name(table.getName())
                .schemaName(table.getSchemaName())
                .comment(table.getComment())
                .type(table.getType())
                .columns(columns)
                .build();
    }

    /**
     * Convert TableColumn to ERDColumnVO
     */
    private ERDColumnVO convertToERDColumn(String tableName, TableColumn column, Map<String, ForeignKeyInfo> fkMap) {
        // Check if this column has a foreign key
        String fkKey = tableName + "." + column.getName();
        ForeignKeyInfo fk = fkMap.get(fkKey);

        ERDForeignKeyVO foreignKeyVO = null;
        if (fk != null) {
            foreignKeyVO = ERDForeignKeyVO.builder()
                    .constraintName(fk.getConstraintName())
                    .referencedSchema(fk.getPkSchemaName())
                    .referencedTable(fk.getPkTableName())
                    .referencedColumn(fk.getPkColumnName())
                    .inferred(fk.getConstraintName() != null && fk.getConstraintName().startsWith("inferred_"))
                    .build();
        }

        return ERDColumnVO.builder()
                .name(column.getName())
                .dataType(column.getColumnType())
                .isPrimaryKey(column.getPrimaryKey() != null && column.getPrimaryKey())
                .isNullable(column.getNullable() != null && column.getNullable() == 1)
                .foreignKey(foreignKeyVO)
                .comment(column.getComment())
                .ordinalPosition(column.getOrdinalPosition())
                .build();
    }

    private void inferMissingForeignKeys(List<Table> tables, Map<String, ForeignKeyInfo> fkMap, String defaultSchemaName) {
        if (CollectionUtils.isEmpty(tables)) {
            return;
        }

        Map<String, Table> tableByName = new HashMap<>();
        for (Table table : tables) {
            tableByName.put(table.getName().toLowerCase(Locale.ROOT), table);
        }

        for (Table table : tables) {
            if (CollectionUtils.isEmpty(table.getColumnList())) {
                continue;
            }

            for (TableColumn column : table.getColumnList()) {
                if (column.getName() == null || column.getPrimaryKey() != null && column.getPrimaryKey()) {
                    continue;
                }

                String columnName = column.getName().toLowerCase(Locale.ROOT);
                String fkKey = table.getName() + "." + column.getName();
                if (fkMap.containsKey(fkKey) || !columnName.endsWith("_id")) {
                    continue;
                }

                Table referencedTable = findReferencedTable(tableByName, columnName.substring(0, columnName.length() - 3));
                if (referencedTable == null || !hasIdColumn(referencedTable)) {
                    continue;
                }

                fkMap.put(fkKey, ForeignKeyInfo.builder()
                        .constraintName("inferred_" + table.getName() + "_" + column.getName())
                        .fkDatabaseName(table.getDatabaseName())
                        .fkSchemaName(StringUtils.defaultIfBlank(table.getSchemaName(), defaultSchemaName))
                        .fkTableName(table.getName())
                        .fkColumnName(column.getName())
                        .pkDatabaseName(referencedTable.getDatabaseName())
                        .pkSchemaName(StringUtils.defaultIfBlank(referencedTable.getSchemaName(), defaultSchemaName))
                        .pkTableName(referencedTable.getName())
                        .pkColumnName("id")
                        .keySequence(1)
                        .build());
            }
        }
    }

    private Table findReferencedTable(Map<String, Table> tableByName, String baseName) {
        if (StringUtils.isBlank(baseName)) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(baseName);
        candidates.add(baseName + "s");
        if (baseName.endsWith("y")) {
            candidates.add(baseName.substring(0, baseName.length() - 1) + "ies");
        }

        for (String candidate : candidates) {
            Table table = tableByName.get(candidate);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    private boolean hasIdColumn(Table table) {
        if (CollectionUtils.isEmpty(table.getColumnList())) {
            return false;
        }
        return table.getColumnList().stream()
                .anyMatch(column -> "id".equalsIgnoreCase(column.getName()));
    }
}

