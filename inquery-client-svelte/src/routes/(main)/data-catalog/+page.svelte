<script lang="ts">
	import { onMount } from 'svelte';
	import i18n from '$lib/i18n';
	import { Button, Card, Badge, Separator, Spinner, Dialog, Checkbox } from '$lib/components/ui';
	import { Popover, PopoverTrigger, PopoverContent } from '$lib/components/ui';
	import { Tabs, TabsList, TabsTrigger, TabsContent } from '$lib/components/ui';
	import { Switch } from '$lib/components/ui';
	import {
		LibraryBig, Database, Book, Folder, ChevronRight, ChevronDown, Search, RefreshCw,
		Sparkles, Save, AlertTriangle, FolderX, Eye, Table2, Key,
		Lightbulb, X, Loader2, Check, AlertCircle, CheckCircle, GitBranch, Maximize2
	} from 'lucide-svelte';
	import connectionService from '$lib/service/connection';
	import catalogService from '$lib/service/catalog';
	import businessInsightService from '$lib/service/businessInsight';
	import configService from '$lib/service/config';
	import { getWorkspaceStore, setCurrentConnection } from '$lib/stores/workspace.svelte';
	import MarkdownRenderer from '$lib/components/MarkdownRenderer/MarkdownRenderer.svelte';
	import AISparkleIcon from '$lib/components/AISparkleIcon/AISparkleIcon.svelte';
	import LineageGraph from '$lib/components/LineageGraph/LineageGraph.svelte';
	import { filterGraphByTable } from '$lib/components/LineageGraph/lineageLayout';
	import type { IConnectionListItem } from '$lib/types/connection';
	import type { IDataCatalogColumn, ILineageGraph } from '$lib/service/catalog';
	import type { IBusinessInsight } from '$lib/service/businessInsight';
	import { databaseMap } from '$lib/types/database';
	import message from '$lib/utils/message';
	import {
		getCollectionStore,
		setBulkCollecting,
		setShowCollectionProgress,
		setCollectionProgressList,
		updateCollectionProgress,
		setCollectionAbortController,
		resetCollectionProgress,
		saveActiveJobs,
		loadActiveJobs,
		clearActiveJobs,
		type CollectionProgress,
		type StoredCollectionJob
	} from '$lib/stores/dataCatalog.svelte';

	const collectionStore = getCollectionStore();
	const ws = getWorkspaceStore();

	// ═════════════════════════════════════════════════
	// Main view tab: catalog vs lineage
	// ═════════════════════════════════════════════════
	let mainTab = $state<'catalog' | 'lineage'>('catalog');

	// ═════════════════════════════════════════════════
	// Lineage state
	// ═════════════════════════════════════════════════
	let lineageGraph = $state<ILineageGraph | null>(null);
	let lineageLoading = $state(false);

	type LineageStatus = {
		state: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string;
		lastRunTime?: string;
		nodeCount?: number;
		edgeCount?: number;
		errorMessage?: string;
	};
	let lineageStatus = $state<LineageStatus | null>(null);
	let lineageDetecting = $state(false);
	let lineageStatusPollTimer: ReturnType<typeof setTimeout> | null = null;

	let filteredLineageCount = $derived.by(() => {
		if (!lineageGraph) return 0;
		if (!selectedTable?.tableName) return lineageGraph.nodes.length;
		return filterGraphByTable(lineageGraph, selectedTable.tableName, selectedTable.databaseName, selectedTable.schemaName).nodes.length;
	});

	async function loadLineageGraph() {
		if (!selectedConnectionId) return;
		lineageLoading = true;
		try {
			const res = await catalogService.getLineageGraph({ dataSourceId: selectedConnectionId });
			if (res && res.nodes && res.nodes.length > 0) {
				lineageGraph = res;
			} else {
				lineageGraph = null;
			}
		} catch {
			lineageGraph = null;
		} finally {
			lineageLoading = false;
		}
		void refreshLineageStatus();
	}

	async function refreshLineageStatus() {
		if (!selectedConnectionId) return;
		try {
			const res = await catalogService.getLineageStatus({ dataSourceId: selectedConnectionId });
			lineageStatus = (res as LineageStatus) ?? null;
			// If the backend is still detecting (e.g. detection was kicked off by the
			// `connect` call when entering the workspace, not by the Re-detect button),
			// start polling so the graph refreshes automatically when it finishes.
			if (lineageStatus?.state === 'RUNNING' && !lineageStatusPollTimer) {
				lineageDetecting = true;
				pollLineageStatus();
			}
		} catch {
			// status endpoint may 404 if detection has never run for this dataSource — leave as-is
		}
	}

	function stopLineageStatusPolling() {
		if (lineageStatusPollTimer) {
			clearTimeout(lineageStatusPollTimer);
			lineageStatusPollTimer = null;
		}
	}

	function pollLineageStatus() {
		stopLineageStatusPolling();
		lineageStatusPollTimer = setTimeout(async () => {
			await refreshLineageStatus();
			if (lineageStatus?.state === 'RUNNING') {
				pollLineageStatus();
			} else {
				lineageDetecting = false;
				// detection finished — refresh the graph regardless of success/failure
				await loadLineageGraph();
			}
		}, 2000);
	}

	async function redetectLineage() {
		if (!selectedConnectionId || lineageDetecting) return;
		lineageDetecting = true;
		try {
			await catalogService.autoDetectLineage({ dataSourceId: selectedConnectionId });
			lineageStatus = { ...(lineageStatus ?? { state: 'RUNNING' }), state: 'RUNNING' };
			pollLineageStatus();
		} catch (e: any) {
			lineageDetecting = false;
			message.error(e?.message || 'Failed to start lineage detection');
		}
	}

	function formatLineageTime(iso?: string): string {
		if (!iso) return '';
		const d = new Date(iso);
		if (isNaN(d.getTime())) return '';
		const pad = (n: number) => String(n).padStart(2, '0');
		return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
	}


	// ═════════════════════════════════════════════════
	// Connection state
	// ═════════════════════════════════════════════════
	let connections = $state<IConnectionListItem[]>([]);
	let selectedConnectionId = $state<number | null>(null);
	let connPopoverOpen = $state(false);
	let loading = $state(false);
	let search = $state('');
	let treeFilter = $state<'all' | 'saved' | 'unsaved'>('all');

	// ═════════════════════════════════════════════════
	// Tree state (DB → Schema → Table)
	// ═════════════════════════════════════════════════
	interface TreeNode {
		name: string;
		type: 'database' | 'schema' | 'table' | 'table_group' | 'view';
		children?: TreeNode[];
		expanded?: boolean;
		loading?: boolean;
		databaseName?: string;
		schemaName?: string;
		tableName?: string;
		tableGroupCount?: number;
		catalogTableName?: string;
	}
	let treeData = $state<TreeNode[]>([]);

	function getSelectedConn(): IConnectionListItem | undefined {
		return connections.find(c => c.id === selectedConnectionId);
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

	// GA4 BigQuery export variants (events_YYYYMMDD, events_intraday_YYYYMMDD,
	// events_fresh_YYYYMMDD) share the same schema, so collapse the variant suffix
	// into the base prefix and treat them as a single logical group.
	function normalizeShardPrefix(prefix: string): string {
		return prefix.replace(/_(intraday|fresh)$/i, '');
	}

	function groupShardedTables(tables: TreeNode[], isBigQuery: boolean): TreeNode[] {
		if (!isBigQuery) return tables;
		const groups: Record<string, TreeNode[]> = {};
		const ungrouped: TreeNode[] = [];

		for (const t of tables) {
			const { isSharded, prefix } = isShardedTableName(t.name);
			if (isSharded) {
				const base = normalizeShardPrefix(prefix);
				if (!groups[base]) groups[base] = [];
				groups[base].push(t);
			} else {
				ungrouped.push(t);
			}
		}

		const result: TreeNode[] = [...ungrouped];
		for (const [prefix, members] of Object.entries(groups)) {
			const sorted = members.sort((a, b) => b.name.localeCompare(a.name));
			// Prefer a finalized daily table as the representative (same schema as
			// intraday/fresh but more stable); fall back to the latest by name.
			const representative = sorted.find(m => !/_(intraday|fresh)_\d{8}$/i.test(m.name)) || sorted[0];
			result.push({
				name: `${prefix}_`,
				type: 'table_group',
				tableName: representative.name,
				catalogTableName: `${prefix}_YYYYMMDD`,
				databaseName: representative.databaseName,
				schemaName: representative.schemaName,
			});
		}
		return result.sort((a, b) => a.name.localeCompare(b.name));
	}

	// ═════════════════════════════════════════════════
	// Selected table / Catalog data
	// ═════════════════════════════════════════════════
	let selectedTable = $state<{ databaseName: string; schemaName?: string; tableName: string; catalogTableName?: string } | null>(null);
	let catalogData = $state<{ tableDescription: string; columns: IDataCatalogColumn[] } | null>(null);
	let editedColumns = $state<IDataCatalogColumn[]>([]);
	let tableDescription = $state('');
	let originalTableDescription = $state('');
	let originalColumns = $state<IDataCatalogColumn[]>([]);
	let catalogLoading = $state(false);
	let savingCatalog = $state(false);
	let collectingAI = $state(false);
	let collectingExamples = $state(false);

	// Example values input state (comma-separated display)
	let exampleValuesInput = $state<Record<string, string>>({});
	let expandedExampleRows = $state<Set<string>>(new Set());
	const EXAMPLE_TAG_LIMIT = 3;

	// "View larger" description modal: lets the user expand a cramped description cell
	// (e.g. the long per-event event_name definition) into a full-size editable textarea.
	let showDescDialog = $state(false);
	let descDialogTitle = $state('');
	// Target of the modal edit: the table description, or a specific column row by index.
	let descDialogTarget = $state<{ type: 'table' } | { type: 'column'; idx: number } | null>(null);

	const descDialogValue = $derived(
		descDialogTarget?.type === 'table'
			? tableDescription
			: descDialogTarget?.type === 'column'
				? (editedColumns[descDialogTarget.idx]?.description ?? '')
				: ''
	);

	function openDescDialog(target: { type: 'table' } | { type: 'column'; idx: number }, title: string) {
		descDialogTarget = target;
		descDialogTitle = title;
		showDescDialog = true;
	}

	function updateDescDialogValue(value: string) {
		if (!descDialogTarget) return;
		if (descDialogTarget.type === 'table') {
			tableDescription = value;
		} else {
			const i = descDialogTarget.idx;
			editedColumns[i] = { ...editedColumns[i], description: value };
			editedColumns = [...editedColumns];
		}
	}

	// ═════════════════════════════════════════════════
	// Unsaved changes
	// ═════════════════════════════════════════════════
	let showUnsavedDialog = $state(false);
	let pendingTableChange = $state<typeof selectedTable>(null);

	let hasUnsavedChanges = $derived.by(() => {
		if (tableDescription !== originalTableDescription) return true;
		if (editedColumns.length !== originalColumns.length) return true;
		for (let i = 0; i < editedColumns.length; i++) {
			if (editedColumns[i]?.description !== originalColumns[i]?.description) return true;
			if (editedColumns[i]?.exampleValues !== originalColumns[i]?.exampleValues) return true;
		}
		return false;
	});

	// ═════════════════════════════════════════════════
	// Bulk AI Collection (multi-step)
	// ═════════════════════════════════════════════════
	let showAIConfigDialog = $state(false);
	let showDbSchemaDialog = $state(false);
	let showConfirmDialog = $state(false);
	let showCompletionDialog = $state(false);

	// DB/Schema selection tree
	interface SelectionNode {
		title: string;
		key: string;
		isLeaf?: boolean;
		children?: SelectionNode[];
	}
	let selectionTree = $state<SelectionNode[]>([]);
	let selectionTreeLoading = $state(false);
	let checkedKeys = $state<Set<string>>(new Set());
	let expandedKeys = $state<Set<string>>(new Set());

	// Confirmation data
	let confirmData = $state<{ totalTableCount: number; schemas: { databaseName: string; schemaName: string; displayName: string }[] } | null>(null);

	// Progress - uses global store (collectionStore)

	// Table active toggle
	let tableActiveMap = $state<Record<string, boolean>>({});

	// ═════════════════════════════════════════════════
	// Business Insight
	// ═════════════════════════════════════════════════
	let showInsightDialog = $state(false);
	let insightData = $state<IBusinessInsight>({ dataSourceId: 0, databaseName: '', playStoreLink: '', appStoreLink: '', webLink: '', insightContent: '' });
	let insightLoading = $state(false);
	let insightGenerating = $state(false);
	let insightSaving = $state(false);
	let insightTab = $state('edit');

	// ═════════════════════════════════════════════════
	// Column resize
	// ═════════════════════════════════════════════════
	let columnWidths = $state<Record<string, number>>({ name: 150, columnType: 50, description: 370, exampleValues: 300 });
	let resizingColumn = $state<string | null>(null);

	// Panel resize
	let panelWidth = $state(320);
	let isResizing = $state(false);

	// ═════════════════════════════════════════════════
	// STRUCT/RECORD column flattening (BigQuery)
	// ═════════════════════════════════════════════════
	function parseStructFields(columnType: string): Array<{ name: string; columnType: string }> {
		if (!columnType) return [];
		let structContent = columnType.trim();
		if (structContent.toUpperCase().startsWith('ARRAY<')) structContent = structContent.slice(6, -1).trim();
		if (!structContent.toUpperCase().startsWith('STRUCT<')) return [];
		const startIdx = structContent.indexOf('<') + 1;
		let depth = 1, endIdx = startIdx;
		for (let i = startIdx; i < structContent.length && depth > 0; i++) {
			if (structContent[i] === '<') depth++;
			else if (structContent[i] === '>') depth--;
			if (depth === 0) endIdx = i;
		}
		const fieldsStr = structContent.substring(startIdx, endIdx);
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
		const trimmed = currentField.trim();
		if (trimmed) {
			const spaceIdx = trimmed.indexOf(' ');
			if (spaceIdx > 0) fields.push({ name: trimmed.substring(0, spaceIdx), columnType: trimmed.substring(spaceIdx + 1).trim() });
		}
		return fields;
	}

	function flattenColumns(columns: IDataCatalogColumn[]): IDataCatalogColumn[] {
		// Backend returns flat columns with full path names (e.g., "device.web_info.browser").
		// We need to reconstruct the tree structure for UI display using name-based nesting.
		const colByName = new Map<string, IDataCatalogColumn>();
		columns.forEach(col => colByName.set(col.name, col));

		// Collect all column names and determine parent-child relationships by dot notation
		const allNames = columns.map(c => c.name);
		const childrenOf = new Map<string, string[]>(); // parent full path -> child full paths
		const topLevel: string[] = [];
		const hasParent = new Set<string>();

		for (const name of allNames) {
			const dotIdx = name.lastIndexOf('.');
			if (dotIdx > 0) {
				const parent = name.substring(0, dotIdx);
				if (colByName.has(parent)) {
					if (!childrenOf.has(parent)) childrenOf.set(parent, []);
					childrenOf.get(parent)!.push(name);
					hasParent.add(name);
				}
			}
		}
		for (const name of allNames) {
			if (!hasParent.has(name)) topLevel.push(name);
		}

		const result: IDataCatalogColumn[] = [];
		function addColumn(name: string, depth: number, parentName?: string) {
			const col = colByName.get(name);
			if (!col) return;
			result.push({ ...col, name, isNested: depth > 0, depth, parentName });
			const children = childrenOf.get(name);
			if (children) {
				children.forEach(childName => addColumn(childName, depth + 1, name));
			}
		}
		topLevel.forEach(name => addColumn(name, 0));
		return result;
	}

	// ═════════════════════════════════════════════════
	// Init
	// ═════════════════════════════════════════════════
	onMount(() => {
		(async () => {
			try {
				const res = await connectionService.getList({ pageNo: 1, pageSize: 1000 });
				connections = (res as any)?.data || [];
				// Restore persisted connection, fallback to first
				const saved = ws.currentConnection;
				const restoredConn = saved ? connections.find(c => c.id === saved.id) : null;
				if (restoredConn) {
					selectedConnectionId = restoredConn.id;
					await loadTree(restoredConn.id);
					await loadActiveTables();
				} else if (connections.length > 0) {
					selectedConnectionId = connections[0].id;
					setCurrentConnection(connections[0]);
					await loadTree(connections[0].id);
					await loadActiveTables();
				}
			} catch (e) {
				console.error('Failed to load connections on mount:', e);
			}
		})();

		const handleBeforeUnload = (e: BeforeUnloadEvent) => {
			if (hasUnsavedChanges) { e.preventDefault(); e.returnValue = ''; }
		};
		window.addEventListener('beforeunload', handleBeforeUnload);
		return () => {
			window.removeEventListener('beforeunload', handleBeforeUnload);
			stopLineageStatusPolling();
		};
	});

	// Reset lineage state whenever the active data source changes so the UI doesn't
	// keep stale graph/status from a previous connection.
	let lastLineageConnId: number | null = null;
	$effect(() => {
		const id = selectedConnectionId;
		if (id !== lastLineageConnId) {
			lastLineageConnId = id;
			stopLineageStatusPolling();
			lineageGraph = null;
			lineageStatus = null;
			lineageDetecting = false;
			if (id != null) {
				void refreshLineageStatus();
			}
		}
	});

	// ═════════════════════════════════════════════════
	// Load tree: DB → Schema → Table
	// ═════════════════════════════════════════════════
	async function loadTree(dsId: number, refresh = false) {
		loading = true;
		treeData = [];
		expandedExampleRows = new Set();
		try {
			// 1. Load database list
			const res = await connectionService.getDatabaseList({ dataSourceId: dsId, refresh });
			const dbList: any[] = Array.isArray(res) ? res : [];
			treeData = dbList.map((d: any) => ({
				name: typeof d === 'string' ? d : d.name,
				type: 'database' as const,
				expanded: false,
				children: undefined,
				loading: false
			}));

			// 2. Pre-load all tables/views so search works on everything
			try {
				const allTables = await catalogService.loadAllTablesAndViews({ dataSourceId: dsId, refresh });
				if (allTables && typeof allTables === 'object') {
					// Group by database → schema → tables
					const dbSchemaMap: Record<string, Record<string, TreeNode[]>> = {};
					Object.entries(allTables as Record<string, any[]>).forEach(([schemaKey, tables]) => {
						// schemaKey format: "database.schema" or just "database"
						const dotIdx = schemaKey.indexOf('.');
						let dbName: string, schemaName: string | undefined;
						if (dotIdx > 0) {
							dbName = schemaKey.substring(0, dotIdx);
							schemaName = schemaKey.substring(dotIdx + 1);
						} else {
							dbName = schemaKey;
							schemaName = undefined;
						}
						if (!dbSchemaMap[dbName]) dbSchemaMap[dbName] = {};
						const key = schemaName || '__no_schema__';
						if (!dbSchemaMap[dbName][key]) dbSchemaMap[dbName][key] = [];
					(tables || []).forEach((t: any) => {
						dbSchemaMap[dbName][key].push({
							name: t.name,
							type: (t.type === 'VIEW' || t.type === 'MATERIALIZED VIEW') ? 'view' : 'table',
							databaseName: dbName,
							schemaName: schemaName
						});
					});
					});

					// Fill tree nodes with pre-loaded data
					const isBQ = getSelectedConn()?.type === 'BIGQUERY';
					for (const dbNode of treeData) {
						const schemas = dbSchemaMap[dbNode.name];
						if (!schemas) continue;
						const schemaKeys = Object.keys(schemas);
						if (schemaKeys.length === 1 && schemaKeys[0] === '__no_schema__') {
							const sorted = schemas['__no_schema__']?.sort((a, b) => a.name.localeCompare(b.name)) || [];
							dbNode.children = groupShardedTables(sorted, isBQ);
						} else {
							dbNode.children = schemaKeys
								.filter(k => k !== '__no_schema__')
								.sort((a, b) => a.localeCompare(b))
								.map(schemaName => {
									const sorted = schemas[schemaName]?.sort((a, b) => a.name.localeCompare(b.name)) || [];
									return {
										name: schemaName,
										type: 'schema' as const,
										expanded: false,
										children: groupShardedTables(sorted, isBQ),
										loading: false,
										databaseName: dbNode.name
									};
								});
						}
					}
					treeData = [...treeData];
				}
			} catch { /* pre-load failed, search will work on expanded nodes only */ }
		} catch {
			treeData = [];
		} finally {
			loading = false;
		}
	}

	async function toggleDbNode(node: TreeNode) {
		if (node.type !== 'database') return;
		node.expanded = !node.expanded;

		if (node.expanded && !node.children) {
			node.loading = true;
			treeData = [...treeData];
			try {
				const schemas = await connectionService.getSchemaList({ dataSourceId: selectedConnectionId!, databaseName: node.name });
				const schemaList: any[] = Array.isArray(schemas) ? schemas : [];

				if (schemaList.length > 0) {
					// DB → Schema → Table structure
					node.children = schemaList.map((s: any) => ({
						name: typeof s === 'string' ? s : s.name,
						type: 'schema' as const,
						expanded: false,
						children: undefined,
						loading: false,
						databaseName: node.name
					}));
					} else {
					// No schemas, load tables directly (e.g. MySQL)
					const res = await catalogService.loadAllTablesAndViews({ dataSourceId: selectedConnectionId!, refresh: false });
					const tables: TreeNode[] = [];
					if (res) {
						const dbLower = node.name.toLowerCase();
						Object.entries(res as Record<string, any[]>).forEach(([key, tbls]) => {
							const keyLower = key.toLowerCase();
							// Match exact DB name or DB.schema prefix
							if (keyLower === dbLower || keyLower.startsWith(dbLower + '.')) {
								tbls.forEach(t => tables.push({
									name: t.name,
									type: t.type === 'VIEW' ? 'view' : 'table',
									databaseName: node.name
								}));
							}
						});
					}
					const isBQ = getSelectedConn()?.type === 'BIGQUERY';
					node.children = groupShardedTables(tables, isBQ);
				}
				await loadActiveTables(node.name);
			} catch {
				node.children = [];
			}
			node.loading = false;
		}
		treeData = [...treeData];
	}

	async function toggleSchemaNode(node: TreeNode) {
		if (node.type !== 'schema') return;
		node.expanded = !node.expanded;

		if (node.expanded && !node.children) {
			node.loading = true;
			treeData = [...treeData];
			try {
				const tables = await catalogService.loadTablesBySchema({
					dataSourceId: selectedConnectionId!,
					databaseName: node.databaseName!,
					schemaName: node.name
				});
				const tableNodes: TreeNode[] = (tables as any[] || []).map((t: any) => ({
					name: t.name,
					type: (t.type === 'VIEW' || t.type === 'MATERIALIZED VIEW') ? 'view' as const : 'table' as const,
					databaseName: node.databaseName,
					schemaName: node.name
				}));
				const isBQ = getSelectedConn()?.type === 'BIGQUERY';
				node.children = groupShardedTables(tableNodes, isBQ);
				// Load active states for this schema's tables
				await loadActiveTables(node.databaseName);
			} catch {
				node.children = [];
			}
			node.loading = false;
		}
		treeData = [...treeData];
	}

	// ═════════════════════════════════════════════════
	// Select table & Load catalog
	// ═════════════════════════════════════════════════
	function selectTable(tableName: string, databaseName: string, schemaName?: string, catalogTableName?: string) {
		const newSelection = { databaseName, schemaName, tableName, catalogTableName };

		if (hasUnsavedChanges && selectedTable) {
			pendingTableChange = newSelection;
			showUnsavedDialog = true;
			return;
		}

		proceedWithTableSelect(newSelection);
	}

	async function proceedWithTableSelect(selection: typeof selectedTable) {
		if (!selection) return;
		selectedTable = selection;
		catalogLoading = true;
		try {
			const res = await catalogService.queryCatalog({
				dataSourceId: selectedConnectionId!,
				databaseName: selection.databaseName,
				schemaName: selection.schemaName,
				tableName: selection.tableName,
				catalogTableName: selection.catalogTableName
			});
			if (res) {
				const rawColumns: IDataCatalogColumn[] = (res.columns || []).map((col: any, idx: number) => ({
					name: col.name,
					columnType: col.columnType || '',
					nullable: col.nullable === 1 || col.nullable === true,
					primaryKey: col.primaryKey || false,
					defaultValue: col.defaultValue || '',
					comment: col.comment || '',
					description: col.description || '',
					schemaInfo: col.schemaInfo || col.columnType || '',
					exampleValues: col.exampleValues || '',
					ordinalPosition: col.ordinalPosition ?? idx + 1
				}));
				const flatCols = flattenColumns(rawColumns);
				catalogData = { tableDescription: res.tableDescription || '', columns: flatCols };
				tableDescription = res.tableDescription || '';
				editedColumns = flatCols;
				originalTableDescription = res.tableDescription || '';
				originalColumns = JSON.parse(JSON.stringify(flatCols));

				// Build example values input state
				const inputState: Record<string, string> = {};
				flatCols.forEach(col => {
					if (col.exampleValues) {
						try {
							if (col.exampleValues.trim().startsWith('[')) {
								inputState[col.name] = JSON.parse(col.exampleValues).join(', ');
							} else { inputState[col.name] = col.exampleValues; }
						} catch { inputState[col.name] = col.exampleValues; }
					} else { inputState[col.name] = ''; }
				});
				exampleValuesInput = inputState;
			} else {
				catalogData = null;
			}
		} catch { catalogData = null; }
		finally { catalogLoading = false; }
	}

	// ═════════════════════════════════════════════════
	// Unsaved changes dialog handlers
	// ═════════════════════════════════════════════════
	async function handleSaveAndContinue() {
		await handleSave();
		showUnsavedDialog = false;
		if (pendingTableChange) {
			proceedWithTableSelect(pendingTableChange);
			pendingTableChange = null;
		}
	}

	function handleDiscardAndContinue() {
		originalTableDescription = tableDescription;
		originalColumns = JSON.parse(JSON.stringify(editedColumns));
		showUnsavedDialog = false;
		if (pendingTableChange) {
			proceedWithTableSelect(pendingTableChange);
			pendingTableChange = null;
		}
	}

	function handleCancelTableChange() {
		showUnsavedDialog = false;
		pendingTableChange = null;
	}

	// ═════════════════════════════════════════════════
	// Save catalog
	// ═════════════════════════════════════════════════
	async function handleSave() {
		if (!selectedTable || !selectedConnectionId || !catalogData) return;
		savingCatalog = true;
		try {
			await catalogService.saveCatalog({
				dataSourceId: selectedConnectionId,
				databaseName: selectedTable.databaseName,
				schemaName: selectedTable.schemaName,
				tableName: selectedTable.tableName,
				tableDescription,
				columns: editedColumns.map(col => ({
					columnName: col.name,
					columnDescription: col.description || '',
					schemaInfo: col.schemaInfo || '',
					exampleValues: col.exampleValues || '',
					ordinalPosition: col.ordinalPosition
				}))
			});
			originalTableDescription = tableDescription;
			originalColumns = JSON.parse(JSON.stringify(editedColumns));
			// Mark this table as saved so the tree indicator updates immediately
			const key = buildTableKey(selectedTable.databaseName, selectedTable.schemaName, selectedTable.tableName);
			if (!(key in tableActiveMap)) {
				tableActiveMap = { ...tableActiveMap, [key]: true };
			}
		} catch (e) { console.error(e); }
		finally { savingCatalog = false; }
	}

	// ═════════════════════════════════════════════════
	// AI Collection (per-table)
	// ═════════════════════════════════════════════════
	async function handleCollectAI() {
		if (!selectedTable || !selectedConnectionId) return;
		collectingAI = true;
		collectingExamples = true;
		try {
			const params = {
				dataSourceId: selectedConnectionId,
				databaseName: selectedTable.databaseName,
				schemaName: selectedTable.schemaName,
				tableName: selectedTable.tableName
			};

			const [aiResult, exampleResult] = await Promise.allSettled([
				catalogService.collectAIMetadata(params),
				catalogService.collectExampleValues(params)
			]);

			const aiData = aiResult.status === 'fulfilled' ? aiResult.value : null;
			const examplePayload = exampleResult.status === 'fulfilled' ? exampleResult.value : null;
			const exampleValuesMap = examplePayload?.exampleValues ?? {};
			const exampleDescriptions = examplePayload?.columnDescriptions ?? {};

			if (aiData?.tableDescription !== undefined) {
				tableDescription = aiData.tableDescription || '';
			}

			// Merge example values
			const mergedExamples: Record<string, string[]> = {};
			if (aiData?.columnExampleValues) {
				Object.entries(aiData.columnExampleValues).forEach(([colName, values]) => {
					if (values && values.length > 0) mergedExamples[colName] = values;
				});
			}
			Object.entries(exampleValuesMap).forEach(([colName, values]) => {
				if (values && values.length > 0) mergedExamples[colName] = values;
			});

			// Merge descriptions — prefer enriched event_name (per-event definitions) when available
			const pickDescription = (colName: string, fallback: string) => {
				const ai = aiData?.columnDescriptions?.[colName];
				const ex = exampleDescriptions[colName];
				const isEnriched = (d?: string) => !!d?.includes('Events present in this dataset');
				if (isEnriched(ai)) return ai!;
				if (isEnriched(ex)) return ex!;
				if (ai !== undefined) return ai || '';
				if (ex !== undefined) return ex || '';
				return fallback || '';
			};

			if (aiData?.columnDescriptions || Object.keys(exampleDescriptions).length > 0 || Object.keys(mergedExamples).length > 0) {
				editedColumns = editedColumns.map(col => ({
					...col,
					description: pickDescription(col.name, col.description || ''),
					exampleValues: mergedExamples[col.name]
						? JSON.stringify(mergedExamples[col.name])
						: (col.exampleValues || '')
				}));
			}

			if (Object.keys(mergedExamples).length > 0) {
				const newInput: Record<string, string> = {};
				Object.entries(mergedExamples).forEach(([colName, values]) => {
					newInput[colName] = values.join(', ');
				});
				exampleValuesInput = { ...exampleValuesInput, ...newInput };
			}
		} catch (e) { console.error(e); }
		finally { collectingAI = false; collectingExamples = false; }
	}

	async function handleCollectExampleValues() {
		if (!selectedTable || !selectedConnectionId) return;
		collectingExamples = true;
		try {
			const res = await catalogService.collectExampleValues({
				dataSourceId: selectedConnectionId,
				databaseName: selectedTable.databaseName,
				schemaName: selectedTable.schemaName,
				tableName: selectedTable.tableName
			});
			if (res) {
				const valuesMap = res.exampleValues ?? {};
				const descMap = res.columnDescriptions ?? {};
				editedColumns = editedColumns.map(col => {
					const values = valuesMap[col.name] || [];
					return {
						...col,
						description: descMap[col.name] !== undefined ? (descMap[col.name] || '') : (col.description || ''),
						exampleValues: JSON.stringify(values)
					};
				});
				const newInput: Record<string, string> = {};
				Object.keys(valuesMap).forEach(colName => {
					newInput[colName] = (valuesMap[colName] || []).join(', ');
				});
				exampleValuesInput = { ...exampleValuesInput, ...newInput };
			}
		} catch (e) { console.error(e); }
		finally { collectingExamples = false; }
	}

	// ═════════════════════════════════════════════════
	// Bulk AI Collection (multi-step flow)
	// ═════════════════════════════════════════════════
	async function handleBulkCollectStart() {
		if (!selectedConnectionId) return;
		try {
			const config = await configService.getAiSystemConfig({});
			const hasKey = !!((config as any)?.apiKey || (config as any)?.secretKey || (config as any)?.apiHost);
			if (!hasKey) { showAIConfigDialog = true; return; }
		} catch { showAIConfigDialog = true; return; }

		checkedKeys = new Set();
		expandedKeys = new Set();
		showDbSchemaDialog = true;
		await loadSelectionTree();

		// Pre-select current DB/Schema if a table is selected
		if (selectedTable?.databaseName) {
			const dbKey = `db:${selectedTable.databaseName}`;
			expandedKeys = new Set([dbKey]);
			if (selectedTable?.schemaName) {
				const schemaKey = `schema:${selectedTable.databaseName}:${selectedTable.schemaName}`;
				checkedKeys = new Set([schemaKey]);
			}
		}
	}

	async function loadSelectionTree() {
		if (!selectedConnectionId) return;
		selectionTreeLoading = true;
		try {
			const dbList = await connectionService.getDatabaseList({ dataSourceId: selectedConnectionId });
			const results = await Promise.all(
				((dbList as any[]) || []).map(async (db: any) => {
					const dbName = typeof db === 'string' ? db : db.name;
					try {
						const schemas = await connectionService.getSchemaList({ dataSourceId: selectedConnectionId!, databaseName: dbName });
						const schemaNames = ((schemas as any[]) || []).map((s: any) => typeof s === 'string' ? s : s.name);
						return {
							title: dbName,
							key: `db:${dbName}`,
							children: schemaNames.map((sn: string) => ({ title: sn, key: `schema:${dbName}:${sn}`, isLeaf: true }))
						} as SelectionNode;
					} catch { return null; }
				})
			);
			selectionTree = results.filter((r): r is SelectionNode => r !== null);
		} catch { selectionTree = []; }
		finally { selectionTreeLoading = false; }
	}

	function toggleSelectionExpand(key: string) {
		const next = new Set(expandedKeys);
		if (next.has(key)) next.delete(key); else next.add(key);
		expandedKeys = next;
	}

	function toggleSelectionCheck(key: string, node: SelectionNode) {
		const next = new Set(checkedKeys);
		if (node.isLeaf) {
			if (next.has(key)) next.delete(key); else next.add(key);
		} else {
			const allChecked = node.children?.every(c => next.has(c.key));
			if (allChecked) node.children?.forEach(c => next.delete(c.key));
			else node.children?.forEach(c => next.add(c.key));
		}
		checkedKeys = next;
	}

	function getDbCheckState(node: SelectionNode): 'checked' | 'indeterminate' | 'unchecked' {
		if (!node.children?.length) return 'unchecked';
		const count = node.children.filter(c => checkedKeys.has(c.key)).length;
		if (count === 0) return 'unchecked';
		if (count === node.children.length) return 'checked';
		return 'indeterminate';
	}

	let selectedSchemaCount = $derived(Array.from(checkedKeys).filter(k => k.startsWith('schema:')).length);

	async function handleContinueToConfirm() {
		const selectedSchemas = Array.from(checkedKeys)
			.filter(k => k.startsWith('schema:'))
			.map(k => { const parts = k.split(':'); return { databaseName: parts[1], schemaName: parts[2], displayName: `${parts[1]}.${parts[2]}` }; });
		if (selectedSchemas.length === 0) return;

		showDbSchemaDialog = false;
		let totalTableCount = 0;
		try {
			const counts = await Promise.all(
				selectedSchemas.map(async ({ databaseName, schemaName }) => {
					try {
						const tables = await catalogService.loadTablesBySchema({ dataSourceId: selectedConnectionId!, databaseName, schemaName });
						return (tables as any[])?.length || 0;
					} catch { return 0; }
				})
			);
			totalTableCount = counts.reduce((s, c) => s + c, 0);
		} catch { /* ignore */ }

		confirmData = { totalTableCount, schemas: selectedSchemas };
		showConfirmDialog = true;
	}

	async function executeCollection() {
		if (!confirmData || !selectedConnectionId) return;
		showConfirmDialog = false;
		setBulkCollecting(true);
		setShowCollectionProgress(true);
		setCollectionProgressList([]);

		// Capture connection ID locally so it survives page navigation
		const connectionId = selectedConnectionId;
		const schemas = [...confirmData.schemas];
		const activeJobs: StoredCollectionJob[] = [];

		// Initialize progress for all schemas upfront
		schemas.forEach(({ databaseName, schemaName }, i) => {
			updateCollectionProgress(i, { current: 0, total: 0, percent: 0, currentTable: 'Waiting...', successCount: 0, failCount: 0, schemaName, databaseName });
		});

		// Process schemas sequentially - each schema is ONE API call to the backend
		for (let schemaIndex = 0; schemaIndex < schemas.length; schemaIndex++) {
			const { databaseName, schemaName } = schemas[schemaIndex];

			updateCollectionProgress(schemaIndex, { current: 0, total: 0, percent: 0, currentTable: 'Starting...', successCount: 0, failCount: 0, schemaName, databaseName });

			try {
				// Start async collection on backend (single API call per schema)
				const { jobId } = await catalogService.startBulkCollection({
					dataSourceId: connectionId, databaseName, schemaName
				});

				// Persist job to localStorage for refresh recovery
				activeJobs.push({ jobId, databaseName, schemaName, connectionId, startedAt: Date.now() });
				saveActiveJobs(activeJobs);

				// Poll for progress every 2 seconds
				let done = false;
				while (!done) {
					await new Promise(r => setTimeout(r, 2000));

					try {
						const progress = await catalogService.getCollectionProgress(jobId);

						updateCollectionProgress(schemaIndex, {
							current: progress.processedTables,
							total: progress.totalTables,
							percent: progress.totalTables > 0 ? Math.round((progress.processedTables / progress.totalTables) * 100) : 0,
							currentTable: progress.currentTable || '',
							successCount: progress.successCount,
							failCount: progress.failCount,
							schemaName,
							databaseName
						});

						if (progress.status === 'COMPLETED' || progress.status === 'FAILED') {
							done = true;
							// Remove completed job from active list
							const idx = activeJobs.findIndex(j => j.jobId === jobId);
							if (idx !== -1) activeJobs.splice(idx, 1);
							saveActiveJobs(activeJobs);
						}
					} catch (e) {
						console.error('Failed to poll progress:', e);
						done = true;
					}
				}
			} catch (e) {
				console.error('Failed to start collection for schema:', schemaName, e);
				updateCollectionProgress(schemaIndex, { current: 0, total: 0, percent: 0, currentTable: 'Error', successCount: 0, failCount: 1, schemaName, databaseName });
			}
		}

		setBulkCollecting(false);
		setCollectionAbortController(null);
		clearActiveJobs();
		setShowCollectionProgress(false);
		showCompletionDialog = true;

		// Auto-reload current table if it was in one of the collected schemas
		if (selectedTable && selectedConnectionId) {
			proceedWithTableSelect(selectedTable);
		}
	}

	// totalSuccess, totalFail, totalCount, completedSchemas are now in collectionStore

	// ═════════════════════════════════════════════════
	// Table active toggle
	// ═════════════════════════════════════════════════
	// Build unique table key: "database.schema.table" (lowercase) to avoid collisions across schemas
	function buildTableKey(databaseName: string, schemaName?: string, tableName?: string): string {
		const schema = schemaName || 'PUBLIC';
		return `${databaseName}.${schema}.${tableName || ''}`.toLowerCase();
	}

	async function loadActiveTables(dbName?: string) {
		if (!selectedConnectionId) return;
		try {
			const res = await catalogService.getTableActiveStates({ dataSourceId: selectedConnectionId, databaseName: dbName });
			if (res && typeof res === 'object') {
				const map: Record<string, boolean> = { ...tableActiveMap };
				// Backend key format: "database.schema.table" → use lowercase as-is
				Object.entries(res).forEach(([key, active]) => {
					map[key.toLowerCase()] = active;
				});
				tableActiveMap = map;
			}
		} catch { /* ignore */ }
	}

	function isTableSaved(databaseName: string, schemaName?: string, tableName?: string): boolean {
		const key = buildTableKey(databaseName, schemaName, tableName);
		return key in tableActiveMap;
	}

	function isTableActive(databaseName: string, schemaName?: string, tableName?: string): boolean {
		const key = buildTableKey(databaseName, schemaName, tableName);
		// Default to true if not explicitly set
		return tableActiveMap[key] !== false;
	}

	async function handleToggleActive(tableName: string, active: boolean) {
		if (!selectedConnectionId || !selectedTable) return;
		const key = buildTableKey(selectedTable.databaseName, selectedTable.schemaName, tableName);
		tableActiveMap = { ...tableActiveMap, [key]: active };
		try {
			await catalogService.saveTableActiveState({
				dataSourceId: selectedConnectionId, databaseName: selectedTable.databaseName,
				schemaName: selectedTable.schemaName, tableName, active
			});
		} catch { tableActiveMap = { ...tableActiveMap, [key]: !active }; }
	}

	async function handleToggleActiveInTree(tableName: string, databaseName: string, schemaName?: string) {
		if (!selectedConnectionId) return;
		const key = buildTableKey(databaseName, schemaName, tableName);
		const currentActive = tableActiveMap[key] !== false;
		const newActive = !currentActive;
		tableActiveMap = { ...tableActiveMap, [key]: newActive };
		try {
			await catalogService.saveTableActiveState({
				dataSourceId: selectedConnectionId, databaseName, schemaName, tableName, active: newActive
			});
		} catch { tableActiveMap = { ...tableActiveMap, [key]: currentActive }; }
	}

	// Database-level toggle: toggles all tables under this database
	let databaseDisabledMap = $state<Record<string, boolean>>({});

	function isDatabaseActive(databaseName: string): boolean {
		return databaseDisabledMap[databaseName.toLowerCase()] !== true;
	}

	async function handleToggleDatabaseActive(databaseName: string) {
		if (!selectedConnectionId) return;
		const dbLower = databaseName.toLowerCase();
		const currentlyActive = isDatabaseActive(databaseName);
		const newActive = !currentlyActive;
		databaseDisabledMap = { ...databaseDisabledMap, [dbLower]: !newActive };

		try {
			// Build batch active states map and update local state
			const dbNode = treeData.find(n => n.name === databaseName);
			const activeStates: Record<string, boolean> = {};
			if (dbNode?.children) {
				for (const child of dbNode.children) {
					if (child.type === 'schema' && child.children) {
						for (const table of child.children) {
							const key = buildTableKey(databaseName, child.name, table.name);
							tableActiveMap = { ...tableActiveMap, [key]: newActive };
							activeStates[`${databaseName}.${child.name}.${table.name}`] = newActive;
						}
					} else if (child.type === 'table' || child.type === 'view') {
						const key = buildTableKey(databaseName, undefined, child.name);
						tableActiveMap = { ...tableActiveMap, [key]: newActive };
						activeStates[`${databaseName}..${child.name}`] = newActive;
					}
				}
			}
			// Single batch API call instead of individual calls per table
			if (Object.keys(activeStates).length > 0) {
				await catalogService.batchSaveTableActiveStates(selectedConnectionId!, activeStates);
			}
		} catch {
			databaseDisabledMap = { ...databaseDisabledMap, [dbLower]: currentlyActive };
		}
	}

	// ═════════════════════════════════════════════════
	// Business Insight
	// ═════════════════════════════════════════════════
	/** Ensure all optional string fields are never null/undefined */
	function sanitizeInsight(data: IBusinessInsight): IBusinessInsight {
		return {
			...data,
			playStoreLink: data.playStoreLink ?? '',
			appStoreLink: data.appStoreLink ?? '',
			webLink: data.webLink ?? '',
			insightContent: data.insightContent ?? ''
		};
	}

	async function openInsightDialog() {
		if (!selectedConnectionId) return;
		showInsightDialog = true;
		insightLoading = true;
		insightTab = 'edit';
		try {
			const res = await businessInsightService.getInsight({ dataSourceId: selectedConnectionId, databaseName: '' });
			if (res) insightData = sanitizeInsight(res);
			else insightData = { dataSourceId: selectedConnectionId, databaseName: '', playStoreLink: '', appStoreLink: '', webLink: '', insightContent: '' };
		} catch {
			insightData = { dataSourceId: selectedConnectionId!, databaseName: '', playStoreLink: '', appStoreLink: '', webLink: '', insightContent: '' };
		}
		finally { insightLoading = false; }
	}

	async function handleGenerateInsight() {
		if (!selectedConnectionId) return;
		insightGenerating = true;
		try {
			const res = await businessInsightService.generateInsight({ ...insightData, dataSourceId: selectedConnectionId, databaseName: '' });
			if (res) { insightData = sanitizeInsight(res); insightTab = 'preview'; }
		} catch (e) { console.error(e); }
		finally { insightGenerating = false; }
	}

	async function handleSaveInsight() {
		if (!selectedConnectionId) return;
		insightSaving = true;
		try {
			const res = await businessInsightService.saveInsight({ ...insightData, dataSourceId: selectedConnectionId, databaseName: '' });
			if (res) insightData = sanitizeInsight(res);
			showInsightDialog = false;
		} catch (e) { console.error(e); }
		finally { insightSaving = false; }
	}

	// ═════════════════════════════════════════════════
	// Connection change
	// ═════════════════════════════════════════════════
	function handleConnectionChange(id: number) {
		selectedConnectionId = id;
		const conn = connections.find(c => c.id === id);
		if (conn) setCurrentConnection(conn);
		selectedTable = null;
		catalogData = null;
		editedColumns = [];
		originalColumns = [];
		tableDescription = '';
		originalTableDescription = '';
		tableActiveMap = {};
		exampleValuesInput = {};
		lineageGraph = null;
		search = '';
		treeFilter = 'all';
		connPopoverOpen = false;
		loadTree(id);
		loadActiveTables();
	}

	// ═════════════════════════════════════════════════
	// Panel resize
	// ═════════════════════════════════════════════════
	function handlePanelResize(e: MouseEvent) {
		isResizing = true;
		const startX = e.clientX;
		const startW = panelWidth;
		function onMouseMove(ev: MouseEvent) { panelWidth = Math.max(200, Math.min(500, startW + (ev.clientX - startX))); }
		function onMouseUp() { isResizing = false; window.removeEventListener('mousemove', onMouseMove); window.removeEventListener('mouseup', onMouseUp); }
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	// ═════════════════════════════════════════════════
	// Column resize
	// ═════════════════════════════════════════════════
	function handleColumnResize(e: MouseEvent, colKey: string) {
		e.preventDefault();
		resizingColumn = colKey;
		const startX = e.clientX;
		const startWidth = columnWidths[colKey] || 100;
		function onMouseMove(ev: MouseEvent) { columnWidths = { ...columnWidths, [colKey]: Math.max(10, startWidth + (ev.clientX - startX)) }; }
		function onMouseUp() { resizingColumn = null; window.removeEventListener('mousemove', onMouseMove); window.removeEventListener('mouseup', onMouseUp); }
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	// ═════════════════════════════════════════════════
	// Filtered tree
	// ═════════════════════════════════════════════════
	function filterTreeNode(node: TreeNode, q: string, filter: 'all' | 'saved' | 'unsaved'): TreeNode | null {
		if (!q && filter === 'all') return node;

		if (node.type === 'table' || node.type === 'view') {
			const matchText = !q || node.name.toLowerCase().includes(q);
			let matchFilter = true;
			if (filter !== 'all') {
				const saved = isTableSaved(node.databaseName!, node.schemaName, node.name);
				matchFilter = filter === 'saved' ? saved : !saved;
			}
			return matchText && matchFilter ? node : null;
		}

		if (node.type === 'table_group') {
			const matchText = !q || node.name.toLowerCase().includes(q);
			return matchText && filter === 'all' ? node : null;
		}

		if (!node.children) {
			const matchSelf = !q || node.name.toLowerCase().includes(q);
			return matchSelf ? node : null;
		}

		const filteredChildren = node.children
			.map(c => filterTreeNode(c, q, filter))
			.filter((c): c is TreeNode => c !== null);
		if (filteredChildren.length > 0) return { ...node, children: filteredChildren, expanded: true };

		const matchSelf = !q || node.name.toLowerCase().includes(q);
		return matchSelf && filter === 'all' ? node : null;
	}

	let filteredTree = $derived(
		(search || treeFilter !== 'all')
			? treeData.map(db => filterTreeNode(db, search.toLowerCase(), treeFilter)).filter((n): n is TreeNode => n !== null)
			: treeData
	);

	function countTreeTables(nodes: TreeNode[], onlySaved: boolean): number {
		let count = 0;
		for (const node of nodes) {
			if (node.type === 'table' || node.type === 'view' || node.type === 'table_group') {
				if (!onlySaved || isTableSaved(node.databaseName!, node.schemaName, node.tableName || node.name)) count++;
			}
			if (node.children) count += countTreeTables(node.children, onlySaved);
		}
		return count;
	}

	let totalTableCount = $derived(countTreeTables(treeData, false));
	let savedTableCount = $derived(countTreeTables(treeData, true));

	let connInfo = $derived.by(() => {
		const conn = connections.find(c => c.id === selectedConnectionId);
		return conn ? { conn, dbInfo: databaseMap[conn.type] } : null;
	});

	function parseExampleValues(text: string): string[] {
		if (!text) return [];
		try {
			if (text.trim().startsWith('[')) return JSON.parse(text);
			return text.split(',').map(v => v.trim()).filter(v => v.length > 0);
		} catch {
			return text.split(',').map(v => v.trim()).filter(v => v.length > 0);
		}
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="flex h-full w-full overflow-hidden">
	<!-- ═══════════════════ Left Sidebar ═══════════════════ -->
	<aside class="border-r border-border bg-card/50 flex flex-col shrink-0" style="width: {panelWidth}px;">
		<!-- Connection Selector (matches React WorkspaceLeftHeader) -->
		<Popover bind:open={connPopoverOpen}>
			<PopoverTrigger>
				<div class="flex items-center justify-between cursor-pointer px-3 py-1.5 border-b border-border hover:bg-accent transition-colors">
					{#if connInfo}
						<div class="flex items-center min-w-0">
							<span class="w-2 h-2 rounded-full shrink-0 mr-2" style="background: {connInfo.conn.environment?.color?.toLowerCase() || '#888'}"></span>
							<div class="flex items-center justify-center w-4 h-4 shrink-0 mr-1.5">
								{#if connInfo.dbInfo?.img}
									<img src={connInfo.dbInfo.img} alt="" class="w-4 h-4 object-contain" />
								{:else}
									<Database size={16} class="text-muted-foreground" />
								{/if}
							</div>
							<div class="truncate text-xs">{connInfo.conn.alias}</div>
						</div>
					{:else}
						<span class="text-sm text-muted-foreground">Select connection</span>
					{/if}
					<ChevronDown size={14} class="shrink-0 ml-2 text-muted-foreground" />
				</div>
			</PopoverTrigger>
			<PopoverContent class="w-[260px] p-1 max-h-[300px] overflow-auto">
				{#each connections as c (c.id)}
					{@const dbInfo = databaseMap[c.type]}
					<button
						class="flex items-center w-full rounded px-2.5 py-1.5 text-xs hover:bg-accent transition-colors cursor-pointer
							{c.id === selectedConnectionId ? 'bg-primary/10 text-primary font-medium' : ''}"
						onclick={() => handleConnectionChange(c.id)}
					>
						<span class="w-2 h-2 rounded-full shrink-0 mr-2" style="background: {c.environment?.color?.toLowerCase() || '#888'}"></span>
						<div class="flex items-center justify-center w-4 h-4 shrink-0 mr-1.5">
							{#if dbInfo?.img}
								<img src={dbInfo.img} alt="" class="w-4 h-4 object-contain" />
							{:else}
								<Database size={16} class="text-muted-foreground" />
							{/if}
						</div>
						<span class="truncate flex-1 text-left">{c.alias}</span>
						{#if c.id === selectedConnectionId}
							<Check size={12} class="ml-auto shrink-0 text-primary" />
						{/if}
					</button>
				{/each}
			</PopoverContent>
		</Popover>

		<!-- Action Buttons -->
		<div class="flex items-center gap-1.5 px-2 h-[30px] overflow-hidden">
			<Button
				variant="ghost"
				size="sm"
				onclick={handleBulkCollectStart}
				disabled={!selectedConnectionId || collectionStore.bulkCollecting}
				class="h-7 px-2 shrink-0 text-purple-600 hover:text-purple-700 hover:bg-purple-50 dark:text-purple-400 dark:hover:bg-purple-900/20"
			>
				{#if collectionStore.bulkCollecting}
					<Loader2 size={14} class="mr-1.5 animate-spin" />
				{:else}
					<AISparkleIcon size={14} class="mr-1.5" filled />
				{/if}
				<span class="text-xs font-medium whitespace-nowrap">{collectionStore.bulkCollecting ? 'Collecting...' : 'AI Collection'}</span>
			</Button>
			<Button
				variant="ghost"
				size="sm"
				onclick={openInsightDialog}
				disabled={!selectedConnectionId}
				class="h-7 px-2 shrink-0 text-purple-600 hover:text-purple-700 hover:bg-purple-50 dark:text-purple-400 dark:hover:bg-purple-900/20"
			>
				<AISparkleIcon size={14} class="mr-1.5" filled />
				<span class="text-xs whitespace-nowrap">Insights</span>
			</Button>
			<div class="flex-1"></div>
			<Button
				variant="ghost"
				size="sm"
				onclick={() => { if (selectedConnectionId) loadTree(selectedConnectionId, true); }}
				title="Refresh"
				class="h-7 w-7 p-0 shrink-0 text-muted-foreground hover:text-foreground"
			>
				<RefreshCw size={14} />
			</Button>
		</div>

		<!-- Search (matches React OperationLine search input) -->
		<div class="px-2 py-1">
			<div class="relative flex items-center gap-1.5 rounded-md border border-input bg-background px-2 py-1">
				<Search size={14} class="text-muted-foreground shrink-0" />
				<input bind:value={search} type="text" placeholder="Search tables..." class="w-full text-xs bg-transparent focus:outline-none pr-5" />
				{#if search}
					<button
						class="absolute right-1.5 top-1/2 -translate-y-1/2 flex items-center justify-center w-4 h-4 rounded-full text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
						onclick={() => { search = ''; }}
					>
						<X size={12} />
					</button>
				{/if}
			</div>
		</div>

		<!-- Filter Chips -->
		<div class="flex items-center gap-1 px-2 pb-1">
			<button
				class="px-2 py-0.5 rounded-full text-[11px] font-medium transition-colors
					{treeFilter === 'all' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'}"
				onclick={() => { treeFilter = 'all'; }}
			>
				All <span class="ml-0.5 opacity-70">{totalTableCount}</span>
			</button>
			<button
				class="px-2 py-0.5 rounded-full text-[11px] font-medium transition-colors
					{treeFilter === 'saved' ? 'bg-emerald-500 text-white dark:bg-emerald-600' : 'bg-muted text-muted-foreground hover:bg-accent'}"
				onclick={() => { treeFilter = 'saved'; }}
			>
				Saved <span class="ml-0.5 opacity-70">{savedTableCount}</span>
			</button>
			<button
				class="px-2 py-0.5 rounded-full text-[11px] font-medium transition-colors
					{treeFilter === 'unsaved' ? 'bg-amber-500 text-white dark:bg-amber-600' : 'bg-muted text-muted-foreground hover:bg-accent'}"
				onclick={() => { treeFilter = 'unsaved'; }}
			>
				Not Saved <span class="ml-0.5 opacity-70">{totalTableCount - savedTableCount}</span>
			</button>
		</div>

		<!-- Tree -->
		<div class="flex-1 overflow-auto py-1">
			{#if loading}
				<div class="flex items-center justify-center py-8"><Spinner size="sm" class="text-primary" /></div>
			{:else if connections.length === 0}
				<div class="flex flex-col items-center justify-center py-8 px-4 text-center">
					<FolderX class="h-12 w-12 text-muted-foreground mb-3" />
					<p class="text-xs text-muted-foreground mb-2">No connections available</p>
					<a href="/connections" class="text-primary underline text-xs">Create connections</a>
				</div>
			{:else}
		{#each filteredTree as dbNode (dbNode.name)}
			<div>
				<!-- Database -->
				<div
					class="flex items-center gap-1 w-full h-8 px-2 text-[13px] rounded hover:bg-accent transition-colors cursor-pointer select-none"
					onclick={() => toggleDbNode(dbNode)}
				>
					{#if dbNode.expanded}
						<ChevronDown size={14} class="shrink-0 text-muted-foreground" />
					{:else}
						<ChevronRight size={14} class="shrink-0 text-muted-foreground" />
					{/if}
					<Book size={16} class="shrink-0" style="color: #4B5563" />
					<!-- svelte-ignore a11y_click_events_have_key_events -->
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div class="shrink-0 translate-y-px" onclick={(e) => e.stopPropagation()}>
						<Switch
							checked={isDatabaseActive(dbNode.name)}
							onchange={() => handleToggleDatabaseActive(dbNode.name)}
							title="Toggle AI context"
							size="small"
							class="mr-1"
						/>
					</div>
					<span class="truncate font-medium flex-1">{dbNode.name}</span>
					{#if dbNode.loading}
						<Loader2 size={12} class="animate-spin text-primary shrink-0" />
					{/if}
				</div>

				{#if dbNode.expanded && dbNode.children}
					{#each dbNode.children as child (child.name)}
						{#if child.type === 'schema'}
							<!-- Schema -->
							<div
								class="flex items-center gap-1 w-full h-8 pl-7 pr-2 text-[13px] rounded hover:bg-accent transition-colors cursor-pointer select-none"
								onclick={() => toggleSchemaNode(child)}
							>
								{#if child.expanded}
									<ChevronDown size={14} class="shrink-0 text-muted-foreground" />
								{:else}
									<ChevronRight size={14} class="shrink-0 text-muted-foreground" />
								{/if}
								{#if child.expanded}
									<Folder size={16} class="shrink-0" style="color: #F59E0B; fill: #F59E0B; fill-opacity: 0.2" />
								{:else}
									<Folder size={16} class="shrink-0" style="color: #F59E0B" />
								{/if}
								<span class="truncate font-medium flex-1">{child.name}</span>
								{#if child.loading}
									<Loader2 size={12} class="animate-spin text-primary shrink-0" />
								{:else if child.children}
									<span class="text-[10px] text-muted-foreground/60 shrink-0">{child.children.length}</span>
								{/if}
							</div>

							{#if child.expanded && child.children}
								{#each child.children as table (table.name)}
									{@const isGroup = table.type === 'table_group'}
									{@const actualName = isGroup ? (table.tableName || table.name) : table.name}
									{@const saved = isTableSaved(dbNode.name, child.name, actualName)}
									<div
										class="flex items-center gap-1.5 w-full h-8 pl-9 pr-1.5 text-[13px] rounded hover:bg-accent transition-colors cursor-pointer select-none
											{isGroup && selectedTable?.catalogTableName === table.catalogTableName && selectedTable?.databaseName === dbNode.name && selectedTable?.schemaName === child.name ? 'bg-primary/10 text-primary' : ''}
											{!isGroup && selectedTable?.tableName === table.name && selectedTable?.databaseName === dbNode.name && selectedTable?.schemaName === child.name ? 'bg-primary/10 text-primary' : ''}
											{!isGroup && !saved ? 'opacity-50' : ''}"
										onclick={() => selectTable(actualName, dbNode.name, child.name, isGroup ? table.catalogTableName : undefined)}
									>
										{#if table.type === 'view'}
											<Eye size={16} class="shrink-0" style="color: {saved ? '#10B981' : '#9CA3AF'}" />
										{:else if isGroup}
											<Table2 size={16} class="shrink-0" style="color: #8B5CF6" />
										{:else}
											<Table2 size={16} class="shrink-0" style="color: {saved ? '#3B82F6' : '#9CA3AF'}" />
										{/if}
										<!-- svelte-ignore a11y_click_events_have_key_events -->
										<!-- svelte-ignore a11y_no_static_element_interactions -->
										<div class="shrink-0 translate-y-px" onclick={(e) => e.stopPropagation()}>
											<Switch
												checked={isTableActive(dbNode.name, child.name, actualName)}
												onchange={() => handleToggleActiveInTree(actualName, dbNode.name, child.name)}
												title="Toggle AI context"
												size="small"
												class="mr-1"
											/>
										</div>
										<span class="truncate {saved || isGroup ? 'font-medium' : 'font-normal text-muted-foreground'}">{table.name}</span>
										{#if !saved && !isGroup}
											<span class="shrink-0 text-[9px] text-amber-500 dark:text-amber-400 font-medium uppercase tracking-wide">new</span>
										{/if}
									</div>
								{/each}
							{/if}
						{:else}
							<!-- Table (no schema level) -->
							{@const saved = isTableSaved(dbNode.name, undefined, child.name)}
							<div
								class="flex items-center gap-1.5 w-full h-8 pl-6 pr-1.5 text-[13px] rounded hover:bg-accent transition-colors cursor-pointer select-none
									{selectedTable?.tableName === child.name ? 'bg-primary/10 text-primary' : ''}
									{!saved ? 'opacity-50' : ''}"
								onclick={() => selectTable(child.name, dbNode.name)}
							>
								{#if child.type === 'view'}
									<Eye size={16} class="shrink-0" style="color: {saved ? '#10B981' : '#9CA3AF'}" />
								{:else}
									<Table2 size={16} class="shrink-0" style="color: {saved ? '#3B82F6' : '#9CA3AF'}" />
								{/if}
								<!-- svelte-ignore a11y_click_events_have_key_events -->
								<!-- svelte-ignore a11y_no_static_element_interactions -->
								<div class="shrink-0 translate-y-px" onclick={(e) => e.stopPropagation()}>
									<Switch
										checked={isTableActive(dbNode.name, undefined, child.name)}
										onchange={() => handleToggleActiveInTree(child.name, dbNode.name)}
										title="Toggle AI context"
										size="small"
										class="mr-1"
									/>
								</div>
								<span class="truncate {saved ? 'font-medium' : 'font-normal text-muted-foreground'}">{child.name}</span>
								{#if !saved}
									<span class="shrink-0 text-[9px] text-amber-500 dark:text-amber-400 font-medium uppercase tracking-wide">new</span>
								{/if}
							</div>
						{/if}
					{/each}
				{/if}
			</div>
		{/each}
			{/if}
		</div>
	</aside>

	<!-- Resize Handle -->
	<div class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isResizing ? 'bg-primary/30' : ''}" onmousedown={handlePanelResize}></div>

	<!-- ═══════════════════ Main Content ═══════════════════ -->
	<div class="flex-1 h-full overflow-hidden flex flex-col">
		<!-- Tab bar -->
		<div class="flex items-center border-b border-border bg-card/50 px-2 shrink-0">
			<div class="flex items-center rounded-lg bg-muted p-0.5 my-1.5 ml-2">
				<button
					class="flex items-center gap-1.5 px-3.5 py-1.5 rounded-md text-xs font-medium transition-all
						{mainTab === 'catalog'
							? 'bg-background text-foreground shadow-sm border border-border/50'
							: 'text-muted-foreground hover:text-foreground border border-transparent'}"
					onclick={() => { mainTab = 'catalog'; }}
				>
					<Table2 size={14} />
					Catalog
				</button>
				<button
					class="flex items-center gap-1.5 px-3.5 py-1.5 rounded-md text-xs font-medium transition-all
						{mainTab === 'lineage'
							? 'bg-background text-foreground shadow-sm border border-border/50'
							: 'text-muted-foreground hover:text-foreground border border-transparent'}"
					onclick={() => { mainTab = 'lineage'; if (!lineageGraph && !lineageLoading) loadLineageGraph(); }}
				>
					<GitBranch size={14} />
					Lineage
					{#if lineageGraph}
						<span class="px-1.5 py-0.5 rounded-full bg-primary/10 text-primary text-[10px] font-semibold leading-none">{filteredLineageCount}</span>
					{/if}
				</button>
			</div>
		</div>

		{#if mainTab === 'lineage'}
			<!-- ═══════════════════ Lineage View ═══════════════════ -->
			<div class="flex-1 overflow-hidden flex flex-col">
				<!-- Lineage toolbar: status + re-detect -->
				<div class="flex items-center justify-between gap-3 px-3 py-2 border-b border-border/50 bg-card/30 shrink-0 text-xs">
					<div class="flex items-center gap-2 min-w-0 text-muted-foreground">
						{#if lineageDetecting || lineageStatus?.state === 'RUNNING'}
							<Loader2 size={12} class="animate-spin text-primary shrink-0" />
							<span class="text-primary font-medium">Detecting lineage…</span>
						{:else if lineageStatus?.state === 'FAILED'}
							<AlertCircle size={12} class="text-destructive shrink-0" />
							<span class="text-destructive truncate" title={lineageStatus?.errorMessage || ''}>
								Last detection failed{lineageStatus?.errorMessage ? `: ${lineageStatus.errorMessage}` : ''}
							</span>
						{:else if lineageStatus?.state === 'COMPLETED'}
							<CheckCircle size={12} class="text-emerald-500 shrink-0" />
							<span class="truncate">
								Last detected {formatLineageTime(lineageStatus?.lastRunTime)}
								{#if lineageStatus?.nodeCount !== undefined}
									· {lineageStatus.nodeCount} nodes / {lineageStatus.edgeCount ?? 0} edges
								{/if}
							</span>
						{:else}
							<AlertTriangle size={12} class="text-amber-500 shrink-0" />
							<span>No lineage data yet — click "Re-detect" to build the graph.</span>
						{/if}
					</div>
					<Button
						variant="outline"
						size="sm"
						class="h-7 text-xs gap-1.5 shrink-0"
						disabled={!selectedConnectionId || lineageDetecting || lineageStatus?.state === 'RUNNING'}
						onclick={redetectLineage}
					>
						{#if lineageDetecting || lineageStatus?.state === 'RUNNING'}
							<Loader2 size={12} class="animate-spin" />
							Detecting…
						{:else}
							<RefreshCw size={12} />
							Re-detect
						{/if}
					</Button>
				</div>

				<div class="flex-1 overflow-hidden">
					<LineageGraph
						graph={lineageGraph}
						loading={lineageLoading}
						focusTable={selectedTable?.tableName || ''}
						focusDatabase={selectedTable?.databaseName || ''}
						focusSchema={selectedTable?.schemaName || ''}
						detectionState={lineageDetecting
							? 'RUNNING'
							: lineageStatus?.state === 'RUNNING'
								|| lineageStatus?.state === 'COMPLETED'
								|| lineageStatus?.state === 'FAILED'
									? lineageStatus.state
									: null}
						detectionError={lineageStatus?.errorMessage || ''}
					/>
				</div>
			</div>
		{:else}
		<!-- ═══════════════════ Catalog View ═══════════════════ -->
		<div class="flex-1 overflow-y-auto">
		{#if !selectedTable}
			<div class="flex items-center justify-center h-full">
				<div class="text-center space-y-3">
					<div class="text-xl font-semibold">Select a table or View</div>
					<div class="text-base text-muted-foreground max-w-md">
						Please select a table from the left sidebar to view or edit its catalog information.
					</div>
				</div>
			</div>
		{:else if catalogLoading}
			<div class="flex items-center justify-center h-full">
				<Spinner size="lg" class="text-primary" />
			</div>
		{:else if catalogData}
			<div class="px-8 py-4">
				<!-- Header -->
				<div class="flex items-center justify-between mb-4">
					<div class="flex items-center gap-2">
						<Switch
							checked={isTableActive(selectedTable.databaseName, selectedTable.schemaName, selectedTable.tableName)}
							onchange={() => handleToggleActive(selectedTable!.tableName, !isTableActive(selectedTable!.databaseName, selectedTable!.schemaName, selectedTable!.tableName))}
							title="Toggle AI context (active tables will be used for AI responses)"
						/>
						<h2 class="text-sm font-semibold">{selectedTable.catalogTableName || selectedTable.tableName}</h2>
						{#if hasUnsavedChanges}
							<Badge variant="outline" class="text-amber-500 border-amber-500">
								<AlertTriangle size={12} class="mr-1" /> Unsaved
							</Badge>
						{/if}
					</div>
					<div class="flex gap-1.5">
						<button
							type="button"
							onclick={handleCollectAI}
							disabled={collectingAI}
							class="inline-flex items-center justify-center rounded-md bg-gradient-to-r from-violet-500 to-purple-600 hover:from-violet-600 hover:to-purple-700 text-white h-7 text-xs px-2.5 font-medium disabled:opacity-50 disabled:pointer-events-none"
						>
							{#if collectingAI}
								<Loader2 size={12} class="mr-1 animate-spin" />
							{:else}
								<Sparkles size={12} class="mr-1" />
							{/if}
							AI Collection
						</button>
						<Button size="sm" onclick={handleSave} disabled={savingCatalog} class="h-7 text-xs px-2.5">
							{#if savingCatalog}<Loader2 size={12} class="mr-1 animate-spin" />{/if}
							{i18n('catalog.save')}
						</Button>
					</div>
				</div>

				<!-- Table Description -->
				<div class="mb-4">
					<div class="flex items-center justify-between mb-1">
						<label class="block text-xs font-medium text-muted-foreground" for="catalog-table-desc">{i18n('catalog.table.description')}</label>
						<button
							type="button"
							class="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-primary transition-colors"
							title={i18n('catalog.description.expand')}
							onclick={() => openDescDialog({ type: 'table' }, i18n('catalog.table.description'))}
						>
							<Maximize2 size={12} />
							{i18n('catalog.description.expand')}
						</button>
					</div>
					<textarea
						id="catalog-table-desc"
						bind:value={tableDescription}
						rows={4}
						placeholder={i18n('catalog.table.description')}
						class="flex w-full rounded-md border border-input bg-background px-3 py-2 text-xs min-h-[80px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
					></textarea>
				</div>

				<!-- Columns Table -->
				<div>
					<h3 class="text-xs font-semibold mb-2 text-muted-foreground">Columns ({editedColumns.length})</h3>
					<div class="border rounded-md overflow-x-auto">
						<table class="w-full text-sm" style="table-layout: fixed;">
							<colgroup>
								<col style="width: {columnWidths.name}px" />
								<col style="width: {columnWidths.columnType}px" />
								<col style="width: {columnWidths.description}px" />
								<col style="width: {columnWidths.exampleValues}px" />
							</colgroup>
							<thead class="bg-muted/50">
								<tr>
									{#each [
										{ key: 'name', label: i18n('catalog.column.name') || 'Column Name' },
										{ key: 'columnType', label: i18n('catalog.column.schema') || 'Schema' },
										{ key: 'description', label: i18n('catalog.column.description') || 'Description' },
										{ key: 'exampleValues', label: (i18n('catalog.column.example') || 'Example Values') + ' (comma-separated)' }
									] as header}
										<th class="px-3 py-2 text-left text-xs font-medium text-muted-foreground relative select-none" style="width: {columnWidths[header.key]}px">
											{header.label}
											<div
												class="absolute right-0 top-0 h-full w-1.5 cursor-col-resize hover:bg-primary/30 transition-colors {resizingColumn === header.key ? 'bg-primary/40' : ''}"
												onmousedown={(e) => handleColumnResize(e, header.key)}
											></div>
										</th>
									{/each}
								</tr>
							</thead>
							<tbody>
								{#each editedColumns as col, idx}
									{@const depth = col.depth || 0}
									{@const isNested = col.isNested || false}
									{@const displayName = isNested ? col.name.split('.').pop() : col.name}
									{@const displayType = (col.columnType?.toUpperCase()?.includes('STRUCT<') || col.columnType?.toUpperCase()?.includes('RECORD')) ? 'RECORD' : (col.columnType || '')}
									{@const valuesString = exampleValuesInput[col.name] !== undefined ? exampleValuesInput[col.name] : parseExampleValues(col.exampleValues).join(', ')}
									<tr class="border-t border-border hover:bg-accent/30">
										<td class="px-3 py-1.5" style="width: {columnWidths.name}px">
											<div class="truncate" style="font-weight: {isNested ? 400 : 500}; padding-left: {depth * 16}px; color: {isNested ? 'hsl(var(--muted-foreground))' : 'inherit'}" title={col.name}>
												{#if isNested}<span class="text-muted-foreground/60 mr-1">└</span>{/if}
												{displayName}
												{#if col.primaryKey}<span title="Primary Key"><Key size={12} class="inline text-amber-500 ml-1" /></span>{/if}
											</div>
										</td>
										<td class="px-3 py-1.5" style="width: {columnWidths.columnType}px">
											<div class="truncate text-xs text-muted-foreground" title={col.columnType}>{displayType}</div>
										</td>
										<td class="px-3 py-1.5" style="width: {columnWidths.description}px">
											<div class="group/desc relative">
												<textarea
													value={col.description}
													oninput={(e) => {
														editedColumns[idx] = { ...editedColumns[idx], description: (e.target as HTMLTextAreaElement).value };
														editedColumns = [...editedColumns];
													}}
													rows={2}
													placeholder={i18n('catalog.column.description')}
													class="w-full bg-transparent text-xs border border-transparent hover:border-border focus:border-primary focus:outline-none rounded px-1 py-0.5 pr-6 resize-none min-h-0"
												></textarea>
												<button
													type="button"
													class="absolute top-0.5 right-0.5 p-0.5 rounded text-muted-foreground/60 opacity-0 group-hover/desc:opacity-100 hover:text-primary hover:bg-accent transition-all"
													title={i18n('catalog.description.expand')}
													onclick={() => openDescDialog({ type: 'column', idx }, displayName || col.name)}
												>
													<Maximize2 size={12} />
												</button>
											</div>
										</td>
										<td class="px-3 py-1.5" style="width: {columnWidths.exampleValues}px">
										{#if true}{@const tags = valuesString ? valuesString.split(',').map(v => v.trim()).filter(v => v.length > 0) : []}
										{@const isExpanded = expandedExampleRows.has(col.name)}
										{@const visibleTags = isExpanded ? tags : tags.slice(0, EXAMPLE_TAG_LIMIT)}
										{@const hiddenCount = tags.length - EXAMPLE_TAG_LIMIT}
										<div class="flex flex-wrap items-center gap-1 min-h-[28px] w-full rounded px-1 py-0.5 border border-transparent hover:border-border focus-within:border-primary transition-colors">
											{#each visibleTags as tag, tagIdx}
												<span class="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-md bg-primary/10 text-primary text-[11px] leading-tight whitespace-nowrap max-w-[150px]">
													<span class="truncate">{tag}</span>
													<button
														type="button"
														class="shrink-0 hover:text-destructive transition-colors ml-0.5"
														onclick={() => {
															const newTags = tags.filter((_, i) => i !== tagIdx);
															const newValue = newTags.join(', ');
															exampleValuesInput = { ...exampleValuesInput, [col.name]: newValue };
															editedColumns[idx] = { ...editedColumns[idx], exampleValues: JSON.stringify(newTags) };
															editedColumns = [...editedColumns];
														}}
													>
														<X size={10} />
													</button>
												</span>
											{/each}
											{#if !isExpanded && hiddenCount > 0}
												<button
													type="button"
													class="px-1.5 py-0.5 rounded-md bg-muted text-muted-foreground text-[11px] leading-tight hover:bg-muted/80 transition-colors"
													onclick={() => { expandedExampleRows = new Set([...expandedExampleRows, col.name]); }}
												>+{hiddenCount} more</button>
											{:else if isExpanded && hiddenCount > 0}
												<button
													type="button"
													class="px-1.5 py-0.5 rounded-md bg-muted text-muted-foreground text-[11px] leading-tight hover:bg-muted/80 transition-colors"
													onclick={() => { const next = new Set(expandedExampleRows); next.delete(col.name); expandedExampleRows = next; }}
												>Show less</button>
											{/if}
											<input
												type="text"
												placeholder={tags.length === 0 ? 'Add values...' : ''}
												class="flex-1 min-w-[60px] bg-transparent text-xs outline-none py-0.5"
												onkeydown={(e) => {
													const input = e.target as HTMLInputElement;
													if ((e.key === ',' || e.key === 'Enter') && input.value.trim()) {
														e.preventDefault();
														const newTag = input.value.trim().replace(/,$/, '');
														if (newTag) {
															const newTags = [...tags, newTag];
															const newValue = newTags.join(', ');
															exampleValuesInput = { ...exampleValuesInput, [col.name]: newValue };
															editedColumns[idx] = { ...editedColumns[idx], exampleValues: JSON.stringify(newTags) };
															editedColumns = [...editedColumns];
														}
														input.value = '';
													} else if (e.key === 'Backspace' && !input.value && tags.length > 0) {
														const newTags = tags.slice(0, -1);
														const newValue = newTags.join(', ');
														exampleValuesInput = { ...exampleValuesInput, [col.name]: newValue };
														editedColumns[idx] = { ...editedColumns[idx], exampleValues: JSON.stringify(newTags) };
														editedColumns = [...editedColumns];
													}
												}}
												onblur={(e) => {
													const input = e.target as HTMLInputElement;
													if (input.value.trim()) {
														const newTag = input.value.trim().replace(/,$/, '');
														if (newTag) {
															const newTags = [...tags, newTag];
															const newValue = newTags.join(', ');
															exampleValuesInput = { ...exampleValuesInput, [col.name]: newValue };
															editedColumns[idx] = { ...editedColumns[idx], exampleValues: JSON.stringify(newTags) };
															editedColumns = [...editedColumns];
														}
														input.value = '';
													}
												}}
											/>
										</div>
										{/if}</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				</div>
			</div>
		{:else}
			<div class="flex items-center justify-center h-full">
				<div class="text-center space-y-3">
					<div class="text-xl font-semibold">Select a table or View</div>
					<div class="text-base text-muted-foreground max-w-md">
						Please select a table from the left sidebar to view or edit its catalog information.
					</div>
				</div>
			</div>
		{/if}
		</div>
		{/if}
	</div>
</div>

<!-- ═══════════════════════════════════════════════ -->
<!-- Unsaved Changes Dialog                         -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showUnsavedDialog}>
	<h2 class="text-lg font-semibold pr-6">Unsaved Changes</h2>
	<p class="text-sm text-muted-foreground mt-2">
		You have unsaved changes in the current table. Do you want to save your changes before leaving?
	</p>
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={handleCancelTableChange}>Cancel</Button>
		<Button variant="destructive" onclick={handleDiscardAndContinue}>Don't Save</Button>
		<Button onclick={handleSaveAndContinue} disabled={savingCatalog}>
			{#if savingCatalog}<Loader2 size={14} class="mr-1 animate-spin" />{/if}
			Save
		</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- AI Config Required Dialog                      -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showAIConfigDialog}>
	<h2 class="text-lg font-semibold flex items-center gap-3 pr-6">
		<AlertCircle size={24} class="text-yellow-500" />
		AI Configuration Required
	</h2>
	<p class="text-sm text-muted-foreground mt-2">Configure your AI settings to use metadata collection features.</p>
	<div class="py-4">
		<p class="text-sm leading-relaxed mb-3">To use AI metadata collection features, you need to configure your AI settings first.</p>
		<p class="text-sm text-muted-foreground leading-relaxed">Please set up your AI API key (OpenAI, Azure, Custom AI, etc.) in the settings to enable AI-powered metadata generation.</p>
	</div>
	<div class="flex justify-end">
		<Button onclick={() => { showAIConfigDialog = false; window.location.href = '/setting'; }}>Go to AI Settings</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- DB/Schema Selection Dialog                     -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showDbSchemaDialog} class="sm:max-w-md">
	<h2 class="text-lg font-semibold flex items-center gap-3 pr-6">
		<Database size={24} class="text-primary" />
		Select Databases and Schemas
	</h2>
	<p class="text-sm text-muted-foreground mt-1">Select the databases and schemas for AI metadata collection.</p>
	<div class="mt-4">
		{#if selectionTreeLoading}
			<div class="flex justify-center py-10"><Loader2 size={24} class="animate-spin text-muted-foreground" /></div>
		{:else if selectionTree.length === 0}
			<div class="text-center py-10 text-muted-foreground">No databases found.</div>
		{:else}
			<div class="max-h-[400px] overflow-y-auto border rounded-lg p-3">
				{#each selectionTree as dbNode (dbNode.key)}
					{@const dbState = getDbCheckState(dbNode)}
					<div class="mb-1">
						<div class="flex items-center gap-2 py-1.5 px-1 rounded hover:bg-accent">
							<button class="p-0.5" onclick={() => toggleSelectionExpand(dbNode.key)}>
								<ChevronRight size={16} class="transition-transform {expandedKeys.has(dbNode.key) ? 'rotate-90' : ''}" />
							</button>
							<Checkbox
								checked={dbState === 'checked' ? true : dbState === 'indeterminate' ? 'indeterminate' : false}
								onchange={() => toggleSelectionCheck(dbNode.key, dbNode)}
							/>
							<Database size={16} class="text-muted-foreground" />
							<span class="text-sm">{dbNode.title}</span>
						</div>
						{#if expandedKeys.has(dbNode.key) && dbNode.children}
							{#each dbNode.children as schemaNode (schemaNode.key)}
								<div class="flex items-center gap-2 py-1.5 pl-10 pr-1 rounded hover:bg-accent">
									<Checkbox
										checked={checkedKeys.has(schemaNode.key)}
										onchange={() => toggleSelectionCheck(schemaNode.key, schemaNode)}
									/>
									<span class="text-sm">{schemaNode.title}</span>
								</div>
							{/each}
						{/if}
					</div>
				{/each}
			</div>
			{#if selectedSchemaCount > 0}
				<div class="mt-3 text-xs text-muted-foreground">{selectedSchemaCount} schema(s) selected</div>
			{/if}
		{/if}
	</div>
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={() => { showDbSchemaDialog = false; }}>Cancel</Button>
		<Button onclick={handleContinueToConfirm} disabled={selectedSchemaCount === 0}>Continue</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Confirm Collection Dialog                      -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showConfirmDialog} class="sm:max-w-md">
	<h2 class="text-lg font-semibold flex items-center gap-2 pr-6">
		<Sparkles size={20} class="text-primary" />
		Collect AI Metadata for Selected Schemas
	</h2>
	{#if confirmData}
		<div class="mt-3">
			<p class="text-sm">
				This will collect AI metadata (descriptions, categories) for
				<strong>{confirmData.totalTableCount} tables</strong>
				in the following {confirmData.schemas.length} schema(s):
			</p>
			<div class="mt-3 max-h-[150px] overflow-y-auto bg-muted p-2 rounded text-xs font-mono">
				{#each confirmData.schemas as s}
					<div>{s.displayName}</div>
				{/each}
			</div>
			<p class="mt-3 text-sm text-muted-foreground">This process may take some time depending on the number of tables. Do you want to continue?</p>
		</div>
	{/if}
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={() => { showConfirmDialog = false; }}>Cancel</Button>
		<Button onclick={executeCollection}>Start Collection</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Completion Dialog                              -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showCompletionDialog} class="sm:max-w-md">
	<h2 class="text-lg font-semibold flex items-center gap-2 pr-6">
		<CheckCircle size={20} class="text-green-500" />
		Collection Completed
	</h2>
	<p class="text-sm text-muted-foreground mt-1">AI metadata collection has finished for {collectionStore.collectionProgressList.length} schema(s).</p>
	<div class="mt-4">
		<div class="grid grid-cols-3 gap-2 text-center bg-muted p-3 rounded-lg mb-4">
			<div>
				<div class="text-xs text-muted-foreground">Total</div>
				<div class="text-lg font-semibold">{collectionStore.totalCount}</div>
			</div>
			<div>
				<div class="text-xs text-green-500">Success</div>
				<div class="text-lg font-semibold text-green-500">{collectionStore.totalSuccess}</div>
			</div>
			<div>
				<div class="text-xs text-red-500">Failed</div>
				<div class="text-lg font-semibold text-red-500">{collectionStore.totalFail}</div>
			</div>
		</div>
		{#if collectionStore.collectionProgressList.length > 1}
			<div>
				<div class="font-medium text-sm mb-2">Schema Details:</div>
				<div class="max-h-[150px] overflow-y-auto bg-muted p-2 rounded text-xs">
					{#each collectionStore.collectionProgressList as p, i}
						<div class="flex justify-between py-1">
							<span class="font-mono">{p.databaseName}.{p.schemaName}</span>
							<span>
								<span class="text-green-500">{p.successCount}</span> / <span class="text-red-500">{p.failCount}</span> / {p.total}
							</span>
						</div>
					{/each}
				</div>
			</div>
		{/if}
	</div>
	<div class="flex justify-end mt-6">
		<Button onclick={() => { showCompletionDialog = false; resetCollectionProgress(); }}>Close</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Business Insight Dialog                        -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={showInsightDialog} class="sm:max-w-4xl">
	<h2 class="text-xl font-semibold text-center pt-2 pr-6">Business Insights</h2>
	<div class="mt-4 overflow-y-auto max-h-[70vh] px-2">
		{#if insightLoading}
			<div class="flex justify-center py-12"><Loader2 size={24} class="animate-spin text-primary" /></div>
		{:else}
			<!-- Reference Links -->
			<div class="mb-4 bg-muted/50 p-3 rounded-md border">
				<h3 class="text-sm font-medium mb-3">Reference Links</h3>
				<div class="space-y-2.5">
					<div>
						<label class="block text-xs text-muted-foreground mb-1" for="ref-play-store">Google Play Store Link</label>
						<input
							id="ref-play-store"
							type="text"
							bind:value={insightData.playStoreLink}
							placeholder="https://play.google.com/store/apps/details?id=..."
							class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						/>
					</div>
					<div>
						<label class="block text-xs text-muted-foreground mb-1" for="ref-app-store">Apple App Store Link</label>
						<input
							id="ref-app-store"
							type="text"
							bind:value={insightData.appStoreLink}
							placeholder="https://apps.apple.com/app/..."
							class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						/>
					</div>
					<div>
						<label class="block text-xs text-muted-foreground mb-1" for="ref-website">Website Link</label>
						<input
							id="ref-website"
							type="text"
							bind:value={insightData.webLink}
							placeholder="https://example.com"
							class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						/>
					</div>
				</div>
			</div>

			<!-- Actions -->
			<div class="flex justify-end gap-2 mb-4">
				<Button onclick={handleGenerateInsight} disabled={insightGenerating}>
					{#if insightGenerating}
						<Loader2 size={14} class="mr-1.5 animate-spin" />
					{:else}
						<Sparkles size={14} class="mr-1.5" />
					{/if}
					Generate with AI
				</Button>
				<Button variant="outline" onclick={handleSaveInsight} disabled={insightSaving}>
					{#if insightSaving}<Loader2 size={14} class="mr-1.5 animate-spin" />{/if}
					Save
				</Button>
			</div>

			<!-- Insight Content with Edit/Preview Tabs -->
			<div class="mb-4 bg-muted/50 p-3 rounded-md border">
				<h3 class="text-sm font-medium mb-3">Insight Content</h3>
				<Tabs bind:value={insightTab}>
					<TabsList>
						<TabsTrigger value="edit">Edit</TabsTrigger>
						<TabsTrigger value="preview">Preview</TabsTrigger>
					</TabsList>
					<TabsContent value="edit">
						<textarea
							bind:value={insightData.insightContent}
							rows={8}
							placeholder="Enter business insights here or generate with AI..."
							class="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-none"
						></textarea>
					</TabsContent>
					<TabsContent value="preview">
						<div class="p-3 bg-background border rounded-md min-h-[150px] max-h-[250px] overflow-y-auto">
							{#if insightData.insightContent}
								<MarkdownRenderer content={insightData.insightContent} />
							{:else}
								<div class="text-center text-muted-foreground p-10">No content to preview</div>
							{/if}
						</div>
					</TabsContent>
				</Tabs>
			</div>
		{/if}
	</div>
</Dialog>

<!-- Description "View larger" modal -->
<Dialog bind:open={showDescDialog} class="sm:max-w-3xl">
	<h2 class="text-base font-semibold pt-2 pr-6 truncate" title={descDialogTitle}>{descDialogTitle}</h2>
	<p class="text-xs text-muted-foreground mt-1">{i18n('catalog.column.description') || 'Description'}</p>
	<div class="mt-3">
		<textarea
			value={descDialogValue}
			oninput={(e) => updateDescDialogValue((e.target as HTMLTextAreaElement).value)}
			rows={16}
			placeholder={i18n('catalog.column.description')}
			class="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-relaxed max-h-[60vh] min-h-[200px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-y font-mono"
		></textarea>
	</div>
	<div class="flex justify-end gap-2 mt-4">
		<Button onclick={() => { showDescDialog = false; }}>
			{i18n('catalog.description.done')}
		</Button>
	</div>
</Dialog>
