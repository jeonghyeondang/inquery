import createRequest from './base';

export interface IDriverResponse {
	driverConfigList: { jdbcDriver: string; jdbcDriverClass: string }[];
	defaultDriverConfig: { jdbcDriverClass: string };
}

const getList = createRequest<Record<string, unknown>, unknown>('/api/connection/datasource/list');
const getDetails = createRequest<{ id: number }, unknown>('/api/connection/datasource/:id');
const save = createRequest<Record<string, unknown>, number>('/api/connection/datasource/create', { method: 'post', delayTime: true });
// Attach the data source: validates JDBC, returns DB list, and triggers backend
// background jobs (metadata cache warmup + lineage auto-detect).
// errorLevel: false -> backend errors don't surface as global toasts; callers fire-and-forget.
const connect = createRequest<{ id: number }, unknown>('/api/connection/datasource/connect', { errorLevel: false });
const close = createRequest<Record<string, unknown>, void>('/api/connection/datasource/close', { method: 'post' });
const test = createRequest<Record<string, unknown>, boolean>('/api/connection/datasource/pre_connect', { method: 'post', delayTime: true });
const update = createRequest<Record<string, unknown>, void>('/api/connection/datasource/update', { method: 'post' });
const remove = createRequest<{ id: number }, void>('/api/connection/datasource/:id', { method: 'delete' });
const clone = createRequest<{ id: number }, number>('/api/connection/datasource/clone', { method: 'post' });
// errorLevel: false -> a failing data source (e.g. missing JDBC driver) is handled
// locally (empty tree) instead of surfacing a global red toast on the workspace.
const getDatabaseList = createRequest<{ dataSourceId: number; refresh?: boolean }, unknown>('/api/rdb/database/list', { errorLevel: false });
const getSchemaList = createRequest<{ dataSourceId: number; databaseName?: string; refresh?: boolean }, unknown>('/api/rdb/schema/list', { errorLevel: false });
const getDriverList = createRequest<{ dbType: string }, IDriverResponse>('/api/jdbc/driver/list', { errorLevel: false });
const downloadDriver = createRequest<{ dbType: string }, void>('/api/jdbc/driver/download', { method: 'post' });
const getEnvList = createRequest<void, unknown[]>('/api/common/environment/list_all', { errorLevel: false });
const importFromNcx = createRequest<FormData, void>('/api/converter/ncx/upload', { method: 'post' });
const saveDriver = createRequest<{ multipartFiles: any; jdbcDriverClass: string; dbType: string }, void>('/api/jdbc/driver/save', { method: 'post' });
const importFromDbp = createRequest<FormData, void>('/api/converter/dbp/upload', { method: 'post' });

export default {
	getEnvList, getList, getDetails, save, connect, test, update, remove, clone,
	getDatabaseList, getSchemaList, close, getDriverList, downloadDriver, saveDriver,
	importFromNcx, importFromDbp
};
