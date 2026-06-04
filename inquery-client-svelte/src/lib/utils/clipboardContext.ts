interface CopyContext {
	startLine: number;
	endLine: number;
	language: string;
	timestamp: number;
}

let context: CopyContext | null = null;

const EXPIRY_MS = 60_000;

export function setCopyContext(info: Omit<CopyContext, 'timestamp'>) {
	context = { ...info, timestamp: Date.now() };
}

export function getCopyContext(): CopyContext | null {
	if (!context) return null;
	if (Date.now() - context.timestamp > EXPIRY_MS) {
		context = null;
		return null;
	}
	return context;
}
