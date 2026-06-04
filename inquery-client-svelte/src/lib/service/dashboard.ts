import createRequest from './base';

export interface IDashboard {
	id: number;
	name: string;
	description?: string;
	schema?: string;
	refreshRule?: 'NONE' | '1MIN' | '10MIN' | '1HOUR' | '1DAY';
	chartIds?: number[];
	gmtCreate?: string;
	gmtModified?: string;
	shareToken?: string;
	isPublic?: boolean;
}

export interface IChart {
	id: number;
	name: string;
	description?: string;
	schema?: string;
	dataSourceId?: number;
	dataSourceName?: string;
	type?: string;
	databaseName?: string;
	schemaName?: string;
	ddl?: string;
	gmtCreate?: string;
	gmtModified?: string;
	connectable?: boolean;
	sourceType?: 'AI_CHAT' | 'WORKSPACE' | 'DASHBOARD';
}

const getDashboardList = createRequest<Record<string, unknown>, unknown>('/api/dashboard/list');
const getDashboard = createRequest<{ id: number }, IDashboard>('/api/dashboard/:id');
const createDashboard = createRequest<Record<string, unknown>, number>('/api/dashboard/create', { method: 'post' });
const updateDashboard = createRequest<Record<string, unknown>, void>('/api/dashboard/update', { method: 'post' });
const deleteDashboard = createRequest<{ id: number }, void>('/api/dashboard/:id', { method: 'delete' });
const getChart = createRequest<{ id: number }, IChart>('/api/chart/:id');
const getChartList = createRequest<Record<string, unknown>, unknown>('/api/chart/list');
const getChartsByIds = createRequest<{ ids: number[] }, IChart[]>('/api/chart/listByIds');
const createChart = createRequest<Record<string, unknown>, number>('/api/chart/create', { method: 'post' });
const updateChart = createRequest<Record<string, unknown>, void>('/api/chart/update', { method: 'post' });
const deleteChart = createRequest<{ id: number }, void>('/api/chart/:id', { method: 'delete' });
const summarizeDashboard = createRequest<Record<string, unknown>, { summary: string }>('/api/dashboard/summarize', { method: 'post' });

const enableShare = createRequest<{ id: number }, { shareToken: string }>('/api/dashboard/:id/share', { method: 'post' });
const disableShare = createRequest<{ id: number }, void>('/api/dashboard/:id/share', { method: 'delete' });
const getPublicDashboard = createRequest<{ shareToken: string }, { dashboard: IDashboard; charts: IChart[] }>('/api/dashboard/public/:shareToken');

export default {
	getDashboardList, getDashboard, createDashboard, updateDashboard, deleteDashboard,
	getChart, getChartList, getChartsByIds, createChart, updateChart, deleteChart,
	summarizeDashboard, enableShare, disableShare, getPublicDashboard
};
