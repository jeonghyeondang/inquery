import createRequest from './base';

const getSystemConfig = createRequest<{ code: string }, { code: string; content: string }>('/api/config/system_config/:code', { errorLevel: false });
const setSystemConfig = createRequest<{ code: string; content: string }, void>('/api/config/system_config', { method: 'post' });
const getAiSystemConfig = createRequest<{ aiSqlSource?: string }, unknown>('/api/config/system_config/ai', { errorLevel: false });
const setAiSystemConfig = createRequest<Record<string, unknown>, void>('/api/config/system_config/ai', { method: 'post' });
const getUserAIConfig = createRequest<void, Record<string, unknown>>('/api/config/ai', { errorLevel: false });
const setUserAIConfig = createRequest<Record<string, unknown>, void>('/api/config/ai', { method: 'post' });
const getVersionInfo = createRequest<void, { backendVersion?: string; frontendVersion?: string }>('/api/system/get-version-info', { errorLevel: false });
const testAiApiKey = createRequest<Record<string, unknown>, { success: boolean; message: string }>('/api/config/ai/test', { method: 'post', errorLevel: false });

const disconnectOutlookOAuth = createRequest<void, void>('/api/config/ai/outlook/oauth/disconnect', { method: 'post' });
const disconnectGoogleOAuth = createRequest<void, void>('/api/config/ai/google/oauth/disconnect', { method: 'post' });
const getPineconeConfig = createRequest<void, { apiKey?: string; host?: string; indexName?: string; namespace?: string }>('/api/config/pinecone', { errorLevel: false });
const setPineconeConfig = createRequest<{ apiKey?: string; host?: string; indexName?: string; namespace?: string }, void>('/api/config/pinecone', { method: 'post' });
const syncFromPinecone = createRequest<{ namespace?: string }, { success: boolean; synced?: number; skipped?: number; totalVectors?: number; message?: string }>('/api/config/pinecone/sync-from-pinecone', { method: 'post', errorLevel: false });
// Vector DB provider config
const getVectorDbType = createRequest<void, { type: string; displayName: string; description: string }>('/api/config/vectordb/type', { errorLevel: false });
const setVectorDbType = createRequest<{ type: string }, void>('/api/config/vectordb/type', { method: 'post' });
const getVectorDbProviders = createRequest<void, Array<{ type: string; displayName: string; description: string; configured: boolean; active: boolean }>>('/api/config/vectordb/providers', { errorLevel: false });
const getVectorDbStats = createRequest<void, Record<string, unknown>>('/api/config/vectordb/stats', { errorLevel: false });
const testVectorDbConnection = createRequest<{ type: string }, { success: boolean; message: string }>('/api/config/vectordb/test', { method: 'post', errorLevel: false });
const getQdrantConfig = createRequest<void, { host?: string; port?: string; apiKey?: string; collectionName?: string; useTls?: string }>('/api/config/qdrant', { errorLevel: false });
const setQdrantConfig = createRequest<{ host?: string; port?: string; apiKey?: string; collectionName?: string; useTls?: string }, void>('/api/config/qdrant', { method: 'post' });

const getSlackConfig = createRequest<void, Record<string, unknown>>('/api/slack/config', { errorLevel: false });
const getSlackAvailableModels = createRequest<void, Array<{ label: string; value: string }>>('/api/slack/available-models', { errorLevel: false });
const setSlackConfig = createRequest<Record<string, unknown>, { success: boolean; errorMessage?: string }>('/api/slack/config', { method: 'post', errorLevel: false });
const testSlackConnection = createRequest<{ botToken: string }, { success: boolean; message: string }>('/api/slack/test', { method: 'post', errorLevel: false });

export interface IUserAIConfig {
	confluenceBaseUrl: string;
	confluenceUsername: string;
	confluenceApiToken: string;
	jiraBaseUrl: string;
	jiraUsername: string;
	jiraApiToken: string;
	slackUserToken: string;
	githubToken: string;
	githubBaseUrl: string;
	githubOrganization: string;
	outlookTenantId: string;
	outlookClientId: string;
	outlookClientSecret: string;
	outlookUserEmail: string;
	outlookConnected?: boolean;
	googleClientId: string;
	googleClientSecret: string;
	googleConnected?: boolean;
	geminiModel: string;
	dbtIntegrationType: 'git' | 'artifacts' | 'cloud' | '';
	dbtGitRepoUrl: string;
	dbtGitBranch: string;
	dbtProjectPath: string;
	dbtGitToken: string;
	dbtManifestUrl: string;
	dbtCatalogUrl: string;
	dbtArtifactToken: string;
	dbtCloudBaseUrl: string;
	dbtCloudAccountId: string;
	dbtCloudProjectId: string;
	dbtCloudEnvironmentId: string;
	dbtCloudJobId: string;
	dbtCloudApiToken: string;
}

export default {
	getSystemConfig, setSystemConfig, getAiSystemConfig, setAiSystemConfig,
	getUserAIConfig, setUserAIConfig, getVersionInfo, testAiApiKey, disconnectOutlookOAuth, disconnectGoogleOAuth,
	getPineconeConfig, setPineconeConfig, syncFromPinecone,
	getVectorDbType, setVectorDbType, getVectorDbProviders, getVectorDbStats, testVectorDbConnection,
	getQdrantConfig, setQdrantConfig,
	getSlackConfig, getSlackAvailableModels, setSlackConfig, testSlackConnection
};
