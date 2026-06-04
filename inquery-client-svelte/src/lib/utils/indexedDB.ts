/**
 * IndexedDB helper for console draft storage
 * Uses idb-keyval for simple key-value storage
 */
import { get, set, del, keys } from 'idb-keyval';

const PREFIX = 'console-draft:';

export interface IConsoleDraft {
	consoleId: string | number;
	ddl: string;
	updatedAt: number;
}

export async function saveDraft(consoleId: string | number, ddl: string): Promise<void> {
	const key = PREFIX + consoleId;
	const draft: IConsoleDraft = { consoleId, ddl, updatedAt: Date.now() };
	await set(key, draft);
}

export async function loadDraft(consoleId: string | number): Promise<IConsoleDraft | undefined> {
	const key = PREFIX + consoleId;
	return await get<IConsoleDraft>(key);
}

export async function removeDraft(consoleId: string | number): Promise<void> {
	const key = PREFIX + consoleId;
	await del(key);
}

export async function getAllDraftKeys(): Promise<string[]> {
	const allKeys = await keys();
	return allKeys
		.filter(k => typeof k === 'string' && k.startsWith(PREFIX))
		.map(k => (k as string).replace(PREFIX, ''));
}

export async function clearAllDrafts(): Promise<void> {
	const draftKeys = await getAllDraftKeys();
	for (const id of draftKeys) {
		await del(PREFIX + id);
	}
}
