/**
 * Unified SQL IntelliSense Provider (Singleton Pattern)
 * Ported from React version for Svelte 5 / Monaco Editor
 *
 * - Single global provider (no dispose/re-register)
 * - Data updates via variable mutation
 * - All suggestions in one provider
 */
import { DatabaseTypeCode } from '$lib/types/database';
import { getKeywordsAndFunctions } from './keywords';
import sqlService from '$lib/service/sql';
import catalogService from '$lib/service/catalog';
import { getEditorSettingStore } from '$lib/stores/editorSetting.svelte';

// Monaco instance reference
let monacoInstance: any = null;

// =============================================================================
// SINGLETON STATE
// =============================================================================

let globalCompletionProvider: any = null;

// Current data state
let currentDatabaseType: DatabaseTypeCode | undefined;
let currentDataSourceId: number | undefined;
let currentDatabaseName: string | undefined;
let currentSchemaName: string | undefined;

// Cached data
let currentDatabases: Array<{ name: string; dataSourceName?: string }> = [];
let currentTables: Array<{ name: string; comment?: string; databaseName?: string; schemaName?: string }> = [];
let currentViews: Array<{ name: string; databaseName?: string }> = [];

// Size-bounded cache using Proxy
function createBoundedCache<T>(maxSize: number): Record<string, T> {
	const insertionOrder: string[] = [];
	const target: Record<string, T> = {};
	return new Proxy(target, {
		set(obj, prop: string, value: T) {
			if (!(prop in obj)) {
				insertionOrder.push(prop);
				while (insertionOrder.length > maxSize) {
					const oldest = insertionOrder.shift()!;
					delete obj[oldest];
				}
			}
			obj[prop] = value;
			return true;
		},
		deleteProperty(obj, prop: string) {
			const idx = insertionOrder.indexOf(prop);
			if (idx !== -1) insertionOrder.splice(idx, 1);
			delete obj[prop];
			return true;
		}
	});
}

const COLUMN_CACHE_MAX = 200;
const SCHEMA_CACHE_MAX = 50;
const TABLE_CACHE_MAX = 50;

let columnCache = createBoundedCache<Array<{ name: string; tableName: string; dataType?: string }>>(COLUMN_CACHE_MAX);
let schemaCache = createBoundedCache<Array<{ name: string }>>(SCHEMA_CACHE_MAX);
let tableCache = createBoundedCache<Array<{ name: string; comment?: string }>>(TABLE_CACHE_MAX);

// =============================================================================
// HELPERS
// =============================================================================

// i18n fallback
function i18n(key: string): string {
	const map: Record<string, string> = {
		'sqlEditor.text.keyword': 'Keyword',
		'sqlEditor.text.function': 'Function',
		'sqlEditor.text.databaseName': 'Database',
		'sqlEditor.text.tableName': 'Table',
		'sqlEditor.text.viewName': 'View',
		'sqlEditor.text.fieldName': 'Column',
		'sqlEditor.text.schemaName': 'Schema'
	};
	return map[key] || key;
}

interface SQLContext {
	afterDot: boolean;
	qualifiedPrefix: string | null;
	inTableContext: boolean;
	inFieldContext: boolean;
}

