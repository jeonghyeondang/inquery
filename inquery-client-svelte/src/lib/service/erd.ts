import createRequest from './base';
import type { ERDSchema } from '$lib/components/ERDVisualization/types';

export interface ERDSchemaRequest {
	dataSourceId: number;
	databaseName?: string;
	schemaName?: string;
	refresh?: boolean;
}

const getSchema = createRequest<ERDSchemaRequest, ERDSchema>('/api/erd/schema', { errorLevel: false });

/**
 * Prefetch schema in background (for preloading).
 * Fails silently - this is just a performance optimization.
 */
async function prefetchSchema(request: ERDSchemaRequest): Promise<void> {
	try {
		await getSchema(request);
	} catch {
		// Fail silently - prefetch is best-effort
	}
}

export default { getSchema, prefetchSchema };
