import createRequest from './base';
import type { IPageParams, IPageResponse } from '$lib/types';

// Types
export interface ITeamUserVO {
	id: number;
	userName: string;
	nickName: string;
	email?: string;
	roleCode: string;
	status: string;
	gmtModified?: number;
}

export interface IDataSourceVO {
	id: number;
	alias: string;
	url: string;
	type: string;
}

export interface ITeamVO {
	id: number;
	code: string;
	name: string;
	description?: string;
	status: string;
}

// =============================== DataSource ============================
export const getDataSourceList = createRequest<IPageParams, IPageResponse<IDataSourceVO>>(
	'/api/admin/data_source/page', { method: 'get' }
);
export const createDataSource = createRequest<Record<string, unknown>, number>(
	'/api/admin/data_source/create', { method: 'post' }
);
export const updateDataSource = createRequest<Record<string, unknown>, number>(
	'/api/admin/data_source/update', { method: 'post' }
);
export const deleteDataSource = createRequest<{ id: number }, boolean>(
	'/api/admin/data_source/:id', { method: 'delete' }
);

// ====================== User ======================
export const getUserManagementList = createRequest<IPageParams, IPageResponse<ITeamUserVO>>(
	'/api/admin/user/page', { method: 'get' }
);
export const createUser = createRequest<Record<string, unknown>, number>(
	'/api/admin/user/create', { method: 'post' }
);
export const updateUser = createRequest<Record<string, unknown>, number>(
	'/api/admin/user/update', { method: 'post' }
);
export const deleteUser = createRequest<{ id: number }, boolean>(
	'/api/admin/user/:id', { method: 'delete' }
);

// ======================== Team ======================
export const getTeamList = createRequest<IPageParams, IPageResponse<ITeamVO>>(
	'/api/admin/team/page', { method: 'get' }
);
export const createTeam = createRequest<Record<string, unknown>, number>(
	'/api/admin/team/create', { method: 'post' }
);
export const updateTeam = createRequest<Record<string, unknown>, number>(
	'/api/admin/team/update', { method: 'post' }
);
export const deleteTeam = createRequest<{ id: number }, boolean>(
	'/api/admin/team/:id', { method: 'delete' }
);

// ======================= Common Lists =====================
export const getCommonUserList = createRequest<{ searchKey: string }, ITeamUserVO[]>(
	'/api/admin/common/user/list', { method: 'get' }
);
export const getCommonTeamList = createRequest<{ searchKey: string }, ITeamVO[]>(
	'/api/admin/common/team/list', { method: 'get' }
);
export const getCommonDataSourceList = createRequest<{ searchKey: string }, IDataSourceVO[]>(
	'/api/admin/common/data_source/list', { method: 'get' }
);
export const getCommonUserAndTeamList = createRequest<{ searchKey: string }, Array<{ id: number; code: string; name: string; type: string }>>(
	'/api/admin/common/team_user/list', { method: 'get' }
);

// ======================= User Affiliations =====================
export const getTeamListFromUser = createRequest<IPageParams & { userId: number }, IPageResponse<any>>(
	'/api/admin/user/team/page', { method: 'get' }
);
export const updateTeamListFromUser = createRequest<{ userId: number; teamIdList: number[] }, void>(
	'/api/admin/user/team/batch_create', { method: 'post' }
);
export const deleteTeamFromUser = createRequest<{ id: number }, void>(
	'/api/admin/user/team/:id', { method: 'delete' }
);
export const getDataSourceListFromUser = createRequest<IPageParams & { userId: number }, IPageResponse<any>>(
	'/api/admin/user/data_source/page', { method: 'get' }
);
export const updateDataSourceListFromUser = createRequest<{ userId: number; dataSourceIdList: number[] }, void>(
	'/api/admin/user/data_source/batch_create', { method: 'post' }
);
export const deleteDataSourceFromUser = createRequest<{ id: number }, void>(
	'/api/admin/user/data_source/:id', { method: 'delete' }
);

// ======================= Team Affiliations =====================
export const getUserListFromTeam = createRequest<IPageParams & { teamId: number }, IPageResponse<any>>(
	'/api/admin/team/user/page', { method: 'get' }
);
export const updateUserListFromTeam = createRequest<{ teamId: number; userIdList: number[] }, void>(
	'/api/admin/team/user/batch_create', { method: 'post' }
);
export const deleteUserFromTeam = createRequest<{ id: number }, void>(
	'/api/admin/team/user/:id', { method: 'delete' }
);
export const getDataSourceListFromTeam = createRequest<IPageParams & { teamId: number }, IPageResponse<any>>(
	'/api/admin/team/data_source/page', { method: 'get' }
);
export const updateDataSourceListFromTeam = createRequest<{ teamId: number; dataSourceIdList: number[] }, void>(
	'/api/admin/team/data_source/batch_create', { method: 'post' }
);
export const deleteDataSourceFromTeam = createRequest<{ id: number }, void>(
	'/api/admin/team/data_source/:id', { method: 'delete' }
);

// ======================= DataSource Affiliations =====================
export const getUserAndTeamListFromDataSource = createRequest<IPageParams & { dataSourceId: number }, IPageResponse<any>>(
	'/api/admin/data_source/access/page', { method: 'get' }
);
export const updateUserAndTeamListFromDataSource = createRequest<{ dataSourceId: number; accessObjectList: Array<{ id: number; type: string }> }, void>(
	'/api/admin/data_source/access/batch_create', { method: 'post' }
);
export const deleteUserOrTeamFromDataSource = createRequest<{ id: number }, void>(
	'/api/admin/data_source/access/:id', { method: 'delete' }
);
