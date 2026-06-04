import createRequest from './base';

export interface IBusinessInsight {
	id?: number;
	dataSourceId: number;
	databaseName: string;
	playStoreLink?: string;
	appStoreLink?: string;
	webLink?: string;
	insightContent?: string;
	referenceLinks?: string;
	createTime?: string;
	updateTime?: string;
}

const getInsight = createRequest<{ dataSourceId: number; databaseName: string }, IBusinessInsight>('/api/business-insight', { errorLevel: false });
const saveInsight = createRequest<IBusinessInsight, IBusinessInsight>('/api/business-insight', { method: 'post' });
const generateInsight = createRequest<IBusinessInsight, IBusinessInsight>('/api/business-insight/generate', { method: 'post' });

export default { getInsight, saveInsight, generateInsight };
