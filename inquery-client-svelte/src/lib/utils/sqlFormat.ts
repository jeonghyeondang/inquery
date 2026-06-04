import { format as sqlFormatterFormat } from 'sql-formatter';

/**
 * Map the backend `dbType` enum (e.g. POSTGRESQL, SNOWFLAKE) to the
 * dialect string sql-formatter v15 expects. Unknown / unsupported types
 * fall back to the universal 'sql' dialect, which still formats safely.
 */
const DIALECT_MAP: Record<string, string> = {
	POSTGRESQL: 'postgresql',
	POSTGRES: 'postgresql',
	MYSQL: 'mysql',
	MARIADB: 'mariadb',
	SQLITE: 'sqlite',
	ORACLE: 'plsql',
	MSSQL: 'transactsql',
	SQLSERVER: 'transactsql',
	SNOWFLAKE: 'snowflake',
	BIGQUERY: 'bigquery',
	REDSHIFT: 'redshift',
	DB2: 'db2',
	HIVE: 'hive',
	SPARK: 'spark',
	PRESTO: 'trino',
	TRINO: 'trino',
	CLICKHOUSE: 'clickhouse',
	DUCKDB: 'duckdb',
	TIDB: 'tidb',
};

export function getSqlFormatterLanguage(dbType?: string | null): string {
	if (!dbType) return 'sql';
	return DIALECT_MAP[String(dbType).toUpperCase()] ?? 'sql';
}

/**
 * Format SQL for read-only display (multi-aspect cards, history previews,
 * etc.). Falls back to the raw string on any parser/formatter error so we
 * never block the UI on a malformed query.
 */
export function formatSql(sql: string | null | undefined, dbType?: string | null): string {
	if (!sql || typeof sql !== 'string') return '';
	const trimmed = sql.trim();
	if (!trimmed) return '';
	try {
		// sql-formatter's `language` field accepts a fixed string union; we
		// already validate against DIALECT_MAP so this cast is safe.
		const language = getSqlFormatterLanguage(dbType);
		return sqlFormatterFormat(trimmed, {
			language,
			tabWidth: 2,
			keywordCase: 'upper',
			linesBetweenQueries: 1,
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		} as any);
	} catch {
		return sql;
	}
}
