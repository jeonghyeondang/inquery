import createRequest from './base';

export interface IHistoryRecord {
	id?: number | null;
	dataSourceId?: number | null;
	dataSourceName?: string | null;
	databaseName?: string | null;
	schemaName?: string | null;
	ddl?: string | null;
	status?: string | null;
	operationRows?: number | null;
	useTime?: number | null;
	type?: string | null;
	name?: string | null;
	extendInfo?: string | null;
	source?: 'WORKSPACE' | 'AI_CHAT' | null;
	gmtCreate: string;
}

export interface IHistoryListParams {
	pageNo?: number;
	pageSize?: number;
	dataSourceId?: number;
	databaseName?: string;
	source?: 'WORKSPACE' | 'AI_CHAT';
}

export interface IPageResponse<T> {
	data: T[];
	total: number;
	pageNo: number;
	pageSize: number;
	hasNextPage?: boolean;
}

const createConsole = createRequest<Record<string, unknown>, number>('/api/operation/saved/create', { method: 'post' });
const updateSavedConsole = createRequest<Record<string, unknown>, number>('/api/operation/saved/update', { method: 'post' });
const getConsoleList = createRequest<Record<string, unknown>, unknown>('/api/operation/saved/list');
const deleteSavedConsole = createRequest<{ id: number }, string>('/api/operation/saved/:id', { method: 'delete' });
const createHistory = createRequest<Record<string, unknown>, void>('/api/operation/log/create', { method: 'post' });
const getHistoryList = createRequest<IHistoryListParams, IPageResponse<IHistoryRecord>>('/api/operation/log/list');
const deleteHistory = createRequest<{ id: number }, void>('/api/operation/log/:id', { method: 'delete' });
const clearHistory = createRequest<{ dataSourceId: number }, void>('/api/operation/log/clear', { method: 'delete' });

export default {
	getConsoleList, updateSavedConsole, getHistoryList,
	createConsole, deleteSavedConsole, createHistory, deleteHistory, clearHistory
};
