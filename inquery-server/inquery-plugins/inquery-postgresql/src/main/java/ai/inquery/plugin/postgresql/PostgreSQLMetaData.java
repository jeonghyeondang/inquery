package ai.inquery.plugin.postgresql;

import ai.inquery.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.inquery.plugin.postgresql.type.*;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.SqlBuilder;
import ai.inquery.spi.jdbc.DefaultMetaService;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.SQLExecutor;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.inquery.plugin.postgresql.consts.SequenceCommonConst.*;
import static ai.inquery.plugin.postgresql.consts.SQLConst.*;
import static ai.inquery.server.tools.base.constant.SymbolConstant.*;
import static ai.inquery.spi.util.SortUtils.sortDatabase;

@Slf4j
public class PostgreSQLMetaData extends DefaultMetaService implements MetaData {

    private static final String SELECT_KEY_INDEX = "SELECT ccu.table_schema AS Foreign_schema_name, ccu.table_name AS Foreign_table_name, ccu.column_name AS Foreign_column_name, constraint_type AS Constraint_type, tc.CONSTRAINT_NAME AS Key_name, tc.TABLE_NAME, kcu.Column_name, tc.is_deferrable, tc.initially_deferred FROM information_schema.table_constraints AS tc JOIN information_schema.key_column_usage AS kcu ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name WHERE tc.TABLE_SCHEMA = '%s'  AND tc.TABLE_NAME = '%s';";


