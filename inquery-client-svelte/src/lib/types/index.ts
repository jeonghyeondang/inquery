export * from './constants';
export * from './user';
export * from './connection';


export interface IPageResponse<T> {
	data: T[];
	pageNo: number;
	pageSize: number;
	total: number;
	hasNextPage?: boolean;
}

export interface IPageParams {
	searchKey?: string;
	pageNo: number;
	pageSize: number;
	refresh?: boolean;
}

export interface IUniversalTableParams {
	dataSourceId: string;
	databaseName?: string;
	schemaName?: string;
	tableName?: string;
}
