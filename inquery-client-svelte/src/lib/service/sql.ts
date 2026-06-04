import createRequest from './base';

export interface IExecuteSqlParams {
	sql?: string;
	consoleId?: number;
	dataSourceId?: number;
	databaseName?: string;
	schemaName?: string | null;
	tableName?: string;
	pageNo?: number;
	pageSize?: number;
}

export interface IManageResultData {
	sql: string;
	description: string;
	message: string;
	success: boolean;
	headerList: unknown[];
	dataList: unknown[];
}

const getTableList = createRequest<Record<string, unknown>, unknown>('/api/rdb/table/list', { errorLevel: false });
const executeSql = createRequest<IExecuteSqlParams, IManageResultData[]>('/api/rdb/dml/execute', { method: 'post', delayTime: 10 });
const viewTable = createRequest<IExecuteSqlParams, IManageResultData[]>('/api/rdb/dml/execute_table', { method: 'post', delayTime: 10 });
const connectConsole = createRequest<{ consoleId: number; dataSourceId: number; databaseName: string }, void>('/api/connection/console/connect');
const getAllTableList = createRequest<{ dataSourceId: number; databaseName?: string | null; schemaName?: string | null }, Array<{ name: string; comment: string }>>('/api/rdb/table/table_list', { errorLevel: false });
const getAllFieldByTable = createRequest<{ dataSourceId: number; databaseName?: string; schemaName?: string | null; tableName: string }, Array<{ name: string; tableName: string }>>('/api/rdb/table/column_list');
const getColumnList = createRequest<Record<string, unknown>, unknown[]>('/api/rdb/ddl/column_list', { delayTime: 200 });
const getIndexList = createRequest<Record<string, unknown>, unknown[]>('/api/rdb/ddl/index_list', { delayTime: 200 });
const sqlFormat = createRequest<{ sql: string; dbType: string }, string>('/api/sql/format');
const getSchemaList = createRequest<{ dataSourceId: number; databaseName?: string }, Array<{ name: string }>>('/api/rdb/ddl/schema_list', { errorLevel: false });
const getDatabaseSchemaList = createRequest<{ dataSourceId: number }, Array<{ databaseName: string; schemaName: string }>>('/api/rdb/ddl/database_schema_list', { errorLevel: false });
const getAllTablesFromAllDatabases = createRequest<{ dataSourceId: number }, Array<{ name: string; databaseName?: string; schemaName?: string }>>('/api/rdb/table/all_tables', { errorLevel: false });
const exportCreateTableSql = createRequest<{ dataSourceId: number; databaseName?: string; schemaName?: string; name: string }, string>('/api/rdb/ddl/export', { errorLevel: false });

export interface IBigQueryDryRunParams {
	sql: string;
	dataSourceId: number;
	databaseName?: string;
	schemaName?: string;
}

export interface IBigQueryDryRunResponse {
	success: boolean;
	totalBytesProcessed?: number;
	totalBytesBilled?: number;
	estimatedBytesProcessed?: number;
	estimatedCostUSD?: number;
	cacheHit?: boolean;
	error?: string;
	errorReason?: string;
}

const bigQueryDryRun = createRequest<IBigQueryDryRunParams, IBigQueryDryRunResponse>('/api/ai/agent/bigquery/dryrun', { method: 'post' });

const deleteTable = createRequest<Record<string, unknown>, void>('/api/rdb/ddl/delete', { method: 'post' });
const addTablePin = createRequest<Record<string, unknown>, void>('/api/pin/table/add', { method: 'post' });
const deleteTablePin = createRequest<Record<string, unknown>, void>('/api/pin/table/delete', { method: 'post' });
const getKeyList = createRequest<Record<string, unknown>, unknown[]>('/api/rdb/ddl/key_list', { delayTime: 200 });

export default {
	getTableList, executeSql, viewTable, connectConsole,
	getAllTableList, getAllFieldByTable, getColumnList, getIndexList, getKeyList, sqlFormat,
	getSchemaList, getDatabaseSchemaList, getAllTablesFromAllDatabases, exportCreateTableSql,
	bigQueryDryRun, deleteTable, addTablePin, deleteTablePin
};