    private List<String> systemDatabases = Arrays.asList("postgres");

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> list = new ArrayList<>();
        Database database = new Database();
        database.setName(currentDatabaseName(connection));
        list.add(database);
        return sortDatabase(list, systemDatabases, connection);
    }

    private String currentDatabaseName(Connection connection) {
        try {
            String catalog = connection.getCatalog();
            if (StringUtils.isNotBlank(catalog)) {
                return catalog;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return SQLExecutor.getInstance().execute(connection, "SELECT current_database();", resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return "postgres";
        });
    }

    private List<String> systemSchemas = Arrays.asList("pg_toast", "pg_temp_1", "pg_toast_temp_1", "pg_catalog", "information_schema");

/*    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = SQLExecutor.getInstance().execute(connection,
                                                                 "SELECT catalog_name, schema_name FROM information_schema.schemata;", resultSet -> {
                    List<Schema> databases = new ArrayList<>();
                    while (resultSet.next()) {
                        Schema schema = new Schema();
                        String name = resultSet.getString("schema_name");
                        String catalogName = resultSet.getString("catalog_name");
                        schema.setName(name);
                        schema.setDatabaseName(catalogName);
                        databases.add(schema);
                    }
                    return databases;
                });
        return SortUtils.sortSchema(schemas, systemSchemas);
    }*/

    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String databaseName, String schemaName) {
        return foreignKeys(connection, databaseName, schemaName, null);
    }

    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = buildForeignKeysQuery(connection, databaseName, schemaName, tableName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    foreignKeys.add(ForeignKeyInfo.builder()
                            .constraintName(resultSet.getString("constraint_name"))
                            .fkDatabaseName(resultSet.getString("fk_table_catalog"))
                            .fkSchemaName(resultSet.getString("fk_table_schema"))
                            .fkTableName(resultSet.getString("fk_table_name"))
                            .fkColumnName(resultSet.getString("fk_column_name"))
                            .pkDatabaseName(resultSet.getString("pk_table_catalog"))
                            .pkSchemaName(resultSet.getString("pk_table_schema"))
                            .pkTableName(resultSet.getString("pk_table_name"))
                            .pkColumnName(resultSet.getString("pk_column_name"))
                            .keySequence(resultSet.getInt("key_sequence"))
                            .updateRule(resultSet.getString("update_rule"))
                            .deleteRule(resultSet.getString("delete_rule"))
                            .build());
                }
            } catch (SQLException e) {
                log.error("Error reading PostgreSQL foreign key metadata", e);
            }
            return foreignKeys;
        });
    }

    private String buildForeignKeysQuery(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                  con.conname AS constraint_name,
                  current_database() AS fk_table_catalog,
                  fk_namespace.nspname AS fk_table_schema,
                  fk_table.relname AS fk_table_name,
                  fk_column.attname AS fk_column_name,
                  current_database() AS pk_table_catalog,
                  pk_namespace.nspname AS pk_table_schema,
                  pk_table.relname AS pk_table_name,
                  pk_column.attname AS pk_column_name,
                  fk_key.ord::int AS key_sequence,
                  CASE con.confupdtype
                    WHEN 'a' THEN 'NO ACTION'
                    WHEN 'r' THEN 'RESTRICT'
                    WHEN 'c' THEN 'CASCADE'
                    WHEN 'n' THEN 'SET NULL'
                    WHEN 'd' THEN 'SET DEFAULT'
                  END AS update_rule,
                  CASE con.confdeltype
                    WHEN 'a' THEN 'NO ACTION'
                    WHEN 'r' THEN 'RESTRICT'
                    WHEN 'c' THEN 'CASCADE'
                    WHEN 'n' THEN 'SET NULL'
                    WHEN 'd' THEN 'SET DEFAULT'
                  END AS delete_rule
                FROM pg_constraint con
                JOIN pg_class fk_table ON fk_table.oid = con.conrelid
                JOIN pg_namespace fk_namespace ON fk_namespace.oid = fk_table.relnamespace
                JOIN pg_class pk_table ON pk_table.oid = con.confrelid
                JOIN pg_namespace pk_namespace ON pk_namespace.oid = pk_table.relnamespace
                JOIN unnest(con.conkey) WITH ORDINALITY AS fk_key(attnum, ord) ON true
                JOIN unnest(con.confkey) WITH ORDINALITY AS pk_key(attnum, ord) ON pk_key.ord = fk_key.ord
                JOIN pg_attribute fk_column
                  ON fk_column.attrelid = con.conrelid
                 AND fk_column.attnum = fk_key.attnum
                JOIN pg_attribute pk_column
                  ON pk_column.attrelid = con.confrelid
                 AND pk_column.attnum = pk_key.attnum
                WHERE con.contype = 'f'
                """);

        String effectiveDatabaseName = StringUtils.defaultIfBlank(databaseName, currentDatabaseName(connection));
        if (StringUtils.isNotBlank(effectiveDatabaseName)) {
            sql.append(" AND current_database() = '").append(escapeSqlLiteral(effectiveDatabaseName)).append("'");
        }
        if (StringUtils.isNotBlank(schemaName)) {
            sql.append(" AND fk_namespace.nspname = '").append(escapeSqlLiteral(schemaName)).append("'");
        }
        if (StringUtils.isNotBlank(tableName)) {
            sql.append(" AND fk_table.relname = '").append(escapeSqlLiteral(tableName)).append("'");
        }
        sql.append(" ORDER BY fk_namespace.nspname, fk_table.relname, con.conname, fk_key.ord");
        return sql.toString();
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }


    private static final String SELECT_TABLE_INDEX = "SELECT tmp.INDISPRIMARY AS Index_primary, tmp.TABLE_SCHEM, tmp.TABLE_NAME, tmp.NON_UNIQUE, tmp.INDEX_QUALIFIER, tmp.INDEX_NAME AS Key_name, tmp.indisclustered, tmp.ORDINAL_POSITION AS Seq_in_index, TRIM ( BOTH '\"' FROM pg_get_indexdef ( tmp.CI_OID, tmp.ORDINAL_POSITION, FALSE ) ) AS Column_name,CASE  tmp.AM_NAME   WHEN 'btree' THEN CASE   tmp.I_INDOPTION [ tmp.ORDINAL_POSITION - 1 ] & 1 :: SMALLINT   WHEN 1 THEN  'D' ELSE'A'  END ELSE NULL  END AS Collation, tmp.CARDINALITY, tmp.PAGES, tmp.FILTER_CONDITION , tmp.AM_NAME AS Index_method, tmp.DESCRIPTION AS Index_comment FROM ( SELECT  n.nspname AS TABLE_SCHEM,  ct.relname AS TABLE_NAME,  NOT i.indisunique AS NON_UNIQUE, NULL AS INDEX_QUALIFIER,  ci.relname AS INDEX_NAME,i.INDISPRIMARY , i.indisclustered ,  ( information_schema._pg_expandarray ( i.indkey ) ).n AS ORDINAL_POSITION,  ci.reltuples AS CARDINALITY,   ci.relpages AS PAGES,  pg_get_expr ( i.indpred, i.indrelid ) AS FILTER_CONDITION,  ci.OID AS CI_OID, i.indoption AS I_INDOPTION,  am.amname AS AM_NAME , d.description  FROM   pg_class ct   JOIN pg_namespace n ON ( ct.relnamespace = n.OID )   JOIN pg_index i ON ( ct.OID = i.indrelid )   JOIN pg_class ci ON ( ci.OID = i.indexrelid )  JOIN pg_am am ON ( ci.relam = am.OID )      left outer join pg_description d on i.indexrelid = d.objoid  WHERE  n.nspname = '%s'   AND ct.relname = '%s'   ) AS tmp ;";
    private static String ROUTINES_SQL = "SELECT p.proname, p.prokind, pg_catalog.pg_get_functiondef(p.oid) as \"code\" FROM pg_catalog.pg_proc p where p.prokind = '%s' and p.proname='%s'";
    private static String TRIGGER_SQL
            = "SELECT n.nspname AS \"schema\", c.relname AS \"table_name\", t.tgname AS \"trigger_name\", t.tgenabled AS "
            + "\"enabled\", pg_get_triggerdef(t.oid) AS \"trigger_body\" FROM pg_trigger t JOIN pg_class c ON c.oid = t"
            + ".tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '%s' AND t.tgname ='%s';";
    private static String TRIGGER_SQL_LIST
            = "SELECT n.nspname AS \"schema\", c.relname AS \"table_name\", t.tgname AS \"trigger_name\", t.tgenabled AS "
            + "\"enabled\", pg_get_triggerdef(t.oid) AS \"trigger_body\" FROM pg_trigger t JOIN pg_class c ON c.oid = t"
            + ".tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '%s';";
    private static String VIEW_SQL
            = "SELECT schemaname, viewname, definition FROM pg_views WHERE schemaname = '%s' AND viewname = '%s';";

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, schemaName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("trigger_name"));
                trigger.setSchemaName(schemaName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columns = columns(connection, databaseName, schemaName, tableName);
        if (columns == null || columns.isEmpty()) {
            return null;
        }

        List<String> definitions = new ArrayList<>();
        columns.stream()
                .sorted(Comparator.comparing(column -> Optional.ofNullable(column.getOrdinalPosition()).orElse(Integer.MAX_VALUE)))
                .forEach(column -> definitions.add(columnDDL(column)));

        List<TableColumn> primaryKeyColumns = columns.stream()
                .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
                .sorted(Comparator.comparingInt(TableColumn::getPrimaryKeyOrder))
                .collect(Collectors.toList());
        if (!primaryKeyColumns.isEmpty()) {
            String primaryKeyName = primaryKeyColumns.stream()
                    .map(TableColumn::getPrimaryKeyName)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse(tableName + "_pkey");
            definitions.add("CONSTRAINT " + quoteIdentifier(primaryKeyName) + " PRIMARY KEY ("
                    + primaryKeyColumns.stream()
                    .map(column -> quoteIdentifier(column.getName()))
                    .collect(Collectors.joining(", "))
                    + ")");
        }

        Map<String, List<ForeignKeyInfo>> foreignKeysByName = foreignKeys(connection, databaseName, schemaName, tableName).stream()
                .collect(Collectors.groupingBy(ForeignKeyInfo::getConstraintName, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<ForeignKeyInfo>> entry : foreignKeysByName.entrySet()) {
            List<ForeignKeyInfo> foreignKeys = entry.getValue().stream()
                    .sorted(Comparator.comparing(fk -> Optional.ofNullable(fk.getKeySequence()).orElse(Integer.MAX_VALUE)))
                    .collect(Collectors.toList());
            if (foreignKeys.isEmpty()) {
                continue;
            }
            ForeignKeyInfo first = foreignKeys.get(0);
            definitions.add("CONSTRAINT " + quoteIdentifier(entry.getKey()) + " FOREIGN KEY ("
                    + foreignKeys.stream()
                    .map(fk -> quoteIdentifier(fk.getFkColumnName()))
                    .collect(Collectors.joining(", "))
                    + ") REFERENCES " + qualifiedName(first.getPkSchemaName(), first.getPkTableName()) + " ("
                    + foreignKeys.stream()
                    .map(fk -> quoteIdentifier(fk.getPkColumnName()))
                    .collect(Collectors.joining(", "))
                    + ")"
                    + foreignKeyAction("ON UPDATE", first.getUpdateRule())
                    + foreignKeyAction("ON DELETE", first.getDeleteRule()));
        }

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ")
                .append(qualifiedName(schemaName, tableName))
                .append(" (\n  ")
                .append(String.join(",\n  ", definitions))
                .append("\n);");

        String tableComment = tableComment(connection, schemaName, tableName);
        if (StringUtils.isNotBlank(tableComment)) {
            ddl.append("\n\nCOMMENT ON TABLE ")
                    .append(qualifiedName(schemaName, tableName))
                    .append(" IS '")
                    .append(escapeSqlLiteral(tableComment))
                    .append("';");
        }

        columns.stream()
                .filter(column -> StringUtils.isNotBlank(column.getComment()))
                .forEach(column -> ddl.append("\nCOMMENT ON COLUMN ")
                        .append(qualifiedName(schemaName, tableName))
                        .append(".")
                        .append(quoteIdentifier(column.getName()))
                        .append(" IS '")
                        .append(escapeSqlLiteral(column.getComment()))
                        .append("';"));

        return ddl.toString();
    }

    private String columnDDL(TableColumn column) {
        StringBuilder ddl = new StringBuilder();
        ddl.append(quoteIdentifier(column.getName())).append(" ").append(column.getColumnType());
        if (StringUtils.isNotBlank(column.getDefaultValue()) && !isSerialType(column.getColumnType())) {
            ddl.append(" DEFAULT ").append(column.getDefaultValue());
        }
        if (column.getNullable() != null && column.getNullable() == DatabaseMetaData.columnNoNulls) {
            ddl.append(" NOT NULL");
        }
        return ddl.toString();
    }

    private boolean isSerialType(String columnType) {
        return StringUtils.equalsAnyIgnoreCase(columnType, "SERIAL", "BIGSERIAL", "SMALLSERIAL");
    }

    private String foreignKeyAction(String keyword, String rule) {
        if (StringUtils.isBlank(rule) || StringUtils.equalsIgnoreCase(rule, "NO ACTION")) {
            return "";
        }
        return " " + keyword + " " + rule;
    }

    private String tableComment(Connection connection, String schemaName, String tableName) {
        String sql = """
                SELECT obj_description(table_info.oid, 'pg_class') AS table_comment
                FROM pg_class table_info
                JOIN pg_namespace namespace_info ON namespace_info.oid = table_info.relnamespace
                WHERE namespace_info.nspname = '%s'
                  AND table_info.relname = '%s'
                """.formatted(escapeSqlLiteral(schemaName), escapeSqlLiteral(tableName));
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString("table_comment");
                }
            } catch (SQLException e) {
                log.error("Error reading PostgreSQL table comment", e);
            }
            return null;
        });
    }

    private String qualifiedName(String schemaName, String objectName) {
        if (StringUtils.isBlank(schemaName)) {
            return quoteIdentifier(objectName);
        }
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(objectName);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + StringUtils.defaultString(identifier).replace("\"", "\"\"") + "\"";
    }


    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String sql = String.format(ROUTINES_SQL, "f", functionName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("code"));
            }
            return function;
        });

    }

    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, schemaName, viewName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("definition"));
            }
            return table;
        });
    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {

        String sql = String.format(TRIGGER_SQL, schemaName, triggerName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("trigger_body"));
            }

            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(ROUTINES_SQL, "p", procedureName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            if (resultSet.next()) {
                procedure.setProcedureBody(resultSet.getString("code"));
            }
            return procedure;
        });
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {

        String constraintSql = String.format(SELECT_KEY_INDEX, schemaName, tableName);
        Map<String, String> constraintMap = new HashMap();
        LinkedHashMap<String, TableIndex> foreignMap = new LinkedHashMap();
        SQLExecutor.getInstance().execute(connection, constraintSql, resultSet -> {
            while (resultSet.next()) {
                String keyName = resultSet.getString("Key_name");
                String constraintType = resultSet.getString("Constraint_type");
                constraintMap.put(keyName, constraintType);
                if (StringUtils.equalsIgnoreCase(constraintType, PostgreSQLIndexTypeEnum.FOREIGN.getKeyword())) {
                    TableIndex tableIndex = foreignMap.get(keyName);
                    String columnName = resultSet.getString("Column_name");
                    if (tableIndex == null) {
                        tableIndex = new TableIndex();
                        tableIndex.setDatabaseName(databaseName);
                        tableIndex.setSchemaName(schemaName);
                        tableIndex.setTableName(tableName);
                        tableIndex.setName(keyName);
                        tableIndex.setForeignSchemaName(resultSet.getString("Foreign_schema_name"));
                        tableIndex.setForeignTableName(resultSet.getString("Foreign_table_name"));
                        tableIndex.setForeignColumnNamelist(Lists.newArrayList(columnName));
                        tableIndex.setType(PostgreSQLIndexTypeEnum.FOREIGN.getName());
                        foreignMap.put(keyName, tableIndex);
                    } else {
                        tableIndex.getForeignColumnNamelist().add(columnName);
                    }
                }
            }
            return null;
        });

        String sql = String.format(SELECT_TABLE_INDEX, schemaName, tableName);
        return SQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap(foreignMap);

            while (resultSet.next()) {
                String keyName = resultSet.getString("Key_name");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    List<TableIndexColumn> columnList = tableIndex.getColumnList();
                    if (columnList == null) {
                        columnList = new ArrayList<>();
                        tableIndex.setColumnList(columnList);
                    }
                    columnList.add(getTableIndexColumn(resultSet));
                    columnList = columnList.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition))
                            .collect(Collectors.toList());
                    tableIndex.setColumnList(columnList);
                } else {
                    TableIndex index = new TableIndex();
                    index.setDatabaseName(databaseName);
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(keyName);
                    index.setUnique(!StringUtils.equals("t", resultSet.getString("NON_UNIQUE")));
                    index.setMethod(resultSet.getString("Index_method"));
                    index.setComment(resultSet.getString("Index_comment"));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    String constraintType = constraintMap.get(keyName);
                    if (StringUtils.equals("t", resultSet.getString("Index_primary"))) {
                        index.setType(PostgreSQLIndexTypeEnum.PRIMARY.getName());
                    } else if (StringUtils.equalsIgnoreCase(constraintType, PostgreSQLIndexTypeEnum.UNIQUE.getName())) {
                        index.setType(PostgreSQLIndexTypeEnum.UNIQUE.getName());
                    } else {
                        index.setType(PostgreSQLIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columnList = super.columns(connection, databaseName, schemaName, tableName);
        Map<String, TableColumn> primaryKeyColumns = primaryKeyColumns(connection, databaseName, schemaName, tableName);

        EasyCollectionUtils.stream(columnList).forEach(v -> {
            if (StringUtils.equalsIgnoreCase(v.getColumnType(), "bpchar")) {
                v.setColumnType(PostgreSQLColumnTypeEnum.CHAR.getColumnType().getTypeName().toUpperCase());
            } else {
                v.setColumnType(v.getColumnType().toUpperCase());
            }
            TableColumn primaryKeyColumn = primaryKeyColumns.get(primaryKeyColumnKey(v.getTableName(), v.getName()));
            if (primaryKeyColumn != null) {
                v.setPrimaryKey(true);
                v.setPrimaryKeyName(primaryKeyColumn.getPrimaryKeyName());
                v.setPrimaryKeyOrder(primaryKeyColumn.getPrimaryKeyOrder());
            }
        });
        return columnList;
    }

    private Map<String, TableColumn> primaryKeyColumns(Connection connection, String databaseName, String schemaName, String tableName) {
        if (StringUtils.isBlank(schemaName)) {
            return Collections.emptyMap();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT
                  con.conname AS primary_key_name,
                  table_info.relname AS table_name,
                  column_info.attname AS column_name,
                  key_info.ord::int AS primary_key_order
                FROM pg_constraint con
                JOIN pg_class table_info ON table_info.oid = con.conrelid
                JOIN pg_namespace namespace_info ON namespace_info.oid = table_info.relnamespace
                JOIN unnest(con.conkey) WITH ORDINALITY AS key_info(attnum, ord) ON true
                JOIN pg_attribute column_info
                  ON column_info.attrelid = con.conrelid
                 AND column_info.attnum = key_info.attnum
                WHERE con.contype = 'p'
                """);
        String effectiveDatabaseName = StringUtils.defaultIfBlank(databaseName, currentDatabaseName(connection));
        if (StringUtils.isNotBlank(effectiveDatabaseName)) {
            sql.append(" AND current_database() = '").append(escapeSqlLiteral(effectiveDatabaseName)).append("'");
        }
        sql.append(" AND namespace_info.nspname = '").append(escapeSqlLiteral(schemaName)).append("'");
        if (StringUtils.isNotBlank(tableName)) {
            sql.append(" AND table_info.relname = '").append(escapeSqlLiteral(tableName)).append("'");
        }
        sql.append(" ORDER BY table_info.relname, key_info.ord");

        return SQLExecutor.getInstance().execute(connection, sql.toString(), resultSet -> {
            Map<String, TableColumn> map = new HashMap<>();
            try {
                while (resultSet.next()) {
                    TableColumn tableColumn = new TableColumn();
                    tableColumn.setPrimaryKeyName(resultSet.getString("primary_key_name"));
                    tableColumn.setPrimaryKeyOrder(resultSet.getInt("primary_key_order"));
                    map.put(primaryKeyColumnKey(resultSet.getString("table_name"), resultSet.getString("column_name")), tableColumn);
                }
            } catch (SQLException e) {
                log.error("Error reading PostgreSQL primary key metadata", e);
            }
            return map;
        });
    }

    private String primaryKeyColumnKey(String tableName, String columnName) {
        return StringUtils.lowerCase(StringUtils.defaultString(tableName)) + "." + StringUtils.lowerCase(StringUtils.defaultString(columnName));
    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("Column_name"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("Seq_in_index"));
        tableIndexColumn.setCollation(resultSet.getString("Collation"));
        tableIndexColumn.setAscOrDesc(resultSet.getString("Collation"));
        return tableIndexColumn;
    }

    @Override
    public SqlBuilder getSqlBuilder() {
        return new PostgreSQLSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(PostgreSQLColumnTypeEnum.getTypes())
                .charsets(PostgreSQLCharsetEnum.getCharsets())
                .collations(PostgreSQLCollationEnum.getCollations())
                .indexTypes(PostgreSQLIndexTypeEnum.getIndexTypes())
                .defaultValues(PostgreSQLDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(name -> "\"" + name + "\"").collect(Collectors.joining("."));
    }

    @Override
    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    @Override
    public List<String> getSystemSchemas() {
        return systemSchemas;
    }

    @Override
    @SneakyThrows
    public String sequenceDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
                              @NotEmpty String sequenceName) {
        DatabaseMetaData metaData = connection.getMetaData();
        double databaseProductVersion = Double.parseDouble(metaData.getDatabaseProductVersion());
        String[] args = new String[]{sequenceName, schemaName};
        return SQLExecutor.getInstance().preExecute(connection, EXPORT_SEQUENCE_DDL_SQL, args, resultSet -> {
                    StringBuilder stringBuilder = new StringBuilder();
                    if (resultSet.next()) {
                        String nspname = resultSet.getString("nspname");
                        String relname = resultSet.getString("relname");
                        String typname = getConversionType(resultSet.getString("typname"));
                        String seqcache = resultSet.getString("seqcache");
                        String rolname = resultSet.getString("rolname");
                        String comment = resultSet.getString("comment");
                        String seqstart = resultSet.getString("seqstart");
                        String seqincrement = resultSet.getString("seqincrement");
                        String seqmax = resultSet.getString("seqmax");
                        String seqmin = resultSet.getString("seqmin");
                        Boolean seqcycle = resultSet.getBoolean("seqcycle");

                        stringBuilder.append(CREATE_SEQUENCE).append(getMetaDataName(nspname, relname)).append(NEW_LINE);

                        if (Double.compare(databaseProductVersion, 10.0) >= 0) {
                            stringBuilder.append(AS).append(typname).append(NEW_LINE);
                        }

                        Optional.ofNullable(seqstart).ifPresent(v -> stringBuilder.append(START_WITH).append(v).append(NEW_LINE));

                        Optional.ofNullable(seqincrement).ifPresent(v -> stringBuilder.append(INCREMENT_BY).append(v).append(NEW_LINE));

                        Optional.ofNullable(seqmin).ifPresent(v -> stringBuilder.append(MINVALUE).append(v).append(NEW_LINE));

                        Optional.ofNullable(seqmax).ifPresent(v -> stringBuilder.append(MAXVALUE).append(v).append(NEW_LINE));

                        Optional.ofNullable(seqcache).ifPresent(v -> stringBuilder.append(CACHE).append(v).append(NEW_LINE));

                        Optional.ofNullable(seqcycle).ifPresent(v -> {
                            if (Boolean.TRUE.equals(seqcycle)) {
                                stringBuilder.append(CYCLE).append(NEW_LINE);
                            }
                        });

                        stringBuilder.append(SEMICOLON).append(BLANK_LINE);

                        Optional.ofNullable(comment).ifPresent(v -> stringBuilder.append(COMMENT_ON_SEQUENCE)
                                .append(getMetaDataName(nspname, relname))
                                .append(IS).append(SQUOT).append(v).append(SQUOT).append(SEMICOLON).append(BLANK_LINE));

                        Optional.ofNullable(rolname).ifPresent(v -> stringBuilder.append(ALTER_SEQUENCE)
                                .append(getMetaDataName(nspname, relname))
                                .append(OWNER_TO).append(getMetaDataName(v)).append(SEMICOLON));
                    }
                    return stringBuilder.toString();
                });
    }

    @Override
    public List<SimpleSequence> sequences(Connection connection, String databaseName, String schemaName) {
        List<SimpleSequence> simpleSequences = new ArrayList<>();
        String[] args = new String[]{schemaName};
        return SQLExecutor.getInstance().preExecute(connection, EXPORT_SEQUENCES_SQL, args, resultSet -> {
                    while (resultSet.next()) {
                        String relname = resultSet.getString("relname");
                        String comment = resultSet.getString("comment");
                        simpleSequences.add(SimpleSequence.builder()
                                .name(relname)
                                .comment(comment)
                                .build());
                    }
                    return simpleSequences;
                });
    }

    @Override
    public Sequence sequences(Connection connection, @NotEmpty String databaseName, String schemaName, String sequenceName) {
        String[] args = new String[]{sequenceName, schemaName};
        return SQLExecutor.getInstance().preExecute(connection, EXPORT_SEQUENCE_DDL_SQL, args, resultSet -> {
            if (resultSet.next()) {
                return Sequence.builder()
                        .nspname(resultSet.getString("nspname"))
                        .relname(resultSet.getString("relname"))
                        .typname(getConversionType(resultSet.getString("typname")))
                        .seqcache(resultSet.getString("seqcache"))
                        .rolname(resultSet.getString("rolname"))
                        .comment(resultSet.getString("comment"))
                        .seqstart(resultSet.getString("seqstart"))
                        .seqincrement(resultSet.getString("seqincrement"))
                        .seqmax(resultSet.getString("seqmax"))
                        .seqmin(resultSet.getString("seqmin"))
                        .seqcycle(resultSet.getBoolean("seqcycle"))
                        .build();
            }
            return null;
        });
    }

    @Override
    public List<String> usernames(Connection connection) {
        List<String> usernames = new ArrayList<>();
        return SQLExecutor.getInstance().preExecute(connection, EXPORT_USERS_SQL, null, resultSet -> {
            while (resultSet.next()) {
                String username = resultSet.getString("username");
                usernames.add(username);
            }
            return usernames;
        });
    }

    private String getConversionType(String typname) {
        switch (typname) {
            case "int2" -> typname = "SMALLINT";
            case "int8" -> typname = "BIGINT";
            default -> typname = "INTEGER";
        }
        return typname;
    }
}