function analyzeSQLContext(textBeforeCursor: string): SQLContext {
	const trimmed = textBeforeCursor.trim();

	const dotMatch = trimmed.match(/([^\s,]+)\.\s*$/);
	if (dotMatch) {
		return {
			afterDot: true,
			qualifiedPrefix: dotMatch[1].replace(/["`[\]]/g, ''),
			inTableContext: false,
			inFieldContext: true
		};
	}

	const lastWord = trimmed.split(/\s+/).pop() || '';
	if (lastWord.includes('.') && !trimmed.endsWith(' ')) {
		const parts = lastWord.split('.');
		return {
			afterDot: true,
			qualifiedPrefix: parts.slice(0, -1).join('.').replace(/["`[\]]/g, ''),
			inTableContext: false,
			inFieldContext: false
		};
	}

	const upperText = trimmed.toUpperCase();
	const tableKeywordPattern = /\b(FROM|JOIN|INNER\s+JOIN|LEFT\s+JOIN|RIGHT\s+JOIN|FULL\s+JOIN|CROSS\s+JOIN|UPDATE|INTO|TABLE)\s*$/i;
	const inTableContext = tableKeywordPattern.test(upperText) || /\bFROM\b.*,\s*$/i.test(upperText);

	const columnKeywordPattern = /\b(SELECT|WHERE|AND|OR|ON|GROUP\s+BY|ORDER\s+BY|SET|HAVING|DISTINCT)\s*$/i;
	const inFieldContext =
		columnKeywordPattern.test(upperText) ||
		/\bSELECT\b.*,\s*$/i.test(upperText) ||
		/[=<>!]+\s*$/i.test(upperText) ||
		/\(\s*$/i.test(upperText);

	return { afterDot: false, qualifiedPrefix: null, inTableContext, inFieldContext };
}

interface TableReference {
	fullPath: string;
	databaseName?: string;
	schemaName?: string;
	tableName: string;
}

function extractTablesFromSQL(sql: string): TableReference[] {
	const tables: TableReference[] = [];
	const seenPaths = new Set<string>();

	const tablePatterns = [
		/\bFROM\s+([^\s,;()]+)/gi,
		/\bJOIN\s+([^\s,;()]+)/gi,
		/\bINTO\s+([^\s,;()]+)/gi,
		/\bUPDATE\s+([^\s,;()]+)/gi
	];

	for (const pattern of tablePatterns) {
		let match;
		while ((match = pattern.exec(sql)) !== null) {
			const fullPath = match[1].replace(/["`[\]]/g, '');
			if (seenPaths.has(fullPath.toLowerCase())) continue;
			seenPaths.add(fullPath.toLowerCase());

			const parts = fullPath.split('.');
			if (parts.length === 3) {
				tables.push({ fullPath, databaseName: parts[0], schemaName: parts[1], tableName: parts[2] });
			} else if (parts.length === 2) {
				tables.push({ fullPath, schemaName: parts[0], tableName: parts[1] });
			} else {
				tables.push({ fullPath, tableName: parts[0] });
			}
		}
	}
	return tables;
}

const SCHEMA_REQUIRED_DBS = new Set([
	DatabaseTypeCode.POSTGRESQL, DatabaseTypeCode.BIGQUERY,
	DatabaseTypeCode.SNOWFLAKE, DatabaseTypeCode.ORACLE,
	DatabaseTypeCode.SQLSERVER, DatabaseTypeCode.DB2
]);

async function loadColumnsForTable(tableRef: TableReference): Promise<void> {
	const cacheKey = tableRef.fullPath;
	if (columnCache[cacheKey]) return;
	if (!currentDataSourceId) return;

	const effectiveDatabaseName = tableRef.databaseName || currentDatabaseName;
	const effectiveSchemaName = tableRef.schemaName || currentSchemaName;

	if (effectiveSchemaName && effectiveSchemaName === effectiveDatabaseName) return;
	if (currentDatabaseType && SCHEMA_REQUIRED_DBS.has(currentDatabaseType) && !effectiveSchemaName) return;
	if (effectiveDatabaseName && currentDatabaseName && effectiveDatabaseName !== currentDatabaseName) {
		const isSuspicious = effectiveDatabaseName.includes('-') && effectiveDatabaseName.length > 30;
		if (isSuspicious) return;
	}

	try {
		const data = await sqlService.getAllFieldByTable({
			dataSourceId: currentDataSourceId,
			databaseName: effectiveDatabaseName,
			schemaName: effectiveSchemaName,
			tableName: tableRef.tableName
		});
		columnCache[cacheKey] = data || [];
	} catch {
		columnCache[cacheKey] = [];
	}
}

// =============================================================================
// MAIN PROVIDER
// =============================================================================

function ensureCompletionProvider(monaco: any): void {
	if (globalCompletionProvider || !monaco) return;

	globalCompletionProvider = monaco.languages.registerCompletionItemProvider('sql', {
		triggerCharacters: [' ', '.', ',', '('],

		provideCompletionItems: async (model: any, position: any) => {
			const word = model.getWordUntilPosition(position);
			const range = {
				startLineNumber: position.lineNumber,
				endLineNumber: position.lineNumber,
				startColumn: word.startColumn,
				endColumn: word.endColumn
			};

			const fullTextUntilPosition = model.getValueInRange({
				startLineNumber: 1,
				startColumn: 1,
				endLineNumber: position.lineNumber,
				endColumn: position.column
			});

			const lineContent = model.getLineContent(position.lineNumber);
			const textBeforeCursor = lineContent.substring(0, position.column - 1);
			const context = analyzeSQLContext(textBeforeCursor);
			const suggestions: any[] = [];

			// ===== AFTER DOT =====
			if (context.afterDot && context.qualifiedPrefix) {
				const parts = context.qualifiedPrefix.split('.');

				if (parts.length === 1) {
					const name = parts[0];

					// Try cached columns
					const tableKey = Object.keys(columnCache).find(
						(key) => key.toLowerCase() === name.toLowerCase() || key.endsWith(`.${name}`)
					);
					if (tableKey && columnCache[tableKey]) {
						columnCache[tableKey].forEach((col, idx) => {
							suggestions.push({
								label: { label: col.name, detail: ` (${col.tableName})`, description: col.dataType || i18n('sqlEditor.text.fieldName') },
								kind: monacoInstance.languages.CompletionItemKind.Field,
								insertText: col.name,
								range,
								sortText: `0${idx.toString().padStart(3, '0')}`
							});
						});
						if (suggestions.length > 0) return { suggestions };
					}

					// Lazy load columns
					const matchingTable = currentTables.find((t) => t.name.toLowerCase() === name.toLowerCase());
					if (matchingTable && currentDataSourceId) {
						const tableRef: TableReference = {
							fullPath: name,
							databaseName: matchingTable.databaseName || currentDatabaseName,
							schemaName: matchingTable.schemaName || currentSchemaName,
							tableName: matchingTable.name
						};
						await loadColumnsForTable(tableRef);
						if (columnCache[name]) {
							columnCache[name].forEach((col, idx) => {
								suggestions.push({
									label: { label: col.name, detail: ` (${col.tableName})`, description: col.dataType || i18n('sqlEditor.text.fieldName') },
									kind: monacoInstance.languages.CompletionItemKind.Field,
									insertText: col.name,
									range,
									sortText: `0${idx.toString().padStart(3, '0')}`
								});
							});
							if (suggestions.length > 0) return { suggestions };
						}
					}

					// Try schemas for database name
					if (currentDataSourceId) {
						const schCacheKey = `${currentDataSourceId}:${name}`;
						if (!schemaCache[schCacheKey]) {
							try {
								const schemas = await sqlService.getSchemaList({ dataSourceId: currentDataSourceId, databaseName: name }) as any;
								schemaCache[schCacheKey] = schemas || [];
							} catch { schemaCache[schCacheKey] = []; }
						}
						if (schemaCache[schCacheKey]?.length > 0) {
							schemaCache[schCacheKey].forEach((schema, idx) => {
								suggestions.push({
									label: { label: schema.name, detail: ` (${name})`, description: i18n('sqlEditor.text.schemaName') },
									kind: monacoInstance.languages.CompletionItemKind.Folder,
									insertText: schema.name + '.',
									range,
									sortText: `0${idx.toString().padStart(3, '0')}`,
									command: { id: 'editor.action.triggerSuggest', title: 'Trigger Suggest' }
								});
							});
							return { suggestions };
						}
					}
				}

				if (parts.length === 2) {
					const [part1, part2] = parts;
					const tableKey = `${part1}.${part2}`;
					const shouldLoadColumns = part1 !== currentDatabaseName;
					const hasRequiredSchema = !currentDatabaseType || !SCHEMA_REQUIRED_DBS.has(currentDatabaseType) || (part1 !== currentDatabaseName);
					if (!columnCache[tableKey] && currentDataSourceId && shouldLoadColumns && hasRequiredSchema) {
						try {
							const data = await sqlService.getAllFieldByTable({
								dataSourceId: currentDataSourceId,
								databaseName: currentDatabaseName,
								schemaName: part1,
								tableName: part2
							});
							columnCache[tableKey] = data || [];
						} catch { columnCache[tableKey] = []; }
					}
					if (columnCache[tableKey]?.length > 0) {
						columnCache[tableKey].forEach((col, idx) => {
							suggestions.push({
								label: { label: col.name, detail: ` (${col.tableName})`, description: col.dataType || i18n('sqlEditor.text.fieldName') },
								kind: monacoInstance.languages.CompletionItemKind.Field,
								insertText: col.name,
								range,
								sortText: `0${idx.toString().padStart(3, '0')}`
							});
						});
						if (suggestions.length > 0) return { suggestions };
					}

					if (currentDataSourceId) {
						const tblCacheKey = `${currentDataSourceId}:${part1}:${part2}`;
						if (!tableCache[tblCacheKey]) {
							try {
								const tables = await catalogService.loadTablesBySchema({
									dataSourceId: currentDataSourceId,
									databaseName: part1,
									schemaName: part2
								});
								tableCache[tblCacheKey] = tables || [];
							} catch { tableCache[tblCacheKey] = []; }
						}
						if (tableCache[tblCacheKey]?.length > 0) {
							tableCache[tblCacheKey].forEach((table, idx) => {
								suggestions.push({
									label: { label: table.name, detail: ` (${part1}.${part2})`, description: i18n('sqlEditor.text.tableName') },
									kind: monacoInstance.languages.CompletionItemKind.Folder,
									insertText: table.name,
									range,
									sortText: `0${idx.toString().padStart(3, '0')}`
								});
							});
							return { suggestions };
						}
					}
				}

				if (parts.length === 3) {
					const [dbName, schemaName, tableName] = parts;
					const tableKey = `${dbName}.${schemaName}.${tableName}`;
					const shouldLoadColumns = schemaName !== dbName;
					if (!columnCache[tableKey] && currentDataSourceId && shouldLoadColumns) {
						try {
							const data = await sqlService.getAllFieldByTable({
								dataSourceId: currentDataSourceId,
								databaseName: dbName,
								schemaName: schemaName,
								tableName: tableName
							});
							columnCache[tableKey] = data || [];
						} catch { columnCache[tableKey] = []; }
					}
					if (columnCache[tableKey]) {
						columnCache[tableKey].forEach((col, idx) => {
							suggestions.push({
								label: { label: col.name, detail: ` (${tableName})`, description: col.dataType || i18n('sqlEditor.text.fieldName') },
								kind: monacoInstance.languages.CompletionItemKind.Field,
								insertText: col.name,
								range,
								sortText: `0${idx.toString().padStart(3, '0')}`
							});
						});
					}
					return { suggestions };
				}
			}

			// ===== NORMAL CONTEXT =====
			const tablesInQuery = extractTablesFromSQL(fullTextUntilPosition);
			for (const tableRef of tablesInQuery) {
				await loadColumnsForTable(tableRef);
			}

			const { keywords, functions } = getKeywordsAndFunctions(currentDatabaseType);
			const editorSettings = getEditorSettingStore();
			const keywordCase = editorSettings.settings.keywordCase || 'upper';

			// Keywords
			keywords.forEach((keyword, idx) => {
				const displayKeyword = keywordCase === 'upper' ? keyword.toUpperCase() : keyword.toLowerCase();
				suggestions.push({
					label: { label: displayKeyword, description: i18n('sqlEditor.text.keyword') },
					kind: monacoInstance.languages.CompletionItemKind.Keyword,
					insertText: displayKeyword,
					range,
					sortText: `1${idx.toString().padStart(4, '0')}`
				});
			});

			// Functions
			functions.forEach((fn, idx) => {
				const displayFn = keywordCase === 'upper' ? fn.toUpperCase() : fn.toLowerCase();
				suggestions.push({
					label: { label: displayFn, description: i18n('sqlEditor.text.function') },
					kind: monacoInstance.languages.CompletionItemKind.Function,
					insertText: displayFn + '($0)',
					insertTextRules: monacoInstance.languages.CompletionItemInsertTextRule.InsertAsSnippet,
					range,
					sortText: `2${idx.toString().padStart(4, '0')}`
				});
			});

			// Databases
			currentDatabases.forEach((db, idx) => {
				suggestions.push({
					label: { label: db.name, detail: db.dataSourceName ? ` (${db.dataSourceName})` : undefined, description: i18n('sqlEditor.text.databaseName') },
					kind: monacoInstance.languages.CompletionItemKind.Module,
					insertText: db.name + '.',
					range,
					sortText: context.inTableContext ? `0a${idx.toString().padStart(3, '0')}` : `3${idx.toString().padStart(4, '0')}`,
					command: { id: 'editor.action.triggerSuggest', title: 'Trigger Suggest' }
				});
			});

			// Tables
			currentTables.forEach((table, idx) => {
				const displayDetail = table.schemaName && table.databaseName
					? ` (${table.databaseName}.${table.schemaName})`
					: table.databaseName ? ` (${table.databaseName})` : undefined;
				suggestions.push({
					label: { label: table.name, detail: displayDetail, description: i18n('sqlEditor.text.tableName') },
					kind: monacoInstance.languages.CompletionItemKind.Folder,
					insertText: table.name,
					range,
					sortText: context.inTableContext ? `0b${idx.toString().padStart(3, '0')}` : `4${idx.toString().padStart(4, '0')}`
				});
			});

			// Views
			currentViews.forEach((view, idx) => {
				suggestions.push({
					label: { label: view.name, detail: view.databaseName ? ` (${view.databaseName})` : undefined, description: i18n('sqlEditor.text.viewName') },
					kind: monacoInstance.languages.CompletionItemKind.Interface,
					insertText: view.name,
					range,
					sortText: context.inTableContext ? `0c${idx.toString().padStart(3, '0')}` : `5${idx.toString().padStart(4, '0')}`
				});
			});

			// Columns from cache
			Object.keys(columnCache).forEach((tableKey) => {
				columnCache[tableKey].forEach((col, idx) => {
					suggestions.push({
						label: { label: col.name, detail: ` (${col.tableName})`, description: col.dataType || i18n('sqlEditor.text.fieldName') },
						kind: monacoInstance.languages.CompletionItemKind.Field,
						insertText: col.name,
						range,
						sortText: context.inFieldContext ? `0d${idx.toString().padStart(3, '0')}` : `6${idx.toString().padStart(4, '0')}`
					});
				});
			});

			return { suggestions };
		}
	});
}

// =============================================================================
// PUBLIC API
// =============================================================================

export function initUnifiedIntelliSense(monaco?: any): void {
	if (monaco) monacoInstance = monaco;
	if (monacoInstance) ensureCompletionProvider(monacoInstance);
}

export function updateDatabaseType(databaseType?: DatabaseTypeCode): void {
	currentDatabaseType = databaseType;
}

export function updateConnectionInfo(params: {
	dataSourceId?: number;
	databaseName?: string;
	schemaName?: string;
	dataSourceName?: string;
}): void {
	if (currentDataSourceId !== undefined && params.dataSourceId !== currentDataSourceId) {
		clearIntelliSenseCache();
	} else if (
		params.dataSourceId === currentDataSourceId &&
		currentDatabaseName !== undefined &&
		params.databaseName !== currentDatabaseName
	) {
		schemaCache = createBoundedCache(SCHEMA_CACHE_MAX);
		tableCache = createBoundedCache(TABLE_CACHE_MAX);
		columnCache = createBoundedCache(COLUMN_CACHE_MAX);
	} else if (
		params.dataSourceId === currentDataSourceId &&
		params.databaseName === currentDatabaseName &&
		currentSchemaName !== undefined &&
		params.schemaName !== currentSchemaName
	) {
		tableCache = createBoundedCache(TABLE_CACHE_MAX);
		columnCache = createBoundedCache(COLUMN_CACHE_MAX);
	}

	currentDataSourceId = params.dataSourceId;

	if (params.databaseName && params.dataSourceName && params.databaseName === params.dataSourceName) {
		currentDatabaseName = undefined;
	} else {
		currentDatabaseName = params.databaseName;
	}

	if (params.schemaName && params.schemaName === params.databaseName) {
		currentSchemaName = undefined;
	} else {
		currentSchemaName = params.schemaName;
	}
}

export function updateDatabases(databases: Array<{ name: string; dataSourceName?: string }>): void {
	currentDatabases = databases;
}

export function updateTables(tables: Array<{ name: string; comment?: string; databaseName?: string; schemaName?: string }>): void {
	currentTables = tables;
}

export function updateViews(views: Array<{ name: string; databaseName?: string }>): void {
	currentViews = views;
}

export function clearIntelliSenseCache(): void {
	currentDatabases = [];
	currentTables = [];
	currentViews = [];
	columnCache = createBoundedCache(COLUMN_CACHE_MAX);
	schemaCache = createBoundedCache(SCHEMA_CACHE_MAX);
	tableCache = createBoundedCache(TABLE_CACHE_MAX);
}

export function disposeUnifiedIntelliSense(): void {
	if (globalCompletionProvider) {
		globalCompletionProvider.dispose();
		globalCompletionProvider = null;
	}
	clearIntelliSenseCache();
}

export function getCurrentTableNames(): string[] {
	return currentTables.map((t) => t.name);
}

/**
 * One-call sync for pages that don't manage schema trees (AI Chat, etc.).
 * Loads databases + tables for the given connection and pushes them into IntelliSense.
 */
export async function syncIntelliSenseForConnection(params: {
	dataSourceId: number;
	databaseType: string;
	alias?: string;
	supportSchema?: boolean;
	databaseName?: string;
	schemaName?: string;
}): Promise<void> {
	const { dataSourceId, databaseType, alias, supportSchema, databaseName, schemaName } = params;

	updateDatabaseType(databaseType as any);
	updateConnectionInfo({
		dataSourceId,
		databaseName,
		schemaName,
		dataSourceName: alias,
	});

	try {
		const { default: connectionService } = await import('$lib/service/connection');
		const dbRes = await connectionService.getDatabaseList({ dataSourceId, refresh: false });
		const dbList = Array.isArray(dbRes)
			? dbRes.map((db: any) => ({ name: typeof db === 'string' ? db : db.name }))
			: [];
		updateDatabases(dbList);

		const targetDb = databaseName || (dbList.length > 0 ? dbList[0].name : undefined);
		if (!targetDb) return;

		if (!supportSchema) {
			const tables = await catalogService.loadTablesBySchema({ dataSourceId, databaseName: targetDb });
			if (Array.isArray(tables)) {
				const tableItems = tables.filter((t: any) => {
					const tt = (t.type || '').toUpperCase();
					return tt !== 'VIEW' && tt !== 'MATERIALIZED VIEW';
				});
				const viewItems = tables.filter((t: any) => {
					const tt = (t.type || '').toUpperCase();
					return tt === 'VIEW' || tt === 'MATERIALIZED VIEW';
				});
				updateTables(tableItems.map((t: any) => ({ name: t.name || t, comment: t.comment, databaseName: targetDb })));
				updateViews(viewItems.map((t: any) => ({ name: t.name || t, databaseName: targetDb })));
			}
		} else if (schemaName) {
			const tables = await catalogService.loadTablesBySchema({ dataSourceId, databaseName: targetDb, schemaName });
			if (Array.isArray(tables)) {
				const tableItems = tables.filter((t: any) => {
					const tt = (t.type || '').toUpperCase();
					return tt !== 'VIEW' && tt !== 'MATERIALIZED VIEW';
				});
				const viewItems = tables.filter((t: any) => {
					const tt = (t.type || '').toUpperCase();
					return tt === 'VIEW' || tt === 'MATERIALIZED VIEW';
				});
				updateTables(tableItems.map((t: any) => ({ name: t.name || t, comment: t.comment, databaseName: targetDb, schemaName })));
				updateViews(viewItems.map((t: any) => ({ name: t.name || t, databaseName: targetDb })));
			}
		}
	} catch { /* best-effort */ }
}
