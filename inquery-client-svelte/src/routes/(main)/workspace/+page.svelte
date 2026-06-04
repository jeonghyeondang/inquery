<script lang="ts">
	import { onMount, onDestroy, tick } from 'svelte';
	import { Button, Popover, PopoverTrigger, PopoverContent, DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '$lib/components/ui';
	import {
		Database, Folder, FolderX, ChevronDown, ChevronRight, Plus, RefreshCw, Search,
		Play, Save, AlignLeft, X, FileCode, MoreVertical, ArrowLeft, Check, Pencil, Trash2,
		Table2, Eye, Copy, Terminal, Workflow, History, PanelLeft, Book,
		Columns, KeyRound, Hash, Wand2, Zap, ArrowUp, Clipboard, StopCircle, Gauge, RotateCcw,
		AlertTriangle, AlertCircle, XCircle, CheckCircle, Clock, HardDrive, Cpu,
		Pin, PinOff, GitBranch, TableProperties, DatabaseZap, BarChart2, Download,
		Scissors, ClipboardPaste, MousePointerClick
	} from 'lucide-svelte';
	import AISparkleIcon from '$lib/components/AISparkleIcon/AISparkleIcon.svelte';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import ContextMenu from '$lib/components/ContextMenu/ContextMenu.svelte';
	import type { ContextMenuItem } from '$lib/components/ContextMenu/ContextMenu.svelte';
	import {
		type QueryEstimatorData, type PlanNode, type ResourceLevel,
		parseExplainResult, parseRawExplain, calculateResourceLevel, levelConfig,
		formatNumber, formatTime
	} from '$lib/utils/parseExplain';
	import type { IHistoryRecord } from '$lib/service/history';
	import {
		getWorkspaceStore, fetchConsoleList, fetchSavedConsoleList,
		setActiveConsoleId, setCurrentConnection, togglePanelLeft, setLeftTab, createConsole,
		saveConsole, debouncedAutoSave, deleteConsole, closeConsole,
		renameConsole, openSavedConsole, setPanelLeftWidth, setConsoleHeight,
		togglePanelRight, setPanelRightWidth, setPendingSql,
		consumePrefetchCache
	} from '$lib/stores/workspace.svelte';
	import connectionService from '$lib/service/connection';
	import type { IConnectionListItem } from '$lib/types/connection';
	import { updateConnectionInfo, updateDatabases, updateTables, updateViews, updateDatabaseType } from '$lib/utils/intellisense/unified-provider';
	import sqlService from '$lib/service/sql';
	import catalogService from '$lib/service/catalog';
	import { downloadTableAsCSV, downloadTableAsJSON, downloadInsertSQL } from '$lib/utils/export';
	import dashboardService from '$lib/service/dashboard';
	import type { IChart } from '$lib/service/dashboard';
	import erdService from '$lib/service/erd';
	import type { ERDSchema } from '$lib/components/ERDVisualization/types';
	import { databaseMap } from '$lib/types/database';
	import { supportsERD } from '$lib/types/database';
	import { EmbeddedAIChat } from '$lib/components/EmbeddedAIChat';
	import message from '$lib/utils/message';
	import confirmDialog from '$lib/utils/confirmDialog';
	import { getBaseURL } from '$lib/service/base';
	import i18n, { currentLang } from '$lib/i18n';
	import { formatDDL, parseDDLToSchema, type SchemaField } from '$lib/utils/ddlFormatter';
	import {
		isBigQueryNestedJson, parseBigQueryValue, getNestedDisplayValue,
		detectNestedColumns, flattenDataWithNestedArrays,
		type NestedColumnInfo, type FlattenedRow
	} from '$lib/utils/bigquery';
	import { matchesShortcut, getShortcutById, formatKeys } from '$lib/stores/shortcuts.svelte';

	const ws = getWorkspaceStore();

	function getLanguageInstruction(): string {
		const fallback =
			currentLang === 'ko-kr'
				? 'Korean (한국어)'
				: currentLang === 'ja-jp'
					? 'Japanese (日本語)'
					: currentLang === 'tr-tr'
						? 'Turkish'
						: 'English';
		return `Respond in the same language as the user's question/request. If this is a UI action with no user-authored natural-language question, use ${fallback}.`;
	}

	// Tree data state
	interface TreeNode {
		name: string;
		type: 'database' | 'schema' | 'table' | 'table_group' | 'view' | 'columns' | 'column' | 'nested_column' | 'keys' | 'key' | 'indexes' | 'index';
		children?: TreeNode[] | null;
		expanded?: boolean;
		loading?: boolean;
		isLeaf?: boolean;
		databaseName?: string;
		schemaName?: string;
		tableName?: string;
		columnType?: string;
		pinned?: boolean;
		comment?: string;
		nestedChildren?: TreeNode[];
		tableGroupCount?: number;
	}

	function getColumnTypeColor(columnType: string | undefined): string {
		if (!columnType) return '';
		const lower = columnType.toLowerCase();
		if (lower.includes('uuid') || lower.includes('rowid')) return '#a855f7';
		if (lower.includes('varchar') || lower.includes('text') || lower.includes('char') || lower.includes('string')) return '#22c55e';
		if (lower.includes('int') || lower.includes('numeric') || lower.includes('decimal') || lower.includes('float') || lower.includes('double') || lower.includes('number') || lower.includes('bigint') || lower.includes('smallint') || lower.includes('tinyint')) return '#3b82f6';
		if (lower.includes('timestamp') || lower.includes('date') || lower.includes('time') || lower.includes('datetime')) return '#f97316';
		if (lower.includes('bool') || lower.includes('boolean')) return '#eab308';
		if (lower.includes('blob') || lower.includes('binary') || lower.includes('bytes')) return '#ef4444';
		if (lower.includes('json') || lower.includes('object') || lower.includes('variant')) return '#06b6d4';
		if (lower.includes('array')) return '#ec4899';
		return '';
	}

	function getDataTypeBadgeStyle(dataType: string, colName: string): { bg: string; color: string; border: string; label: string } {
		const t = (dataType || '').toUpperCase();
		const n = (colName || '').toLowerCase();
		if (n.includes('uuid') || t === 'UUID' || t === 'ROWID' || (t === 'STRING' && n.endsWith('_id')) || (t === 'VARCHAR' && n.endsWith('_id')))
			return { bg: 'rgba(139,92,246,0.15)', color: '#c4b5fd', border: 'rgba(139,92,246,0.2)', label: 'uuid' };
		if (t === 'STRING' || t.includes('VARCHAR') || t.includes('TEXT') || t.includes('CHAR'))
			return { bg: 'rgba(16,185,129,0.15)', color: '#6ee7b7', border: 'rgba(16,185,129,0.2)', label: 'varchar' };
		// Split integer-like vs decimal-like so AVG()/SUM(numeric) columns
		// don't get mis-labelled as "integer" while values are 2.6181818..
		if (t.includes('INT') || t.includes('SERIAL'))
			return { bg: 'rgba(59,130,246,0.15)', color: '#93c5fd', border: 'rgba(59,130,246,0.2)', label: 'integer' };
		if (t === 'NUMERIC' || t.includes('DECIMAL') || t.includes('FLOAT') || t.includes('DOUBLE') || t.includes('NUMBER') || t === 'REAL')
			return { bg: 'rgba(59,130,246,0.15)', color: '#93c5fd', border: 'rgba(59,130,246,0.2)', label: 'numeric' };
		if (t.includes('TIMESTAMP') || t.includes('DATE') || t.includes('TIME'))
			return { bg: 'rgba(249,115,22,0.15)', color: '#fdba74', border: 'rgba(249,115,22,0.2)', label: 'datetime' };
		if (t === 'BOOLEAN' || t === 'BOOL')
			return { bg: 'rgba(234,179,8,0.15)', color: '#fde047', border: 'rgba(234,179,8,0.2)', label: 'boolean' };
		if (t.includes('ARRAY') || t.includes('STRUCT') || t.includes('RECORD')) {
			let structLabel = 'struct';
			if (t.startsWith('ARRAY<STRUCT')) structLabel = 'array<struct>';
			else if (t.startsWith('ARRAY')) structLabel = 'array';
			else if (t.startsWith('STRUCT')) structLabel = 'struct';
			else if (t.includes('RECORD')) structLabel = 'record';
			return { bg: 'rgba(236,72,153,0.15)', color: '#f9a8d4', border: 'rgba(236,72,153,0.2)', label: structLabel };
		}
		if (t.includes('BLOB') || t.includes('BINARY') || t.includes('BYTES'))
			return { bg: 'rgba(239,68,68,0.15)', color: '#fca5a5', border: 'rgba(239,68,68,0.2)', label: 'binary' };
		if (t.includes('JSON') || t === 'OBJECT' || t === 'VARIANT')
			return { bg: 'rgba(6,182,212,0.15)', color: '#67e8f9', border: 'rgba(6,182,212,0.2)', label: 'json' };
		return { bg: 'rgba(75,85,99,0.15)', color: '#d1d5db', border: 'rgba(75,85,99,0.2)', label: dataType?.toLowerCase() || 'unknown' };
	}

	function formatCellValue(val: any): string {
		if (val === null || val === undefined) return '';
		const str = String(val);
		if (isBigQueryNestedJson(str)) return getNestedDisplayValue(str);
		return str;
	}

	function parseStructFields(columnType: string): Array<{ name: string; columnType: string }> {
		if (!columnType) return [];
		let content = columnType.trim();
		if (content.toUpperCase().startsWith('ARRAY<')) content = content.slice(6, -1).trim();
		if (!content.toUpperCase().startsWith('STRUCT<')) return [];

		const startIdx = content.indexOf('<') + 1;
		let depth = 1;
		let endIdx = startIdx;
		for (let i = startIdx; i < content.length && depth > 0; i++) {
			if (content[i] === '<') depth++;
			else if (content[i] === '>') depth--;
			if (depth === 0) endIdx = i;
		}

		const fieldsStr = content.substring(startIdx, endIdx);
		const fields: Array<{ name: string; columnType: string }> = [];
		depth = 0;
		let currentField = '';
		for (let i = 0; i < fieldsStr.length; i++) {
			const char = fieldsStr[i];
			if (char === '<') depth++;
			else if (char === '>') depth--;
			else if (char === ',' && depth === 0) {
				const trimmed = currentField.trim();
				if (trimmed) {
					const spaceIdx = trimmed.indexOf(' ');
					if (spaceIdx > 0) fields.push({ name: trimmed.substring(0, spaceIdx), columnType: trimmed.substring(spaceIdx + 1).trim() });
				}
				currentField = '';
				continue;
			}
			currentField += char;
		}
		const last = currentField.trim();
		if (last) {
			const spaceIdx = last.indexOf(' ');
			if (spaceIdx > 0) fields.push({ name: last.substring(0, spaceIdx), columnType: last.substring(spaceIdx + 1).trim() });
		}
		return fields;
	}

	function columnsToTreeNodes(columns: any[], parentNode?: Partial<TreeNode>): TreeNode[] {
		return (columns || []).map((item: any) => {
			const colType: string = item.columnType || item.dataType || item.type || '';
			const upper = colType.toUpperCase();
			const isRecordType = upper.includes('RECORD') || upper.includes('STRUCT');
			const hasApiChildren = item.children && item.children.length > 0;

			let nested: TreeNode[] | undefined;
			if (hasApiChildren) {
				nested = columnsToTreeNodes(item.children, parentNode);
			} else if (isRecordType) {
				const parsed = parseStructFields(colType);
				if (parsed.length > 0) nested = columnsToTreeNodes(parsed, parentNode);
			}

			const hasChildren = nested && nested.length > 0;
			return {
				name: item.name,
				type: (hasChildren || isRecordType ? 'nested_column' : 'column') as TreeNode['type'],
				isLeaf: !hasChildren && !isRecordType,
				columnType: colType,
				databaseName: parentNode?.databaseName,
				schemaName: parentNode?.schemaName,
				tableName: parentNode?.tableName,
				nestedChildren: nested,
			} satisfies TreeNode;
		});
	}

	function simplifyColumnType(colType: string): string {
		const upper = colType.toUpperCase();
		if (upper.startsWith('ARRAY<STRUCT<')) {
			const fields = parseStructFields(colType);
			return `ARRAY<STRUCT> (${fields.length})`;
		}
		if (upper.startsWith('STRUCT<')) {
			const fields = parseStructFields(colType);
			return `STRUCT (${fields.length})`;
		}
		return upper;
	}

	function isShardedTableName(name: string): { isSharded: boolean; prefix: string } {
		const match = name.match(/^(.+)_(\d{8})$/);
		if (match) {
			const dateStr = match[2];
			const year = parseInt(dateStr.substring(0, 4), 10);
			const month = parseInt(dateStr.substring(4, 6), 10);
			const day = parseInt(dateStr.substring(6, 8), 10);
			if (year >= 2000 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
				return { isSharded: true, prefix: match[1] };
			}
		}
		return { isSharded: false, prefix: '' };
	}

	function groupShardedTables(tables: any[], isBigQuery: boolean, dbName?: string, schemaName?: string): TreeNode[] {
		const filtered = tables.filter((t: any) => {
			const tableType = (t.type || '').toUpperCase();
			return tableType !== 'VIEW' && tableType !== 'MATERIALIZED VIEW';
		});
		const views = tables.filter((t: any) => {
			const tableType = (t.type || '').toUpperCase();
			return tableType === 'VIEW' || tableType === 'MATERIALIZED VIEW';
		});

		if (!isBigQuery) {
			return [
				...filtered.map((t: any) => ({
					name: t.name || t,
					type: 'table' as const,
					databaseName: dbName,
					schemaName: schemaName,
					pinned: t.pinned || false,
					comment: t.comment || '',
				})),
				...views.map((t: any) => ({
					name: t.name || t,
					type: 'view' as const,
					databaseName: dbName,
					schemaName: schemaName,
				})),
			];
		}

		const groups = new Map<string, any[]>();
		const ungrouped: any[] = [];
		for (const table of filtered) {
			const { isSharded, prefix } = isShardedTableName(table.name);
			if (isSharded) {
				if (!groups.has(prefix)) groups.set(prefix, []);
				groups.get(prefix)!.push(table);
			} else {
				ungrouped.push(table);
			}
		}

		const result: TreeNode[] = [];
		groups.forEach((groupTables, prefix) => {
			if (groupTables.length >= 2) {
				const sorted = [...groupTables].sort((a, b) => b.name.localeCompare(a.name));
				const latest = sorted[0];
				result.push({
					name: `${prefix}_ (${groupTables.length})`,
					type: 'table_group',
					tableName: latest.name,
					databaseName: dbName,
					schemaName: schemaName,
					tableGroupCount: groupTables.length,
					comment: `${groupTables.length} sharded tables`,
				});
			} else {
				ungrouped.push(...groupTables);
			}
		});

		for (const table of ungrouped) {
			result.push({
				name: table.name || table,
				type: 'table',
				databaseName: dbName,
				schemaName: schemaName,
				pinned: table.pinned || false,
				comment: table.comment || '',
			});
		}

		result.sort((a, b) => {
			if (a.type === 'table_group' && b.type !== 'table_group') return -1;
			if (a.type !== 'table_group' && b.type === 'table_group') return 1;
			return a.name.localeCompare(b.name);
		});

		for (const v of views) {
			result.push({ name: v.name || v, type: 'view', databaseName: dbName, schemaName: schemaName });
		}
		return result;
	}

	let treeData = $state<TreeNode[]>([]);
	let treeLoading = $state(false);
	let treeSearch = $state('');
	let treeSearchPreloaded = $state(false);
	let connections = $state<IConnectionListItem[]>([]);
	let selectedConnectionId = $state<number | null>(null);
	let showConnectionDropdown = $state(false);

	// Active tab computed
	let activeConsole = $derived(ws.consoleList.find(c => c.id === ws.activeConsoleId));
	let isTableView = $derived(activeConsole?.operationType === 'tableView');
	let isERDView = $derived(activeConsole?.operationType === 'erd');
	let isLineageView = $derived(activeConsole?.operationType === 'lineage');

	// Console state
	let consoleContent = $state('-- Write your SQL here\nSELECT 1;');
	let resultDataList = $state<any[]>([]);
	let activeResultIndex = $state(0);
	let executing = $state(false);
	let executionTime = $state<number | null>(null);
	let executionAbortController = $state<AbortController | null>(null);

	// Pagination
	let currentPageNo = $state(1);
	let currentPageSize = $state(50);
	let hasMoreRows = $state(false);
	let currentQuerySql = $state(''); // Stores the last executed SQL for re-fetching pages

	// Column resize
	let columnWidths = $state<number[]>([]);
	let resizingColIdx = $state<number | null>(null);
	let resizeStartX = $state(0);
	let resizeStartWidth = $state(0);

	// Chart modal
	let showChartModal = $state(false);
	let showDashboardSelect = $state(false);
	let pendingChartData = $state<any>(null);
	let dashboardList = $state<{ id: number; name: string }[]>([]);
	let selectedDashboardId = $state<number | undefined>(undefined);
	let newDashboardName = $state('');
	let savingChart = $state(false);

	// Export dropdown
	let showExportDropdown = $state(false);
	let exporting = $state(false);

	// Cell detail modal
	let cellDetailValue = $state<string | null>(null);
	let cellDetailColumn = $state('');

	// Result table context menu
	let resultContextMenu = $state<{ x: number; y: number; cellValue: any; rowData: any[]; headers: any[]; colIdx: number } | null>(null);

	// Monaco editor ref
	let editorComponent: any = $state(null);

	// Editor context menu
	let editorContextMenu = $state<{ x: number; y: number; hasSelection: boolean } | null>(null);

	let currentDbType = $derived(connections.find(c => c.id === selectedConnectionId)?.type);

	// Tab editing
	let editingTabId = $state<string | number | null>(null);
	let editingTabName = $state('');
	let tabsContainer = $state<HTMLDivElement | null>(null);

	// Saved list
	let sortBy = $state<'date' | 'name' | 'type'>('date');
	let showSortDropdown = $state(false);
	let renamingConsoleId = $state<string | number | null>(null);
	let renamingConsoleName = $state('');

	// Resizing
	let isResizingLeft = $state(false);
	let isResizingResults = $state(false);
	let isResizingRight = $state(false);
	let isResizingHistory = $state(false);
	let resultsPanelHeight = $state(200);
	let historyPanelHeight = $state(200);

	// Right panel extend
	let rightPanelExtend = $state<'ddl' | 'aiChat' | null>(null);

	// Selected database/schema
	let selectedDatabaseName = $state<string | undefined>(undefined);
	let selectedSchemaName = $state<string | undefined>(undefined);
	let focusedNodeId = $state<string | null>(null); // track focused tree node for bg-primary

	// Context menu
	let contextMenu = $state<{ x: number; y: number; items: ContextMenuItem[] } | null>(null);

	// Result panel tab
	let resultActiveTab = $state<'results' | 'flow' | 'stats'>('results');
	let rowCount = $derived(resultDataList[activeResultIndex]?.rows?.length || 0);
	let hasResults = $derived(resultDataList.length > 0 || executing);

	// View All Tables tab
	let viewAllTablesData = $state<{ name: string; type: string; comment?: string }[] | null>(null);
	let viewAllTablesDb = $state('');

	// Floating AI button & inline edit
	let selectionInfo = $state<{ text: string; top: number; left: number; lineNumber: number } | null>(null);
	let inlineEditOpen = $state(false);
	let inlineEditValue = $state('');
	let inlineEditLoading = $state(false);
	let inlineEditTop = $state(0);
	let inlineEditLeft = $state(44);
	const INLINE_EDIT_ZONE_HEIGHT = 84;
	let scrollDisposer: { dispose: () => void } | null = null;
	let savedInlineEditSelectionRange: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number } | null = null;

	// Explain/Optimize result panel
	let explainResult = $state<{ content: string; loading: boolean; visible: boolean }>({ content: '', loading: false, visible: false });
	let explainPosition = $state<{ top: number; left: number } | null>(null);
	let isDraggingExplain = $state(false);
	let dragOffset = { x: 0, y: 0 };

	// AI Generate state
	const aiGenerationLoadingMessage = 'Generating from your request...';
	let isAiInputVisible = $state(false);
	let aiInputValue = $state('');
	let isAiGenerating = $state(false);
	let showDiffView = $state(false);
	let diffOriginal = $state('');
	let diffModified = $state('');
	let aiStreamAbortController: AbortController | null = null;

	// Cleanup tracking for event listeners
	let editorContextMenuHandler: ((e: MouseEvent) => void) | null = null;
	let editorDomNode: HTMLElement | null = null;

	// Tab switch abort controller to cancel stale async draft loads
	let tabSwitchController: AbortController | null = null;

	// Pending generation state - tracks original content for accept/reject
	let pendingGeneration = $state<{
		originalContent: string;
		selectionRange: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number } | null;
		insertLineNumber?: number;
	} | null>(null);

	// Cycling loading messages
	// Query Estimator state
	let showQueryEstimator = $state(false);
	let queryEstimatorLoading = $state(false);
	let queryEstimatorData = $state<QueryEstimatorData | null>(null);
	let queryEstimatorWidth = $state(450);
	let isResizingEstimator = $state(false);

	// AI Plan Analysis state
	let aiAnalysisContent = $state('');
	let aiAnalysisLoading = $state(false);
	let aiAnalysisVisible = $state(false);
	let aiAnalysisSql = $state('');

	// Diff view - Monaco DiffEditor
	let diffContainerEl = $state<HTMLDivElement | null>(null);
	let diffEditorInstance: any = null;

	// Explain widget - floating near selection
	let explainWidgetDomNode: HTMLDivElement | null = null;
	let contentWidgetRef: any = null;

	// Editor decorations for AI-generated SQL highlighting
	let editorDecorations: string[] = [];

	function clearDecorations() {
		const editor = editorComponent?.getEditor?.();
		if (editor && editorDecorations.length > 0) {
			editor.deltaDecorations(editorDecorations, []);
			editorDecorations = [];
		}
	}

	function applyGeneratedSqlDecorations(startLine: number, endLine: number) {
		const editor = editorComponent?.getEditor?.();
		if (!editor) return;
		const model = editor.getModel?.();
		if (!model) return;
		const safeEndLine = Math.min(endLine, model.getLineCount());
		editorDecorations = editor.deltaDecorations(editorDecorations, [{
			range: {
				startLineNumber: startLine,
				startColumn: 1,
				endLineNumber: safeEndLine,
				endColumn: model.getLineMaxColumn(safeEndLine)
			},
			options: {
				isWholeLine: true,
				className: 'ai-generated-highlight',
				glyphMarginClassName: 'ai-generated-glyph'
			}
		}]);
	}

	async function handleDeleteTable(node: TreeNode) {
		const db = node.databaseName || selectedDatabaseName || '';
		const schema = node.schemaName || selectedSchemaName;
		const confirmed = await confirmDialog({
			title: i18n('workspace.deleteTable.title'),
			message: i18n('workspace.deleteTable.message', node.name),
			confirmText: i18n('workspace.menu.deleteTable'),
			variant: 'destructive'
		});
		if (!confirmed) return;
		try {
			await sqlService.deleteTable({
				dataSourceId: selectedConnectionId,
				databaseName: db,
				schemaName: schema,
				tableName: node.name
			});
			message.success(i18n('workspace.deleteTable.success', node.name));
			// Refresh parent
			const parent = treeData.find(d => d.name === db);
			if (parent) { parent.children = null; parent.expanded = false; treeData = [...treeData]; toggleTreeNode(parent); }
		} catch (err: any) {
			message.error(err.message || i18n('workspace.deleteTable.failed'));
		}
	}

	async function handleTogglePin(node: TreeNode) {
		const db = node.databaseName || selectedDatabaseName || '';
		const schema = node.schemaName || selectedSchemaName;
		try {
			if (node.pinned) {
				await sqlService.deleteTablePin({ dataSourceId: selectedConnectionId, databaseName: db, schemaName: schema, tableName: node.name });
				node.pinned = false;
				message.success(i18n('workspace.unpin.success'));
			} else {
				await sqlService.addTablePin({ dataSourceId: selectedConnectionId, databaseName: db, schemaName: schema, tableName: node.name });
				node.pinned = true;
				message.success(i18n('workspace.pin.success'));
			}
			treeData = [...treeData];
		} catch (err: any) {
			message.error(err.message || i18n('workspace.pin.failed'));
		}
	}

	function handleTreeContextMenu(e: MouseEvent, node: TreeNode, parentDb?: string) {
		e.preventDefault();
		const items: ContextMenuItem[] = [];
		const conn = getSelectedConnection();
		const dbType = conn?.type || '';

		if (node.type === 'database') {
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				createConsoleWithConn({databaseName: node.name, ddl: `-- ${node.name}\n` });
			}});
			items.push({ label: i18n('workspace.menu.viewAllTables'), icon: Eye, action: () => openViewAllTables(node.name) });
			if (supportsERD(dbType)) {
				items.push({ label: i18n('workspace.menu.viewERD'), icon: Workflow, action: () => {
					openERDTab(node.name);
				}});
			}
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: i18n('workspace.menu.createTable'), icon: TableProperties, action: () => {
				createConsoleWithConn({databaseName: node.name, ddl: `-- Create Table\nCREATE TABLE table_name (\n  id INT PRIMARY KEY,\n  name VARCHAR(255)\n);\n` });
			}});
			items.push({ label: i18n('workspace.menu.createSchema'), icon: DatabaseZap, action: () => {
				createConsoleWithConn({databaseName: node.name, ddl: `-- Create Schema\nCREATE SCHEMA schema_name;\n` });
			}});
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: i18n('workspace.menu.copyName'), icon: Copy, action: () => navigator.clipboard.writeText(node.name) });
			items.push({ label: i18n('workspace.menu.refresh'), icon: RefreshCw, action: () => {
				node.children = null;
				node.expanded = false;
				treeData = [...treeData];
				toggleTreeNode(node);
			}});
		} else if (node.type === 'schema') {
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				createConsoleWithConn({databaseName: node.databaseName || selectedDatabaseName, schemaName: node.name, ddl: `-- ${node.name}\n` });
			}});
			items.push({ label: i18n('workspace.menu.viewAllTables'), icon: Eye, action: () => openViewAllTables(node.databaseName || selectedDatabaseName || '', node.name) });
			if (supportsERD(dbType)) {
				items.push({ label: i18n('workspace.menu.viewERD'), icon: Workflow, action: () => {
					openERDTab(node.databaseName || selectedDatabaseName || '', node.name);
				}});
			}
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: i18n('workspace.menu.createTable'), icon: TableProperties, action: () => {
				createConsoleWithConn({
					databaseName: node.databaseName || selectedDatabaseName,
					schemaName: node.name,
					ddl: `-- Create Table in ${node.name}\nCREATE TABLE ${node.name}.table_name (\n  id INT PRIMARY KEY,\n  name VARCHAR(255)\n);\n`
				});
			}});
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: i18n('workspace.menu.copyName'), icon: Copy, action: () => navigator.clipboard.writeText(node.name) });
			items.push({ label: i18n('workspace.menu.refresh'), icon: RefreshCw, action: () => {
				node.children = null;
				node.expanded = false;
				treeData = [...treeData];
				toggleTreeNode(node);
			}});
		} else if (node.type === 'table' || node.type === 'view') {
			const db = parentDb || node.databaseName || selectedDatabaseName || '';
			const schema = node.schemaName || selectedSchemaName;
			const qualifiedName = schema ? `${schema}.${node.name}` : node.name;
			// Build fully qualified name: db.schema.table
			const fqParts: string[] = [];
			if (db) fqParts.push(db);
			if (schema) fqParts.push(schema);
			fqParts.push(node.name);
			let fullTableName = fqParts.join('.');
			if (dbType === 'BIGQUERY') fullTableName = `\`${fullTableName}\``;

			items.push({ label: i18n('workspace.menu.openTable'), icon: Table2, action: () => {
				openTableView(db, schema, node.name, fullTableName);
			}});
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				createConsoleWithConn({databaseName: db, ddl: `-- ${qualifiedName}\n` });
			}});
			items.push({ label: i18n('workspace.menu.ViewDDL'), icon: FileCode, action: () => {
				rightPanelExtend = 'ddl';
				if (!ws.layout.panelRight) togglePanelRight();
				loadDDL(node.name, node.schemaName);
			}});
			if (supportsERD(dbType)) {
				items.push({ label: i18n('workspace.menu.viewERD'), icon: Workflow, action: () => {
					openERDTab(db, schema);
				}});
			}
			items.push({ label: i18n('workspace.menu.viewLineage'), icon: GitBranch, action: () => {
				openLineageTab(db, schema, node.name);
			}});
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: node.pinned ? i18n('workspace.menu.unpin') : i18n('workspace.menu.pin'), icon: node.pinned ? PinOff : Pin, action: () => handleTogglePin(node) });
			items.push({ label: i18n('workspace.menu.deleteTable'), icon: Trash2, action: () => handleDeleteTable(node) });
			items.push({ separator: true, label: '', action: () => {} });
			items.push({ label: i18n('workspace.menu.copyName'), icon: Copy, action: () => {
				// Build full qualified name
				const parts: string[] = [];
				if (db) parts.push(db);
				if (schema) parts.push(schema);
				parts.push(node.name);
				let fullName = parts.join('.');
				if (dbType === 'BIGQUERY') fullName = `\`${fullName}\``;
				navigator.clipboard.writeText(fullName);
			}});
			items.push({ label: i18n('workspace.menu.refresh'), icon: RefreshCw, action: () => {
				node.children = null;
				node.expanded = false;
				treeData = [...treeData];
			}});
		} else if (node.type === 'table_group') {
			const db = parentDb || node.databaseName || selectedDatabaseName || '';
			const schema = node.schemaName || selectedSchemaName;
			const actualName = node.tableName || node.name;
			const fqParts: string[] = [];
			if (db) fqParts.push(db);
			if (schema) fqParts.push(schema);
			fqParts.push(actualName);
			let fullTableName = fqParts.join('.');
			if (dbType === 'BIGQUERY') fullTableName = `\`${fullTableName}\``;
			items.push({ label: i18n('workspace.menu.openTableLatest'), icon: Table2, action: () => {
				openTableView(db, schema, actualName, fullTableName);
			}});
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				createConsoleWithConn({databaseName: db, ddl: `-- ${actualName}\n` });
			}});
			items.push({ label: i18n('workspace.menu.copyName'), icon: Copy, action: () => navigator.clipboard.writeText(fullTableName) });
			items.push({ label: i18n('workspace.menu.refresh'), icon: RefreshCw, action: () => {
				node.children = null;
				node.expanded = false;
				treeData = [...treeData];
			}});
		} else if (node.type === 'column' || node.type === 'nested_column' || node.type === 'key' || node.type === 'index') {
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				const db = node.databaseName || selectedDatabaseName || '';
				createConsoleWithConn({databaseName: db, ddl: `-- ${node.name}\n` });
			}});
			items.push({ label: i18n('workspace.menu.copyName'), icon: Copy, action: () => navigator.clipboard.writeText(node.name) });
		} else if (node.type === 'columns' || node.type === 'keys' || node.type === 'indexes') {
			items.push({ label: i18n('workspace.menu.newConsole'), icon: Terminal, action: () => {
				const db = node.databaseName || selectedDatabaseName || '';
				createConsoleWithConn({databaseName: db, ddl: `-- console\n` });
			}});
			items.push({ label: i18n('workspace.menu.refresh'), icon: RefreshCw, action: () => {
				node.children = null;
				node.expanded = false;
				treeData = [...treeData];
				toggleTreeNode(node);
			}});
		}

		contextMenu = { x: e.clientX, y: e.clientY, items };
	}

	async function openViewAllTables(dbName: string, schemaName?: string) {
		viewAllTablesDb = dbName;
		try {
			const tables = await catalogService.loadTablesBySchema({
				dataSourceId: selectedConnectionId!,
				databaseName: dbName,
				schemaName: schemaName || selectedSchemaName
			});
			viewAllTablesData = Array.isArray(tables) ? tables.map((t: any) => ({
				name: t.name || t,
				type: t.type || 'TABLE',
				comment: t.comment
			})) : [];
		} catch { viewAllTablesData = []; }
	}

	// Init column widths when results change
	$effect(() => {
		const result = resultDataList[activeResultIndex];
		if (result && 'headers' in result) {
			const headers = result.headers;
			const hasRowNum = headers.length > 0 && (headers[0]?.dataType === 'INQUERY_ROW_NUMBER' || headers[0]?.name === 'Row Number');
			const displayCount = hasRowNum ? headers.length - 1 : headers.length;
			if (columnWidths.length !== displayCount) {
				// Try to restore saved widths for this tab
				let restored = false;
				if (ws.activeConsoleId && typeof localStorage !== 'undefined') {
					try {
						const saved = localStorage.getItem(`col-widths-${ws.activeConsoleId}`);
						if (saved) {
							const parsed = JSON.parse(saved);
							if (Array.isArray(parsed) && parsed.length === displayCount) {
								columnWidths = parsed;
								restored = true;
							}
						}
					} catch { /* ignore */ }
				}
				if (!restored) {
					columnWidths = Array(displayCount).fill(150);
				}
			}
		}
	});

	$effect(() => {
		if (typeof localStorage !== 'undefined') {
			try {
				const saved = localStorage.getItem('workspace-results-height');
				if (saved) resultsPanelHeight = Number(saved);
				const savedHistory = localStorage.getItem('workspace-history-height');
				if (savedHistory) historyPanelHeight = Number(savedHistory);
			} catch {}
		}
	});

	function getSelectedConnection(): IConnectionListItem | undefined {
		return connections.find(c => c.id === selectedConnectionId);
	}

	async function openERDTab(dbName?: string, schemaName?: string) {
		if (!selectedConnectionId) return;
		const db = dbName || selectedDatabaseName || '';
		const erdTabName = schemaName ? `ERD: ${db}.${schemaName}` : `ERD: ${db}`;
		const erdKey = `erd:${selectedConnectionId}:${db}:${schemaName || ''}`;

		const existingTab = ws.consoleList.find(
			c => c.operationType === 'erd' && c.tableName === erdKey
		);
		if (existingTab) {
			setActiveConsoleId(existingTab.id);
			return;
		}

		await createConsoleWithConn({
			name: erdTabName,
			databaseName: db,
			schemaName: schemaName,
			ddl: '',
			operationType: 'erd' as any,
			tableName: erdKey,
		});
	}

	// ═════════════════════════════════════════════════
	// Lineage tab
	// ═════════════════════════════════════════════════
	import type { ILineageGraph } from '$lib/service/catalog';
	import LineageGraph from '$lib/components/LineageGraph/LineageGraph.svelte';

	let lineageGraphData = $state<ILineageGraph | null>(null);
	let lineageLoading = $state(false);

	let lineageFocusMap = $state<Record<string | number, string>>({});

	async function openLineageTab(dbName?: string, schemaName?: string, tableName?: string) {
		if (!selectedConnectionId) return;
		const db = dbName || selectedDatabaseName || '';
		const tabName = tableName ? `Lineage: ${tableName}` : `Lineage: ${db}`;
		const lineageKey = tableName
			? `lineage:${selectedConnectionId}:${db}:${schemaName || ''}:${tableName}`
			: `lineage:${selectedConnectionId}:${db}:${schemaName || ''}`;

		const existingTab = ws.consoleList.find(
			c => c.operationType === 'lineage' && c.tableName === lineageKey
		);
		if (existingTab) {
			setActiveConsoleId(existingTab.id);
			return;
		}

		const id = await createConsoleWithConn({
			name: tabName,
			databaseName: db,
			schemaName: schemaName,
			ddl: tableName || '',
			operationType: 'lineage' as any,
			tableName: lineageKey,
		});
		if (id && tableName) {
			lineageFocusMap[id] = tableName;
		}
	}

	async function loadActiveLineage() {
		if (!activeConsole || activeConsole.operationType !== 'lineage') return;
		if (!selectedConnectionId) return;
		lineageLoading = true;
		lineageGraphData = null;
		try {
			const res = await catalogService.getLineageGraph({ dataSourceId: selectedConnectionId });
			if (res && res.nodes && res.nodes.length > 0) {
				lineageGraphData = res;
			}
		} catch { lineageGraphData = null; }
		finally { lineageLoading = false; }
	}

	let lastLineageTabId = $state<string | number | null>(null);

	$effect(() => {
		if (isLineageView) {
			const currentTabId = activeConsole?.id ?? null;
			if (currentTabId !== lastLineageTabId) {
				lastLineageTabId = currentTabId;
				lineageGraphData = null;
				loadActiveLineage();
			} else if (!lineageGraphData && !lineageLoading) {
				loadActiveLineage();
			}
		}
	});

	let erdSchema = $state<ERDSchema | null>(null);
	let erdLoading = $state(false);

	async function loadActiveERD() {
		if (!activeConsole || activeConsole.operationType !== 'erd') return;
		if (!selectedConnectionId) return;
		const db = activeConsole.databaseName || selectedDatabaseName || '';
		const schema = activeConsole.schemaName;
		erdLoading = true;
		erdSchema = null;
		try {
			const res = await erdService.getSchema({
				dataSourceId: selectedConnectionId,
				databaseName: db,
				...(schema ? { schemaName: schema } : {})
			});
			erdSchema = res as ERDSchema;
		} catch { erdSchema = null; }
		finally { erdLoading = false; }
	}

	let lastERDTabId = $state<string | number | null>(null);

	$effect(() => {
		if (isERDView) {
			const currentTabId = activeConsole?.id ?? null;
			if (currentTabId !== lastERDTabId) {
				lastERDTabId = currentTabId;
				erdSchema = null;
				loadActiveERD();
			} else if (!erdSchema && !erdLoading) {
				loadActiveERD();
			}
		}
	});

	onMount(async () => {
		const saved = ws.currentConnection;
		const cache = saved ? consumePrefetchCache(saved.id) : null;

		// 1. Connection list — reuse from prefetch or fetch fresh
		try {
			if (cache?.connectionList) {
				connections = cache.connectionList;
			} else {
				const res = await connectionService.getList({ pageNo: 1, pageSize: 1000 });
				connections = (res as any)?.data || [];
			}
		} catch { connections = []; }

		// 2. Database list — await prefetched promise or fetch fresh
		const restoredConn = saved ? connections.find(c => c.id === saved.id) : null;
		if (restoredConn) {
			selectedConnectionId = restoredConn.id;
			loadDatabases(restoredConn.id, cache?.dbListPromise);
		} else if (connections.length > 0 && !selectedConnectionId) {
			selectedConnectionId = connections[0].id;
			setCurrentConnection(connections[0]);
			loadDatabases(connections[0].id);
		}

		// 3. Console lists — pass prefetched promises directly
		await fetchConsoleList(cache?.consoleListPromise);
		await fetchSavedConsoleList(cache?.savedConsoleListPromise);

		// 4. Restore editor draft
		if (ws.activeConsoleId) {
			try {
				const { loadDraft } = await import('$lib/utils/indexedDB');
				const draft = await loadDraft(ws.activeConsoleId);
				if (draft?.ddl) {
					consoleContent = draft.ddl;
				}
			} catch { /* ignore */ }
		}
		draftLoaded = true;
	});

	onDestroy(() => {
		// Cleanup editor context menu listener
		if (editorDomNode && editorContextMenuHandler) {
			editorDomNode.removeEventListener('contextmenu', editorContextMenuHandler);
		}
		// Cancel pending tab switch loads
		tabSwitchController?.abort();
	});

	// Track when draft loading is complete so pendingSql doesn't get overwritten
	let draftLoaded = $state(false);

	// Load draft content when switching tabs
	let prevActiveConsoleId: string | number | null = null;
	$effect(() => {
		const currentId = ws.activeConsoleId;
		if (currentId && currentId !== prevActiveConsoleId) {
			prevActiveConsoleId = currentId;
			draftLoaded = false;
			// Cancel any pending tab switch load
			tabSwitchController?.abort();
			const controller = new AbortController();
			tabSwitchController = controller;

			// Clear previous results and diff state immediately on tab switch
			resultDataList = [];
			executionTime = null;
			if (showDiffView) {
				disposeDiffEditor();
				showDiffView = false;
				diffOriginal = '';
				diffModified = '';
				pendingGeneration = null;
			}
			clearDecorations();

			// Check if this is a tableView tab
			const switchedConsole = ws.consoleList.find(c => c.id === currentId);
			const isTableViewTab = switchedConsole?.operationType === 'tableView';

			// Then load the new tab's draft
			(async () => {
				try {
					const { loadDraft } = await import('$lib/utils/indexedDB');
					const draft = await loadDraft(currentId);
					// Check if this load is still relevant (not superseded by another tab switch)
					if (controller.signal.aborted) return;
					if (draft?.ddl) {
						consoleContent = draft.ddl;
					} else {
						consoleContent = (switchedConsole as any)?.ddl || '-- Write your SQL here\n';
					}

					draftLoaded = true;

					// Auto-execute for tableView tabs
					if (isTableViewTab && !controller.signal.aborted) {
						handleRunQuery();
					}
				} catch {
					draftLoaded = true;
				}
			})();
		}
	});

	// Consume pendingSql from AI chat: append to current editor content
	// Wait for draft loading to complete to avoid race condition where
	// the async draft load overwrites the appended SQL
	$effect(() => {
		const sql = ws.pendingSql;
		if (!sql) return;
		if (!draftLoaded) return;
		const separator = consoleContent.trim() ? '\n\n' : '';
		consoleContent = consoleContent + separator + sql;
		if (ws.activeConsoleId) {
			debouncedAutoSave(ws.activeConsoleId, consoleContent);
		}
		setPendingSql(null);
		// Move cursor to end of appended SQL and show toast
		tick().then(() => {
			const editor = editorComponent?.getEditor?.();
			if (editor) {
				const model = editor.getModel();
				if (model) {
					const lastLine = model.getLineCount();
					const lastCol = model.getLineMaxColumn(lastLine);
					editor.setPosition({ lineNumber: lastLine, column: lastCol });
					editor.revealLineInCenter(lastLine);
				}
				editor.focus();
			}
			message.success(i18n('workspace.sql.pinned'));
		});
	});

	async function loadDatabases(dataSourceId: number, prefetchedPromise?: Promise<any>) {
		treeLoading = true;
		treeSearchPreloaded = false;
		try {
			const res = prefetchedPromise
				? await prefetchedPromise
				: await connectionService.getDatabaseList({ dataSourceId, refresh: false });
			const dbList = Array.isArray(res) ? res.map((db: any) => ({
				name: typeof db === 'string' ? db : db.name,
				type: 'database' as const,
				children: null,
				expanded: false,
				loading: false
			})) : [];
			treeData = dbList;
			updateDatabases(dbList.map(d => ({ name: d.name })));
			if (dbList.length > 0 && !selectedDatabaseName) {
				handleDatabaseSelect(dbList[0].name, dataSourceId);
			}
		} catch {
			treeData = [];
		} finally {
			treeLoading = false;
		}
	}

	async function handleDatabaseSelect(dbName: string, dataSourceId?: number) {
		const dsId = dataSourceId || selectedConnectionId;
		if (!dsId) return;
		selectedDatabaseName = dbName;

		const conn = connections.find(c => c.id === dsId);
		if (conn?.type) updateDatabaseType(conn.type as any);

		updateConnectionInfo({
			dataSourceId: dsId,
			databaseName: dbName,
			dataSourceName: conn?.alias
		});

		// For databases without schema support (MySQL), load tables for intellisense
		// For schema-based DBs (PostgreSQL, BigQuery), tables load when schema expands
		if (!conn?.supportSchema) {
			try {
				const tables = await catalogService.loadTablesBySchema({
					dataSourceId: dsId,
					databaseName: dbName
				});
				if (Array.isArray(tables)) {
					const tableItems = tables.filter((t: any) => {
						const tt = (t.type || '').toUpperCase();
						return tt !== 'VIEW' && tt !== 'MATERIALIZED VIEW';
					});
					const viewItems = tables.filter((t: any) => {
						const tt = (t.type || '').toUpperCase();
						return tt === 'VIEW' || tt === 'MATERIALIZED VIEW';
					});
					updateTables(tableItems.map((t: any) => ({
						name: t.name || t,
						comment: t.comment,
						databaseName: dbName
					})));
					updateViews(viewItems.map((t: any) => ({
						name: t.name || t,
						databaseName: dbName
					})));
				}
			} catch { /* ignore */ }
		}
	}

	function getNodeId(node: TreeNode): string {
		return `${node.type}:${node.databaseName || ''}:${node.schemaName || ''}:${node.name}`;
	}

	function handleTableNodeClick(node: TreeNode) {
		focusedNodeId = getNodeId(node);
		if (node.type === 'table' || node.type === 'table_group' || node.type === 'view') {
			rightPanelExtend = 'ddl';
			if (!ws.layout.panelRight) togglePanelRight();
			const actualName = node.type === 'table_group' ? (node.tableName || node.name) : node.name;
			loadDDL(actualName, node.schemaName);
		}
	}

	async function toggleTreeNode(node: TreeNode) {
		if (node.type === 'database') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				const conn = getSelectedConnection();
				node.loading = true;
				treeData = [...treeData];
				handleDatabaseSelect(node.name);

				if (conn?.supportSchema) {
					try {
						const schemas = await connectionService.getSchemaList({
							dataSourceId: selectedConnectionId!,
							databaseName: node.name
						});
						node.children = Array.isArray(schemas) ? (schemas as any[]).map((s: any) => ({
							name: typeof s === 'string' ? s : s.name,
							type: 'schema' as const,
							children: null,
							expanded: false,
							loading: false,
							databaseName: node.name
						})) : [];
					} catch {
						node.children = [];
					}
			} else {
				try {
						const tables = await catalogService.loadTablesBySchema({
							dataSourceId: selectedConnectionId!,
							databaseName: node.name
						});
						const isBQ = conn?.type === 'BIGQUERY';
						node.children = Array.isArray(tables) ? groupShardedTables(tables, isBQ, node.name) : [];
					} catch {
						node.children = [];
					}
				}
				node.loading = false;
			}
			treeData = [...treeData];
		} else if (node.type === 'schema') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.loading = true;
				selectedSchemaName = node.name;
				treeData = [...treeData];
			try {
				const tables = await catalogService.loadTablesBySchema({
						dataSourceId: selectedConnectionId!,
						databaseName: node.databaseName!,
						schemaName: node.name
					});
					const tableList = Array.isArray(tables) ? tables : [];
					const conn = getSelectedConnection();
					const isBQ = conn?.type === 'BIGQUERY';
					node.children = groupShardedTables(tableList, isBQ, node.databaseName, node.name);
					updateConnectionInfo({
						dataSourceId: selectedConnectionId!,
						databaseName: node.databaseName,
						schemaName: node.name,
						dataSourceName: getSelectedConnection()?.alias
					});
					const schemaTableItems = tableList.filter((t: any) => {
						const tt = (t.type || '').toUpperCase();
						return tt !== 'VIEW' && tt !== 'MATERIALIZED VIEW';
					});
					const schemaViewItems = tableList.filter((t: any) => {
						const tt = (t.type || '').toUpperCase();
						return tt === 'VIEW' || tt === 'MATERIALIZED VIEW';
					});
					updateTables(schemaTableItems.map((t: any) => ({
						name: t.name || t,
						comment: t.comment,
						databaseName: node.databaseName || '',
						schemaName: node.name
					})));
					updateViews(schemaViewItems.map((t: any) => ({
						name: t.name || t,
						databaseName: node.databaseName || ''
					})));
				} catch {
					node.children = [];
				}
				node.loading = false;
			}
			treeData = [...treeData];
		} else if (node.type === 'table') {
			// Table expand: show columns/keys/indexes folders (React pattern)
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.children = [
					{ name: 'columns', type: 'columns', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.name },
					{ name: 'keys', type: 'keys', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.name },
					{ name: 'indexes', type: 'indexes', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.name },
				];
			}
			treeData = [...treeData];
		} else if (node.type === 'table_group') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.children = [
					{ name: 'columns', type: 'columns', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.tableName },
					{ name: 'keys', type: 'keys', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.tableName },
					{ name: 'indexes', type: 'indexes', children: null, databaseName: node.databaseName, schemaName: node.schemaName, tableName: node.tableName },
				];
			}
			treeData = [...treeData];
		} else if (node.type === 'nested_column') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children && node.nestedChildren) {
				node.children = node.nestedChildren;
			}
			treeData = [...treeData];
		} else if (node.type === 'columns') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.loading = true;
				treeData = [...treeData];
				try {
					const cols = await sqlService.getColumnList({
						dataSourceId: selectedConnectionId!,
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName
					});
					node.children = Array.isArray(cols) ? columnsToTreeNodes(cols, {
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName,
					}) : [];
				} catch {
					node.children = [];
				}
				node.loading = false;
			}
			treeData = [...treeData];
		} else if (node.type === 'keys') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.loading = true;
				treeData = [...treeData];
				try {
					const keys = await sqlService.getColumnList({
						dataSourceId: selectedConnectionId!,
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName
					});
					// Filter to only key columns (primary/unique)
					node.children = Array.isArray(keys) ? keys.filter((k: any) => k.primaryKey || k.key).map((k: any) => ({
						name: k.name || k,
						type: 'key' as const,
						isLeaf: true,
						columnType: k.columnType || k.dataType || k.type,
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName
					})) : [];
				} catch {
					node.children = [];
				}
				node.loading = false;
			}
			treeData = [...treeData];
		} else if (node.type === 'indexes') {
			node.expanded = !node.expanded;
			if (node.expanded && !node.children) {
				node.loading = true;
				treeData = [...treeData];
				try {
					const indexes = await sqlService.getIndexList({
						dataSourceId: selectedConnectionId!,
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName
					});
					node.children = Array.isArray(indexes) ? indexes.map((idx: any) => ({
						name: idx.name || idx,
						type: 'index' as const,
						isLeaf: true,
						columnType: idx.columnList || idx.type,
						databaseName: node.databaseName,
						schemaName: node.schemaName,
						tableName: node.tableName
					})) : [];
				} catch {
					node.children = [];
				}
				node.loading = false;
			}
			treeData = [...treeData];
		}
	}

	function handleConnectionChange(conn: IConnectionListItem) {
		selectedConnectionId = conn.id;
		setCurrentConnection(conn);
		selectedDatabaseName = undefined;
		selectedSchemaName = undefined;
		showConnectionDropdown = false;
		loadDatabases(conn.id);
	}

	async function handleRunQuery(pageNo = 1) {
		if (executing) return;
		if (!selectedConnectionId) { message.warning('Please select a connection first'); return; }
		executing = true;
		executionTime = null;
		if (pageNo === 1) { resultDataList = []; activeResultIndex = 0; }
		resultActiveTab = 'results';
		currentPageNo = pageNo;

		const abortCtrl = new AbortController();
		executionAbortController = abortCtrl;

		const start = Date.now();
		const sql = pageNo === 1
			? (editorComponent?.getSelectedText?.() || editorComponent?.getCurrentQueryAtCursor?.() || consoleContent)
			: currentQuerySql;
		if (pageNo === 1) currentQuerySql = sql;

		try {
			const res = await sqlService.executeSql({
				dataSourceId: selectedConnectionId!,
				sql: sql.trim(),
				consoleId: ws.activeConsoleId as number,
				databaseName: selectedDatabaseName,
				schemaName: selectedSchemaName,
				pageNo,
				pageSize: currentPageSize
			});
			if (abortCtrl.signal.aborted) return;
			executionTime = Date.now() - start;
			const rawList = Array.isArray(res) ? res : [res];
			resultDataList = rawList.map((item: any) => {
				if (item?.headerList && item.headerList.length > 0) {
					const result: any = { headers: item.headerList, rows: item.dataList || [], success: item.success !== false, sql: item.sql, description: item.description };
					const conn = getSelectedConnection();
					if (conn?.type === 'BIGQUERY' && result.rows.length > 0) {
						const nested = detectNestedColumns(result.headers, result.rows);
						if (nested.length > 0) {
							const { flattenedData, expandedHeaders } = flattenDataWithNestedArrays(result.headers, result.rows, nested);
							result.headers = expandedHeaders;
							result.flattenedRows = flattenedData;
							result.rows = flattenedData.map((fRow: FlattenedRow) =>
								expandedHeaders.map((h: any) => fRow[h.name] ?? null)
							);
							result.nestedExpanded = true;
							result.description = `${result.description || 'Query result'} (${nested.length} nested columns expanded)`;
						}
					}
					return result;
				} else if (item?.message || item?.description) {
					return { message: item.message || item.description, success: item.success !== false, sql: item.sql };
				} else {
					return item;
				}
			});
			const firstResult = resultDataList[0];
			hasMoreRows = firstResult?.rows?.length >= currentPageSize;
			window.dispatchEvent(new CustomEvent('query-executed'));
		} catch (e: any) {
			if (abortCtrl.signal.aborted) return;
			resultDataList = [{ error: e?.message || 'Query execution failed' }];
			executionTime = Date.now() - start;
			hasMoreRows = false;
			message.error(e?.message || 'Query execution failed');
			window.dispatchEvent(new CustomEvent('query-executed'));
		} finally {
			executing = false;
			executionAbortController = null;
		}
	}

	function handleStopQuery() {
		if (executionAbortController) {
			executionAbortController.abort();
			executionAbortController = null;
		}
		executing = false;
		message.info('Query cancelled');
	}

	// Column resize handlers
	function handleColumnResizeStart(e: MouseEvent, colIdx: number) {
		e.preventDefault();
		e.stopPropagation();
		resizingColIdx = colIdx;
		resizeStartX = e.clientX;
		resizeStartWidth = columnWidths[colIdx] || 150;

		const onMouseMove = (ev: MouseEvent) => {
			if (resizingColIdx === null) return;
			const diff = ev.clientX - resizeStartX;
			const newWidth = Math.max(60, Math.min(1080, resizeStartWidth + diff));
			columnWidths[resizingColIdx] = newWidth;
			columnWidths = [...columnWidths];
		};
		const onMouseUp = () => {
			resizingColIdx = null;
			document.removeEventListener('mousemove', onMouseMove);
			document.removeEventListener('mouseup', onMouseUp);
			document.body.style.cursor = '';
			document.body.style.userSelect = '';
			// Persist column widths
			if (ws.activeConsoleId && typeof localStorage !== 'undefined') {
				try {
					localStorage.setItem(`col-widths-${ws.activeConsoleId}`, JSON.stringify(columnWidths));
				} catch { /* ignore */ }
			}
		};
		document.body.style.cursor = 'col-resize';
		document.body.style.userSelect = 'none';
		document.addEventListener('mousemove', onMouseMove);
		document.addEventListener('mouseup', onMouseUp);
	}

	// Client-side export
	function handleClientExport(exportType: 'CSV' | 'INSERT' | 'JSON') {
		const currentResult = resultDataList[activeResultIndex];
		if (!currentResult || !('headers' in currentResult)) {
			message.warning('No data to export');
			return;
		}

		showExportDropdown = false;
		
		const rawName = activeConsole?.name || '';
		const consoleName = (!rawName || /^untitled$/i.test(rawName.trim()) ? 'query_result' : rawName).replace(/[\\/:*?"<>|]+/g, '_');
		const now = new Date();
		const ts = [
			now.getFullYear(),
			String(now.getMonth() + 1).padStart(2, '0'),
			String(now.getDate()).padStart(2, '0'),
			'_',
			String(now.getHours()).padStart(2, '0'),
			String(now.getMinutes()).padStart(2, '0'),
			String(now.getSeconds()).padStart(2, '0'),
		].join('');
		const filename = `${consoleName}_${ts}`;

		try {
			if (exportType === 'CSV') {
				downloadTableAsCSV(currentResult.headers, currentResult.rows, filename);
			} else if (exportType === 'JSON') {
				downloadTableAsJSON(currentResult.headers, currentResult.rows, filename);
			} else {
				downloadInsertSQL(consoleName, currentResult.headers, currentResult.rows, filename);
			}
			message.success('Export complete');
		} catch (err: any) {
			message.error(err?.message || 'Export failed');
		}
	}

	// Chart creation from results
	async function handleOpenChartModal() {
		showChartModal = true;
	}

	async function handleChartSave(chartData: { name: string; schema: string; dataSourceId?: number; databaseName?: string }) {
		pendingChartData = chartData;
		showChartModal = false;
		// Fetch dashboard list
		try {
			const res = await dashboardService.getDashboardList({});
			dashboardList = (Array.isArray(res) ? res : (res as any)?.data || []).map((d: any) => ({ id: d.id, name: d.name }));
		} catch { dashboardList = []; }
		selectedDashboardId = undefined;
		newDashboardName = '';
		showDashboardSelect = true;
	}

	async function handlePinToDashboard() {
		if (!pendingChartData) return;
		savingChart = true;
		try {
			// Create chart
			const chartId = await dashboardService.createChart({
				name: pendingChartData.name,
				schema: pendingChartData.schema,
				dataSourceId: pendingChartData.dataSourceId || selectedConnectionId,
				databaseName: pendingChartData.databaseName || selectedDatabaseName,
				sourceType: 'WORKSPACE',
			}) as number;

			// Get or create dashboard
			let dashId = selectedDashboardId;
			if (!dashId && newDashboardName.trim()) {
				dashId = await dashboardService.createDashboard({ name: newDashboardName.trim() }) as number;
			}
			if (dashId) {
				// Fetch existing dashboard to get current chartIds
				const dashboard = await dashboardService.getDashboard({ id: dashId });
				const existingChartIds = (dashboard as any)?.chartIds || [];
				await dashboardService.updateDashboard({
					id: dashId,
					chartIds: [...existingChartIds, chartId],
				});
				message.success('Chart added to dashboard');
			} else {
				message.success('Chart created');
			}
		} catch (err: any) {
			message.error(err?.message || 'Failed to save chart');
		} finally {
			savingChart = false;
			showDashboardSelect = false;
			pendingChartData = null;
		}
	}

	// Cell detail - detect if value should open in modal
	function shouldOpenCellModal(value: any): boolean {
		if (value === null || value === undefined) return false;
		if (typeof value === 'object') return true;
		if (typeof value === 'string') {
			if (value.length > 100) return true;
			try {
				const parsed = JSON.parse(value);
				if (typeof parsed === 'object') return true;
			} catch { /* not JSON */ }
		}
		return false;
	}

	function handleCellDblClick(value: any, colName: string) {
		if (value === null || value === undefined) return;
		const str = typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
		cellDetailValue = str;
		cellDetailColumn = colName;
	}

	function handleResultCellContextMenu(e: MouseEvent, cellValue: any, rowData: any[], headers: any[], colIdx: number) {
		e.preventDefault();
		resultContextMenu = { x: e.clientX, y: e.clientY, cellValue, rowData, headers, colIdx };
	}

	function copyToClipboard(text: string) {
		navigator.clipboard.writeText(text);
		message.success('Copied to clipboard');
		resultContextMenu = null;
	}

	function copyRowAsInsert(row: any[], headers: any[]) {
		const colNames = headers.map((h: any) => h.name || h).join(', ');
		const values = row.map(v => {
			if (v === null || v === undefined) return 'NULL';
			if (typeof v === 'number') return String(v);
			return `'${String(v).replace(/'/g, "''")}'`;
		}).join(', ');
		copyToClipboard(`INSERT INTO table_name (${colNames}) VALUES (${values});`);
	}

	function copyRowAsTab(row: any[]) {
		copyToClipboard(row.map(v => v === null ? '' : String(v)).join('\t'));
	}

	function handleLoadHistoryToEditor(item: IHistoryRecord) {
		consoleContent = item.ddl || '';
	}

	async function handleSaveConsole() {
		if (!ws.activeConsoleId) return;
		await saveConsole(ws.activeConsoleId, consoleContent);
		message.success('Saved successfully');
	}

	function createConsoleWithConn(params: { name?: string; databaseName?: string; schemaName?: string; ddl?: string; operationType?: 'console' | 'tableView' | 'erd'; tableName?: string }) {
		const conn = getSelectedConnection();
		return createConsole({
			...params,
			dataSourceId: selectedConnectionId || undefined,
			dataSourceName: conn?.alias || 'Unknown',
			databaseType: conn?.type || 'MYSQL',
		});
	}

	/** Open Table: reuse existing tableView tab or create a new one */
	async function openTableView(db: string, schema: string | undefined, tableName: string, fullTableName: string) {
		// Check if a tableView tab for this table already exists
		const existingTab = ws.consoleList.find(
			c => c.operationType === 'tableView' && c.tableName === fullTableName
		);
		if (existingTab) {
			setActiveConsoleId(existingTab.id);
			return;
		}

		currentPageNo = 1;
		currentPageSize = 50;
		const sql = `SELECT * FROM ${fullTableName} LIMIT 50;\n`;
		await createConsoleWithConn({
			name: fullTableName,
			databaseName: db,
			schemaName: schema,
			ddl: sql,
			operationType: 'tableView',
			tableName: fullTableName,
		});
	}

	async function handleNewConsole() {
		if (ws.consoleList.length >= 20) {
			message.warning('Maximum 20 consoles allowed');
			return;
		}
		if (!selectedConnectionId) {
			message.warning('Please select a connection first');
			return;
		}
		await createConsoleWithConn({
			databaseName: selectedDatabaseName,
			ddl: ''
		});
		await tick();
		if (tabsContainer) {
			tabsContainer.scrollLeft = tabsContainer.scrollWidth;
		}
	}

	async function handleCloseTab(tabId: number | string) {
		await closeConsole(tabId);
	}

	function startEditingTab(tab: any) {
		editingTabId = tab.id;
		editingTabName = tab.name || `Console ${tab.id}`;
	}

	async function finishEditingTab() {
		if (editingTabId && editingTabName.trim()) {
			await renameConsole(editingTabId, editingTabName.trim());
		}
		editingTabId = null;
	}

	async function handleFormatSQL(selectedSQL?: string) {
		const sqlToFormat = selectedSQL?.trim() || editorComponent?.getSelectedText?.()?.trim() || consoleContent.trim();
		if (!sqlToFormat) return;
		const conn = getSelectedConnection();
		try {
			const res = await sqlService.sqlFormat({
				sql: sqlToFormat,
				dbType: conn?.type || 'MYSQL'
			});
			if (typeof res === 'string' && res) {
				const editor = editorComponent?.getEditor?.();
				const selection = editor?.getSelection();
				// If text was selected, replace only the selection
				if (editor && selection && !selection.isEmpty()) {
					editor.executeEdits('format', [{ range: selection, text: res, forceMoveMarkers: true }]);
				} else {
					consoleContent = res;
				}
			}
		} catch {
			message.error('Failed to format SQL');
		}
	}

	// --- Editor Context Menu (custom) ---
	function editorMenuRun() {
		editorContextMenu = null;
		handleRunQuery();
	}
	function editorMenuFormat() {
		editorContextMenu = null;
		handleFormatSQL();
	}
	function editorMenuExplain() {
		editorContextMenu = null;
		const editor = editorComponent?.getEditor?.();
		const sel = editor?.getSelection();
		const text = sel ? editor?.getModel()?.getValueInRange(sel) || '' : '';
		handleExplainSQL(text || editorComponent?.getCurrentQueryAtCursor?.() || consoleContent);
	}
	function editorMenuOptimize() {
		editorContextMenu = null;
		const editor = editorComponent?.getEditor?.();
		const sel = editor?.getSelection();
		const text = sel ? editor?.getModel()?.getValueInRange(sel) || '' : '';
		handleOptimizeSQL(text || editorComponent?.getCurrentQueryAtCursor?.() || consoleContent);
	}
	function editorMenuCut() {
		const editor = editorComponent?.getEditor?.();
		if (editor) {
			editor.focus();
			editor.trigger('contextmenu', 'editor.action.clipboardCutAction', null);
		}
		editorContextMenu = null;
	}
	function editorMenuCopy() {
		const editor = editorComponent?.getEditor?.();
		if (editor) {
			editor.focus();
			editor.trigger('contextmenu', 'editor.action.clipboardCopyAction', null);
		}
		editorContextMenu = null;
	}
	async function editorMenuPaste() {
		const editor = editorComponent?.getEditor?.();
		if (editor) {
			try {
				const text = await navigator.clipboard.readText();
				editor.focus();
				editor.trigger('contextmenu', 'type', { text });
			} catch { /* clipboard permission denied */ }
		}
		editorContextMenu = null;
	}
	function editorMenuSelectAll() {
		const editor = editorComponent?.getEditor?.();
		if (editor) {
			editor.focus();
			const model = editor.getModel();
			if (model) {
				const fullRange = model.getFullModelRange();
				editor.setSelection(fullRange);
			}
		}
		editorContextMenu = null;
	}

	// --- AI: Explain SQL (floating widget) ---
	let lastExplainSql = '';

	function showExplainWidget() {
		const editor = editorComponent?.getEditor?.();
		if (!editor || !explainWidgetDomNode) return;

		// Remove previous widget
		if (contentWidgetRef) {
			try { editor.removeContentWidget(contentWidgetRef); } catch {}
			contentWidgetRef = null;
		}

		const selection = editor.getSelection();
		const position = selection && !selection.isEmpty()
			? { lineNumber: selection.endLineNumber, column: selection.endColumn }
			: editor.getPosition();

		if (!position) return;

		const widget = {
			getId: () => 'explain.widget',
			getDomNode: () => explainWidgetDomNode!,
			getPosition: () => ({
				position,
				preference: [1, 2] // 1: BELOW, 2: ABOVE
			})
		};

		editor.addContentWidget(widget);
		contentWidgetRef = widget;
	}

	function closeExplainWidget() {
		explainResult = { content: '', loading: false, visible: false };
		explainPosition = null;
		const editor = editorComponent?.getEditor?.();
		if (editor && contentWidgetRef) {
			try { editor.removeContentWidget(contentWidgetRef); } catch {}
			contentWidgetRef = null;
		}
	}

	async function handleExplainSQL(sql: string) {
		if (!sql.trim()) return;
		lastExplainSql = sql;
		explainResult = { content: '', loading: true, visible: true };

		// Calculate position in viewport coordinates, clamped to stay visible
		const editor = editorComponent?.getEditor?.();
		if (editor) {
			const editorDom = editor.getDomNode();
			const editorRect = editorDom?.getBoundingClientRect();
			const selection = editor.getSelection();
			const pos = selection && !selection.isEmpty()
				? editor.getScrolledVisiblePosition({ lineNumber: selection.endLineNumber, column: selection.endColumn })
				: editor.getScrolledVisiblePosition(editor.getPosition());
			if (pos && editorRect) {
				const WIDGET_W = 500, WIDGET_H = 400;
				const rawTop = editorRect.top + pos.top + 24;
				const rawLeft = editorRect.left + Math.min(pos.left + 60, 200);
				const top = Math.max(8, Math.min(rawTop, window.innerHeight - WIDGET_H - 8));
				const left = Math.max(8, Math.min(rawLeft, window.innerWidth - WIDGET_W - 8));
				explainPosition = { top, left };
			} else {
				explainPosition = { top: Math.max(8, window.innerHeight - 408), left: Math.max(8, window.innerWidth - 508) };
			}
		}

		// Show the widget in loading state
		requestAnimationFrame(() => showExplainWidget());

		try {
			const token = typeof localStorage !== 'undefined' ? localStorage.getItem('Inquery') || '' : '';
			const response = await fetch(`${getBaseURL()}/api/ai/interpret`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json', ...(token && { Inquery: token }) },
				body: JSON.stringify({
					originalQuery: `Explain this SQL query in detail. Describe what it does step by step, what tables and columns are involved, any joins, filters, aggregations, and the expected output. Do NOT say "no data" - this is a query explanation task, not a data analysis task. ${getLanguageInstruction()}`,
					sqlResult: null,
					generatedSql: sql
				})
			});
			const result = await response.json();
			explainResult = { content: result.data?.interpretation || 'Unable to explain the SQL.', loading: false, visible: true };
		} catch (err: any) {
			explainResult = { content: 'Error: ' + (err.message || 'Failed to explain SQL'), loading: false, visible: true };
		}
	}

	// --- AI: Optimize SQL (with DDL context, diff view) ---
	let isOptimizeLoading = $state(false);
	async function handleOptimizeSQL(sql: string) {
		if (!sql.trim()) return;
		if (!selectedConnectionId) { message.warning('Please select a connection first'); return; }
		isOptimizeLoading = true;

		try {
			const token = typeof localStorage !== 'undefined' ? localStorage.getItem('Inquery') || '' : '';
			const conn = getSelectedConnection();

			// Extract table names from SQL for DDL context (preserve full qualified names like DB.SCHEMA.TABLE)
			let schemaContext = '';
			// Collect CTE names to exclude them from DDL lookup (they are not real tables)
			// Handles: WITH cte1 AS (...), cte2 AS (...), WITH RECURSIVE cte AS (...)
			const cteNames = new Set<string>();
			{
				// Match first CTE after WITH (also handles WITH RECURSIVE)
				const withPattern = /WITH\s+(?:RECURSIVE\s+)?([\w]+)\s+AS\s*\(/gi;
				let cteMatch;
				while ((cteMatch = withPattern.exec(sql)) !== null) {
					cteNames.add(cteMatch[1].toUpperCase());
				}
				// Match subsequent CTEs after comma: , cte_name AS (
				const commaCtePattern = /,\s*([\w]+)\s+AS\s*\(/gi;
				while ((cteMatch = commaCtePattern.exec(sql)) !== null) {
					cteNames.add(cteMatch[1].toUpperCase());
				}
			}
			// SQL keywords that can appear after FROM/JOIN but are not table names
			const sqlKeywords = new Set(['UNNEST', 'LATERAL', 'VALUES', 'TABLE', 'GENERATE_SERIES', 'DUAL']);
			const tablePattern = /(?:FROM|JOIN)\s+([\w]+(?:\.[\w]+){0,2})/gi;
			const tableNames: string[] = [];
			{
				const seen = new Set<string>();
				let match;
				while ((match = tablePattern.exec(sql)) !== null) {
					const name = match[1];
					if (cteNames.has(name.toUpperCase())) continue; // skip CTE references
					if (sqlKeywords.has(name.toUpperCase())) continue; // skip SQL keywords
					if (!seen.has(name.toUpperCase())) {
						seen.add(name.toUpperCase());
						tableNames.push(name);
					}
				}
			}
			if (tableNames.length > 0 && selectedConnectionId && selectedDatabaseName) {
				const ddlPromises = tableNames.slice(0, 5).map(async (tbl) => {
					try {
						const parts = tbl.split('.');
						let parsedDb = selectedDatabaseName;
						let parsedSchema = selectedSchemaName;
						let parsedTable = tbl;
						if (parts.length === 3) {
							parsedDb = parts[0];
							parsedSchema = parts[1];
							parsedTable = parts[2];
						} else if (parts.length === 2) {
							parsedSchema = parts[0];
							parsedTable = parts[1];
						}
						const ddl = await sqlService.exportCreateTableSql({
							dataSourceId: selectedConnectionId!,
							databaseName: parsedDb || '',
							schemaName: parsedSchema,
							name: parsedTable
						});
						return ddl ? `-- ${tbl}\n${ddl}` : null;
					} catch { return null; }
				});
				const ddlResults = (await Promise.all(ddlPromises)).filter(Boolean);
				if (ddlResults.length > 0) schemaContext = ddlResults.join('\n\n');
			}

			const response = await fetch(`${getBaseURL()}/api/ai/optimize`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json', ...(token && { Inquery: token }) },
				body: JSON.stringify({
					sql,
					databaseType: conn?.type || 'SQL',
					...(schemaContext && { schemaContext })
				})
			});

			if (!response.ok) {
				throw new Error(`Server returned ${response.status}`);
			}

			const result = await response.json();
			let optimizedSQL = result.data?.optimizedSql || '';

			if (!optimizedSQL.trim()) {
				isOptimizeLoading = false;
				return;
			}

			// Extract SQL from response - LLM may include explanation text
			const codeBlockMatch = optimizedSQL.match(/```(?:sql)?\s*\n([\s\S]*?)```/i);
			if (codeBlockMatch) {
				optimizedSQL = codeBlockMatch[1].trim();
			} else {
				const sqlKeywords = /^(SELECT|WITH|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|MERGE)\b/im;
				const match = optimizedSQL.match(sqlKeywords);
				if (match && match.index !== undefined && match.index > 0) {
					optimizedSQL = optimizedSQL.substring(match.index).trim();
				}
				const lastSemicolon = optimizedSQL.lastIndexOf(';');
				if (lastSemicolon !== -1) {
					optimizedSQL = optimizedSQL.substring(0, lastSemicolon + 1).trim();
				}
			}

			// Show in diff view (full-document diff for optimize)
			const originalContent = consoleContent;
			// Replace the original SQL within the full document
			const sqlIndex = originalContent.indexOf(sql);
			let modifiedContent: string;
			let insertLineNumber: number;
			if (sqlIndex !== -1) {
				modifiedContent = originalContent.substring(0, sqlIndex) + optimizedSQL + originalContent.substring(sqlIndex + sql.length);
				insertLineNumber = (originalContent.substring(0, sqlIndex).match(/\n/g) || []).length + 1;
			} else {
				modifiedContent = optimizedSQL;
				insertLineNumber = 1;
			}
			pendingGeneration = { originalContent, selectionRange: null, insertLineNumber };
			diffOriginal = originalContent;
			diffModified = modifiedContent;
			showDiffView = true;
		} catch (err: any) {
			console.error('Failed to optimize SQL:', err);
			message.error('Failed to optimize SQL');
		} finally {
			isOptimizeLoading = false;
		}
	}

	function handleCopyExplain() {
		if (explainResult.content) navigator.clipboard.writeText(explainResult.content);
	}

	function handleRegenerateExplain() {
		if (lastExplainSql) handleExplainSQL(lastExplainSql);
	}

	function handleExplainDragStart(e: MouseEvent) {
		if (!explainPosition) return;
		e.preventDefault();
		isDraggingExplain = true;
		dragOffset = { x: e.clientX - explainPosition.left, y: e.clientY - explainPosition.top };

		const onMouseMove = (ev: MouseEvent) => {
			const WIDGET_W = 500, WIDGET_H = 400;
			const top = Math.max(0, Math.min(ev.clientY - dragOffset.y, window.innerHeight - WIDGET_H));
			const left = Math.max(0, Math.min(ev.clientX - dragOffset.x, window.innerWidth - WIDGET_W));
			explainPosition = { top, left };
		};
		const onMouseUp = () => {
			isDraggingExplain = false;
			document.removeEventListener('mousemove', onMouseMove);
			document.removeEventListener('mouseup', onMouseUp);
			document.body.style.cursor = '';
			document.body.style.userSelect = '';
		};
		document.body.style.cursor = 'grabbing';
		document.body.style.userSelect = 'none';
		document.addEventListener('mousemove', onMouseMove);
		document.addEventListener('mouseup', onMouseUp);
	}

	// --- DiffEditor lifecycle ---
	function initDiffEditor() {
		if (!diffContainerEl) return;
		import('monaco-editor').then((monaco) => {
			import('$lib/components/MonacoEditor/editorThemes').then(({ registerCustomThemes }) => {
				import('$lib/stores/editorSetting.svelte').then(({ getEditorSettingStore }) => {
					if (diffEditorInstance) { diffEditorInstance.dispose(); diffEditorInstance = null; }
					registerCustomThemes(monaco);

					// Apply current editor theme
					const currentTheme = getEditorSettingStore().settings.editorTheme;
					if (currentTheme) monaco.editor.setTheme(currentTheme);

					const diffEditor = monaco.editor.createDiffEditor(diffContainerEl!, {
						readOnly: true,
						renderSideBySide: window.innerWidth > 960,
						enableSplitViewResizing: true,
						originalEditable: false,
						automaticLayout: true,
						scrollBeyondLastLine: false,
						minimap: { enabled: false },
						fontSize: 13,
						lineNumbers: 'on',
						folding: true,
						renderOverviewRuler: false
					});

					const originalModel = monaco.editor.createModel(diffOriginal, 'sql');
					const modifiedModel = monaco.editor.createModel(diffModified, 'sql');
					diffEditor.setModel({ original: originalModel, modified: modifiedModel });
					diffEditorInstance = diffEditor;


					// Scroll to the exact line where SQL was inserted
					// Double rAF: first frame lets Monaco finish layout, second applies scroll
					requestAnimationFrame(() => {
						requestAnimationFrame(() => {
							try {
								diffEditor.layout();
								const targetLine = pendingGeneration?.insertLineNumber ?? 1;
								const originalEd = diffEditor.getOriginalEditor();
								const modifiedEd = diffEditor.getModifiedEditor();
								for (const ed of [originalEd, modifiedEd]) {
									const scrollTop = (ed as any).getTopForLineNumber?.(targetLine);
									if (typeof scrollTop === 'number') {
										(ed as any).setScrollTop(scrollTop);
									} else {
										ed.revealLineInCenter(targetLine);
									}
									ed.setSelection({ startLineNumber: targetLine, startColumn: 1, endLineNumber: targetLine, endColumn: 1 });
								}
								modifiedEd.focus();
							} catch { /* best effort */ }
						});
					});
				});
			});
		});
	}

	function disposeDiffEditor() {
		if (diffEditorInstance) { diffEditorInstance.dispose(); diffEditorInstance = null; }
	}

	// Initialize diff editor when showDiffView becomes true
	$effect(() => {
		if (showDiffView && diffContainerEl) {
			initDiffEditor();
		} else if (!showDiffView) {
			disposeDiffEditor();
		}
	});

	// --- Query Estimator ---
	function formatEstimatedCost(cost: number | undefined, databaseType?: string): string {
		if (cost === undefined || cost === null) return '-';
		const dbType = (databaseType || '').toLowerCase();
		if (dbType.includes('bigquery') || dbType.includes('snowflake')) {
			return `$${cost.toFixed(2)}`;
		}
		return `${formatNumber(cost)} units`;
	}

	async function handleQueryEstimate() {
		const sql = editorComponent?.getSelectedText?.() || editorComponent?.getCurrentQueryAtCursor?.() || consoleContent;
		if (!sql?.trim()) { message.warning('No SQL to estimate'); return; }
		if (!selectedConnectionId) { message.warning('Please select a connection first'); return; }

		const conn = getSelectedConnection();
		const dbType = (conn?.type || '').toLowerCase();
		const cleanedSql = sql.trim().replace(/;+$/, '');

		queryEstimatorLoading = true;
		showQueryEstimator = true;
		aiAnalysisContent = '';
		aiAnalysisVisible = false;
		aiAnalysisSql = sql;
		queryEstimatorData = null;

		// BigQuery: use DRY_RUN API for pre-execution cost estimation
		if (dbType.includes('bigquery')) {
			try {
				const dryRunResponse = await sqlService.bigQueryDryRun({
					sql: cleanedSql,
					dataSourceId: selectedConnectionId,
					databaseName: selectedDatabaseName || '',
					schemaName: selectedSchemaName || ''
				});

				if (dryRunResponse.success) {
					const bytesProcessed = dryRunResponse.totalBytesProcessed || 0;
					const estimatedCost = dryRunResponse.estimatedCostUSD || 0;
					const cacheHit = dryRunResponse.cacheHit || false;

					queryEstimatorData = {
						metrics: {
							estimatedCost: estimatedCost,
							estimatedMemoryGB: bytesProcessed / (1024 * 1024 * 1024)
						},
						warnings: cacheHit
							? [{ type: 'info' as const, message: 'Query will be served from cache', detail: 'No bytes will be processed' }]
							: bytesProcessed > 10 * 1024 * 1024 * 1024
							? [{ type: 'warning' as const, message: 'Large data scan', detail: `${(bytesProcessed / (1024 * 1024 * 1024)).toFixed(2)} GB will be processed` }]
							: [],
						plan: null,
						rawPlan:
							`BigQuery DRY_RUN Estimation (Pre-execution)\n\n` +
							`Bytes to Process: ${(bytesProcessed / (1024 * 1024)).toFixed(2)} MB\n` +
							`Estimated Cost: $${estimatedCost.toFixed(4)}\n` +
							`Cache Hit: ${cacheHit}\n\n` +
							`✅ This is a pre-execution estimate.\nThe query has not been executed yet.`
					};
				} else {
					queryEstimatorData = {
						metrics: {},
						warnings: [{ type: 'error' as const, message: 'DRY_RUN failed', detail: dryRunResponse.error || 'Unknown error' }],
						plan: null,
						rawPlan: `BigQuery DRY_RUN Error\n\nError: ${dryRunResponse.error || 'Unknown error'}` +
							(dryRunResponse.errorReason ? `\nReason: ${dryRunResponse.errorReason}` : '')
					};
				}
			} catch (error: any) {
				queryEstimatorData = {
					metrics: {},
					warnings: [{ type: 'error' as const, message: 'Failed to estimate query', detail: error.message }],
					plan: null,
					rawPlan: `Error: ${error.message}\n\nBigQuery DRY_RUN failed.`
				};
			} finally {
				queryEstimatorLoading = false;
			}
			return;
		}

		let explainSql = '';
		if (dbType.includes('snowflake')) {
			explainSql = `EXPLAIN USING JSON ${cleanedSql}`;
		} else if (dbType.includes('postgres')) {
			explainSql = `EXPLAIN (COSTS, FORMAT JSON) ${cleanedSql}`;
		} else if (dbType.includes('mysql')) {
			explainSql = `EXPLAIN FORMAT=JSON ${cleanedSql}`;
		} else {
			explainSql = `EXPLAIN ${cleanedSql}`;
		}

		try {
			// For Snowflake: fetch warehouse size for accurate cost estimation
			let warehouseSize: string | undefined;
			if (dbType.includes('snowflake') && selectedConnectionId) {
				try {
					const details = await connectionService.getDetails({ id: selectedConnectionId }) as any;
					const warehouseInfo = details?.extendInfo?.find(
						(item: any) => item.key?.toLowerCase() === 'warehouse'
					);
					warehouseSize = warehouseInfo?.value || details?.ssh?.warehouseSize || details?.warehouseSize;
				} catch { /* ignore - will use default 'medium' */ }
			}

			const response = await sqlService.executeSql({
				dataSourceId: selectedConnectionId,
				databaseName: selectedDatabaseName,
				schemaName: selectedSchemaName,
				sql: explainSql
			});

			const results = Array.isArray(response) ? response : [response];
			if (results.length > 0 && results[0]) {
				const result = results[0] as any;

				if (result.dataList && result.dataList.length > 0) {
					try {
						const firstRow = result.dataList[0];
						let jsonStr = '';
						if (Array.isArray(firstRow)) {
							const candidate = firstRow.find((c: any) => typeof c === 'string' && (c.startsWith('{') || c.startsWith('[')));
							jsonStr = candidate || JSON.stringify(firstRow);
						} else if (typeof firstRow === 'string' && (firstRow.startsWith('{') || firstRow.startsWith('['))) {
							jsonStr = firstRow;
						} else {
							jsonStr = JSON.stringify(firstRow);
						}
						const parsed = JSON.parse(jsonStr);
						queryEstimatorData = parseExplainResult(parsed, conn?.type || '', warehouseSize ? { warehouseSize } : undefined);
					} catch {
						// Non-JSON: fall back to raw text parsing
						const rawText = result.dataList.map((row: any) =>
							Array.isArray(row) ? row.join('\t') : String(row)
						).join('\n');
						queryEstimatorData = parseRawExplain(rawText);
					}
				} else if (result.message || result.description) {
					queryEstimatorData = parseRawExplain(result.message || result.description);
				} else {
					queryEstimatorData = { metrics: {}, warnings: [], plan: null, rawPlan: 'No execution plan returned' };
				}
			}
		} catch (err: any) {
			queryEstimatorData = {
				metrics: {},
				warnings: [{ type: 'error', message: 'Estimation failed', detail: err.message }],
				plan: null,
				rawPlan: `Error: ${err.message || 'Failed to estimate query'}`
			};
		} finally {
			queryEstimatorLoading = false;
		}
	}

	// --- AI Plan Analysis ---
	async function handleAIAnalysis() {
		if (!queryEstimatorData || aiAnalysisLoading) return;

		const sql = aiAnalysisSql || editorComponent?.getSelectedText?.() || editorComponent?.getCurrentQueryAtCursor?.() || consoleContent;
		if (!sql?.trim()) return;

		const conn = getSelectedConnection();
		const dbType = conn?.type || '';

		aiAnalysisLoading = true;
		aiAnalysisVisible = true;
		aiAnalysisContent = '';

		try {
			const metricsStr = JSON.stringify(queryEstimatorData.metrics, null, 2);
			const warningsStr = queryEstimatorData.warnings.length > 0
				? queryEstimatorData.warnings.map(w => `[${w.type.toUpperCase()}] ${w.message}${w.detail ? ': ' + w.detail : ''}`).join('\n')
				: '';
			const token = localStorage.getItem('Inquery');
			const headers: Record<string, string> = { 'Content-Type': 'application/json' };
			if (token) headers.Inquery = token;

			const response = await fetch(`${getBaseURL()}/api/ai/analyze-plan`, {
				method: 'POST',
				headers,
				body: JSON.stringify({
					sql: sql.trim(),
					databaseType: dbType,
					executionPlan: queryEstimatorData.rawPlan || '',
					metrics: metricsStr,
					warnings: warningsStr,
					language: currentLang
				})
			});

			if (!response.ok) {
				aiAnalysisContent = `Server returned ${response.status}. Please rebuild and restart the backend server.`;
				return;
			}

			const result = await response.json();
			if (result.errorCode) {
				aiAnalysisContent = `Error: ${result.errorMessage || result.errorCode}`;
			} else {
				aiAnalysisContent = result.data?.analysis || 'No analysis returned from AI.';
			}
		} catch (error: any) {
			aiAnalysisContent = `Analysis failed: ${error.message || 'Unknown error'}`;
		} finally {
			aiAnalysisLoading = false;
		}
	}

	// --- Inline AI Edit ---
	const inlineEditPlaceholders = ['Make a quick edit...', 'Format this better...', 'Add a WHERE clause...', 'Optimize this query...'];
	let inlineEditPlaceholderIdx = $state(0);

	$effect(() => {
		if (inlineEditOpen && !inlineEditValue) {
			const interval = setInterval(() => {
				inlineEditPlaceholderIdx = (inlineEditPlaceholderIdx + 1) % inlineEditPlaceholders.length;
			}, 1000);
			return () => clearInterval(interval);
		} else {
			inlineEditPlaceholderIdx = 0;
		}
	});

	function openInlineEdit() {
		if (!selectionInfo) return;
		inlineEditOpen = true;
		const editor = editorComponent?.getEditor?.();
		// Save selection range before editor loses focus
		const sel = editor?.getSelection?.();
		savedInlineEditSelectionRange = (sel && !sel.isEmpty())
			? { startLineNumber: sel.startLineNumber, startColumn: sel.startColumn, endLineNumber: sel.endLineNumber, endColumn: sel.endColumn }
			: null;
		inlineEditLeft = editor?.getLayoutInfo?.()?.contentLeft ?? 44;
		editorComponent?.addViewZone?.(selectionInfo.lineNumber - 1, INLINE_EDIT_ZONE_HEIGHT);
		inlineEditTop = editorComponent?.getLineTop?.(selectionInfo.lineNumber) - INLINE_EDIT_ZONE_HEIGHT;
		scrollDisposer = editorComponent?.onDidScroll?.(() => {
			if (selectionInfo) {
				inlineEditTop = editorComponent?.getLineTop?.(selectionInfo.lineNumber) - INLINE_EDIT_ZONE_HEIGHT;
			}
		});
	}

	function closeInlineEdit(skipFocus = false) {
		inlineEditOpen = false;
		inlineEditValue = '';
		inlineEditLoading = false;
		editorComponent?.removeViewZone?.();
		scrollDisposer?.dispose();
		scrollDisposer = null;
		if (!skipFocus) editorComponent?.focus?.();
	}

	async function submitInlineEdit() {
		if (!inlineEditValue.trim() || inlineEditLoading) return;
		const prompt = inlineEditValue.trim();
		const capturedText = selectionInfo?.text || '';
		const capturedRange = savedInlineEditSelectionRange;
		savedInlineEditSelectionRange = null;
		// Skip editor focus to prevent Korean IME commit from overwriting editor content
		closeInlineEdit(true);
		// Pass pre-captured selection directly to avoid IME overwrite issues
		await handleGenerateSQL(prompt, capturedText, capturedRange);
	}

	function handleInlineEditKeyDown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			closeInlineEdit();
		} else if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			submitInlineEdit();
		}
	}

	// --- AI Generate SQL ---
	function handleAiInputKeyDown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			isAiInputVisible = false;
			aiInputValue = '';
		} else if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			handleGenerateSQL();
		}
	}

	/** Collect excluded tables from Data Catalog localStorage (activeTables) */
	function getExcludedTables(): string {
		if (typeof localStorage === 'undefined' || !selectedDatabaseName) return '';
		try {
			const raw = localStorage.getItem('data-catalog-active-tables');
			if (!raw) return '';
			const stored = JSON.parse(raw);
			const activeTables: Record<string, boolean> = stored?.state?.activeTables || {};
			const dbPrefix = `${selectedConnectionId}:${selectedDatabaseName}`.toLowerCase();
			const prefixWithDot = `${dbPrefix}.`;
			const excluded = Object.entries(activeTables)
				.filter(([key, isActive]) => !isActive && key.startsWith(prefixWithDot))
				.map(([key]) => {
					const remainder = key.toLowerCase().slice(prefixWithDot.length);
					const parts = remainder.split('.');
					if (parts.length >= 2) return `${parts[0]}.${parts.slice(1).join('.')}`;
					return '';
				})
				.filter(Boolean);
			return excluded.length > 0 ? JSON.stringify(excluded) : '';
		} catch { return ''; }
	}

	async function handleGenerateSQL(
		directPrompt?: string,
		preSelectedText?: string,
		preSelectedRange?: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number } | null
	) {
		const prompt = directPrompt || aiInputValue.trim();
		if (!prompt || isAiGenerating) return;
		if (!selectedConnectionId) { message.warning('Please select a connection first'); return; }
		isAiGenerating = true;

		// Save original content and selection for accept/reject
		const originalContent = consoleContent;
		const editor = editorComponent?.getEditor?.();
		const selection = editor?.getSelection?.();
		// Use pre-captured selection (from inline edit) to avoid IME overwrite issues
		const selectedText = preSelectedText !== undefined
			? preSelectedText
			: ((selection && !selection.isEmpty()) ? (editor?.getModel?.()?.getValueInRange(selection) || '') : '');
		const selectionRange = preSelectedRange !== undefined
			? preSelectedRange
			: ((selection && !selection.isEmpty())
				? { startLineNumber: selection.startLineNumber, startColumn: selection.startColumn, endLineNumber: selection.endLineNumber, endColumn: selection.endColumn }
				: null);

		const messageWithContext = selectedText
			? `The user has selected the following SQL in their editor and is asking about it:\n\`\`\`sql\n${selectedText}\n\`\`\`\n\nUser's request: ${prompt}`
			: prompt;

		// Parse table names from selected SQL for targeted schema search
		const parsedTableNames: string[] = [];
		if (selectedText) {
			const fromMatches = selectedText.matchAll(/FROM\s+([^\s,(]+(?:\s*,\s*[^\s,(]+)*)/gi);
			for (const m of fromMatches) {
				m[1].split(',').forEach((t: string) => {
					const name = t.trim().split(/\s+/)[0].replace(/[`"[\]]/g, '');
					if (name && !/^(where|group|order|having|limit|union|select|set|values)$/i.test(name)) {
						parsedTableNames.push(name);
					}
				});
			}
			const joinRegex = /JOIN\s+(\S+)/gi;
			let jm;
			while ((jm = joinRegex.exec(selectedText)) !== null) {
				const name = jm[1].replace(/[`"[\]]/g, '');
				if (name && !parsedTableNames.includes(name)) parsedTableNames.push(name);
			}
		}

		const excludedTablesJson = getExcludedTables();
		const uid = `workspace_${selectedConnectionId}_${Date.now()}`;

		// Build SSE URL — AUTO mode uses Agent API, otherwise Chat API
		const isAutoMode = true; // Workspace always uses AUTO mode (agent)
		let eventSourceUrl: string;
		const token = localStorage.getItem('Inquery') ?? '';

		if (isAutoMode) {
			const sseParams = new URLSearchParams({
				message: messageWithContext,
				conversationId: uid,
				dataSourceId: String(selectedConnectionId),
				...(selectedDatabaseName && { databaseName: selectedDatabaseName }),
				...(selectedSchemaName && { schemaName: selectedSchemaName }),
				executeQuery: 'false',
				agentMode: 'deep',
				queryType: 'sql',
				...(excludedTablesJson && { excludedTables: excludedTablesJson }),
				...(token && { token })
			});
			parsedTableNames.forEach((name) => sseParams.append('tableNames', name));
			eventSourceUrl = `${getBaseURL()}/api/ai/agent/chat/stream?${sseParams.toString()}`;
		} else {
			const sseParams = new URLSearchParams({
				message: messageWithContext,
				promptType: 'NL_2_SQL',
				dataSourceId: String(selectedConnectionId),
				...(selectedDatabaseName && { databaseName: selectedDatabaseName }),
				...(selectedSchemaName && { schemaName: selectedSchemaName }),
				...(excludedTablesJson && { excludedTables: excludedTablesJson }),
				...(token && { token })
			});
			parsedTableNames.forEach((name) => sseParams.append('tableNames', name));
			eventSourceUrl = `${getBaseURL()}/api/ai/chat?${sseParams.toString()}&uid=${uid}`;
		}

		const eventSource = new EventSource(eventSourceUrl);
		let generatedSql = '';
		let isStreamCompleted = false;

		const handleStreamComplete = () => {
			if (isStreamCompleted) return;
			isStreamCompleted = true;

			if (generatedSql.trim()) {
				let cleanedSql = generatedSql.trim();
				const codeBlockMatch = cleanedSql.match(/```(?:sql)?\s*\n([\s\S]*?)```/i);
				if (codeBlockMatch) {
					cleanedSql = codeBlockMatch[1].trim();
				} else {
					cleanedSql = cleanedSql.replace(/^```(?:sql)?\s*/i, '').replace(/```\s*$/i, '').trim();
				}
				if (cleanedSql && !cleanedSql.endsWith(';')) cleanedSql += ';';

				let modifiedContent: string;
				let insertLineNumber: number = 1;
				if (selectionRange && editor) {
					const model = editor.getModel?.();
					if (model) {
						const startOffset = model.getOffsetAt({ lineNumber: selectionRange.startLineNumber, column: selectionRange.startColumn });
						const endOffset = model.getOffsetAt({ lineNumber: selectionRange.endLineNumber, column: selectionRange.endColumn });
						modifiedContent = originalContent.substring(0, startOffset) + cleanedSql + originalContent.substring(endOffset);
					} else {
						modifiedContent = cleanedSql;
					}
					insertLineNumber = selectionRange.startLineNumber;
				} else {
					const position = editor?.getPosition?.();
					if (position && editor?.getModel?.()) {
						const model = editor.getModel();
						const cursorOffset = model.getOffsetAt(position);
						const insertText = originalContent.trim() ? `\n\n${cleanedSql}` : cleanedSql;
						modifiedContent = originalContent.substring(0, cursorOffset) + insertText + originalContent.substring(cursorOffset);
						// +2 for the prepended \n\n when there is existing content
						insertLineNumber = position.lineNumber + (originalContent.trim() ? 2 : 0);
					} else {
						modifiedContent = originalContent.trim() ? `${originalContent}\n\n${cleanedSql}` : cleanedSql;
						insertLineNumber = originalContent.trim()
							? (originalContent.match(/\n/g) || []).length + 3
							: 1;
					}
				}

				pendingGeneration = { originalContent, selectionRange, insertLineNumber };
				diffOriginal = originalContent;
				diffModified = modifiedContent;
				showDiffView = true;
			} else {
				message.warning('No SQL generated');
			}

			isAiGenerating = false;
			aiInputValue = '';
			isAiInputVisible = false;
		};

		// Store ref for cancellation
		aiStreamAbortController = { abort: () => { eventSource.close(); } } as any;

		// Named event listeners (Agent API)
		eventSource.addEventListener('thinking', () => {
			// Thinking phase — loading message already cycling
		});

		eventSource.addEventListener('content', (e: any) => {
			try {
				const data = e.data;
				if (data && !data.startsWith('{')) {
					// Real-time text — could be displayed if needed
				}
			} catch { /* ignore */ }
		});

		eventSource.addEventListener('response', (e: any) => {
			try {
				const result = JSON.parse(e.data);
				if (result.generatedSql) {
					generatedSql = result.generatedSql;
				}
			} catch { /* ignore */ }
		});

		eventSource.addEventListener('done', () => {
			eventSource.close();
			handleStreamComplete();
		});

		// Legacy onmessage handler (Chat API / MANUAL mode fallback)
		eventSource.onmessage = (event) => {
			if (isAutoMode) return;
			try {
				const data = event.data;
				if (data === '[DONE]') {
					eventSource.close();
					handleStreamComplete();
					return;
				}
				if (data) {
					try {
						const parsed = JSON.parse(data);
						if (parsed.content) generatedSql += parsed.content;
					} catch {
						generatedSql += data;
					}
				}
			} catch { /* ignore */ }
		};

		// Error event from server
		eventSource.addEventListener('error', (event: any) => {
			if (generatedSql && generatedSql.trim().length > 0) {
				eventSource.close();
				handleStreamComplete();
				return;
			}
			eventSource.close();
			isAiGenerating = false;
			aiInputValue = '';
			isAiInputVisible = false;
			pendingGeneration = null;
			clearDecorations();

			let errorMessage = 'Failed to generate SQL';
			if (event.data) {
				try {
					const errorData = JSON.parse(event.data);
					errorMessage = errorData.message || errorMessage;
				} catch {
					errorMessage = event.data;
				}
			}
			message.error(errorMessage);
		});

		// Connection error or close
		eventSource.onerror = () => {
			if (eventSource.readyState === EventSource.CLOSED) {
				handleStreamComplete();
				return;
			}
			eventSource.close();
			if (!isStreamCompleted) {
				isAiGenerating = false;
				aiInputValue = '';
				isAiInputVisible = false;
				pendingGeneration = null;
				clearDecorations();
				message.error('Connection failed. Please try again.');
			}
		};
	}

	function cancelAiGeneration() {
		aiStreamAbortController?.abort();
		isAiGenerating = false;
		aiInputValue = '';
	}

	function handleAcceptDiff() {
		if (diffModified) {
			const originalContent = pendingGeneration?.originalContent || diffOriginal;
			// Capture before pendingGeneration is nulled out below
			const scrollToLine = pendingGeneration?.insertLineNumber ?? pendingGeneration?.selectionRange?.startLineNumber ?? 1;
			consoleContent = diffModified;
			// Programmatic content change doesn't trigger onchange, so save explicitly
			if (ws.activeConsoleId) {
				debouncedAutoSave(ws.activeConsoleId, diffModified);
			}

			// Scroll to inserted SQL position after accept
			requestAnimationFrame(() => {
				const editor = editorComponent?.getEditor?.();
				if (editor) {
					const scrollTop = (editor as any).getTopForLineNumber?.(scrollToLine);
					if (typeof scrollTop === 'number') {
						(editor as any).setScrollTop(scrollTop);
					} else {
						editor.revealLineInCenter(scrollToLine);
					}
					// Place cursor without selection to clear drag highlight
					editor.setPosition({ lineNumber: scrollToLine, column: 1 });
					editor.setSelection({ startLineNumber: scrollToLine, startColumn: 1, endLineNumber: scrollToLine, endColumn: 1 });
					editor.focus();
				}
			});
		}
		disposeDiffEditor();
		showDiffView = false;
		diffOriginal = '';
		diffModified = '';
		pendingGeneration = null;
		message.success('SQL accepted');
	}

	function handleRejectDiff() {
		// Restore original content if we have pending generation
		if (pendingGeneration?.originalContent) {
			consoleContent = pendingGeneration.originalContent;
		}
		clearDecorations();
		disposeDiffEditor();
		showDiffView = false;
		diffOriginal = '';
		diffModified = '';
		pendingGeneration = null;
		message.info('Changes rejected');
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape' && isAiGenerating) {
			cancelAiGeneration();
			return;
		}
		if (e.key === 'Escape' && showQueryEstimator) {
			showQueryEstimator = false;
			return;
		}
		if (e.key === 'Escape' && explainResult.visible) {
			closeExplainWidget();
			return;
		}
		if (e.key === 'Escape' && showDiffView) {
			handleRejectDiff();
			return;
		}
		if (matchesShortcut(e, 'run-query')) {
			e.preventDefault();
			handleRunQuery();
		}
		if (matchesShortcut(e, 'save-console')) {
			e.preventDefault();
			handleSaveConsole();
		}
		if (matchesShortcut(e, 'new-console')) {
			e.preventDefault();
			handleNewConsole();
		}
	}

	function handleResultsResize(e: MouseEvent) {
		isResizingResults = true;
		const startY = e.clientY;
		const startH = resultsPanelHeight;
		function onMouseMove(ev: MouseEvent) {
			const diff = startY - ev.clientY;
			resultsPanelHeight = Math.max(100, Math.min(500, startH + diff));
		}
		function onMouseUp() {
			isResizingResults = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
			if (typeof localStorage !== 'undefined') {
				localStorage.setItem('workspace-results-height', String(resultsPanelHeight));
			}
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	function handleHistoryResize(e: MouseEvent) {
		isResizingHistory = true;
		const startY = e.clientY;
		const startH = historyPanelHeight;
		function onMouseMove(ev: MouseEvent) {
			const diff = startY - ev.clientY;
			historyPanelHeight = Math.max(80, Math.min(400, startH + diff));
		}
		function onMouseUp() {
			isResizingHistory = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
			if (typeof localStorage !== 'undefined') {
				localStorage.setItem('workspace-history-height', String(historyPanelHeight));
			}
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	function handleLeftResize(e: MouseEvent) {
		isResizingLeft = true;
		const startX = e.clientX;
		const startW = ws.layout.panelLeftWidth;
		function onMouseMove(ev: MouseEvent) {
			const diff = ev.clientX - startX;
			const newW = Math.max(200, Math.min(500, startW + diff));
			setPanelLeftWidth(newW);
		}
		function onMouseUp() {
			isResizingLeft = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	function handleRefreshTree() {
		if (selectedConnectionId) {
			treeData = [];
			loadDatabases(selectedConnectionId);
		}
	}

	// DDL state for right panel
	let ddlContent = $state('');
	let ddlLoading = $state(false);
	let ddlTableName = $state('');
	let ddlSubTab = $state<'schema' | 'ddl'>('schema');
	let schemaFields = $state<SchemaField[]>([]);
	let formattedDDL = $state('');
	let schemaIsBigQuery = $state(false);

	async function loadDDL(tableName: string, schemaName?: string) {
		if (!selectedConnectionId || !selectedDatabaseName) return;
		ddlLoading = true;
		ddlTableName = tableName;
		schemaFields = [];
		formattedDDL = '';
		const resolvedSchema = schemaName || selectedSchemaName;
		try {
			const [ddlRes, colsRes] = await Promise.allSettled([
				sqlService.exportCreateTableSql({
					dataSourceId: selectedConnectionId,
					databaseName: selectedDatabaseName,
					schemaName: resolvedSchema,
					name: tableName
				}),
				sqlService.getColumnList({
					dataSourceId: selectedConnectionId,
					databaseName: selectedDatabaseName,
					schemaName: resolvedSchema,
					tableName: tableName
				})
			]);

			ddlContent = ddlRes.status === 'fulfilled' && typeof ddlRes.value === 'string' ? ddlRes.value : '';
			const cols: any[] = colsRes.status === 'fulfilled' && Array.isArray(colsRes.value) ? colsRes.value : [];
			const conn = getSelectedConnection();
			const isBQ = conn?.type === 'BIGQUERY';
			schemaIsBigQuery = isBQ;

			if (isBQ && ddlContent) {
				schemaFields = parseDDLToSchema(ddlContent);
				formattedDDL = formatDDL(ddlContent);
			} else if (cols.length > 0) {
				schemaFields = columnsToSchemaFields(cols);
				formattedDDL = ddlContent;
			} else {
				schemaFields = [];
				formattedDDL = ddlContent;
			}

			ddlSubTab = schemaFields.length > 0 ? 'schema' : 'ddl';
		} catch {
			ddlContent = `-- Could not load DDL for ${tableName}`;
			formattedDDL = ddlContent;
			schemaFields = [];
		} finally {
			ddlLoading = false;
		}
	}

	function columnsToSchemaFields(cols: any[]): SchemaField[] {
		return cols.map((col: any) => {
			const colType = col.columnType || col.typeName || '';
			const upper = colType.toUpperCase();
			const isRecord = upper.includes('STRUCT') || upper.includes('RECORD');
			const nullable = col.nullable === 1 || col.nullableInt === 1;

			let mode: SchemaField['mode'] = nullable ? 'NULLABLE' : 'REQUIRED';
			let displayType = colType || 'UNKNOWN';
			let children: SchemaField[] | undefined;

			if (upper.startsWith('ARRAY<STRUCT<')) {
				mode = 'REPEATED';
				displayType = 'RECORD';
				children = parseStructToSchemaFields(colType);
			} else if (upper.startsWith('STRUCT<')) {
				displayType = 'RECORD';
				children = parseStructToSchemaFields(colType);
			} else if (isRecord && col.children && col.children.length > 0) {
				displayType = 'RECORD';
				children = columnsToSchemaFields(col.children);
			}

			const field: SchemaField = {
				name: col.name,
				type: displayType,
				mode,
				...(col.primaryKey ? { primaryKey: true } : {}),
				...(col.comment ? { comment: col.comment } : {}),
				...(col.defaultValue != null ? { defaultValue: col.defaultValue } : {}),
				...(children && children.length > 0 ? { children } : {}),
			} as SchemaField;

			return field;
		});
	}

	function parseStructToSchemaFields(colType: string): SchemaField[] {
		const parsed = parseStructFields(colType);
		return parsed.map(f => {
			const upper = f.columnType.toUpperCase();
			let displayType = f.columnType;
			let mode: SchemaField['mode'] = 'NULLABLE';
			let children: SchemaField[] | undefined;

			if (upper.startsWith('ARRAY<STRUCT<')) {
				mode = 'REPEATED';
				displayType = 'RECORD';
				children = parseStructToSchemaFields(f.columnType);
			} else if (upper.startsWith('STRUCT<')) {
				displayType = 'RECORD';
				children = parseStructToSchemaFields(f.columnType);
			}

			return { name: f.name, type: displayType, mode, ...(children ? { children } : {}) } as SchemaField;
		});
	}

	function toggleSchemaField(field: SchemaField) {
		field.expanded = !field.expanded;
		schemaFields = [...schemaFields];
	}

	function toggleRightExtend(type: 'ddl' | 'aiChat') {
		if (rightPanelExtend === type) {
			rightPanelExtend = null;
			if (ws.layout.panelRight) togglePanelRight();
		} else {
			rightPanelExtend = type;
			if (!ws.layout.panelRight) togglePanelRight();
		}
	}

	function handleRightResize(e: MouseEvent) {
		isResizingRight = true;
		const startX = e.clientX;
		const startW = ws.layout.panelRightWidth;
		function onMouseMove(ev: MouseEvent) {
			const diff = startX - ev.clientX;
			const newW = Math.max(200, Math.min(600, startW + diff));
			setPanelRightWidth(newW);
		}
		function onMouseUp() {
			isResizingRight = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	async function preloadTreeForSearch() {
		if (treeSearchPreloaded || !selectedConnectionId) return;
		treeSearchPreloaded = true;
		const conn = getSelectedConnection();
		const dsId = selectedConnectionId;

		for (const dbNode of treeData) {
			if (dbNode.children) continue;
			try {
				if (conn?.supportSchema) {
					const schemas = await connectionService.getSchemaList({ dataSourceId: dsId, databaseName: dbNode.name });
					const schemaNodes: TreeNode[] = Array.isArray(schemas) ? (schemas as any[]).map((s: any) => ({
						name: typeof s === 'string' ? s : s.name,
						type: 'schema' as const,
						children: null,
						expanded: false,
						loading: false,
						databaseName: dbNode.name
					})) : [];
				for (const schemaNode of schemaNodes) {
					try {
							const tables = await catalogService.loadTablesBySchema({ dataSourceId: dsId, databaseName: dbNode.name, schemaName: schemaNode.name });
							schemaNode.children = Array.isArray(tables) ? tables.map((t: any) => ({
								name: t.name || t,
								type: (t.type === 'VIEW' ? 'view' : 'table') as 'table' | 'view',
								databaseName: dbNode.name,
								schemaName: schemaNode.name
							})) : [];
						} catch { schemaNode.children = []; }
					}
					dbNode.children = schemaNodes;
				} else {
					const tables = await catalogService.loadTablesBySchema({ dataSourceId: dsId, databaseName: dbNode.name });
					dbNode.children = Array.isArray(tables) ? tables.map((t: any) => ({
						name: t.name || t,
						type: (t.type === 'VIEW' ? 'view' : 'table') as 'table' | 'view',
						databaseName: dbNode.name
					})) : [];
				}
			} catch { dbNode.children = []; }
		}
		treeData = [...treeData];
	}

	$effect(() => {
		if (treeSearch.length > 0 && !treeSearchPreloaded) {
			preloadTreeForSearch();
		}
	});

	function filterTreeNode(node: TreeNode, q: string): TreeNode | null {
		if (!q) return node;
		const matchSelf = node.name.toLowerCase().includes(q);
		if (node.type === 'table' || node.type === 'table_group' || node.type === 'view') return matchSelf ? node : null;
		if (!node.children) return matchSelf ? node : null;
		const filteredChildren = node.children
			.map(c => filterTreeNode(c, q))
			.filter((c): c is TreeNode => c !== null);
		if (filteredChildren.length > 0) return { ...node, children: filteredChildren, expanded: true };
		return matchSelf ? node : null;
	}

	let filteredTree = $derived(
		treeSearch
			? treeData.map(db => filterTreeNode(db, treeSearch.toLowerCase())).filter((n): n is TreeNode => n !== null)
			: treeData
	);

	// Sorted saved list
	let sortedSavedList = $derived(
		[...ws.savedConsoleList].sort((a, b) => {
			if (sortBy === 'name') return (a.name || '').localeCompare(b.name || '');
			if (sortBy === 'type') return (a.databaseType || '').localeCompare(b.databaseType || '');
			// date: sort by id descending (newest first)
			return Number(b.id) - Number(a.id);
		})
	);
</script>

<svelte:window onkeydown={handleKeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="flex h-full w-full overflow-hidden bg-background">
	<!-- Left Panel -->
	{#if ws.layout.panelLeft}
		<div class="flex h-full shrink-0 border-r border-border bg-card overflow-hidden" style="width: {ws.layout.panelLeftWidth}px;">
			<!-- Icon Strip -->
			<div class="flex flex-col items-center py-2 px-1 gap-1.5 bg-card border-r border-border w-10 shrink-0">
				<button
					class="w-7 h-7 flex items-center justify-center cursor-pointer rounded transition-all
						{ws.leftTab === 'database' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}"
					onclick={() => setLeftTab('database')}
					title="Database Structure"
				>
					<Database size={16} strokeWidth={1.5} />
				</button>
				<button
					class="w-7 h-7 flex items-center justify-center cursor-pointer rounded transition-all
						{ws.leftTab === 'workspace' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}"
					onclick={() => setLeftTab('workspace')}
					title="Workspace"
				>
					<Folder size={16} strokeWidth={1.5} />
				</button>
			</div>

			<!-- Content Area -->
			<div class="flex-1 flex flex-col overflow-hidden min-w-0">
				{#if ws.leftTab === 'database'}
					{#if connections.length === 0}
						<!-- No connections -->
						<div class="h-full flex flex-col items-center justify-center text-center px-4">
							<FolderX class="h-16 w-16 text-muted-foreground mb-4" />
							<p class="text-sm text-muted-foreground mb-2">No connections available</p>
							<a href="/connections" class="text-primary underline text-sm hover:text-primary/80">
								Create connections
							</a>
						</div>
					{:else}
						<!-- Connection Selector -->
						<Popover bind:open={showConnectionDropdown}>
							<PopoverTrigger class="flex items-center justify-between w-full cursor-pointer px-3 py-1.5 border-b border-border hover:bg-accent transition-colors">
								{#if selectedConnectionId}
									{@const conn = getSelectedConnection()}
									{#if conn}
										<div class="flex items-center gap-2 min-w-0">
											<span class="w-2 h-2 rounded-full shrink-0" style="background: #10b981"></span>
											{#if databaseMap[conn.type]}
												<img src={databaseMap[conn.type].img} alt={databaseMap[conn.type].name} class="w-4 h-4 object-contain shrink-0" />
											{:else}
												<Database size={16} class="text-muted-foreground shrink-0" />
											{/if}
											<span class="truncate text-xs">{conn.alias}</span>
										</div>
									{/if}
								{:else}
									<span class="text-sm text-muted-foreground">Select connection</span>
								{/if}
								<ChevronDown size={14} class="shrink-0 ml-2 text-muted-foreground" />
							</PopoverTrigger>
							<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] max-h-64 overflow-auto p-0">
								{#each connections as conn (conn.id)}
									<button
										class="flex items-center gap-2 w-full px-3 py-1.5 hover:bg-accent transition-colors text-left"
										onclick={() => handleConnectionChange(conn)}
									>
										<span class="w-2 h-2 rounded-full shrink-0" style="background: #10b981"></span>
										{#if databaseMap[conn.type]}
											<img src={databaseMap[conn.type].img} alt={databaseMap[conn.type].name} class="w-4 h-4 object-contain shrink-0" />
										{:else}
											<Database size={16} class="text-muted-foreground shrink-0" />
										{/if}
										<span class="truncate text-xs">{conn.alias}</span>
									</button>
								{/each}
							</PopoverContent>
						</Popover>

						<!-- Operation Line -->
						<div class="flex justify-between items-center h-[30px] px-2">
							<div class="flex items-center gap-1">
								<button
									class="flex items-center justify-center w-5 h-5 rounded cursor-pointer hover:bg-accent text-muted-foreground hover:text-foreground"
									title="Add connection"
									onclick={() => window.location.href = '/connections'}
								>
									<Plus size={15} />
								</button>
								<button
									class="flex items-center justify-center w-5 h-5 rounded cursor-pointer hover:bg-accent text-muted-foreground hover:text-foreground"
									title="Refresh"
									onclick={handleRefreshTree}
								>
									<RefreshCw size={14} />
								</button>
							</div>
						</div>

						<!-- Search -->
						<div class="px-2 py-1">
							<div class="relative flex items-center gap-1.5 rounded-md border border-input bg-background px-2 py-1">
								<Search size={14} class="text-muted-foreground shrink-0" />
								<input
									bind:value={treeSearch}
									type="text"
									placeholder="Search tables..."
									class="w-full text-xs bg-transparent focus:outline-none pr-5"
								/>
								{#if treeSearch}
									<button
										class="absolute right-1.5 top-1/2 -translate-y-1/2 flex items-center justify-center w-4 h-4 rounded-full text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
										onclick={() => { treeSearch = ''; }}
									>
										<X size={12} />
									</button>
								{/if}
							</div>
						</div>

						<!-- Tree Node Renderer -->
						{#snippet treeNodeRow(node: TreeNode, depth: number, parentDb: string | undefined)}
							{@const isFocused = focusedNodeId === getNodeId(node)}
							{@const isLeaf = (node.isLeaf && node.type !== 'nested_column') || node.type === 'column' || node.type === 'key' || node.type === 'index' || node.type === 'view'}
							<button
								class="flex items-center overflow-hidden rounded cursor-pointer select-none pr-1.5 h-[26px] text-xs transition-[opacity,height] duration-100 w-full text-left
									{isFocused ? 'bg-primary text-primary-foreground' : 'hover:bg-accent'}"
								onclick={() => {
									focusedNodeId = getNodeId(node);
									if (node.type === 'table' || node.type === 'table_group' || node.type === 'view') {
										handleTableNodeClick(node);
										if (node.type === 'table' || node.type === 'table_group') toggleTreeNode(node);
									} else if (!isLeaf) {
										toggleTreeNode(node);
									}
								}}
							ondblclick={() => {
								if (node.type === 'table' || node.type === 'table_group' || node.type === 'view') {
									const db = parentDb || node.databaseName || selectedDatabaseName || '';
									const schema = node.schemaName || selectedSchemaName;
									const conn = getSelectedConnection();
									const dblDbType = conn?.type || '';
									const actualName = node.type === 'table_group' ? (node.tableName || node.name) : node.name;
									const fqParts: string[] = [];
									if (db) fqParts.push(db);
									if (schema) fqParts.push(schema);
									fqParts.push(actualName);
									let fullTableName = fqParts.join('.');
									if (dblDbType === 'BIGQUERY') fullTableName = `\`${fullTableName}\``;
									openTableView(db, schema, actualName, fullTableName);
								}
							}}
								oncontextmenu={(e) => handleTreeContextMenu(e, node, parentDb)}
								title={node.type === 'column' || node.type === 'nested_column' || node.type === 'key' || node.type === 'index' ? `${node.name}${node.columnType ? ' (' + node.columnType + ')' : ''}` : node.type === 'table_group' ? `${node.name} — latest: ${node.tableName || ''}` : node.name}
							>
								<!-- Indent lines -->
								{#each Array(depth) as _}
									<div class="w-5 h-full relative shrink-0">
										<div class="absolute top-0 left-[9px] bottom-0 border-l border-border/60"></div>
									</div>
								{/each}
								<!-- Chevron / Spacer -->
								{#if isLeaf}
									<div class="shrink-0 w-[18px]"></div>
								{:else}
									<div class="shrink-0 h-5 w-[18px] flex items-center justify-center">
										{#if node.loading}
											<div class="animate-spin rounded-full h-3.5 w-3.5 border-b-2 border-current"></div>
										{:else if node.expanded}
											<ChevronDown size={14} class="shrink-0 {isFocused ? 'text-primary-foreground' : 'text-muted-foreground'}" />
										{:else}
											<ChevronRight size={14} class="shrink-0 {isFocused ? 'text-primary-foreground' : 'text-muted-foreground'}" />
										{/if}
									</div>
								{/if}
								<!-- Icon -->
								<div class="shrink-0 w-5 flex items-center justify-center">
									{#if node.type === 'database'}
										<Book size={16} class="shrink-0" style="color: #4B5563" />
									{:else if node.type === 'schema'}
										{#if node.expanded}
											<Folder size={16} class="shrink-0" style="color: #F59E0B; fill: #F59E0B; fill-opacity: 0.2" />
										{:else}
											<Folder size={16} class="shrink-0" style="color: #F59E0B" />
										{/if}
									{:else if node.type === 'table'}
										<Table2 size={16} class="shrink-0" style="color: #3B82F6" />
									{:else if node.type === 'table_group'}
										<Table2 size={16} class="shrink-0" style="color: #8B5CF6" />
									{:else if node.type === 'view'}
										<Eye size={16} class="shrink-0" style="color: #10B981" />
									{:else if node.type === 'nested_column'}
										<Columns size={14} class="shrink-0" style="color: #8B5CF6" />
									{:else if node.type === 'columns' || node.type === 'column'}
										<Columns size={14} class="shrink-0" style="color: #6B7280" />
									{:else if node.type === 'keys' || node.type === 'key'}
										<KeyRound size={14} class="shrink-0" style="color: #F59E0B" />
									{:else if node.type === 'indexes' || node.type === 'index'}
										<Hash size={14} class="shrink-0" style="color: #9CA3AF" />
									{/if}
								</div>
								<!-- Name + Column Type Badge -->
								<span class="truncate leading-5 ml-1 {isFocused ? 'text-primary-foreground' : ''}">{node.name}</span>
								{#if node.type === 'nested_column' && node.columnType}
									<span
										class="text-[11px] shrink-0 leading-5 ml-auto pl-2 font-medium"
										style="color: {isFocused ? '' : '#ec4899'}"
									>{simplifyColumnType(node.columnType)}</span>
								{:else if (node.type === 'column' || node.type === 'key') && node.columnType}
									<span
										class="text-[11px] shrink-0 leading-5 ml-auto pl-2 font-medium"
										style="color: {isFocused ? '' : getColumnTypeColor(node.columnType)}"
									>{node.columnType.toUpperCase()}</span>
								{/if}
								{#if node.type === 'index' && node.columnType}
									<span class="text-[10px] text-muted-foreground/70 ml-auto pl-2 shrink-0 truncate max-w-[100px]">{node.columnType}</span>
								{/if}
							</button>
						{/snippet}

						<!-- Recursive tree children renderer -->
						{#snippet renderTreeChildren(nodes: TreeNode[], depth: number, rootDb: string | undefined)}
							{#each nodes as node (node.name)}
								{@render treeNodeRow(node, depth, rootDb)}
								{#if node.expanded && node.children}
									{@render renderTreeChildren(node.children, depth + 1, rootDb || (node.type === 'database' ? node.name : undefined))}
								{/if}
							{/each}
						{/snippet}

						<!-- Tree -->
						<div class="flex-1 overflow-auto px-1">
							{#if treeLoading}
								<div class="flex items-center justify-center py-8">
									<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-primary"></div>
								</div>
							{:else if filteredTree.length === 0}
								<p class="text-xs text-muted-foreground text-center py-8">No databases found</p>
							{:else}
								{@render renderTreeChildren(filteredTree, 0, undefined)}
							{/if}
						</div>

						<!-- History Resize Handle -->
						<div
							class="h-1 hover:bg-primary/20 cursor-row-resize transition-colors shrink-0 border-t border-border {isResizingHistory ? 'bg-primary/30' : ''}"
							onmousedown={handleHistoryResize}
						></div>

						<!-- Query History (below tree) -->
						<div class="shrink-0 overflow-hidden" style="height: {historyPanelHeight}px;">
							{#await import('$lib/components/QueryHistory/QueryHistoryList.svelte') then { default: QueryHistoryList }}
								<QueryHistoryList
									dataSourceId={selectedConnectionId}
									onLoadToEditor={handleLoadHistoryToEditor}
								/>
							{/await}
						</div>
					{/if}
				{:else}
					<!-- Saved Console List (Workspace) -->
					<div class="flex flex-col h-full">
						<!-- Header -->
						<div class="flex items-center justify-between px-3 py-2 border-b border-border min-h-[40px]">
							<div class="flex items-center flex-1 gap-2">
								<button
									class="p-1 flex items-center justify-center min-w-[24px] h-6 hover:bg-accent rounded"
									onclick={() => setLeftTab('database')}
								>
									<ArrowLeft size={16} />
								</button>
								<span class="text-sm font-medium">Workspace</span>
							</div>
							<div class="flex items-center gap-1">
								<Popover bind:open={showSortDropdown}>
									<PopoverTrigger class="px-2 py-1 flex items-center justify-center gap-1 bg-card border border-border rounded-md text-xs h-7 hover:bg-accent">
										Sort
										<ChevronDown size={14} class="text-muted-foreground" />
									</PopoverTrigger>
									<PopoverContent align="end" class="w-fit min-w-[120px] p-1">
										{#each [{ key: 'date', label: 'Date created' }, { key: 'name', label: 'Name' }, { key: 'type', label: 'Type' }] as s}
											<button
												class="flex items-center gap-2 w-full px-3 py-1.5 text-xs hover:bg-accent transition-colors text-left rounded-sm"
												onclick={() => { sortBy = s.key as any; showSortDropdown = false; }}
											>
												{#if sortBy === s.key}<Check size={14} class="text-primary" />{:else}<span class="w-3.5"></span>{/if}
												<span>{s.label}</span>
											</button>
										{/each}
									</PopoverContent>
								</Popover>
								<button
									class="p-1 flex items-center justify-center text-muted-foreground min-w-[24px] h-6 hover:bg-accent hover:text-foreground rounded"
									onclick={() => setLeftTab('database')}
								>
									<X size={16} />
								</button>
							</div>
						</div>

						<!-- List -->
						<div class="flex-1 overflow-y-auto py-1">
							{#if sortedSavedList.length === 0}
								<p class="text-xs text-muted-foreground text-center py-8">No saved consoles</p>
							{:else}
								{#each sortedSavedList as saved (saved.id)}
									<div
										class="flex items-center px-3 py-2 gap-2 relative transition-colors hover:bg-accent cursor-pointer"
										onclick={() => openSavedConsole(saved.id as number)}
										oncontextmenu={(e) => {
											e.preventDefault();
											contextMenu = {
												x: e.clientX, y: e.clientY,
												items: [
													{ label: 'Open', icon: FileCode, action: () => openSavedConsole(saved.id as number) },
													{ label: 'Rename', icon: Pencil, action: () => { renamingConsoleId = saved.id; renamingConsoleName = saved.name || ''; } },
													{ label: '', action: () => {}, separator: true },
													{ label: 'Delete', icon: Trash2, destructive: true, action: () => deleteConsole(saved.id as number) },
												]
											};
										}}
									>
										<FileCode size={14} class="text-muted-foreground shrink-0" />
										{#if renamingConsoleId === saved.id}
											<input
												class="flex-1 text-sm bg-background border border-input rounded px-1 py-0.5"
												bind:value={renamingConsoleName}
												onclick={(e) => e.stopPropagation()}
												onblur={async () => {
													if (renamingConsoleName.trim()) {
														await renameConsole(saved.id as number, renamingConsoleName.trim());
														await fetchSavedConsoleList();
													}
													renamingConsoleId = null;
												}}
												onkeydown={(e) => { if (e.key === 'Enter') (e.target as HTMLElement).blur(); }}
											/>
										{:else}
											<span class="flex-1 text-sm truncate">{saved.name || `Console ${saved.id}`}</span>
										{/if}
									</div>
								{/each}
							{/if}
						</div>
					</div>
				{/if}
			</div>
		</div>

		<!-- Left Resize Handle -->
		<div
			class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isResizingLeft ? 'bg-primary/30' : ''}"
			onmousedown={handleLeftResize}
		></div>
	{/if}

	<!-- Right Panel -->
	<div class="flex-1 flex flex-col min-w-0">
		<!-- Tab Bar -->
		<div class="flex items-center h-8 border-b border-border bg-card shrink-0">
			<!-- Toggle Left Panel -->
			<button
				onclick={togglePanelLeft}
				class="h-8 w-8 flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent border-r border-border shrink-0"
				title="Toggle sidebar"
			>
				<PanelLeft size={14} />
			</button>

			<!-- Tabs + Add Tab -->
			<div bind:this={tabsContainer} class="flex items-center flex-1 min-w-0 overflow-x-auto">
				{#if ws.consoleList.length > 0}
					{#each ws.consoleList as tab (tab.id)}
						<div
							class="flex items-center gap-1 pl-2.5 pr-1 h-8 leading-8 text-xs whitespace-nowrap border-r border-border cursor-pointer group relative
								{ws.activeConsoleId === tab.id
									? 'bg-background text-foreground border-b-2 border-b-primary'
									: 'text-muted-foreground hover:text-foreground hover:bg-accent'}"
						>
					{#if tab.operationType === 'tableView'}
						<Table2 size={14} class="shrink-0 mr-1 text-emerald-500" />
					{:else if tab.operationType === 'erd'}
						<Workflow size={14} class="shrink-0 mr-1 text-indigo-500" />
					{:else if tab.operationType === 'lineage'}
						<GitBranch size={14} class="shrink-0 mr-1 text-orange-500" />
					{:else}
						<FileCode size={14} class="shrink-0 mr-1" />
					{/if}
						{#if editingTabId === tab.id}
							<input
								class="w-20 text-xs bg-background border border-input rounded px-1"
								bind:value={editingTabName}
								onblur={finishEditingTab}
								onkeydown={(e) => { if (e.key === 'Enter') (e.target as HTMLElement).blur(); }}
							/>
						{:else}
							<button
								class="truncate max-w-[150px]"
								onclick={() => setActiveConsoleId(tab.id)}
								ondblclick={() => { if (tab.operationType !== 'tableView' && tab.operationType !== 'erd' && tab.operationType !== 'lineage') startEditingTab(tab); }}
							>
								{tab.name || 'Console'}
							</button>
						{/if}
							<button
								class="opacity-0 group-hover:opacity-100 w-5 h-5 mx-1 flex items-center justify-center rounded cursor-pointer hover:text-primary hover:bg-accent hover:scale-110 transition-transform"
								onclick={(e) => { e.stopPropagation(); handleCloseTab(tab.id); }}
							>
								<X size={12} />
							</button>
						</div>
					{/each}
				{/if}
				<!-- Add Tab (inline, flows with tabs) -->
				<button
					class="h-8 w-[30px] flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent shrink-0"
					title="New console (max 20)"
					onclick={handleNewConsole}
				>
					<Plus size={14} />
				</button>
			</div>
		</div>

		{#if ws.consoleList.length === 0}
			<!-- Empty State -->
			<div class="flex-1 flex flex-col justify-center items-center gap-4">
				<div class="text-center">
					<h3 class="text-lg font-semibold text-foreground mb-2">Inquery</h3>
					<p class="text-sm text-muted-foreground mb-4">Create a console to start writing SQL</p>
					<Button onclick={handleNewConsole}>
						<Plus size={16} class="mr-1" />
						Create Console
					</Button>
				</div>
				<div class="flex flex-col gap-1 text-xs text-muted-foreground mt-4">
					<span><kbd class="px-1.5 py-0.5 bg-muted rounded text-[10px]">⌘ Enter</kbd> Execute query</span>
					<span><kbd class="px-1.5 py-0.5 bg-muted rounded text-[10px]">⌘ S</kbd> Save console</span>
					<span><kbd class="px-1.5 py-0.5 bg-muted rounded text-[10px]">⌘ Shift L</kbd> New console</span>
				</div>
			</div>
		{:else}
			{#if isERDView}
				<!-- ===== ERD View Mode ===== -->
				<div class="flex-1 flex flex-col min-h-0">
					{#await import('$lib/components/ERDVisualization/ERDVisualization.svelte') then { default: ERDVisualization }}
						<ERDVisualization schema={erdSchema} loading={erdLoading} />
					{/await}
				</div>
			{:else if isLineageView}
				<!-- ===== Lineage View Mode ===== -->
				<div class="flex-1 flex flex-col min-h-0">
					<LineageGraph
						graph={lineageGraphData}
						loading={lineageLoading}
						focusTable={activeConsole ? (lineageFocusMap[activeConsole.id] || (activeConsole.ddl as string) || '') : ''}
						focusDatabase={activeConsole?.databaseName || ''}
						focusSchema={activeConsole?.schemaName || ''}
					/>
				</div>
			{:else if isTableView}
				<!-- ===== Table View Mode ===== -->
				<div class="flex-1 flex flex-col min-h-0">
					<!-- Table View Header -->
					<div class="flex items-center h-10 border-b border-border px-4 shrink-0 bg-card">
						<Table2 size={14} class="shrink-0 mr-2 text-emerald-500" />
						<span class="text-xs font-medium text-foreground truncate mr-3">{activeConsole?.tableName || activeConsole?.name || 'Table'}</span>

						<div class="flex items-center gap-1.5 ml-auto">
							{#if executing}
								<button
									class="flex items-center gap-1 px-2 py-1 rounded text-xs text-destructive hover:bg-destructive/10 transition-colors"
									onclick={handleStopQuery}
								>
									<StopCircle size={12} /> Cancel
								</button>
							{:else}
								<button
									class="flex items-center gap-1 px-2 py-1 rounded text-xs text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
									onclick={() => handleRunQuery()}
									title="Refresh"
								>
									<RefreshCw size={12} />
									Refresh
								</button>
							{/if}

							{#if resultDataList[activeResultIndex] && 'rows' in resultDataList[activeResultIndex]}
								{@const tvResult = resultDataList[activeResultIndex]}
								<div class="w-px h-4 bg-border"></div>
								<span class="text-[10px] text-muted-foreground whitespace-nowrap">
									Rows {(currentPageNo - 1) * currentPageSize + 1}-{(currentPageNo - 1) * currentPageSize + tvResult.rows.length}{hasMoreRows ? '+' : ''}
								</span>
								<button
									class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
									disabled={currentPageNo <= 1 || executing}
									onclick={() => handleRunQuery(currentPageNo - 1)}
								>&lsaquo;</button>
								<span class="text-[10px] text-muted-foreground min-w-[16px] text-center">{currentPageNo}</span>
								<button
									class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
									disabled={!hasMoreRows || executing}
									onclick={() => handleRunQuery(currentPageNo + 1)}
								>&rsaquo;</button>
								<DropdownMenu>
									<DropdownMenuTrigger class="h-5 text-[10px] bg-muted hover:bg-accent rounded text-muted-foreground hover:text-foreground px-1.5 cursor-pointer transition-colors inline-flex items-center gap-0.5">
										{currentPageSize}
										<ChevronDown class="w-2.5 h-2.5 opacity-50" />
									</DropdownMenuTrigger>
									<DropdownMenuContent align="center" side="bottom" class="min-w-[4rem]">
										{#each [50, 100, 200, 500, 1000] as size}
											<DropdownMenuItem
												class="text-[11px] justify-center {currentPageSize === size ? 'bg-accent font-medium' : ''}"
												onSelect={() => { currentPageSize = size; handleRunQuery(1); }}
											>
												{size}
											</DropdownMenuItem>
										{/each}
									</DropdownMenuContent>
								</DropdownMenu>
								<div class="w-px h-4 bg-border"></div>
								<!-- Export dropdown -->
								<div class="relative">
									<button
										class="px-2 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1 disabled:opacity-50"
										disabled={exporting}
										onclick={() => showExportDropdown = !showExportDropdown}
									>
										{#if exporting}
											<div class="animate-spin rounded-full h-[11px] w-[11px] border-b border-current"></div>
										{:else}
											<Download size={11} />
										{/if}
										{exporting ? 'Exporting...' : 'Export'}
										{#if !exporting}<ChevronDown size={10} />{/if}
									</button>
									{#if showExportDropdown && !exporting}
										<!-- svelte-ignore a11y_click_events_have_key_events -->
										<!-- svelte-ignore a11y_no_static_element_interactions -->
										<div class="fixed inset-0 z-40" onclick={() => showExportDropdown = false}></div>
										<div class="absolute right-0 top-full mt-1 z-50 bg-popover border border-border rounded-md shadow-lg py-1 min-w-[180px]">
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('CSV')}>Export as CSV</button>
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('JSON')}>Export as JSON</button>
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('INSERT')}>Export as INSERT SQL</button>
										</div>
									{/if}
								</div>
							{/if}
						</div>
					</div>

					<!-- Table View Data Grid -->
					<div class="flex-1 overflow-auto min-h-0">
						{#if executing && !resultDataList[activeResultIndex]}
							<div class="flex items-center justify-center h-full gap-2">
								<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-primary"></div>
								<span class="text-sm text-muted-foreground">Loading table data...</span>
							</div>
						{:else if resultDataList[activeResultIndex] && 'headers' in resultDataList[activeResultIndex]}
							{@const tvActiveResult = resultDataList[activeResultIndex]}
							{@const hasRowNumCol = tvActiveResult.headers.length > 0 && (tvActiveResult.headers[0]?.dataType === 'INQUERY_ROW_NUMBER' || tvActiveResult.headers[0]?.name === 'Row Number')}
							{@const displayHeaders = hasRowNumCol ? tvActiveResult.headers.slice(1) : tvActiveResult.headers}
							{@const colOffset = hasRowNumCol ? 1 : 0}
							<div class="flex flex-col h-full">
								<div class="flex-1 overflow-auto">
									<table class="text-[13px] border-collapse" style="table-layout: fixed; min-width: 100%;">
										<colgroup>
											<col style="width: 28px; min-width: 28px;" />
											{#each displayHeaders as _, colIdx}
												<col style="width: {columnWidths[colIdx] || 150}px;" />
											{/each}
										</colgroup>
										<thead class="sticky top-0 z-10" style="background: var(--color-bg-subtle, hsl(var(--muted)));">
											<tr class="h-8">
												<th class="text-center font-semibold text-muted-foreground border-b border-border whitespace-nowrap text-xs" style="min-width: 36px; width: 36px; padding: 0;">#</th>
												{#each displayHeaders as header, colIdx}
													{@const hName = header.name || header}
													{@const hType = header.dataType || header.columnType || ''}
													{@const badge = getDataTypeBadgeStyle(hType, hName)}
													<th class="text-center font-semibold text-muted-foreground border-b border-border whitespace-nowrap relative group" style="padding: 0 8px; font-size: 11px; font-family: 'JetBrains Mono', ui-monospace, monospace; width: {columnWidths[colIdx] || 150}px;">
														<div class="flex items-center justify-center gap-1.5 overflow-hidden">
															<span class="truncate">{hName}</span>
															{#if hType && hType !== 'INQUERY_ROW_NUMBER'}
																<span class="text-[8px] font-semibold lowercase rounded shrink-0" style="padding: 0 4px 0 6px; background: {badge.bg}; color: {badge.color}; border: 1px solid {badge.border}; line-height: 1.5; font-family: 'JetBrains Mono', ui-monospace, monospace;">{badge.label}</span>
															{/if}
														</div>
														<!-- Resize handle -->
														<!-- svelte-ignore a11y_no_static_element_interactions -->
														<div
															class="absolute right-0 top-0 bottom-0 w-[5px] cursor-col-resize z-20 hover:bg-primary/30 {resizingColIdx === colIdx ? 'bg-primary/40' : ''}"
															onmousedown={(e) => handleColumnResizeStart(e, colIdx)}
														></div>
													</th>
												{/each}
											</tr>
										</thead>
										<tbody>
											{#each tvActiveResult.rows as row, i}
												{@const fRow = tvActiveResult.flattenedRows?.[i]}
												{@const isGroupFirst = !fRow || fRow._isFirstOfGroup}
												{@const rowSpan = fRow?._rowSpan || 1}
												<tr class="transition-colors {isGroupFirst && tvActiveResult.nestedExpanded ? 'border-t border-border/40' : ''} hover:bg-accent/20" style="height: 28px;">
													{#if isGroupFirst}
														<td class="text-center text-muted-foreground/50 whitespace-nowrap border-r border-border/30 text-xs tabular-nums select-none align-top" style="min-width: 36px; width: 36px; padding: 0;" rowspan={tvActiveResult.nestedExpanded ? rowSpan : 1}>{fRow ? fRow._originalRowIndex + 1 : (currentPageNo - 1) * currentPageSize + i + 1}</td>
													{/if}
													{#each row as cell, cellIdx}
														{#if cellIdx >= colOffset}
															{@const colName = displayHeaders[cellIdx - colOffset]?.name || ''}
															{@const isNested = displayHeaders[cellIdx - colOffset]?.isNested}
															{#if tvActiveResult.nestedExpanded && !isNested && !isGroupFirst}
															{:else}
																{@const span = tvActiveResult.nestedExpanded && !isNested && isGroupFirst ? rowSpan : 1}
																{#if cell === null || cell === undefined}
																	<td
																		class="whitespace-nowrap overflow-hidden truncate select-text cursor-pointer border-r border-b border-border/40 {!isNested && tvActiveResult.nestedExpanded ? 'align-top' : ''}"
																		style="padding: 0 4px; line-height: 27px; color: var(--color-text-tertiary, hsl(var(--muted-foreground) / 0.5));"
																		oncontextmenu={(e) => handleResultCellContextMenu(e, cell, row, displayHeaders, cellIdx - colOffset)}
																		rowspan={span}
																	>&lt;null&gt;</td>
																{:else}
																	<td
																		class="whitespace-nowrap overflow-hidden truncate text-foreground select-text cursor-pointer border-r border-b border-border/40 {!isNested && tvActiveResult.nestedExpanded ? 'align-top' : ''}"
																		style="padding: 0 4px; line-height: 27px;"
																		ondblclick={() => handleCellDblClick(cell, colName)}
																		oncontextmenu={(e) => handleResultCellContextMenu(e, cell, row, displayHeaders, cellIdx - colOffset)}
																		rowspan={span}
																	>{formatCellValue(cell)}</td>
																{/if}
															{/if}
														{/if}
													{/each}
												</tr>
											{/each}
										</tbody>
									</table>
								</div>
								<!-- Status Bar -->
								<div class="flex items-center shrink-0 border-t border-border/50 text-xs text-muted-foreground select-none" style="height: 26px; padding: 0 8px; background: var(--color-bg-subtle, hsl(var(--muted)));">
									<span style="margin-right: 16px;">
										{#if tvActiveResult.description}
											{tvActiveResult.description}
										{:else}
											Table loaded
										{/if}
									</span>
									{#if executionTime !== null}
										<span style="margin-right: 16px;">{executionTime}ms</span>
									{/if}
									<span>{tvActiveResult.rows?.length || 0} row(s)</span>
									{#if hasMoreRows}
										<span class="text-muted-foreground/50 ml-1">(more available)</span>
									{/if}
								</div>
							</div>
						{:else if resultDataList[activeResultIndex] && 'error' in resultDataList[activeResultIndex]}
							<div class="flex flex-col items-center justify-center h-full gap-2">
								<span class="text-sm font-semibold text-destructive">Error</span>
								<span class="text-xs text-muted-foreground max-w-md text-center">{resultDataList[activeResultIndex].error}</span>
								<button
									class="mt-2 flex items-center gap-1 px-3 py-1.5 rounded text-xs bg-muted hover:bg-accent transition-colors"
									onclick={() => handleRunQuery()}
								>
									<RefreshCw size={12} /> Retry
								</button>
							</div>
						{:else}
							<div class="flex items-center justify-center h-full gap-2 text-muted-foreground">
								<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-primary"></div>
								<span class="text-xs">Loading...</span>
							</div>
						{/if}
					</div>
				</div>
			{:else}
			<!-- ===== Console Mode ===== -->
			<!-- Toolbar -->
			<div class="flex items-center h-12 border-b border-border px-4 shrink-0">
				<div class="flex items-center gap-2">
					{#if executing}
						<Button variant="destructive" class="h-8 px-3 rounded-md text-[13px] font-medium hover:-translate-y-0.5 hover:shadow-md transition-all gap-1.5" onclick={handleStopQuery}>
							<StopCircle size={14} />
							Stop
						</Button>
					{:else}
						<Button class="h-8 px-3 rounded-md text-[13px] font-medium hover:-translate-y-0.5 hover:shadow-md transition-all gap-1.5" onclick={() => handleRunQuery()}>
							<Play size={14} fill="currentColor" />
							Execute
							<span class="text-[10px] opacity-50 bg-white/20 px-1 py-0.5 rounded ml-1">⌘↵</span>
						</Button>
					{/if}

					<Button variant="outline" class="h-8 px-3 rounded-md text-[13px] font-medium hover:-translate-y-0.5 hover:shadow-md transition-all gap-1.5" onclick={handleSaveConsole}>
						<Save size={14} />
						Save
						<span class="text-[10px] opacity-50 bg-muted px-1 py-0.5 rounded ml-1">⌘S</span>
					</Button>

					<Button variant="outline" class="h-8 px-3 rounded-md text-[13px] font-medium hover:-translate-y-0.5 hover:shadow-md transition-all gap-1.5" onclick={() => handleFormatSQL()}>
						<AlignLeft size={14} />
						Format
					</Button>

					<Button variant="outline" class="h-8 px-3 rounded-md text-[13px] font-medium hover:-translate-y-0.5 hover:shadow-md transition-all gap-1.5 text-cyan-600 border-cyan-300/30 hover:border-cyan-600 hover:bg-cyan-500/10" onclick={handleQueryEstimate} disabled={queryEstimatorLoading}>
						{#if queryEstimatorLoading}
							<div class="animate-spin rounded-full h-3.5 w-3.5 border-b-2 border-cyan-600"></div>
						{:else}
							<Gauge size={14} />
						{/if}
						Estimate
					</Button>
				</div>

				<div class="flex-1"></div>

				<!-- Generate Button (right side of toolbar) -->
				<button
					class="flex items-center justify-center gap-1.5 rounded-md px-2 py-1 h-8 border-none bg-transparent transition-all cursor-pointer
						hover:bg-gradient-to-r hover:from-pink-500/15 hover:via-purple-500/15 hover:to-blue-500/15 hover:shadow-sm hover:px-3 hover:rounded-lg
						{isAiInputVisible || isAiGenerating ? 'bg-gradient-to-r from-pink-500/15 via-purple-500/15 to-blue-500/15 px-3 rounded-lg' : ''}"
					onclick={() => {
						isAiInputVisible = !isAiInputVisible;
						if (isAiInputVisible) {
							setTimeout(() => {
								document.getElementById('ai-generate-input')?.focus();
							}, 100);
						}
					}}
					disabled={isAiGenerating}
				>
					<AISparkleIcon size={isAiInputVisible ? 16 : 14} filled />
					<span class="text-xs font-medium text-muted-foreground tracking-tight transition-all {isAiInputVisible ? 'text-sm text-foreground' : ''}">
						Generate
					</span>
				</button>
			</div>

			<!-- Editor/ERD/Flow Area -->
			<div class="flex-1 min-h-0 flex flex-col relative">
				<!-- Diff View Overlay (Accept/Reject AI-generated SQL) -->
				{#if showDiffView}
					<div class="absolute inset-0 z-[200] bg-background flex flex-col rounded-lg overflow-hidden">
						<div class="flex justify-between items-center px-4 py-3 bg-muted border-b border-border shrink-0">
							<div class="flex flex-col gap-0.5">
								<span class="text-sm font-semibold">Review Changes</span>
								<span class="text-xs text-muted-foreground">Original (left) vs Generated (right)</span>
							</div>
							<div class="flex gap-2">
								<button
									class="flex items-center gap-1 px-3 h-7 rounded-md text-xs font-medium bg-gradient-to-r from-emerald-500 to-emerald-600 text-white hover:from-emerald-600 hover:to-emerald-700 transition-colors"
									onclick={handleAcceptDiff}
								>
									<Check size={14} />
									Accept
								</button>
								<button
									class="flex items-center gap-1 px-3 h-7 rounded-md text-xs font-medium bg-muted border border-border hover:bg-accent transition-colors"
									onclick={handleRejectDiff}
								>
									<X size={14} />
									Reject
								</button>
							</div>
						</div>
						<div class="flex-1 min-h-0 overflow-hidden" bind:this={diffContainerEl}></div>
					</div>
				{/if}

				<!-- Optimize SQL Loading -->
				{#if isOptimizeLoading}
					<div class="shrink-0 bg-background p-4 flex justify-center items-center">
						<div class="flex items-end gap-2 w-[90%] max-w-[90%]">
							<div class="flex-1 relative rounded-lg p-px ai-loading-border">
								<div class="flex items-center justify-center gap-3 py-3.5 px-4 bg-card rounded-[7px] min-h-[50px]">
									<div class="w-4 h-4 border-2 border-purple-500/20 border-t-purple-500/80 rounded-full animate-spin"></div>
									<span class="text-sm font-medium bg-gradient-to-r from-pink-500 via-purple-500 to-blue-500 bg-clip-text text-transparent min-w-[200px] text-center">
										Optimizing SQL...
									</span>
								</div>
							</div>
						</div>
					</div>
				{/if}

				<!-- AI Input / Loading Panel (inside editor area, pushes editor down) -->
				{#if isAiInputVisible || isAiGenerating}
					<div class="shrink-0 bg-background p-4 flex justify-center items-center">
						<div class="flex items-end gap-2 w-[90%] max-w-[90%]">
							{#if isAiGenerating}
								<!-- Loading state with gradient border -->
								<div class="flex-1 relative rounded-lg p-px ai-loading-border">
									<div class="flex items-center justify-center gap-3 py-3.5 px-4 bg-card rounded-[7px] min-h-[50px]">
										<div class="w-4 h-4 border-2 border-purple-500/20 border-t-purple-500/80 rounded-full animate-spin"></div>
										<span class="text-sm font-medium bg-gradient-to-r from-pink-500 via-purple-500 to-blue-500 bg-clip-text text-transparent min-w-[200px] text-center">
											{aiGenerationLoadingMessage}
										</span>
										<button
											class="flex items-center gap-1 px-3 py-1.5 rounded-md bg-muted text-sm cursor-pointer transition-all hover:bg-accent"
											onclick={cancelAiGeneration}
										>
											<span class="font-medium">Cancel</span>
											<span class="text-muted-foreground text-[11px]">ESC</span>
										</button>
									</div>
								</div>
							{:else}
								<!-- Input state with conic gradient border -->
								<div class="flex-1 relative rounded-lg p-px ai-gradient-border {!aiInputValue.trim() ? 'ai-gradient-border-spin' : ''}">
									<div class="relative w-full bg-card rounded-[6px] flex items-center min-h-[50px]">
										<textarea
											id="ai-generate-input"
											class="flex-1 w-full rounded-md resize-none bg-transparent border-none min-h-[50px] pr-[200px] px-4 py-3 text-sm outline-none placeholder:text-muted-foreground/50"
											placeholder="Describe the SQL you want to generate..."
											bind:value={aiInputValue}
											onkeydown={handleAiInputKeyDown}
										></textarea>
										<div class="absolute right-2 top-1/2 -translate-y-1/2 flex gap-2 items-center">
											<button
												class="flex items-center gap-1 px-3 py-1.5 rounded-md bg-muted text-sm cursor-pointer transition-all hover:bg-accent"
												onclick={() => { isAiInputVisible = false; aiInputValue = ''; }}
											>
												<span class="font-medium">Cancel</span>
												<span class="text-muted-foreground text-[11px]">ESC</span>
											</button>
											<button
												class="flex items-center gap-1 px-3 py-1.5 rounded-md text-sm cursor-pointer transition-all
													{aiInputValue.trim()
														? 'bg-gradient-to-r from-pink-500/15 via-purple-500/15 to-blue-500/15 hover:from-pink-500/25 hover:via-purple-500/25 hover:to-blue-500/25'
														: 'bg-muted opacity-50 cursor-not-allowed'}"
												onclick={() => handleGenerateSQL()}
												disabled={!aiInputValue.trim()}
											>
												<span class="font-medium">Generate</span>
												<span>&#8592;</span>
											</button>
										</div>
									</div>
								</div>
							{/if}
						</div>
					</div>
				{/if}

				{#if viewAllTablesData !== null}
					<!-- View All Tables -->
					<div class="h-full flex flex-col overflow-hidden">
						<div class="flex items-center justify-between px-3 py-2 border-b border-border">
							<span class="text-xs font-medium">All Tables - {viewAllTablesDb} ({viewAllTablesData.length})</span>
							<button class="p-0.5 rounded hover:bg-accent text-muted-foreground" onclick={() => viewAllTablesData = null}>
								<X size={14} />
							</button>
						</div>
						<div class="flex-1 overflow-auto">
							<table class="w-full text-xs">
								<thead class="bg-muted/50 sticky top-0">
									<tr>
										<th class="px-3 py-1.5 text-left font-medium text-muted-foreground">#</th>
										<th class="px-3 py-1.5 text-left font-medium text-muted-foreground">Name</th>
										<th class="px-3 py-1.5 text-left font-medium text-muted-foreground">Type</th>
										<th class="px-3 py-1.5 text-left font-medium text-muted-foreground">Comment</th>
									</tr>
								</thead>
								<tbody>
									{#each viewAllTablesData as t, i}
										<tr class="border-b border-border/50 hover:bg-accent/30 cursor-pointer"
											onclick={() => {
												createConsoleWithConn({databaseName: viewAllTablesDb, ddl: `SELECT * FROM ${t.name} LIMIT 50;\n` });
												viewAllTablesData = null;
											}}
										>
											<td class="px-3 py-1.5 text-muted-foreground">{i + 1}</td>
											<td class="px-3 py-1.5 font-medium">{t.name}</td>
											<td class="px-3 py-1.5 text-muted-foreground">{t.type}</td>
											<td class="px-3 py-1.5 text-muted-foreground">{t.comment || '-'}</td>
										</tr>
									{/each}
								</tbody>
							</table>
						</div>
					</div>
				{:else}

					<!-- Editor with floating AI button -->
					<div class="relative flex-1 min-h-0">
						{#await import('$lib/components/MonacoEditor/MonacoEditor.svelte') then { default: MonacoEditor }}
							<MonacoEditor
								bind:this={editorComponent}
								bind:value={consoleContent}
								language="sql"
								disableContextMenu={true}
								onchange={(v) => {
									consoleContent = v;
									clearDecorations();
									if (ws.activeConsoleId) {
										debouncedAutoSave(ws.activeConsoleId, v);
									}
								}}
								onselectionchange={(sel) => {
									if (inlineEditOpen) return; // Guard: prevent clearing selection while inline edit is open
									if (!sel || !sel.text.trim()) {
										selectionInfo = null;
									} else {
										selectionInfo = { text: sel.text, top: sel.top, left: sel.left, lineNumber: sel.startLine };
									}
								}}
								onmount={(editor, monaco) => {
									// Keyboard shortcuts
									editor.addCommand(
										monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter,
										() => handleRunQuery()
									);
									editor.addCommand(
										monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS,
										() => handleSaveConsole()
									);
									editor.addCommand(
										monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyL,
										() => handleNewConsole()
									);

									// Custom context menu (replace Monaco native)
									editorDomNode = editor.getDomNode() || null;
									editorContextMenuHandler = (e: MouseEvent) => {
										e.preventDefault();
										e.stopPropagation();
										const sel = editor.getSelection();
										const hasSelection = sel ? !sel.isEmpty() : false;
										editorContextMenu = { x: e.clientX, y: e.clientY, hasSelection };
									};
									if (editorDomNode) {
										editorDomNode.addEventListener('contextmenu', editorContextMenuHandler);
									}

									// Initialize explain widget DOM node
									if (!explainWidgetDomNode && typeof document !== 'undefined') {
										explainWidgetDomNode = document.createElement('div');
										explainWidgetDomNode.className = 'explain-widget-container';
										explainWidgetDomNode.style.zIndex = '9999';
									}
								}}
							/>
						{/await}

						<!-- Floating AI Sparkle Button (appears on text selection) -->
						{#if selectionInfo && !inlineEditOpen && !showDiffView && !isAiGenerating && !isOptimizeLoading}
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="absolute z-20 cursor-pointer animate-in fade-in duration-150"
								style="top: {selectionInfo.top + 2}px; left: 44px;"
							onmousedown={(e) => {
								e.preventDefault();
								e.stopPropagation();
								openInlineEdit();
							}}
							>
								<AISparkleIcon size={16} filled />
							</div>
						{/if}

						<!-- Inline AI Edit Input (positioned in viewZone space above selection) -->
						{#if inlineEditOpen && selectionInfo}
							<div
								class="absolute z-30"
								style="top: {Math.max(0, inlineEditTop)}px; left: {inlineEditLeft}px; right: 16px; max-width: 600px;"
							>
								<div class="rounded-xl border border-border bg-card shadow-lg">
									<div class="relative">
										<textarea
											class="w-full bg-transparent px-4 py-3.5 pr-[140px] text-sm outline-none rounded-xl placeholder:text-muted-foreground/50 resize-none"
											placeholder={inlineEditPlaceholders[inlineEditPlaceholderIdx]}
											bind:value={inlineEditValue}
											onkeydown={handleInlineEditKeyDown}
											rows={2}
										></textarea>
										<div class="absolute right-3 top-1/2 -translate-y-1/2 flex items-center gap-2">
											<button
												class="flex items-center gap-1 px-2 py-1 rounded-md text-xs text-muted-foreground hover:bg-muted transition-colors"
												onclick={() => closeInlineEdit()}
											>
												<span class="font-medium">Close</span>
												<span class="text-[10px] text-muted-foreground/50">Esc</span>
											</button>
											<button
												class="flex items-center justify-center w-7 h-7 rounded-full bg-primary/15 text-primary hover:bg-primary/25 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
												onclick={submitInlineEdit}
												disabled={!inlineEditValue.trim() || inlineEditLoading}
											>
												{#if inlineEditLoading}
													<div class="animate-spin rounded-full h-3.5 w-3.5 border-b-2 border-current"></div>
												{:else}
													<ArrowUp size={14} />
												{/if}
											</button>
										</div>
									</div>
								</div>
							</div>
						{/if}
					</div>

				<!-- Floating AI Explain Widget (fixed viewport position, draggable) -->
				{#if explainResult.visible}
					<!-- svelte-ignore a11y_click_events_have_key_events -->
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div
						class="fixed z-[9999] w-[500px] max-h-[400px] bg-card border border-border rounded-lg shadow-2xl overflow-hidden flex flex-col"
						style="top: {explainPosition?.top ?? 100}px; left: {explainPosition?.left ?? 100}px;"
						onmousedown={(e) => e.stopPropagation()}
					>
						<!-- Header (draggable) -->
						<div
							class="flex items-center justify-between px-3 py-2 border-b border-border shrink-0 bg-muted/50 {isDraggingExplain ? 'cursor-grabbing' : 'cursor-grab'}"
							onmousedown={handleExplainDragStart}
						>
							<div class="flex items-center gap-1.5">
								<AISparkleIcon size={14} filled />
								<span class="text-xs font-semibold select-none">AI Explanation</span>
							</div>
								<div class="flex items-center gap-1">
									{#if !explainResult.loading && explainResult.content}
										<button
											class="p-1 rounded hover:bg-accent text-muted-foreground transition-colors"
											onclick={handleCopyExplain}
											title="Copy"
										>
											<Copy size={13} />
										</button>
										<button
											class="p-1 rounded hover:bg-accent text-muted-foreground transition-colors"
											onclick={handleRegenerateExplain}
											title="Regenerate"
										>
											<RotateCcw size={13} />
										</button>
									{/if}
									<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={closeExplainWidget}>
										<X size={13} />
									</button>
								</div>
							</div>
							<!-- Content -->
							<div class="flex-1 overflow-auto px-4 py-3 text-xs leading-relaxed">
								{#if explainResult.loading}
									<div class="flex items-center gap-2 text-muted-foreground">
										<div class="animate-spin rounded-full h-3.5 w-3.5 border-b-2 border-primary"></div>
										Analyzing...
									</div>
								{:else}
									<div class="prose prose-sm max-w-none text-foreground text-xs">
										<MarkdownRenderer content={explainResult.content} />
									</div>
								{/if}
							</div>
						</div>
					{/if}
				{/if}
			</div>

			<!-- Resize Handle -->
			{#if hasResults}
				<div
					class="h-1 hover:bg-primary/20 cursor-row-resize transition-colors shrink-0 border-t border-border {isResizingResults ? 'bg-primary/30' : ''}"
					onmousedown={handleResultsResize}
				></div>
			{/if}

			<!-- Results Panel -->
			<div class="bg-card/30 flex flex-col shrink-0" style="height: {hasResults ? resultsPanelHeight : 32}px;">
				<!-- Result Tabs Header -->
				<div class="flex items-center h-8 border-b border-border text-xs shrink-0">
					<div class="flex items-center h-full overflow-x-auto">
						{#if resultDataList.length > 0}
							{#each resultDataList as result, i}
								<button
									class="h-full px-3 text-xs font-medium transition-colors border-b-2 flex items-center gap-1.5 whitespace-nowrap
										{resultActiveTab === 'results' && activeResultIndex === i ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
									onclick={() => { resultActiveTab = 'results'; activeResultIndex = i; }}
								>
									{#if 'error' in result}
										<span class="w-1.5 h-1.5 rounded-full bg-destructive shrink-0"></span>
									{:else if result.success === false}
										<span class="w-1.5 h-1.5 rounded-full bg-destructive shrink-0"></span>
									{:else}
										<span class="w-1.5 h-1.5 rounded-full bg-green-500 shrink-0"></span>
									{/if}
									Result {i + 1}
									{#if result?.rows?.length}
										<span class="text-[10px] text-muted-foreground">({result.rows.length})</span>
									{/if}
								</button>
							{/each}
						{:else}
							<button
								class="h-full px-3 text-xs font-medium transition-colors border-b-2 {resultActiveTab === 'results' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
								onclick={() => resultActiveTab = 'results'}
							>
								Results
							</button>
						{/if}
						<button
							class="h-full px-3 text-xs font-medium transition-colors border-b-2 flex items-center gap-1 {resultActiveTab === 'flow' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
							onclick={() => resultActiveTab = 'flow'}
						>
							<Workflow size={12} class="text-purple-500" />
							SQL Flow
						</button>
						<button
							class="h-full px-3 text-xs font-medium transition-colors border-b-2 flex items-center gap-1 {resultActiveTab === 'stats' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
							onclick={() => resultActiveTab = 'stats'}
						>
							<Gauge size={12} class="text-blue-500" />
							Stats
						</button>
					</div>
					{#if resultActiveTab === 'results'}
						<div class="flex items-center gap-2 ml-2">
							{#if executing}
								<div class="animate-spin rounded-full h-3 w-3 border-b border-primary"></div>
								<button
									class="px-2 py-0.5 rounded text-[10px] text-destructive hover:bg-destructive/10 transition-colors flex items-center gap-0.5"
									onclick={handleStopQuery}
								>
									<StopCircle size={11} /> Cancel
								</button>
							{/if}
						</div>
						{@const currentResult = resultDataList[activeResultIndex]}
						<div class="ml-auto flex items-center gap-1.5 pr-2">
							{#if currentResult && 'headers' in currentResult}
								<!-- Create Chart -->
								<button
									class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
									title="Create Chart"
									onclick={handleOpenChartModal}
								>
									<BarChart2 size={14} />
								</button>
								<div class="w-px h-3 bg-border mx-0.5"></div>
							{/if}
							{#if currentResult && 'rows' in currentResult}
								<span class="text-[10px] text-muted-foreground whitespace-nowrap">
									Rows {(currentPageNo - 1) * currentPageSize + 1}-{(currentPageNo - 1) * currentPageSize + currentResult.rows.length}{hasMoreRows ? '+' : ''}
								</span>
							{/if}
							{#if currentResult && 'headers' in currentResult}
								<button
									class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
									disabled={currentPageNo <= 1 || executing}
									onclick={() => handleRunQuery(currentPageNo - 1)}
								>&lsaquo;</button>
								<span class="text-[10px] text-muted-foreground min-w-[16px] text-center">{currentPageNo}</span>
								<button
									class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
									disabled={!hasMoreRows || executing}
									onclick={() => handleRunQuery(currentPageNo + 1)}
								>&rsaquo;</button>
								<DropdownMenu>
									<DropdownMenuTrigger class="h-5 text-[10px] bg-muted hover:bg-accent rounded text-muted-foreground hover:text-foreground px-1.5 cursor-pointer transition-colors inline-flex items-center gap-0.5">
										{currentPageSize}
										<ChevronDown class="w-2.5 h-2.5 opacity-50" />
									</DropdownMenuTrigger>
									<DropdownMenuContent align="center" side="top" class="min-w-[4rem]">
										{#each [50, 100, 200, 500, 1000] as size}
											<DropdownMenuItem
												class="text-[11px] justify-center {currentPageSize === size ? 'bg-accent font-medium' : ''}"
												onSelect={() => { currentPageSize = size; handleRunQuery(1); }}
											>
												{size}
											</DropdownMenuItem>
										{/each}
									</DropdownMenuContent>
								</DropdownMenu>
								<div class="w-px h-3 bg-border mx-0.5"></div>
								<!-- Export dropdown -->
								<div class="relative">
									<button
										class="px-2 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1 disabled:opacity-50"
										disabled={exporting}
										onclick={() => showExportDropdown = !showExportDropdown}
									>
										{#if exporting}
											<div class="animate-spin rounded-full h-[11px] w-[11px] border-b border-current"></div>
										{:else}
											<Download size={11} />
										{/if}
										{exporting ? 'Exporting...' : 'Export'}
										{#if !exporting}<ChevronDown size={10} />{/if}
									</button>
									{#if showExportDropdown && !exporting}
										<!-- svelte-ignore a11y_click_events_have_key_events -->
										<!-- svelte-ignore a11y_no_static_element_interactions -->
										<div class="fixed inset-0 z-40" onclick={() => showExportDropdown = false}></div>
										<div class="absolute right-0 top-full mt-1 z-50 bg-popover border border-border rounded-md shadow-lg py-1 min-w-[180px]">
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('CSV')}>Export as CSV</button>
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('JSON')}>Export as JSON</button>
											<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => handleClientExport('INSERT')}>Export as INSERT SQL</button>
										</div>
									{/if}
								</div>
							{/if}
						</div>
					{/if}
				</div>
				<!-- Result Content -->
				<div class="flex-1 overflow-auto">
					{#if resultActiveTab === 'results'}
						{@const activeResult = resultDataList[activeResultIndex]}
						{#if activeResult && 'headers' in activeResult}
							{@const hasRowNumCol = activeResult.headers.length > 0 && (activeResult.headers[0]?.dataType === 'INQUERY_ROW_NUMBER' || activeResult.headers[0]?.name === 'Row Number')}
							{@const displayHeaders = hasRowNumCol ? activeResult.headers.slice(1) : activeResult.headers}
							{@const colOffset = hasRowNumCol ? 1 : 0}
							<div class="flex flex-col h-full">
								<div class="flex-1 overflow-auto">
									<table class="text-[13px] border-collapse" style="table-layout: fixed; min-width: 100%;">
										<colgroup>
											<col style="width: 28px; min-width: 28px;" />
											{#each displayHeaders as _, colIdx}
												<col style="width: {columnWidths[colIdx] || 150}px;" />
											{/each}
										</colgroup>
										<thead class="sticky top-0 z-10" style="background: var(--color-bg-subtle, hsl(var(--muted)));">
											<tr class="h-8">
												<th class="text-center font-semibold text-muted-foreground border-b border-border whitespace-nowrap text-xs" style="min-width: 36px; width: 36px; padding: 0;">#</th>
												{#each displayHeaders as header, colIdx}
													{@const hName = header.name || header}
													{@const hType = header.dataType || header.columnType || ''}
													{@const badge = getDataTypeBadgeStyle(hType, hName)}
													<th class="text-center font-semibold text-muted-foreground border-b border-border whitespace-nowrap relative group" style="padding: 0 8px; font-size: 11px; font-family: 'JetBrains Mono', ui-monospace, monospace; width: {columnWidths[colIdx] || 150}px;">
														<div class="flex items-center justify-center gap-1.5 overflow-hidden">
															<span class="truncate">{hName}</span>
															{#if hType && hType !== 'INQUERY_ROW_NUMBER'}
																<span class="text-[8px] font-semibold lowercase rounded shrink-0" style="padding: 0 4px 0 6px; background: {badge.bg}; color: {badge.color}; border: 1px solid {badge.border}; line-height: 1.5; font-family: 'JetBrains Mono', ui-monospace, monospace;">{badge.label}</span>
															{/if}
														</div>
														<!-- Resize handle -->
														<!-- svelte-ignore a11y_no_static_element_interactions -->
														<div
															class="absolute right-0 top-0 bottom-0 w-[5px] cursor-col-resize z-20 hover:bg-primary/30 {resizingColIdx === colIdx ? 'bg-primary/40' : ''}"
															onmousedown={(e) => handleColumnResizeStart(e, colIdx)}
														></div>
													</th>
												{/each}
											</tr>
										</thead>
										<tbody>
											{#each activeResult.rows as row, i}
												{@const fRow = activeResult.flattenedRows?.[i]}
												{@const isGroupFirst = !fRow || fRow._isFirstOfGroup}
												{@const rowSpan = fRow?._rowSpan || 1}
												<tr class="transition-colors {isGroupFirst && activeResult.nestedExpanded ? 'border-t border-border/40' : ''} hover:bg-accent/20" style="height: 28px;">
													{#if isGroupFirst}
														<td class="text-center text-muted-foreground/50 whitespace-nowrap border-r border-border/30 text-xs tabular-nums select-none align-top" style="min-width: 36px; width: 36px; padding: 0;" rowspan={activeResult.nestedExpanded ? rowSpan : 1}>{fRow ? fRow._originalRowIndex + 1 : (currentPageNo - 1) * currentPageSize + i + 1}</td>
													{/if}
													{#each row as cell, cellIdx}
														{#if cellIdx >= colOffset}
															{@const colName = displayHeaders[cellIdx - colOffset]?.name || ''}
															{@const isNested = displayHeaders[cellIdx - colOffset]?.isNested}
															{#if activeResult.nestedExpanded && !isNested && !isGroupFirst}
															{:else}
																{@const span = activeResult.nestedExpanded && !isNested && isGroupFirst ? rowSpan : 1}
																{#if cell === null || cell === undefined}
																	<td
																		class="whitespace-nowrap overflow-hidden truncate select-text cursor-pointer border-r border-b border-border/40 {!isNested && activeResult.nestedExpanded ? 'align-top' : ''}"
																		style="padding: 0 4px; line-height: 27px; color: var(--color-text-tertiary, hsl(var(--muted-foreground) / 0.5));"
																		oncontextmenu={(e) => handleResultCellContextMenu(e, cell, row, displayHeaders, cellIdx - colOffset)}
																		rowspan={span}
																	>&lt;null&gt;</td>
																{:else}
																	<td
																		class="whitespace-nowrap overflow-hidden truncate text-foreground select-text cursor-pointer border-r border-b border-border/40 {!isNested && activeResult.nestedExpanded ? 'align-top' : ''}"
																		style="padding: 0 4px; line-height: 27px;"
																		ondblclick={() => handleCellDblClick(cell, colName)}
																		oncontextmenu={(e) => handleResultCellContextMenu(e, cell, row, displayHeaders, cellIdx - colOffset)}
																		rowspan={span}
																	>{formatCellValue(cell)}</td>
																{/if}
															{/if}
														{/if}
													{/each}
												</tr>
											{/each}
										</tbody>
									</table>
								</div>
								<!-- Status Bar -->
								<div class="flex items-center shrink-0 border-t border-border/50 text-xs text-muted-foreground select-none" style="height: 26px; padding: 0 8px; background: var(--color-bg-subtle, hsl(var(--muted)));">
									<span style="margin-right: 16px;">
										{#if activeResult.description}
											{activeResult.description}
										{:else if activeResult.success !== false}
											Query successful
										{:else}
											Query failed
										{/if}
									</span>
									{#if executionTime !== null}
										<span style="margin-right: 16px;">{executionTime}ms</span>
									{/if}
									<span>{activeResult.rows?.length || 0} row(s)</span>
									{#if hasMoreRows}
										<span class="text-muted-foreground/50 ml-1">(more available)</span>
									{/if}
								</div>
							</div>
						{:else if activeResult && 'message' in activeResult}
							<div class="flex flex-col items-center justify-center h-full gap-2">
								<span class="text-2xl font-semibold {activeResult.success !== false ? 'text-green-500' : 'text-destructive'}">
									{activeResult.success !== false ? 'Success' : 'Failed'}
								</span>
								<span class="text-xs text-muted-foreground">{activeResult.message}</span>
							</div>
						{:else if activeResult && 'error' in activeResult}
							<div class="p-3 text-xs text-destructive">{activeResult.error}</div>
						{:else}
							<div class="flex items-center justify-center h-full text-xs text-muted-foreground">
								{executing ? 'Executing query...' : 'Run a query to see results'}
							</div>
						{/if}
					{:else if resultActiveTab === 'flow'}
						{#if currentQuerySql && resultDataList.length > 0}
							{#await import('$lib/components/SQLFlowVisualizer/SQLFlowVisualizer.svelte') then { default: SQLFlowVisualizer }}
								<SQLFlowVisualizer sql={currentQuerySql} />
							{/await}
						{:else}
							<div class="flex items-center justify-center h-full text-xs text-muted-foreground">Run a query to see SQL flow</div>
						{/if}
					{:else if resultActiveTab === 'stats'}
						{@const statsResult = resultDataList[activeResultIndex]}
						{#if statsResult && 'headers' in statsResult && statsResult.rows?.length > 0}
							<div class="p-4 space-y-4 text-xs">
								<div class="flex items-center gap-4 text-muted-foreground">
									<span>{statsResult.headers.length} columns</span>
									<span class="text-border">|</span>
									<span>{statsResult.rows.length} rows</span>
								</div>
								{#each statsResult.headers as header, colIdx}
									{@const colName = header.name || header}
									{@const values = statsResult.rows.map((r: any[]) => r[colIdx])}
									{@const nonNull = values.filter((v: any) => v !== null && v !== undefined)}
									{@const nullPct = values.length > 0 ? ((values.length - nonNull.length) / values.length * 100) : 0}
									{@const distinctCount = new Set(nonNull.map(String)).size}
									{@const distinctPct = nonNull.length > 0 ? (distinctCount / nonNull.length * 100) : 0}
									{@const freq = nonNull.reduce((acc: Record<string, number>, v: any) => { const k = String(v); acc[k] = (acc[k] || 0) + 1; return acc; }, {} as Record<string, number>)}
									{@const topEntry = Object.entries(freq).sort(([,a], [,b]) => (b as number) - (a as number))[0]}
									{@const isNumeric = nonNull.length > 0 && !isNaN(Number(nonNull[0]))}
									<div class="border border-border rounded-lg p-3">
										<div class="flex items-center justify-between mb-2">
											<span class="font-semibold text-foreground">{colName}</span>
											<span class="text-muted-foreground">{isNumeric ? 'numeric' : 'string'}</span>
										</div>
										<div class="grid grid-cols-4 gap-3">
											<div>
												<div class="text-muted-foreground mb-0.5">Null %</div>
												<div class="flex items-center gap-1.5">
													<div class="flex-1 h-1.5 bg-muted rounded-full overflow-hidden">
														<div class="h-full bg-amber-500 rounded-full" style="width: {nullPct}%"></div>
													</div>
													<span class="text-foreground tabular-nums">{nullPct.toFixed(1)}%</span>
												</div>
											</div>
											<div>
												<div class="text-muted-foreground mb-0.5">Distinct %</div>
												<div class="flex items-center gap-1.5">
													<div class="flex-1 h-1.5 bg-muted rounded-full overflow-hidden">
														<div class="h-full bg-blue-500 rounded-full" style="width: {distinctPct}%"></div>
													</div>
													<span class="text-foreground tabular-nums">{distinctPct.toFixed(1)}%</span>
												</div>
											</div>
											<div>
												<div class="text-muted-foreground mb-0.5">Distinct</div>
												<span class="text-foreground font-medium">{distinctCount}</span>
											</div>
											<div>
												<div class="text-muted-foreground mb-0.5">Most Frequent</div>
												<span class="text-foreground font-medium truncate block max-w-[150px]" title={topEntry ? topEntry[0] : '-'}>{topEntry ? topEntry[0] : '-'}</span>
											</div>
										</div>
										{#if isNumeric}
											{@const nums = nonNull.map(Number).filter((n: number) => !isNaN(n)).sort((a: number, b: number) => a - b)}
											{@const min = nums[0]}
											{@const max = nums[nums.length - 1]}
											{@const sum = nums.reduce((a: number, b: number) => a + b, 0)}
											{@const mean = sum / nums.length}
											{@const mid = Math.floor(nums.length / 2)}
											{@const median = nums.length % 2 ? nums[mid] : (nums[mid - 1] + nums[mid]) / 2}
											<div class="grid grid-cols-4 gap-3 mt-2 pt-2 border-t border-border/50">
												<div>
													<div class="text-muted-foreground mb-0.5">Min</div>
													<span class="text-foreground font-medium tabular-nums">{min.toLocaleString()}</span>
												</div>
												<div>
													<div class="text-muted-foreground mb-0.5">Max</div>
													<span class="text-foreground font-medium tabular-nums">{max.toLocaleString()}</span>
												</div>
												<div>
													<div class="text-muted-foreground mb-0.5">Mean</div>
													<span class="text-foreground font-medium tabular-nums">{mean.toFixed(2)}</span>
												</div>
												<div>
													<div class="text-muted-foreground mb-0.5">Median</div>
													<span class="text-foreground font-medium tabular-nums">{typeof median === 'number' ? median.toLocaleString() : median}</span>
												</div>
											</div>
										{/if}
									</div>
								{/each}
							</div>
						{:else}
							<div class="flex items-center justify-center h-full text-xs text-muted-foreground">
								Run a query to see statistics
							</div>
						{/if}
					{/if}
				</div>
			</div>
			{/if}
			<!-- ===== End Console/TableView branching ===== -->
		{/if}
	</div>

	<!-- Schema Field Rows Renderer -->
	{#snippet schemaFieldRows(fields: SchemaField[], depth: number)}
		{#each fields as field (field.name + depth)}
			<tr class="border-b border-border/50 hover:bg-accent/30 transition-colors group">
				<td class="py-1.5 px-3">
					<div class="flex items-center" style="padding-left: {depth * 16}px;">
						{#if field.children && field.children.length > 0}
							<button
								class="p-0.5 mr-1 rounded hover:bg-accent text-muted-foreground shrink-0"
								onclick={() => toggleSchemaField(field)}
							>
								{#if field.expanded}
									<ChevronDown size={12} />
								{:else}
									<ChevronRight size={12} />
								{/if}
							</button>
						{:else}
							<span class="w-5 shrink-0"></span>
						{/if}
						{#if !schemaIsBigQuery && field.primaryKey}
							<KeyRound size={12} class="shrink-0 mr-1" style="color: #F59E0B" />
						{/if}
						<span class="truncate {field.children ? 'font-medium' : ''}">{field.name}</span>
					</div>
				</td>
				<td class="py-1.5 px-3 text-muted-foreground">{field.type}</td>
				{#if schemaIsBigQuery}
					<td class="py-1.5 px-3 text-muted-foreground">{field.mode}</td>
				{:else}
					<td class="py-1.5 px-3 text-muted-foreground">{field.mode === 'NULLABLE' ? 'YES' : 'NO'}</td>
					<td class="py-1.5 px-3">{#if field.primaryKey}<span class="text-amber-500 font-medium">PK</span>{/if}</td>
					<td class="py-1.5 px-3 text-muted-foreground truncate max-w-[100px]">{field.defaultValue ?? '-'}</td>
					<td class="py-1.5 px-3 text-muted-foreground truncate max-w-[120px]" title={field.comment || ''}>{field.comment || '-'}</td>
				{/if}
			</tr>
			{#if field.expanded && field.children}
				{@render schemaFieldRows(field.children, depth + 1)}
			{/if}
		{/each}
	{/snippet}

	<!-- Right Panel Resize Handle -->
	{#if ws.layout.panelRight && rightPanelExtend}
		<div
			class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isResizingRight ? 'bg-primary/30' : ''}"
			onmousedown={handleRightResize}
		></div>

		<!-- Right Panel Content -->
		<div class="flex h-full shrink-0 border-l border-border bg-card overflow-hidden" style="width: {ws.layout.panelRightWidth}px;">
			<div class="flex-1 flex flex-col overflow-hidden min-w-0">
				{#if rightPanelExtend === 'ddl'}
					<!-- DDL / Schema View -->
					<div class="flex items-center justify-between px-3 py-1.5 border-b border-border">
						<div class="flex items-center gap-1">
							{#if schemaFields.length > 0}
								<button
									class="px-2 py-1 text-xs rounded transition-colors {ddlSubTab === 'schema' ? 'bg-accent text-foreground font-medium' : 'text-muted-foreground hover:text-foreground hover:bg-accent/50'}"
									onclick={() => ddlSubTab = 'schema'}
								>Schema</button>
								<button
									class="px-2 py-1 text-xs rounded transition-colors {ddlSubTab === 'ddl' ? 'bg-accent text-foreground font-medium' : 'text-muted-foreground hover:text-foreground hover:bg-accent/50'}"
									onclick={() => ddlSubTab = 'ddl'}
								>DDL</button>
								<span class="ml-2 text-xs text-muted-foreground truncate max-w-[150px]">{ddlTableName}</span>
							{:else}
								<span class="text-xs font-medium">DDL {ddlTableName ? `- ${ddlTableName}` : ''}</span>
							{/if}
						</div>
						<button class="p-0.5 rounded hover:bg-accent text-muted-foreground" onclick={() => toggleRightExtend('ddl')}>
							<X size={14} />
						</button>
					</div>
					<div class="flex-1 overflow-hidden">
						{#if ddlLoading}
							<div class="flex items-center justify-center h-full">
								<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-primary"></div>
							</div>
						{:else if ddlSubTab === 'schema' && schemaFields.length > 0}
							<!-- Schema Table View -->
							<div class="h-full overflow-auto">
								<table class="w-full text-xs">
									<thead class="sticky top-0 z-10 bg-muted/80 backdrop-blur-sm">
										<tr class="border-b border-border">
											<th class="text-left py-1.5 px-3 font-medium text-muted-foreground">Field name</th>
											<th class="text-left py-1.5 px-3 font-medium text-muted-foreground w-28">Type</th>
											{#if schemaIsBigQuery}
												<th class="text-left py-1.5 px-3 font-medium text-muted-foreground w-24">Mode</th>
											{:else}
												<th class="text-left py-1.5 px-3 font-medium text-muted-foreground w-16">Null</th>
												<th class="text-left py-1.5 px-3 font-medium text-muted-foreground w-16">Key</th>
												<th class="text-left py-1.5 px-3 font-medium text-muted-foreground w-24">Default</th>
												<th class="text-left py-1.5 px-3 font-medium text-muted-foreground">Comment</th>
											{/if}
										</tr>
									</thead>
									<tbody>
										{@render schemaFieldRows(schemaFields, 0)}
									</tbody>
								</table>
							</div>
						{:else if formattedDDL || ddlContent}
							{#await import('$lib/components/MonacoEditor/MonacoEditor.svelte') then { default: MonacoEditor }}
								<MonacoEditor value={formattedDDL || ddlContent} language="sql" readOnly={true} />
							{/await}
						{:else}
							<div class="flex items-center justify-center h-full text-xs text-muted-foreground">
								Click on a table to view its DDL
							</div>
						{/if}
					</div>
			{:else if rightPanelExtend === 'aiChat'}
					<!-- AI Chat Embed -->
					<div class="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
						<div class="flex items-center gap-2">
							<AISparkleIcon size={16} />
							<span class="text-sm font-semibold">AI Chat</span>
						</div>
						<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => toggleRightExtend('aiChat')}>
							<X size={14} />
						</button>
					</div>
					<div class="flex-1 overflow-hidden">
						<EmbeddedAIChat />
					</div>
				{/if}
			</div>
		</div>
	{/if}

	<!-- Right Panel Icon Nav -->
	<div class="flex flex-col items-center py-2 px-1 gap-1.5 bg-card border-l border-border w-10 shrink-0">
		<button
			class="w-7 h-7 flex items-center justify-center cursor-pointer rounded transition-all
				{rightPanelExtend === 'ddl' ? 'bg-accent text-primary' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}"
			onclick={() => toggleRightExtend('ddl')}
			title="Schema / DDL"
		>
			<Eye size={18} strokeWidth={1.5} />
		</button>
			<!-- Divider -->
		<div class="w-5 h-px bg-border"></div>
		<button
			class="w-7 h-7 flex items-center justify-center cursor-pointer rounded transition-all
				{rightPanelExtend === 'aiChat' ? 'bg-purple-500/15' : 'text-muted-foreground hover:bg-purple-500/10'}"
			onclick={() => toggleRightExtend('aiChat')}
			title="AI Chat"
		>
			<AISparkleIcon size={16} />
		</button>
	</div>
</div>

<!-- Context Menu -->
{#if contextMenu}
	<ContextMenu
		items={contextMenu.items}
		x={contextMenu.x}
		y={contextMenu.y}
		onclose={() => contextMenu = null}
	/>
{/if}

<!-- Query Estimator Drawer -->
{#if showQueryEstimator}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed left-0 top-0 w-screen h-screen z-[300]" onclick={() => { if (!isResizingEstimator) showQueryEstimator = false; }}>
		<!-- Resize handle -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="fixed top-0 h-screen w-1.5 cursor-col-resize hover:bg-primary/20 transition-colors z-[302]"
			style="right: {queryEstimatorWidth}px;"
			onmousedown={(e) => {
				e.preventDefault();
				e.stopPropagation();
				isResizingEstimator = true;
				const startX = e.clientX;
				const startW = queryEstimatorWidth;
				function onMouseMove(ev: MouseEvent) {
					queryEstimatorWidth = Math.max(350, Math.min(800, startW + (startX - ev.clientX)));
				}
				function onMouseUp() {
					window.removeEventListener('mousemove', onMouseMove);
					window.removeEventListener('mouseup', onMouseUp);
					setTimeout(() => { isResizingEstimator = false; }, 0);
				}
				window.addEventListener('mousemove', onMouseMove);
				window.addEventListener('mouseup', onMouseUp);
			}}
		></div>
		<div
			class="fixed right-0 top-0 h-screen bg-background border-l border-border shadow-xl flex flex-col z-[301]"
			style="width: {queryEstimatorWidth}px;"
			onclick={(e) => e.stopPropagation()}
		>
			<!-- Header -->
			<div class="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
				<div class="flex items-center gap-2">
					<Search size={16} class="text-primary" />
					<span class="font-medium">Query Estimator</span>
				</div>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => showQueryEstimator = false}>
					<X size={16} />
				</button>
			</div>

			<!-- Content -->
			<div class="flex-1 overflow-auto p-4">
				{#if queryEstimatorLoading}
					<div class="flex flex-col items-center justify-center h-full gap-3">
						<div class="animate-spin rounded-full h-6 w-6 border-b-2 border-primary"></div>
						<span class="text-sm text-muted-foreground">Estimating query...</span>
					</div>
				{:else if queryEstimatorData}
					{@const resourceLevel = calculateResourceLevel(queryEstimatorData.metrics, getSelectedConnection()?.type)}
					{@const config = levelConfig[resourceLevel]}
					{@const percent = resourceLevel === 'low' ? 25 : resourceLevel === 'medium' ? 50 : resourceLevel === 'high' ? 75 : 100}

					<div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
						<!-- Left Column: Gauge + Metrics -->
						<div class="space-y-4">
							<!-- Resource Gauge -->
							<div class="p-4 rounded-lg border {config.bgLight} {config.borderColor}">
								<div class="flex items-center gap-3 mb-3">
									{#if resourceLevel === 'low'}
										<CheckCircle class="w-8 h-8 {config.color}" />
									{:else if resourceLevel === 'medium'}
										<AlertCircle class="w-8 h-8 {config.color}" />
									{:else if resourceLevel === 'high'}
										<AlertTriangle class="w-8 h-8 {config.color}" />
									{:else}
										<XCircle class="w-8 h-8 {config.color}" />
									{/if}
									<div>
										<div class="font-semibold text-lg {config.color}">{config.label}</div>
										<div class="text-xs text-muted-foreground">{config.description}</div>
									</div>
								</div>
								<!-- Progress bar -->
								<div class="w-full h-2 rounded-full bg-muted overflow-hidden">
									<div
										class="h-full rounded-full transition-all duration-500 {config.bgColor}"
										style="width: {percent}%"
									></div>
								</div>
							</div>

							<!-- Metrics -->
							<div>
								<h4 class="text-xs font-medium text-muted-foreground uppercase mb-2">Metrics</h4>
								<div class="space-y-2">
									<div class="flex items-center justify-between p-2 rounded-md bg-muted/50">
										<div class="flex items-center gap-2 text-muted-foreground">
											<Database size={14} />
											<span class="text-xs">Est. Rows</span>
										</div>
										<span class="text-sm font-medium">{formatNumber(queryEstimatorData.metrics.estimatedRows)}</span>
									</div>
									<div class="flex items-center justify-between p-2 rounded-md bg-muted/50">
										<div class="flex items-center gap-2 text-muted-foreground">
											<Zap size={14} />
											<span class="text-xs">Est. Cost</span>
										</div>
										<span class="text-sm font-medium">{formatEstimatedCost(queryEstimatorData.metrics.estimatedCost, getSelectedConnection()?.type)}</span>
									</div>
									<div class="flex items-center justify-between p-2 rounded-md bg-muted/50">
										<div class="flex items-center gap-2 text-muted-foreground">
											<HardDrive size={14} />
											<span class="text-xs">Memory</span>
										</div>
										<span class="text-sm font-medium">{queryEstimatorData.metrics.estimatedMemoryGB ? `${queryEstimatorData.metrics.estimatedMemoryGB.toFixed(1)} GB` : '-'}</span>
									</div>
									<div class="flex items-center justify-between p-2 rounded-md bg-muted/50">
										<div class="flex items-center gap-2 text-muted-foreground">
											<Clock size={14} />
											<span class="text-xs">Est. Time</span>
										</div>
										<span class="text-sm font-medium">{formatTime(queryEstimatorData.metrics.estimatedTimeSeconds)}</span>
									</div>
								</div>
							</div>
						</div>

						<!-- Right Column: Warnings -->
						<div>
							<h4 class="text-xs font-medium text-muted-foreground uppercase mb-2">Warnings ({queryEstimatorData.warnings.length})</h4>
							{#if queryEstimatorData.warnings.length === 0}
								<div class="flex items-center gap-2 p-3 rounded-md bg-green-500/10 text-green-600">
									<CheckCircle size={16} />
									<span class="text-sm">No issues detected</span>
								</div>
							{:else}
								<div class="space-y-2">
									{#each queryEstimatorData.warnings as warning}
										{@const warnColor = warning.type === 'error' ? 'text-red-500 bg-red-500/10' : warning.type === 'warning' ? 'text-yellow-500 bg-yellow-500/10' : 'text-blue-500 bg-blue-500/10'}
										<div class="flex items-start gap-2 p-2 rounded-md cursor-help {warnColor}" title={warning.detail || ''}>
											{#if warning.type === 'error'}
												<XCircle size={14} class="mt-0.5 shrink-0" />
											{:else if warning.type === 'warning'}
												<AlertTriangle size={14} class="mt-0.5 shrink-0" />
											{:else}
												<AlertCircle size={14} class="mt-0.5 shrink-0" />
											{/if}
											<span class="text-xs break-all">{warning.message}</span>
										</div>
									{/each}
								</div>
							{/if}
						</div>
					</div>

					<!-- Query Plan Tree -->
					{#if queryEstimatorData.plan || queryEstimatorData.rawPlan}
						<div class="mt-4">
							<h4 class="text-xs font-medium text-muted-foreground uppercase mb-2">Query Plan</h4>
							<div class="border border-border rounded-lg p-2 max-h-[300px] overflow-auto bg-muted/30">
								{#if queryEstimatorData.plan}
									{#snippet planTreeNode(node: PlanNode, depth: number)}
										{@const hasChildren = node.children && node.children.length > 0}
										<div class="select-none">
											<button
												class="flex items-start gap-1 py-1 px-2 rounded hover:bg-muted/50 cursor-pointer w-full text-left {depth > 0 ? 'ml-4' : ''}"
												onclick={(e) => {
													const target = e.currentTarget;
													const childrenEl = target.nextElementSibling;
													if (childrenEl) childrenEl.classList.toggle('hidden');
													const chevron = target.querySelector('.plan-chevron');
													if (chevron) chevron.classList.toggle('rotate-90');
												}}
											>
												{#if hasChildren}
													<ChevronDown size={14} class="mt-0.5 shrink-0 plan-chevron transition-transform" />
												{:else}
													<div class="w-3.5"></div>
												{/if}
												<div class="flex-1 min-w-0">
													<div class="flex items-center gap-2">
														<span class="text-xs font-medium text-primary">{node.operation}</span>
														{#if node.cost}
															<span class="text-[10px] text-muted-foreground">cost: {formatNumber(node.cost)}</span>
														{/if}
														{#if node.rows}
															<span class="text-[10px] text-muted-foreground">rows: {formatNumber(node.rows)}</span>
														{/if}
													</div>
													{#if node.details}
														<div class="text-[10px] text-muted-foreground truncate">{node.details}</div>
													{/if}
												</div>
											</button>
											{#if hasChildren}
												<div class="border-l border-border ml-2">
													{#each node.children! as child}
														{@render planTreeNode(child, depth + 1)}
													{/each}
												</div>
											{/if}
										</div>
									{/snippet}
									{@render planTreeNode(queryEstimatorData.plan, 0)}
								{:else}
									<pre class="text-xs font-mono whitespace-pre-wrap">{queryEstimatorData.rawPlan}</pre>
								{/if}
							</div>
						</div>
					{/if}

					<!-- AI Analysis Section -->
					<div class="mt-4">
						{#if !aiAnalysisVisible}
							<button
								class="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-lg border border-dashed border-primary/30 hover:border-primary/60 hover:bg-primary/5 transition-all text-sm text-primary font-medium"
								onclick={handleAIAnalysis}
								disabled={aiAnalysisLoading}
							>
								<AISparkleIcon size={16} />
								Analyze with AI
							</button>
						{:else}
							<div class="border border-border rounded-lg overflow-hidden">
								<div class="flex items-center justify-between px-3 py-2 bg-primary/5 border-b border-border">
									<div class="flex items-center gap-2">
										<AISparkleIcon size={14} />
										<span class="text-xs font-medium">AI Performance Analysis</span>
									</div>
									<div class="flex items-center gap-1">
										{#if !aiAnalysisLoading}
											<button
												class="p-1 rounded hover:bg-accent text-muted-foreground"
												title="Re-analyze"
												onclick={handleAIAnalysis}
											>
												<RotateCcw size={12} />
											</button>
										{/if}
										<button
											class="p-1 rounded hover:bg-accent text-muted-foreground"
											onclick={() => { aiAnalysisVisible = false; aiAnalysisContent = ''; }}
										>
											<X size={12} />
										</button>
									</div>
								</div>
								<div class="p-3 max-h-[400px] overflow-auto">
									{#if aiAnalysisLoading}
										<div class="flex items-center gap-3 py-6 justify-center">
											<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-primary"></div>
											<span class="text-sm text-muted-foreground">Analyzing execution plan...</span>
										</div>
									{:else if aiAnalysisContent}
										<div class="prose prose-sm dark:prose-invert max-w-none [&_h3]:text-sm [&_h3]:font-semibold [&_h3]:mt-3 [&_h3]:mb-1.5 [&_p]:text-xs [&_p]:leading-relaxed [&_li]:text-xs [&_li]:leading-relaxed [&_code]:text-[11px] [&_pre]:text-[11px] [&_pre]:bg-muted/50 [&_pre]:rounded-md">
											<MarkdownRenderer content={aiAnalysisContent} />
										</div>
									{/if}
								</div>
							</div>
						{/if}
					</div>
				{:else}
					<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
						Select SQL and click Estimate
					</div>
				{/if}
			</div>
		</div>
	</div>
{/if}

<!-- Chart Modal -->
{#if showChartModal}
	{#await import('$lib/components/ChartModal/ChartModal.svelte') then { default: ChartModal }}
		{@const activeResult = resultDataList[activeResultIndex]}
		<ChartModal
			onclose={() => showChartModal = false}
			onsave={handleChartSave}
			initialSql={currentQuerySql}
			initialResultData={activeResult && 'headers' in activeResult ? { headerList: activeResult.headers, dataList: activeResult.rows } : undefined}
			initialDataSourceId={selectedConnectionId || undefined}
			initialDatabase={selectedDatabaseName}
			sourceType="WORKSPACE"
		/>
	{/await}
{/if}

<!-- Dashboard Selection Modal -->
{#if showDashboardSelect}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" onclick={() => { showDashboardSelect = false; pendingChartData = null; }}>
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="bg-card rounded-lg shadow-xl border border-border w-[400px] max-h-[500px] flex flex-col" onclick={(e) => e.stopPropagation()}>
			<div class="flex items-center justify-between px-4 py-3 border-b border-border">
				<span class="text-sm font-medium">Pin to Dashboard</span>
				<button class="p-1 rounded hover:bg-accent" onclick={() => { showDashboardSelect = false; pendingChartData = null; }}>
					<X size={16} />
				</button>
			</div>
			<div class="flex-1 overflow-auto p-4 space-y-3">
				{#if dashboardList.length > 0}
					<div class="text-xs text-muted-foreground mb-2">Select existing dashboard:</div>
					{#each dashboardList as dash}
						<button
							class="w-full text-left px-3 py-2 rounded border transition-colors text-sm
								{selectedDashboardId === dash.id ? 'border-primary bg-primary/10 text-foreground' : 'border-border hover:bg-accent text-muted-foreground hover:text-foreground'}"
							onclick={() => { selectedDashboardId = dash.id; newDashboardName = ''; }}
						>
							{dash.name}
						</button>
					{/each}
					<div class="flex items-center gap-2 text-xs text-muted-foreground my-2">
						<div class="flex-1 h-px bg-border"></div>
						<span>or</span>
						<div class="flex-1 h-px bg-border"></div>
					</div>
				{/if}
				<div class="text-xs text-muted-foreground mb-1">Create new dashboard:</div>
				<input
					type="text"
					class="w-full px-3 py-2 rounded border border-border bg-background text-sm focus:border-primary focus:outline-none"
					placeholder="Dashboard name"
					bind:value={newDashboardName}
					onfocus={() => selectedDashboardId = undefined}
				/>
			</div>
			<div class="flex items-center justify-end gap-2 px-4 py-3 border-t border-border">
				<Button variant="outline" class="h-8 text-xs" onclick={() => { showDashboardSelect = false; pendingChartData = null; }}>Cancel</Button>
				<Button
					class="h-8 text-xs"
					disabled={(!selectedDashboardId && !newDashboardName.trim()) || savingChart}
					onclick={handlePinToDashboard}
				>
					{savingChart ? 'Saving...' : 'Pin to Dashboard'}
				</Button>
			</div>
		</div>
	</div>
{/if}

<!-- Cell Detail Modal -->
{#if cellDetailValue !== null}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" onclick={() => { cellDetailValue = null; }}>
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="bg-card rounded-lg shadow-xl border border-border w-[600px] max-h-[80vh] flex flex-col" onclick={(e) => e.stopPropagation()}>
			<div class="flex items-center justify-between px-4 py-3 border-b border-border">
				<span class="text-sm font-medium">{cellDetailColumn || 'Cell Value'}</span>
				<div class="flex items-center gap-1">
					<button class="p-1 rounded hover:bg-accent text-muted-foreground" title="Copy" onclick={() => { navigator.clipboard.writeText(cellDetailValue || ''); message.success('Copied'); }}>
						<Copy size={14} />
					</button>
					<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => cellDetailValue = null}>
						<X size={16} />
					</button>
				</div>
			</div>
			<div class="flex-1 overflow-auto p-4">
				<pre class="text-xs font-mono whitespace-pre-wrap break-all text-foreground">{cellDetailValue}</pre>
			</div>
		</div>
	</div>
{/if}

<!-- Editor Context Menu -->
{#if editorContextMenu}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50" onclick={() => editorContextMenu = null}>
		<div
			class="absolute bg-popover border border-border rounded-md shadow-lg py-1 min-w-[200px] z-50"
			style="left: {editorContextMenu.x}px; top: {editorContextMenu.y}px;"
			onclick={(e) => e.stopPropagation()}
		>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuRun}>
				<Play size={12} /> Run Query
				<span class="ml-auto text-muted-foreground text-[10px]">{formatKeys(getShortcutById('run-query')!.keys)}</span>
			</button>
			<div class="h-px bg-border my-1"></div>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuFormat}>
				<AlignLeft size={12} /> Format SQL
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuExplain}>
				<Wand2 size={12} /> Explain SQL (AI)
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuOptimize}>
				<Zap size={12} /> Optimize SQL
			</button>
			<div class="h-px bg-border my-1"></div>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2 {editorContextMenu.hasSelection ? '' : 'opacity-40 pointer-events-none'}" onclick={editorMenuCut}>
				<Scissors size={12} /> Cut
				<span class="ml-auto text-muted-foreground text-[10px]">Ctrl+X</span>
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2 {editorContextMenu.hasSelection ? '' : 'opacity-40 pointer-events-none'}" onclick={editorMenuCopy}>
				<Copy size={12} /> Copy
				<span class="ml-auto text-muted-foreground text-[10px]">Ctrl+C</span>
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuPaste}>
				<ClipboardPaste size={12} /> Paste
				<span class="ml-auto text-muted-foreground text-[10px]">Ctrl+V</span>
			</button>
			<div class="h-px bg-border my-1"></div>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={editorMenuSelectAll}>
				<MousePointerClick size={12} /> Select All
				<span class="ml-auto text-muted-foreground text-[10px]">Ctrl+A</span>
			</button>
		</div>
	</div>
{/if}

<!-- Result Table Context Menu -->
{#if resultContextMenu}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50" onclick={() => resultContextMenu = null}>
		<div
			class="absolute bg-popover border border-border rounded-md shadow-lg py-1 min-w-[180px] z-50"
			style="left: {resultContextMenu.x}px; top: {resultContextMenu.y}px;"
		>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={() => copyToClipboard(resultContextMenu?.cellValue === null || resultContextMenu?.cellValue === undefined ? 'NULL' : String(resultContextMenu.cellValue))}>
				<Copy size={12} /> Copy Cell
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={() => { if (resultContextMenu) copyRowAsTab(resultContextMenu.rowData); }}>
				<Clipboard size={12} /> Copy Row (Tab)
			</button>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={() => { if (resultContextMenu) copyRowAsInsert(resultContextMenu.rowData, resultContextMenu.headers); }}>
				<FileCode size={12} /> Copy as INSERT
			</button>
			<div class="h-px bg-border my-1"></div>
			<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors flex items-center gap-2" onclick={() => {
				if (resultContextMenu) {
					const val = resultContextMenu.cellValue;
					const colName = resultContextMenu.headers[resultContextMenu.colIdx]?.name || '';
					handleCellDblClick(val, colName);
				}
				resultContextMenu = null;
			}}>
				<Eye size={12} /> View Data
			</button>
		</div>
	</div>
{/if}
