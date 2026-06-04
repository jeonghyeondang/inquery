import createRequest from './base';

export interface SearchTableRequest {
	dataSourceId: number;
	tableName: string;
}

export interface TableLocation {
	databaseName: string;
	schemaName: string;
	tableName: string;
}

export interface SchemaMetaCacheRequest {
	dataSourceId: number;
	databaseName: string;
	schemaName?: string;
}

const searchTableRequest = createRequest<SearchTableRequest, TableLocation[]>('/api/schema-cache/search-table');
const hasSchemaCacheRequest = createRequest<SchemaMetaCacheRequest, boolean>('/api/schema-cache/exists');

export const searchTableByName = (params: SearchTableRequest) => {
	return searchTableRequest(params);
};

export const hasSchemaCache = (params: SchemaMetaCacheRequest) => {
	return hasSchemaCacheRequest(params);
};

export default {
	searchTableByName,
	hasSchemaCache,
};
