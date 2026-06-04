import createRequest from './base';

/** Test backend service connectivity */
const testService = createRequest<void, boolean>('/api/system', {
	errorLevel: false
});

export default { testService };
