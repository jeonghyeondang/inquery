/**
 * Database type constants and configuration
 * Single source of truth for all database type information
 */

export enum DatabaseTypeCode {
	MYSQL = 'MYSQL',
	POSTGRESQL = 'POSTGRESQL',
	ORACLE = 'ORACLE',
	SQLSERVER = 'SQLSERVER',
	SQLITE = 'SQLITE',
	MARIADB = 'MARIADB',
	MONGODB = 'MONGODB',
	REDIS = 'REDIS',
	SNOWFLAKE = 'SNOWFLAKE',
	CLICKHOUSE = 'CLICKHOUSE',
	PRESTO = 'PRESTO',
	HIVE = 'HIVE',
	BIGQUERY = 'BIGQUERY',
	DATABRICKS = 'DATABRICKS',
	DB2 = 'DB2'
}

export interface IDatabase {
	name: string;
	code: DatabaseTypeCode;
	img: string;
	port?: number;
	category: 'RDB' | 'NOSQL' | 'BIGDATA';
}

export const databaseTypeList: IDatabase[] = [
	{ name: 'MySQL', code: DatabaseTypeCode.MYSQL, img: '/images/database/mysql-svgrepo-com.svg', port: 3306, category: 'RDB' },
	{ name: 'PostgreSQL', code: DatabaseTypeCode.POSTGRESQL, img: '/images/database/postgresql-logo-svgrepo-com.svg', port: 5432, category: 'RDB' },
	{ name: 'Snowflake', code: DatabaseTypeCode.SNOWFLAKE, img: '/images/database/snowflake-svgrepo-com.svg', category: 'BIGDATA' },
	{ name: 'BigQuery', code: DatabaseTypeCode.BIGQUERY, img: '/images/database/bigquery-svgrepo-com.svg', category: 'BIGDATA' },
	{ name: 'Oracle', code: DatabaseTypeCode.ORACLE, img: '/images/database/oracle-svgrepo-com.svg', port: 1521, category: 'RDB' },
	{ name: 'SQL Server', code: DatabaseTypeCode.SQLSERVER, img: '/images/database/microsoftsqlserver-svgrepo-com.svg', port: 1433, category: 'RDB' },
	{ name: 'SQLite', code: DatabaseTypeCode.SQLITE, img: '/images/database/sqlite-svgrepo-com.svg', category: 'RDB' },
	{ name: 'MariaDB', code: DatabaseTypeCode.MARIADB, img: '/images/database/mariadb-svgrepo-com.svg', port: 3306, category: 'RDB' },
	{ name: 'ClickHouse', code: DatabaseTypeCode.CLICKHOUSE, img: '/images/database/clickhouse-svgrepo-com.svg', port: 8123, category: 'BIGDATA' },
	{ name: 'MongoDB', code: DatabaseTypeCode.MONGODB, img: '/images/database/mongo-svgrepo-com.svg', port: 27017, category: 'NOSQL' },
	{ name: 'Redis', code: DatabaseTypeCode.REDIS, img: '/images/database/redis-svgrepo-com.svg', port: 6379, category: 'NOSQL' },
	{ name: 'Presto', code: DatabaseTypeCode.PRESTO, img: '/images/database/presto-svgrepo-com.svg', port: 8080, category: 'BIGDATA' },
	{ name: 'Hive', code: DatabaseTypeCode.HIVE, img: '/images/database/hive-svgrepo-com.svg', port: 10000, category: 'BIGDATA' },
	{ name: 'Databricks', code: DatabaseTypeCode.DATABRICKS, img: '/images/database/databricks-svgrepo-com.svg', category: 'BIGDATA' },
	{ name: 'DB2', code: DatabaseTypeCode.DB2, img: '/images/database/db2-svgrepo-com.svg', port: 50000, category: 'RDB' }
];

export const databaseMap = Object.fromEntries(
	databaseTypeList.map(db => [db.code, db])
) as Record<string, IDatabase>;

/** Databases that do not support foreign key constraints (ERD is meaningless) */
const NO_ERD_DATABASES = new Set<DatabaseTypeCode>([
	DatabaseTypeCode.SNOWFLAKE,
	DatabaseTypeCode.BIGQUERY,
	DatabaseTypeCode.HIVE,
	DatabaseTypeCode.CLICKHOUSE,
	DatabaseTypeCode.PRESTO,
	DatabaseTypeCode.DATABRICKS,
	DatabaseTypeCode.MONGODB,
]);

export function supportsERD(dbType: string | undefined): boolean {
	if (!dbType) return false;
	return !NO_ERD_DATABASES.has(dbType as DatabaseTypeCode);
}
