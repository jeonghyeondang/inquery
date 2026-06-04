<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import {
		X, Play, BarChart3, TrendingUp, PieChart, ScatterChart,
		Table2, LayoutGrid, Loader2, ChevronDown, Filter, WrapText,
		ArrowUpDown, Download
	} from 'lucide-svelte';
	import { Button, Card } from '$lib/components/ui';
	import {
		generateChartOptionWithConfig, guessChartType,
		type ChartType, type ChartConfig, type MetricFormat, type XAxisFormat, formatValue, formatCellDisplay
	} from '$lib/utils/chartUtils';
	import connectionService from '$lib/service/connection';
	import sqlService from '$lib/service/sql';
	import type { IConnectionListItem } from '$lib/types/connection';
	import { databaseMap } from '$lib/types/database';

	interface Props {
		onclose: () => void;
		onsave: (data: { name: string; schema: string; dataSourceId?: number; databaseName?: string; id?: number; sourceType?: string }) => void;
		chart?: import('$lib/service/dashboard').IChart | null;
		initialSql?: string;
		initialResultData?: any;
		initialDataSourceId?: number;
		initialDatabase?: string;
		initialChartType?: ChartType;
		initialChartConfig?: ChartConfig;
		initialName?: string;
		sourceType?: string;
	}

	let { onclose, onsave, chart = null, initialSql, initialResultData, initialDataSourceId, initialDatabase, initialChartType, initialChartConfig, initialName, sourceType }: Props = $props();

	// Tabs
	let activeTab = $state<'chart' | 'data'>('data');

	// Data source
	let connections = $state<IConnectionListItem[]>([]);
	let selectedDataSourceId = $state<number | undefined>(undefined);
	let selectedDatabase = $state('');
	let databases = $state<string[]>([]);
	let loadingDatabases = $state(false);
	let showSourceDropdown = $state(false);
	let openDropdown = $state<string | null>(null);

	function toggleDropdown(id: string) {
		openDropdown = openDropdown === id ? null : id;
	}

	function getDbIcon(type: string): string | null {
		const info = databaseMap[type?.toUpperCase()];
		return info?.img || null;
	}

	function getSelectedConnection() {
		return connections.find(c => c.id === selectedDataSourceId);
	}

	// Load databases when connection changes
	async function loadDatabases(dataSourceId: number) {
		if (!dataSourceId || dataSourceId <= 0) return;
		loadingDatabases = true;
		databases = [];
		try {
			const res = await connectionService.getDatabaseList({ dataSourceId });
			databases = (Array.isArray(res) ? res : (res as any)?.data || []).map((d: any) => typeof d === 'string' ? d : d.name || d.databaseName || '').filter(Boolean);
		} catch { databases = []; }
		finally { loadingDatabases = false; }
	}

	function handleConnectionChange(newId: number) {
		selectedDataSourceId = newId;
		selectedDatabase = '';
		if (newId) loadDatabases(newId);
	}

	// SQL
	let sql = $state('');
	let executing = $state(false);
	let executionTime = $state<number | null>(null);

	// Result
	let resultData = $state<{ headerList: any[]; dataList: any[][] } | null>(null);
	let resultError = $state<string | null>(null);

	// Chart
	let chartName = $state('');
	let chartType = $state<ChartType>('BAR');
	let chartOption = $state<Record<string, unknown> | null>(null);
	let chartConfig = $state<ChartConfig>({});

	// Multi-select helpers (reactive)
	let selectedYValues = $derived(chartConfig.yAxes || []);
	let selectedDimValues = $derived(chartConfig.dimensions || (chartConfig.dimension ? [chartConfig.dimension] : []));

	// Column helpers
	let columnNames = $derived(
		resultData?.headerList
			?.filter((h: any) => {
				const name = (typeof h === 'string' ? h : h.name || '').toUpperCase();
				return name !== 'ROW NUMBER' && name !== '#' && name !== 'INQUERY_ROW_NUMBER';
			})
			.map((h: any) => typeof h === 'string' ? h : h.name || '') || []
	);

	// Data type badge (same as workspace)
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
		if (t.includes('ARRAY') || t.includes('STRUCT') || t.includes('RECORD'))
			return { bg: 'rgba(236,72,153,0.15)', color: '#f9a8d4', border: 'rgba(236,72,153,0.2)', label: t.toLowerCase() };
		if (t.includes('BLOB') || t.includes('BINARY') || t.includes('BYTES'))
			return { bg: 'rgba(239,68,68,0.15)', color: '#fca5a5', border: 'rgba(239,68,68,0.2)', label: 'binary' };
		if (t.includes('JSON') || t === 'OBJECT' || t === 'VARIANT')
			return { bg: 'rgba(6,182,212,0.15)', color: '#67e8f9', border: 'rgba(6,182,212,0.2)', label: 'json' };
		return { bg: 'rgba(75,85,99,0.15)', color: '#d1d5db', border: 'rgba(75,85,99,0.2)', label: dataType?.toLowerCase() || 'unknown' };
	}

	// SQL editor / result panel vertical resize
	let editorHeight = $state(200);
	let isResizingPanel = $state(false);
	let panelResizeStartY = 0;
	let panelResizeStartHeight = 0;

	function handlePanelResizeStart(e: MouseEvent) {
		e.preventDefault();
		isResizingPanel = true;
		panelResizeStartY = e.clientY;
		panelResizeStartHeight = editorHeight;

		const onMouseMove = (ev: MouseEvent) => {
			const diff = ev.clientY - panelResizeStartY;
			editorHeight = Math.max(80, Math.min(600, panelResizeStartHeight + diff));
		};
		const onMouseUp = () => {
			isResizingPanel = false;
			document.removeEventListener('mousemove', onMouseMove);
			document.removeEventListener('mouseup', onMouseUp);
			document.body.style.cursor = '';
			document.body.style.userSelect = '';
		};
		document.body.style.cursor = 'row-resize';
		document.body.style.userSelect = 'none';
		document.addEventListener('mousemove', onMouseMove);
		document.addEventListener('mouseup', onMouseUp);
	}

	// Column resize
	let columnWidths = $state<number[]>([]);
	let resizingColIdx = $state<number | null>(null);
	let resizeStartX = 0;
	let resizeStartWidth = 0;

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
		};
		document.body.style.cursor = 'col-resize';
		document.body.style.userSelect = 'none';
		document.addEventListener('mousemove', onMouseMove);
		document.addEventListener('mouseup', onMouseUp);
	}

	// Column sorting
	let sortColIdx = $state<number | null>(null);
	let sortDirection = $state<'asc' | 'desc'>('asc');

	function handleColumnSort(colIdx: number) {
		if (sortColIdx === colIdx) {
			sortDirection = sortDirection === 'asc' ? 'desc' : 'asc';
		} else {
			sortColIdx = colIdx;
			sortDirection = 'asc';
		}
		dataTablePage = 0;
	}

	let sortedDataList = $derived.by(() => {
		if (!resultData) return [];
		if (sortColIdx === null) return resultData.dataList;
		const idx = sortColIdx;
		const dir = sortDirection === 'asc' ? 1 : -1;
		return [...resultData.dataList].sort((a, b) => {
			const va = a[idx];
			const vb = b[idx];
			if (va === null || va === undefined) return 1;
			if (vb === null || vb === undefined) return -1;
			const na = Number(va);
			const nb = Number(vb);
			if (!isNaN(na) && !isNaN(nb)) return (na - nb) * dir;
			return String(va).localeCompare(String(vb)) * dir;
		});
	});

	const chartTypes: { type: ChartType; label: string; icon: typeof BarChart3 }[] = [
		{ type: 'BAR', label: 'Bar', icon: BarChart3 },
		{ type: 'LINE', label: 'Line', icon: TrendingUp },
		{ type: 'PIE', label: 'Pie', icon: PieChart },
		{ type: 'SCATTER', label: 'Scatter', icon: ScatterChart },
		{ type: 'TABLE', label: 'Table', icon: Table2 },
		{ type: 'CARD', label: 'Metrics', icon: LayoutGrid },
	];

	const THEME_COLORS = [
		// Default
		{ value: 'default', label: 'Default', colors: ['#6366f1', '#06b6d4', '#f59e0b', '#ef4444'] },
		// Multi-color palettes
		{ value: 'vivid', label: 'Vivid', colors: ['#1FA8C9', '#454E7C', '#5AC189', '#FF7F44'] },
		{ value: 'tropical', label: 'Tropical', colors: ['#6BD3B3', '#FCC550', '#408184', '#66CBE2'] },
		{ value: 'classic', label: 'Classic', colors: ['#5470C6', '#91CC75', '#FAC858', '#EE6666'] },
		{ value: 'bold', label: 'Bold', colors: ['#3366cc', '#dc3912', '#ff9900', '#109618'] },
		{ value: 'teal', label: 'Teal & Coral', colors: ['#29696B', '#5BCACE', '#F4B02A', '#F1826A'] },
		{ value: 'rainbow', label: 'Rainbow', colors: ['#e6194b', '#3cb44b', '#ffe119', '#4363d8'] },
		{ value: 'ocean', label: 'Ocean', colors: ['#0077B6', '#00B4D8', '#48CAE4', '#90E0EF'] },
		{ value: 'sunset', label: 'Sunset', colors: ['#FF6B6B', '#FFA07A', '#FFD93D', '#6BCB77'] },
		{ value: 'forest', label: 'Forest', colors: ['#2D6A4F', '#40916C', '#52B788', '#74C69D'] },
		{ value: 'berry', label: 'Berry', colors: ['#E63946', '#A8DADC', '#457B9D', '#1D3557'] },
		{ value: 'neon', label: 'Neon', colors: ['#00F5FF', '#FF006E', '#8338EC', '#FFBE0B'] },
		{ value: 'pastel', label: 'Pastel', colors: ['#FFB3BA', '#BAFFC9', '#BAE1FF', '#FFFFBA'] },
		{ value: 'earth', label: 'Earth', colors: ['#8B4513', '#DEB887', '#D2691E', '#CD853F'] },
		{ value: 'nordic', label: 'Nordic', colors: ['#5E81AC', '#81A1C1', '#88C0D0', '#8FBCBB'] },
		{ value: 'retro', label: 'Retro', colors: ['#264653', '#2A9D8F', '#E9C46A', '#F4A261'] },
		{ value: 'candy', label: 'Candy', colors: ['#FF69B4', '#FF1493', '#DB7093', '#FF6EB4'] },
		// Single-hue palettes
		{ value: 'blue', label: 'Blue', colors: ['#3b82f6', '#60a5fa', '#2563eb', '#93c5fd'] },
		{ value: 'indigo', label: 'Indigo', colors: ['#6366f1', '#818cf8', '#4f46e5', '#a5b4fc'] },
		{ value: 'cyan', label: 'Cyan', colors: ['#06b6d4', '#22d3ee', '#0891b2', '#67e8f9'] },
		{ value: 'emerald', label: 'Emerald', colors: ['#10b981', '#34d399', '#059669', '#6ee7b7'] },
		{ value: 'violet', label: 'Violet', colors: ['#8b5cf6', '#a78bfa', '#7c3aed', '#c4b5fd'] },
		{ value: 'amber', label: 'Amber', colors: ['#f59e0b', '#fbbf24', '#d97706', '#fcd34d'] },
		{ value: 'rose', label: 'Rose', colors: ['#f43f5e', '#fb7185', '#e11d48', '#fda4af'] },
		{ value: 'slate', label: 'Slate', colors: ['#475569', '#64748b', '#334155', '#94a3b8'] },
	];

	// Close dropdown on outside click
	function handleOutsideClick(e: MouseEvent) {
		if (showSourceDropdown) {
			const target = e.target as HTMLElement;
			if (!target.closest('.source-dropdown-container')) {
				showSourceDropdown = false;
			}
		}
		if (openDropdown) {
			const target = e.target as HTMLElement;
			if (!target.closest('.custom-dropdown')) {
				openDropdown = null;
			}
		}
	}

	onMount(async () => {
		document.addEventListener('click', handleOutsideClick);

		try {
			const res = await connectionService.getList({ pageNo: 1, pageSize: 1000 });
			connections = (res as any)?.data || [];
			if (connections.length > 0 && !selectedDataSourceId) {
				selectedDataSourceId = connections[0].id;
			}
		} catch { /* ignore */ }

		// Pre-populate if editing existing chart
		if (chart) {
			chartName = chart.name || '';
			if (chart.dataSourceId) selectedDataSourceId = chart.dataSourceId;
			if (chart.databaseName) selectedDatabase = chart.databaseName;
			if (chart.schema) {
				try {
					const parsed = JSON.parse(chart.schema);
					if (parsed.sql) sql = parsed.sql;
					if (parsed.chartType) chartType = parsed.chartType;
					if (parsed.chartConfig) chartConfig = parsed.chartConfig;
					if (parsed.resultData) {
						resultData = parsed.resultData;
						updateChartPreview();
						activeTab = 'chart';
					}
				} catch { /* ignore */ }
			}
		}

		// Pre-populate from initial props (e.g. from AI Chat pin)
		if (initialName && !chart) chartName = initialName;
		if (initialSql && !chart) sql = initialSql;
		if (initialDataSourceId && !chart) selectedDataSourceId = initialDataSourceId;
		if (initialDatabase && !chart) selectedDatabase = initialDatabase;
		if (initialResultData && !chart) {
			const data = Array.isArray(initialResultData) ? initialResultData[0] : initialResultData;
			if (data?.headerList) {
				resultData = { headerList: data.headerList, dataList: data.dataList || [] };
				// Use provided chart type/config, fall back to auto-detect
				if (initialChartConfig) chartConfig = { ...initialChartConfig };
				chartType = initialChartType || guessChartType(resultData);
				updateChartPreview();
				activeTab = 'chart';
			}
		}

		// Load databases only if the selected connection exists in the loaded list
		if (selectedDataSourceId && connections.some(c => c.id === selectedDataSourceId)) {
			loadDatabases(selectedDataSourceId);
		}
	});

	onDestroy(() => {
		document.removeEventListener('click', handleOutsideClick);
	});

	async function handleExecuteSql() {
		if (!sql.trim() || !selectedDataSourceId || executing) return;
		executing = true;
		resultError = null;
		resultData = null;
		executionTime = null;
		const start = Date.now();

		try {
			const res = await sqlService.executeSql({
				dataSourceId: selectedDataSourceId,
				sql: sql.trim(),
				pageNo: 1,
				pageSize: 50,
				...(selectedDatabase ? { databaseName: selectedDatabase } : {})
			});
			executionTime = Date.now() - start;
			
			// Handle both array and single object responses
			const dataList = Array.isArray(res) ? res : [res];
			const data = dataList[0] as any;
			
			if (data?.headerList) {
				const newData = { headerList: data.headerList, dataList: (data.dataList || []) as any[][] };
				resultData = newData;
				const autoType = guessChartType(newData);
				chartType = autoType;
				chartConfig = {}; // Reset config on new data
				updateChartPreview();
			} else {
				resultError = data?.error || data?.message || data?.description || 'No data returned';
			}
		} catch (e: any) {
			resultError = e?.message || 'Query execution failed';
			executionTime = Date.now() - start;
		} finally {
			executing = false;
		}
	}

	// SQL Format
	let formatting = $state(false);
	async function handleFormatSql() {
		if (!sql.trim() || formatting) return;
		formatting = true;
		try {
			// Simple SQL formatting: normalize whitespace and keywords
			const keywords = ['SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'ORDER BY', 'GROUP BY', 'HAVING',
				'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'OUTER JOIN', 'FULL JOIN', 'CROSS JOIN',
				'ON', 'LIMIT', 'OFFSET', 'INSERT', 'UPDATE', 'DELETE', 'SET', 'VALUES', 'INTO',
				'CREATE', 'ALTER', 'DROP', 'TABLE', 'AS', 'UNION', 'UNION ALL', 'EXCEPT', 'INTERSECT',
				'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'WITH', 'DISTINCT', 'IN', 'NOT', 'IS', 'NULL', 'BETWEEN', 'LIKE', 'EXISTS'];
			let formatted = sql.trim();
			// Add newlines before major keywords
			const majorKeywords = ['SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'ORDER BY', 'GROUP BY', 'HAVING',
				'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'LIMIT', 'UNION', 'WITH'];
			for (const kw of majorKeywords) {
				const regex = new RegExp(`\\b(${kw})\\b`, 'gi');
				formatted = formatted.replace(regex, `\n${kw}`);
			}
			// Clean up multiple newlines and trim
			formatted = formatted.replace(/\n\s*\n/g, '\n').trim();
			sql = formatted;
		} finally { formatting = false; }
	}

	// Data table pagination
	const PAGE_SIZES = [50, 100, 200, 500, 1000];
	let dataPageSize = $state(50);
	let dataTablePage = $state(0);
	let dataTablePageCount = $derived(
		sortedDataList.length ? Math.ceil(sortedDataList.length / dataPageSize) : 0
	);
	let pagedResultData = $derived.by(() => {
		if (!sortedDataList.length) return [];
		const start = dataTablePage * dataPageSize;
		return sortedDataList.slice(start, start + dataPageSize);
	});

	// Export
	let showExportDropdown = $state(false);

	function exportAsCSV() {
		if (!resultData) return;
		const headers = resultData.headerList.map((h: any) => h.name || h);
		const rows = sortedDataList.map(row =>
			row.map(cell => {
				if (cell === null || cell === undefined) return '';
				const str = String(cell);
				return str.includes(',') || str.includes('"') || str.includes('\n')
					? `"${str.replace(/"/g, '""')}"` : str;
			}).join(',')
		);
		const csv = [headers.join(','), ...rows].join('\n');
		downloadFile(csv, `${chartName || 'chart-data'}.csv`, 'text/csv');
		showExportDropdown = false;
	}

	function exportAsJSON() {
		if (!resultData) return;
		const headers = resultData.headerList.map((h: any) => h.name || h);
		const data = sortedDataList.map(row => {
			const obj: Record<string, unknown> = {};
			headers.forEach((h: string, index: number) => {
				obj[h] = row[index];
			});
			return obj;
		});
		downloadFile(JSON.stringify(data, null, 2), `${chartName || 'chart-data'}.json`, 'application/json');
		showExportDropdown = false;
	}

	function exportAsInsertSQL() {
		if (!resultData) return;
		const headers = resultData.headerList.map((h: any) => h.name || h);
		const tableName = chartName?.replace(/[^a-zA-Z0-9_]/g, '_') || 'table_name';
		const lines = sortedDataList.map(row => {
			const values = row.map(cell => {
				if (cell === null || cell === undefined) return 'NULL';
				if (typeof cell === 'number') return String(cell);
				return `'${String(cell).replace(/'/g, "''")}'`;
			}).join(', ');
			return `INSERT INTO ${tableName} (${headers.join(', ')}) VALUES (${values});`;
		});
		downloadFile(lines.join('\n'), `${chartName || 'chart-data'}.sql`, 'text/sql');
		showExportDropdown = false;
	}

	function downloadFile(content: string, filename: string, mimeType: string) {
		const blob = new Blob([content], { type: mimeType });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = filename;
		a.click();
		URL.revokeObjectURL(url);
	}

	function updateChartPreview() {
		if (!resultData) { chartOption = null; return; }
		// Always use config-based generation for consistent preview
		chartOption = generateChartOptionWithConfig(resultData, chartType, chartConfig);
	}

	function selectChartType(type: ChartType) {
		chartType = type;
		chartConfig = {
			xAxis: chartConfig.xAxis,
			yAxes: chartConfig.yAxes,
			dimension: chartConfig.dimension,
			themeColor: chartConfig.themeColor,
			showLegend: chartConfig.showLegend,
			showValue: chartConfig.showValue,
			showAxis: chartConfig.showAxis,
			showGridLine: chartConfig.showGridLine,
			xAxisFormat: chartConfig.xAxisFormat,
			yAxisFormat: chartConfig.yAxisFormat,
		};
		updateChartPreview();
	}

	function setConfig(partial: Partial<ChartConfig>) {
		chartConfig = { ...chartConfig, ...partial };
		updateChartPreview();
	}

	function handleSave() {
		if (!chartName.trim()) return;
		const schema = JSON.stringify({
			chartType,
			chartConfig,
			sql,
			resultData,
			chartOption,
			option: chartOption
		});
		onsave({
			name: chartName.trim(),
			schema,
			dataSourceId: selectedDataSourceId,
			databaseName: selectedDatabase || undefined,
			...(chart?.id ? { id: chart.id } : {}),
			...(sourceType ? { sourceType } : {})
		});
	}

	// Selected theme color for dropdown display
	let selectedTheme = $derived(THEME_COLORS.find(t => t.value === (chartConfig.themeColor || 'default')));

	// Check if save should be enabled
	let canSave = $derived(
		chartName.trim() &&
		(chartType === 'TABLE' || chartType === 'CARD' ? !!resultData : !!chartOption)
	);
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onclick={onclose}>
	<div
		class="w-[92vw] max-w-[1800px] h-[85vh] bg-background border border-border rounded-xl shadow-2xl flex flex-col overflow-hidden"
		onclick={(e) => e.stopPropagation()}
	>
		<!-- Header -->
		<div class="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
			<div class="flex items-center gap-3">
				<h2 class="text-sm font-semibold">{chart ? 'Edit Chart' : 'New Chart'}</h2>
				<input
					class="h-7 px-2 border border-input bg-background rounded-md text-sm w-[200px] focus:outline-none focus:ring-1 focus:ring-ring"
					placeholder="Chart name..."
					bind:value={chartName}
				/>
			</div>
			<div class="flex items-center gap-2">
				<Button size="sm" onclick={handleSave} disabled={!canSave}>Save Chart</Button>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={onclose}>
					<X size={16} />
				</button>
			</div>
		</div>

		<!-- Tabs -->
		<div class="flex items-center border-b border-border px-4 shrink-0">
			<button
				class="px-4 py-2 text-xs font-medium border-b-2 transition-colors
					{activeTab === 'chart' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
				onclick={() => activeTab = 'chart'}
			>Chart</button>
			<button
				class="px-4 py-2 text-xs font-medium border-b-2 transition-colors
					{activeTab === 'data' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'}"
				onclick={() => activeTab = 'data'}
			>Data</button>
		</div>

		<!-- Content -->
		<div class="flex-1 min-h-0 overflow-hidden">
			{#if activeTab === 'data'}
				<!-- Data Tab -->
				<div class="flex flex-col h-full">
					<!-- Toolbar -->
					<div class="flex items-center gap-2 px-4 py-2 border-b border-border shrink-0">
					<!-- Custom data source dropdown with DB logo -->
					<div class="relative source-dropdown-container">
						<button
							type="button"
							onclick={() => showSourceDropdown = !showSourceDropdown}
							class="h-8 text-xs border border-input bg-background rounded-md px-2.5 pr-7 flex items-center gap-2 hover:bg-accent/50 focus:outline-none focus:ring-1 focus:ring-ring min-w-[180px] max-w-[220px] transition-colors"
						>
							{#if getSelectedConnection()}
								{@const conn = getSelectedConnection()!}
								{@const icon = getDbIcon(conn.type)}
								{#if icon}
									<img src={icon} alt={conn.type} class="w-4 h-4 object-contain shrink-0" />
								{/if}
								<span class="truncate">{conn.alias}</span>
							{:else}
								<span class="text-muted-foreground">Select data source</span>
							{/if}
							<ChevronDown size={12} class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground shrink-0" />
						</button>
						{#if showSourceDropdown}
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="absolute top-full left-0 mt-1 z-50 w-[240px] bg-popover border border-border rounded-lg shadow-lg py-1 max-h-[240px] overflow-y-auto"
								onmousedown={(e) => e.preventDefault()}
							>
								{#each connections as conn (conn.id)}
									{@const icon = getDbIcon(conn.type)}
									<button
										type="button"
										class="w-full flex items-center gap-2.5 px-3 py-2 text-xs hover:bg-accent/60 transition-colors {conn.id === selectedDataSourceId ? 'bg-accent text-accent-foreground' : 'text-foreground'}"
										onclick={() => { handleConnectionChange(conn.id); showSourceDropdown = false; }}
									>
										{#if icon}
											<img src={icon} alt={conn.type} class="w-4 h-4 object-contain shrink-0" />
										{:else}
											<div class="w-4 h-4 rounded bg-muted flex items-center justify-center shrink-0">
												<span class="text-[8px] font-bold text-muted-foreground">{conn.type?.charAt(0) || 'D'}</span>
											</div>
										{/if}
										<span class="truncate">{conn.alias}</span>
										{#if conn.id === selectedDataSourceId}
											<span class="ml-auto text-primary text-[10px]">✓</span>
										{/if}
									</button>
								{/each}
								{#if connections.length === 0}
									<div class="px-3 py-2 text-xs text-muted-foreground">No connections</div>
								{/if}
							</div>
						{/if}
					</div>

					<Button size="sm" class="h-7 text-xs gap-1" onclick={handleExecuteSql} disabled={executing || !sql.trim()}>
						{#if executing}
							<Loader2 size={12} class="animate-spin" />
						{:else}
							<Play size={12} />
						{/if}
						Run
					</Button>

					<Button variant="ghost" size="sm" class="h-7 text-xs gap-1" onclick={handleFormatSql} disabled={!sql.trim() || formatting} title="Format SQL">
						<WrapText size={12} />
						Format
					</Button>

					{#if executionTime !== null}
						<span class="text-[10px] text-muted-foreground">{executionTime}ms</span>
					{/if}

					{#if resultData}
						<span class="text-[10px] text-muted-foreground">
							{resultData.dataList.length} rows
						</span>
					{/if}

					<span class="text-[10px] text-muted-foreground ml-auto">
						{navigator.platform.includes('Mac') ? '⌘' : 'Ctrl'}+Enter to run
					</span>
					</div>

					<!-- SQL Editor -->
					<div class="flex-1 min-h-0 flex flex-col">
						<div class="shrink-0" style="height: {editorHeight}px;">
							{#await import('$lib/components/MonacoEditor/MonacoEditor.svelte') then { default: MonacoEditor }}
								<MonacoEditor
									bind:value={sql}
									language="sql"
									onmount={(editor, monaco) => {
										editor.addCommand(
											monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter,
											() => handleExecuteSql()
										);
									}}
								/>
							{/await}
						</div>

						<!-- Resize Handle -->
						<!-- svelte-ignore a11y_no_static_element_interactions -->
						<div
							class="shrink-0 flex items-center justify-center cursor-row-resize group border-t border-b border-border hover:border-primary/40 transition-colors {isResizingPanel ? 'border-primary/60 bg-primary/5' : ''}"
							style="height: 5px;"
							onmousedown={handlePanelResizeStart}
						>
							<div class="w-8 h-[3px] rounded-full bg-border group-hover:bg-primary/40 transition-colors {isResizingPanel ? 'bg-primary/60' : ''}"></div>
						</div>

						<!-- Result Preview -->
						<div class="flex-1 min-h-0 flex flex-col">
							{#if resultError}
								<div class="p-3 text-xs text-destructive">{resultError}</div>
							{:else if resultData}
								{@const hasRowNumCol = resultData.headerList.length > 0 && (resultData.headerList[0]?.dataType === 'INQUERY_ROW_NUMBER' || resultData.headerList[0]?.name === 'Row Number')}
								{@const displayHeaders = hasRowNumCol ? resultData.headerList.slice(1) : resultData.headerList}
								{@const colOffset = hasRowNumCol ? 1 : 0}
								<div class="flex-1 overflow-auto">
									<table class="text-[13px] border-collapse" style="table-layout: fixed; min-width: 100%;">
										<colgroup>
											<col style="width: 24px; min-width: 24px; max-width: 24px;" />
											{#each displayHeaders as _, colIdx}
												<col style="width: {columnWidths[colIdx] || 150}px;" />
											{/each}
										</colgroup>
										<thead class="sticky top-0 z-10" style="background: var(--color-bg-subtle, hsl(var(--muted)));">
											<tr class="h-8">
												<th class="text-center font-semibold text-muted-foreground border-b border-border whitespace-nowrap" style="width: 24px; max-width: 24px; padding: 0; font-size: 9px;">#</th>
												{#each displayHeaders as header, colIdx}
													{@const hName = header.name || header}
													{@const hType = header.dataType || header.columnType || ''}
													{@const badge = getDataTypeBadgeStyle(hType, hName)}
													{@const actualColIdx = colIdx + colOffset}
													{@const isSorted = sortColIdx === actualColIdx}
													<th
														class="text-left font-semibold text-muted-foreground border-b border-border whitespace-nowrap relative group cursor-pointer hover:bg-accent/30 transition-colors"
														style="padding: 0 8px; font-size: 11px; font-family: 'JetBrains Mono', ui-monospace, monospace; width: {columnWidths[colIdx] || 150}px;"
														onclick={() => handleColumnSort(actualColIdx)}
													>
														<div class="flex items-center gap-1.5 overflow-hidden">
															<span class="truncate">{hName}</span>
															{#if hType && hType !== 'INQUERY_ROW_NUMBER'}
																<span class="text-[8px] font-semibold lowercase rounded shrink-0" style="padding: 0 4px 0 6px; background: {badge.bg}; color: {badge.color}; border: 1px solid {badge.border}; line-height: 1.5; font-family: 'JetBrains Mono', ui-monospace, monospace;">{badge.label}</span>
															{/if}
															{#if isSorted}
																<span class="text-[9px] text-primary shrink-0">{sortDirection === 'asc' ? '▲' : '▼'}</span>
															{:else}
																<span class="text-[9px] text-muted-foreground/0 group-hover:text-muted-foreground/40 shrink-0 transition-colors">▲</span>
															{/if}
														</div>
														<!-- svelte-ignore a11y_no_static_element_interactions -->
														<div
															class="absolute right-0 top-0 bottom-0 w-[5px] cursor-col-resize z-20 hover:bg-primary/30 {resizingColIdx === colIdx ? 'bg-primary/40' : ''}"
															onmousedown={(e) => { e.stopPropagation(); handleColumnResizeStart(e, colIdx); }}
														></div>
													</th>
												{/each}
											</tr>
										</thead>
										<tbody>
											{#each pagedResultData as row, i}
												<tr class="hover:bg-accent/20 transition-colors" style="height: 28px;">
													<td class="text-center text-muted-foreground/50 whitespace-nowrap border-r border-border/30 tabular-nums select-none" style="width: 24px; max-width: 24px; padding: 0; font-size: 9px;">{dataTablePage * dataPageSize + i + 1}</td>
													{#each row as cell, cellIdx}
														{#if cellIdx >= colOffset}
															{#if cell === null || cell === undefined}
																<td
																	class="whitespace-nowrap overflow-hidden truncate select-text"
																	style="padding: 0 4px; line-height: 27px; color: var(--color-text-tertiary, hsl(var(--muted-foreground) / 0.5));"
																>&lt;null&gt;</td>
															{:else}
																<td
																	class="whitespace-nowrap overflow-hidden truncate text-foreground select-text"
																	style="padding: 0 4px; line-height: 27px;"
																	title={String(cell)}
																>{formatCellDisplay(cell)}</td>
															{/if}
														{/if}
													{/each}
												</tr>
											{/each}
										</tbody>
									</table>
								</div>
								<!-- Status Bar (same as workspace) -->
								<div class="flex items-center shrink-0 border-t border-border/50 text-xs text-muted-foreground select-none" style="height: 26px; padding: 0 8px; background: var(--color-bg-subtle, hsl(var(--muted)));">
									<span class="text-[10px] text-muted-foreground whitespace-nowrap mr-4">
										Rows {dataTablePage * dataPageSize + 1}-{Math.min((dataTablePage + 1) * dataPageSize, sortedDataList.length)} of {sortedDataList.length}
									</span>
									{#if executionTime !== null}
										<span class="text-[10px] text-muted-foreground mr-4">{executionTime}ms</span>
									{/if}
									{#if sortColIdx !== null}
										<button
											class="px-1.5 py-0.5 rounded text-[10px] bg-primary/10 text-primary hover:bg-primary/20 transition-colors mr-2 flex items-center gap-1"
											onclick={() => { sortColIdx = null; sortDirection = 'asc'; }}
										>
											<X size={10} />
											Clear sort
										</button>
									{/if}
									<div class="ml-auto flex items-center gap-1.5">
										<button
											class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
											disabled={dataTablePage <= 0}
											onclick={() => dataTablePage = Math.max(0, dataTablePage - 1)}
										>&lsaquo;</button>
										<span class="text-[10px] text-muted-foreground min-w-[16px] text-center">{dataTablePage + 1}</span>
										<button
											class="px-1.5 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
											disabled={dataTablePage >= dataTablePageCount - 1}
											onclick={() => dataTablePage = Math.min(dataTablePageCount - 1, dataTablePage + 1)}
										>&rsaquo;</button>
										<select
											class="h-5 text-[10px] bg-muted hover:bg-accent rounded text-muted-foreground hover:text-foreground px-1 cursor-pointer transition-colors border-none outline-none"
											value={dataPageSize}
											onchange={(e) => { dataPageSize = Number((e.target as HTMLSelectElement).value); dataTablePage = 0; }}
										>
											{#each PAGE_SIZES as size}
												<option value={size}>{size}</option>
											{/each}
										</select>
										<div class="w-px h-3 bg-border mx-0.5"></div>
										<!-- Export dropdown -->
										<div class="relative">
											<button
												class="px-2 py-0.5 rounded text-[10px] bg-muted hover:bg-accent text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
												onclick={() => showExportDropdown = !showExportDropdown}
											>
												<Download size={11} />
												Export
												<ChevronDown size={10} />
											</button>
											{#if showExportDropdown}
												<!-- svelte-ignore a11y_click_events_have_key_events -->
												<!-- svelte-ignore a11y_no_static_element_interactions -->
												<div class="fixed inset-0 z-40" onclick={() => showExportDropdown = false}></div>
												<div class="absolute right-0 bottom-full mb-1 z-50 bg-popover border border-border rounded-md shadow-lg py-1 min-w-[160px]">
													<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={exportAsCSV}>Export as CSV</button>
													<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={exportAsJSON}>Export as JSON</button>
													<button class="w-full text-left px-3 py-1.5 text-xs hover:bg-accent transition-colors" onclick={exportAsInsertSQL}>Export as INSERT SQL</button>
												</div>
											{/if}
										</div>
									</div>
								</div>
							{:else}
								<div class="flex items-center justify-center h-full text-xs text-muted-foreground">
									Write a query and click Run to preview data
								</div>
							{/if}
						</div>
					</div>
				</div>
			{:else}
				<!-- Chart Tab -->
				<div class="flex h-full">
					<!-- Chart Type Sidebar -->
					<div class="w-[140px] border-r border-border p-3 overflow-auto shrink-0">
						<p class="text-[10px] font-medium text-muted-foreground uppercase mb-2">Chart Type</p>
						<div class="space-y-1">
							{#each chartTypes as ct}
								{@const Icon = ct.icon}
								<button
									class="flex items-center gap-2 w-full px-2 py-1.5 rounded text-xs transition-colors text-left
										{chartType === ct.type ? 'bg-primary/10 text-primary border border-primary/20' : 'hover:bg-accent text-muted-foreground hover:text-foreground'}"
									onclick={() => selectChartType(ct.type)}
								>
									<Icon size={14} />
									<span>{ct.label}</span>
								</button>
							{/each}
						</div>
					</div>

					<!-- Chart Preview -->
					<div class="flex-1 p-4 overflow-auto min-w-0">
						{#if !resultData}
							<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
								<div class="text-center">
									<p>No data available</p>
									<p class="text-xs mt-1">Switch to the Data tab and run a query first</p>
								</div>
							</div>
						{:else if chartType === 'TABLE'}
							<div class="overflow-auto max-h-full">
								<table class="w-full text-xs">
									<thead class="bg-muted/50 sticky top-0">
										<tr>
											{#each resultData.headerList as h}
												<th class="px-3 py-1.5 text-left font-medium text-muted-foreground border-b border-border">{h.name || h}</th>
											{/each}
										</tr>
									</thead>
									<tbody>
										{#each resultData.dataList.slice(0, 100) as row}
											<tr class="border-b border-border/30 hover:bg-accent/20">
												{#each row as cell}
													<td class="px-3 py-1 whitespace-nowrap">{cell ?? 'NULL'}</td>
												{/each}
											</tr>
										{/each}
									</tbody>
								</table>
							</div>
						{:else if chartType === 'CARD'}
							{@const metricsToShow = chartConfig.metrics?.length
								? chartConfig.metrics
								: resultData.headerList
									.filter((h: any) => (h.name || h).toUpperCase() !== 'ROW NUMBER')
									.map((h: any) => h.name || h)}
							<div class="grid grid-cols-3 gap-4">
								{#each metricsToShow as metricName}
									{@const colIdx = resultData.headerList.findIndex((h: any) => (h.name || h) === metricName)}
									{@const val = colIdx >= 0 ? resultData.dataList[0]?.[colIdx] : null}
									{@const sizeClass = chartConfig.metricValueSize === 'small' ? 'text-xl' :
										chartConfig.metricValueSize === 'large' ? 'text-5xl' :
										chartConfig.metricValueSize === 'extraLarge' ? 'text-7xl' : 'text-3xl'}
									<div class="p-4 rounded-lg border border-border bg-card">
										<p class="text-xs text-muted-foreground mb-1">{metricName}</p>
										<p class="{sizeClass} font-bold text-foreground">
											{formatValue(val, chartConfig.yAxisFormat)}
										</p>
										{#if chartConfig.subheader}
											<p class="text-xs text-muted-foreground mt-1">{chartConfig.subheader}</p>
										{/if}
									</div>
								{/each}
							</div>
						{:else if chartOption}
							{#await import('$lib/components/ECharts/ECharts.svelte') then { default: ECharts }}
								<ECharts option={chartOption} height="100%" theme="auto" />
							{/await}
						{:else}
							<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
								Cannot generate chart for this data
							</div>
						{/if}
					</div>

					<!-- Config Sidebar -->
					<div class="w-[260px] shrink-0 overflow-y-auto border-l border-border p-3 space-y-4">
						<!-- Axis Configuration (BAR, LINE, SCATTER) -->
						{#if chartType === 'BAR' || chartType === 'LINE' || chartType === 'SCATTER'}
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">X Axis</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('xAxis')}>
									<span class="truncate">{chartConfig.xAxis || 'Auto detect'}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'xAxis'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
										<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {!chartConfig.xAxis ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxis: undefined }); openDropdown = null; }}>Auto detect</button>
										{#each columnNames as col}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.xAxis === col ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxis: col }); openDropdown = null; }}>{col}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>

							{#if chartType === 'LINE' || chartType === 'BAR'}
								<div class="space-y-2">
									<p class="text-[10px] font-semibold text-muted-foreground uppercase">Y Values (multiple)</p>
									<div class="relative custom-dropdown">
										<button type="button" class="flex items-center justify-between w-full min-h-7 text-xs border border-input bg-background rounded-md px-2 py-1 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('yAxes')}>
											<span class="flex flex-wrap gap-1 truncate">
												{#if selectedYValues.length === 0}
													<span class="text-muted-foreground">Auto detect</span>
												{:else}
													{#each selectedYValues as col}
														<span class="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-primary/10 text-primary text-[10px] font-medium">{col}</span>
													{/each}
												{/if}
											</span>
											<ChevronDown size={12} class="text-muted-foreground shrink-0 ml-1" />
										</button>
										{#if openDropdown === 'yAxes'}
											<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
												{#each columnNames as col}
													<button type="button" class="flex items-center gap-2 w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => {
														const current = chartConfig.yAxes || [];
														const next = current.includes(col) ? current.filter(c => c !== col) : [...current, col];
														setConfig({ yAxes: next.length > 0 ? next : undefined });
													}}>
														<span class="w-3.5 h-3.5 rounded border border-input flex items-center justify-center text-[10px] {selectedYValues.includes(col) ? 'bg-primary text-primary-foreground border-primary' : ''}">
															{#if selectedYValues.includes(col)}✓{/if}
														</span>
														<span class="truncate">{col}</span>
													</button>
												{/each}
											</div>
										{/if}
									</div>
								</div>
							{:else}
							<div class="space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">Y Value</p>
								<div class="relative custom-dropdown">
									<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('yAxis')}>
										<span class="truncate">{chartConfig.yAxes?.[0] || 'Auto detect'}</span>
										<ChevronDown size={12} class="text-muted-foreground shrink-0" />
									</button>
									{#if openDropdown === 'yAxis'}
										<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {!chartConfig.yAxes?.[0] ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ yAxes: undefined }); openDropdown = null; }}>Auto detect</button>
											{#each columnNames as col}
												<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.yAxes?.[0] === col ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ yAxes: [col] }); openDropdown = null; }}>{col}</button>
											{/each}
										</div>
									{/if}
								</div>
							</div>
							{/if}
						{/if}

						<!-- Series / Dimensions (BAR, LINE) — multi-select dropdown -->
					{#if chartType === 'BAR' || chartType === 'LINE'}
					<div class="space-y-2">
						<p class="text-[10px] font-semibold text-muted-foreground uppercase">Series (optional)</p>
						<div class="relative custom-dropdown">
							<button type="button" class="flex items-center justify-between w-full min-h-7 text-xs border border-input bg-background rounded-md px-2 py-1 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('dimension')}>
								<span class="flex flex-wrap gap-1 truncate">
									{#if selectedDimValues.length === 0}
										<span class="text-muted-foreground">None</span>
									{:else}
										{#each selectedDimValues as col}
											<span class="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-primary/10 text-primary text-[10px] font-medium">{col}</span>
										{/each}
									{/if}
								</span>
								<ChevronDown size={12} class="text-muted-foreground shrink-0 ml-1" />
							</button>
							{#if openDropdown === 'dimension'}
								<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
									<button type="button" class="flex items-center gap-2 w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {selectedDimValues.length === 0 ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ dimensions: undefined, dimension: undefined }); openDropdown = null; }}>
										<span class="w-3.5 h-3.5 rounded border border-input flex items-center justify-center text-[10px] {selectedDimValues.length === 0 ? 'bg-primary text-primary-foreground border-primary' : ''}">
											{#if selectedDimValues.length === 0}✓{/if}
										</span>
										<span>None</span>
									</button>
									{#each columnNames as col}
										<button type="button" class="flex items-center gap-2 w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors" onclick={() => {
											const current = chartConfig.dimensions || (chartConfig.dimension ? [chartConfig.dimension] : []);
											const next = current.includes(col) ? current.filter(c => c !== col) : [...current, col];
											setConfig({ dimensions: next.length > 0 ? next : undefined, dimension: next.length > 0 ? next[0] : undefined });
										}}>
											<span class="w-3.5 h-3.5 rounded border border-input flex items-center justify-center text-[10px] {selectedDimValues.includes(col) ? 'bg-primary text-primary-foreground border-primary' : ''}">
												{#if selectedDimValues.includes(col)}✓{/if}
											</span>
											<span class="truncate">{col}</span>
										</button>
									{/each}
								</div>
							{/if}
						</div>
					</div>
					{/if}

					<!-- Ordering (BAR, LINE, SCATTER) -->
					{#if chartType === 'BAR' || chartType === 'LINE' || chartType === 'SCATTER'}
					<div class="space-y-2">
						<p class="text-[10px] font-semibold text-muted-foreground uppercase">Sort Order</p>
						<div class="relative custom-dropdown">
							<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('order')}>
								<span class="truncate">{{ '': 'Default', x_asc: 'X Ascending', x_desc: 'X Descending', y_asc: 'Y Ascending', y_desc: 'Y Descending' }[chartConfig.order || ''] || 'Default'}</span>
								<ChevronDown size={12} class="text-muted-foreground shrink-0" />
							</button>
							{#if openDropdown === 'order'}
								<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
									{#each [{ v: '', l: 'Default' }, { v: 'x_asc', l: 'X Ascending' }, { v: 'x_desc', l: 'X Descending' }, { v: 'y_asc', l: 'Y Ascending' }, { v: 'y_desc', l: 'Y Descending' }] as opt}
										<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.order || '') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ order: (opt.v || undefined) as ChartConfig['order'] }); openDropdown = null; }}>{opt.l}</button>
									{/each}
								</div>
							{/if}
						</div>
					</div>
					{/if}

				{#if chartType === 'PIE'}
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Category</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('pieCategory')}>
									<span class="truncate">{chartConfig.xAxis || 'Auto detect'}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'pieCategory'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
										<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {!chartConfig.xAxis ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxis: undefined }); openDropdown = null; }}>Auto detect</button>
										{#each columnNames as col}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.xAxis === col ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxis: col }); openDropdown = null; }}>{col}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Value</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('pieValue')}>
									<span class="truncate">{chartConfig.yAxes?.[0] || 'Auto detect'}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'pieValue'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
										<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {!chartConfig.yAxes?.[0] ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ yAxes: undefined }); openDropdown = null; }}>Auto detect</button>
										{#each columnNames as col}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.yAxes?.[0] === col ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ yAxes: [col] }); openDropdown = null; }}>{col}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Variant</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('pieVariant')}>
									<span class="truncate">{{ pie: 'Pie', ring: 'Ring (Donut)', rose: 'Rose' }[chartConfig.pieVariant || 'pie']}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'pieVariant'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
										{#each [{ v: 'pie', l: 'Pie' }, { v: 'ring', l: 'Ring (Donut)' }, { v: 'rose', l: 'Rose' }] as opt}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.pieVariant || 'pie') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ pieVariant: opt.v as ChartConfig['pieVariant'] }); openDropdown = null; }}>{opt.l}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
					{/if}

						<!-- BAR specific options -->
					{#if chartType === 'BAR'}
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Orientation</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('barOrientation')}>
									<span class="truncate">{(chartConfig.barOrientation || 'vertical') === 'vertical' ? 'Vertical' : 'Horizontal'}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'barOrientation'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
										{#each [{ v: 'vertical', l: 'Vertical' }, { v: 'horizontal', l: 'Horizontal' }] as opt}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.barOrientation || 'vertical') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ barOrientation: opt.v as ChartConfig['barOrientation'] }); openDropdown = null; }}>{opt.l}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
							<label class="flex items-center gap-2 text-xs cursor-pointer">
								<input
									type="checkbox"
									checked={chartConfig.stack || false}
									onchange={(e) => setConfig({ stack: (e.target as HTMLInputElement).checked })}
									class="rounded"
								/>
								<span>Stack bars</span>
							</label>
						{/if}

						<!-- LINE specific options -->
					{#if chartType === 'LINE'}
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Line Style</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('lineVariant')}>
									<span class="truncate">{{ line: 'Line', area: 'Area', smooth: 'Smooth', step: 'Step' }[chartConfig.lineVariant || 'line']}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'lineVariant'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
										{#each [{ v: 'line', l: 'Line' }, { v: 'area', l: 'Area' }, { v: 'smooth', l: 'Smooth' }, { v: 'step', l: 'Step' }] as opt}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.lineVariant || 'line') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ lineVariant: opt.v as ChartConfig['lineVariant'] }); openDropdown = null; }}>{opt.l}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
					{/if}

						<!-- CARD specific options -->
						{#if chartType === 'CARD'}
							<div class="space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">Metrics</p>
								<div class="space-y-1 max-h-[120px] overflow-y-auto">
									{#each columnNames as col}
										<label class="flex items-center gap-2 text-xs cursor-pointer hover:bg-accent/30 rounded px-1 py-0.5">
											<input
												type="checkbox"
												checked={(chartConfig.metrics || []).includes(col)}
												onchange={(e) => {
													const checked = (e.target as HTMLInputElement).checked;
													const current = chartConfig.metrics || [];
													if (checked) {
														setConfig({ metrics: [...current, col] });
													} else {
														setConfig({ metrics: current.filter(c => c !== col) });
													}
												}}
												class="rounded"
											/>
											<span>{col}</span>
										</label>
									{/each}
								</div>
							</div>
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Value Size</p>
							<div class="relative custom-dropdown">
								<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('metricValueSize')}>
									<span class="truncate">{{ small: 'Small', medium: 'Medium', large: 'Large', extraLarge: 'Extra Large' }[chartConfig.metricValueSize || 'medium']}</span>
									<ChevronDown size={12} class="text-muted-foreground shrink-0" />
								</button>
								{#if openDropdown === 'metricValueSize'}
									<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
										{#each [{ v: 'small', l: 'Small' }, { v: 'medium', l: 'Medium' }, { v: 'large', l: 'Large' }, { v: 'extraLarge', l: 'Extra Large' }] as opt}
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.metricValueSize || 'medium') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ metricValueSize: opt.v }); openDropdown = null; }}>{opt.l}</button>
										{/each}
									</div>
								{/if}
							</div>
						</div>
						<div class="space-y-2">
							<p class="text-[10px] font-semibold text-muted-foreground uppercase">Subheader</p>
							<input
								value={chartConfig.subheader || ''}
								oninput={(e) => setConfig({ subheader: (e.target as HTMLInputElement).value })}
								placeholder="Optional subheader text"
								class="w-full h-7 text-xs border border-input bg-background rounded-md px-2 focus:outline-none focus:ring-1 focus:ring-ring"
							/>
						</div>
					<div class="space-y-2">
						<p class="text-[10px] font-semibold text-muted-foreground uppercase">Subheader Size</p>
						<div class="relative custom-dropdown">
							<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('subheaderSize')}>
								<span class="truncate">{{ small: 'Small', medium: 'Medium', large: 'Large' }[chartConfig.subheaderSize || 'medium']}</span>
								<ChevronDown size={12} class="text-muted-foreground shrink-0" />
							</button>
							{#if openDropdown === 'subheaderSize'}
								<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
									{#each [{ v: 'small', l: 'Small' }, { v: 'medium', l: 'Medium' }, { v: 'large', l: 'Large' }] as opt}
										<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.subheaderSize || 'medium') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ subheaderSize: opt.v }); openDropdown = null; }}>{opt.l}</button>
									{/each}
								</div>
							{/if}
						</div>
					</div>
					{/if}

						<!-- Chart Display Options (for chart types with ECharts) -->
						{#if chartType !== 'TABLE' && chartType !== 'CARD'}
							<div class="border-t border-border pt-3 space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">Display Options</p>
								<div class="grid grid-cols-2 gap-1">
									<label class="flex items-center gap-1.5 text-[11px] cursor-pointer">
										<input type="checkbox"
											checked={chartConfig.showLegend !== false}
											onchange={(e) => setConfig({ showLegend: (e.target as HTMLInputElement).checked })}
											class="rounded"
										/>
										<span>Legend</span>
									</label>
									<label class="flex items-center gap-1.5 text-[11px] cursor-pointer">
										<input type="checkbox"
											checked={chartConfig.showValue !== false}
											onchange={(e) => setConfig({ showValue: (e.target as HTMLInputElement).checked })}
											class="rounded"
										/>
										<span>Data label</span>
									</label>
									<label class="flex items-center gap-1.5 text-[11px] cursor-pointer">
										<input type="checkbox"
											checked={chartConfig.showAxis !== false}
											onchange={(e) => setConfig({ showAxis: (e.target as HTMLInputElement).checked })}
											class="rounded"
										/>
										<span>Axis</span>
									</label>
									<label class="flex items-center gap-1.5 text-[11px] cursor-pointer">
										<input type="checkbox"
											checked={chartConfig.showGridLine !== false}
											onchange={(e) => setConfig({ showGridLine: (e.target as HTMLInputElement).checked })}
											class="rounded"
										/>
										<span>Grid line</span>
									</label>
								</div>
							</div>
						{/if}

						<!-- Theme Color -->
						{#if chartType !== 'TABLE'}
							<div class="border-t border-border pt-3 space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">Theme Color</p>
									<div class="relative">
									<button
										type="button"
										class="w-full h-8 text-xs border border-input bg-background rounded-md px-2 flex items-center justify-between gap-2 focus:outline-none focus:ring-1 focus:ring-ring"
										onclick={() => toggleDropdown('themeColor')}
									>
										<div class="flex items-center gap-2">
											<div class="flex h-4 overflow-hidden rounded border border-border/50">
												{#each (selectedTheme?.colors || []).slice(0, 4) as color}
													<div class="w-3 h-full" style="background: {color}"></div>
												{/each}
											</div>
											<span>{selectedTheme?.label || 'Blue'}</span>
										</div>
										<ChevronDown size={12} class="text-muted-foreground shrink-0" />
									</button>
									{#if openDropdown === 'themeColor'}
										<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-lg py-1 max-h-[200px] overflow-y-auto">
											{#each THEME_COLORS as theme}
												<button
													type="button"
													class="flex items-center gap-2 w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors
														{(chartConfig.themeColor || 'default') === theme.value ? 'bg-accent/50 font-medium' : ''}"
													onclick={() => { setConfig({ themeColor: theme.value }); openDropdown = null; }}
												>
													<div class="flex h-4 overflow-hidden rounded border border-border/30">
														{#each theme.colors.slice(0, 4) as color}
															<div class="w-3 h-full" style="background: {color}"></div>
														{/each}
													</div>
													<span>{theme.label}</span>
												</button>
											{/each}
										</div>
									{/if}
								</div>
							</div>
						{/if}

						<!-- X Axis Format -->
						{#if chartType === 'BAR' || chartType === 'LINE' || chartType === 'SCATTER'}
							<div class="border-t border-border pt-3 space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">X Axis Format</p>
								<div class="relative custom-dropdown">
									<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('xAxisFormat')}>
										<span class="truncate">{{ original: 'Original', date_iso: 'YYYY-MM-DD', date_us: 'MM/DD/YYYY', date_eu: 'DD/MM/YYYY', date_short: 'Jan 01', date_month_year: 'Jan 2024', date_year: 'YYYY', date_month_day: 'MM/DD', date_quarter: 'Q1 2024', date_time: 'MM/DD HH:mm', number_comma: '1,234 (Comma)', number_compact: '1.2K / 1.2M' }[chartConfig.xAxisFormat || 'original']}</span>
										<ChevronDown size={12} class="text-muted-foreground shrink-0" />
									</button>
									{#if openDropdown === 'xAxisFormat'}
										<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[240px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
											<div class="px-2.5 py-1 text-[9px] font-semibold text-muted-foreground/60 uppercase">General</div>
											<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.xAxisFormat || 'original') === 'original' ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxisFormat: undefined }); openDropdown = null; }}>Original</button>
											<div class="px-2.5 py-1 text-[9px] font-semibold text-muted-foreground/60 uppercase mt-1">Date</div>
											{#each [
												{ v: 'date_iso', l: 'YYYY-MM-DD' },
												{ v: 'date_us', l: 'MM/DD/YYYY' },
												{ v: 'date_eu', l: 'DD/MM/YYYY' },
												{ v: 'date_short', l: 'Jan 01' },
												{ v: 'date_month_year', l: 'Jan 2024' },
												{ v: 'date_year', l: 'YYYY' },
												{ v: 'date_month_day', l: 'MM/DD' },
												{ v: 'date_quarter', l: 'Q1 2024' },
												{ v: 'date_time', l: 'MM/DD HH:mm' },
											] as opt}
												<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.xAxisFormat === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxisFormat: opt.v as XAxisFormat }); openDropdown = null; }}>{opt.l}</button>
											{/each}
											<div class="px-2.5 py-1 text-[9px] font-semibold text-muted-foreground/60 uppercase mt-1">Number</div>
											{#each [
												{ v: 'number_comma', l: '1,234 (Comma)' },
												{ v: 'number_compact', l: '1.2K / 1.2M' },
											] as opt}
												<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {chartConfig.xAxisFormat === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ xAxisFormat: opt.v as XAxisFormat }); openDropdown = null; }}>{opt.l}</button>
											{/each}
										</div>
									{/if}
								</div>
							</div>
						{/if}

						<!-- Y Axis Format -->
						{#if chartType !== 'TABLE'}
							<div class="border-t border-border pt-3 space-y-2">
								<p class="text-[10px] font-semibold text-muted-foreground uppercase">Y Axis Format</p>
								<div class="relative custom-dropdown">
									<button type="button" class="flex items-center justify-between w-full h-7 text-xs border border-input bg-background rounded-md px-2 hover:bg-accent/50 transition-colors" onclick={() => toggleDropdown('yAxisFormat')}>
										<span class="truncate">{{ original: 'Original', comma: '1,234 (Comma)', decimal1: '1,234.5', decimal2: '1,234.56', percent0: '46% (Integer)', percent: '45.54%', percent1: '×100 → 45.5%', percent2: '×100 → 45.54%', k: '1.2K / 1.2M', currency: '$1,234.00', compact: '1.2K / 1.2M' }[chartConfig.yAxisFormat || 'comma']}</span>
										<ChevronDown size={12} class="text-muted-foreground shrink-0" />
									</button>
									{#if openDropdown === 'yAxisFormat'}
										<div class="absolute top-full left-0 mt-1 z-50 w-full max-h-[200px] overflow-y-auto rounded-md border border-border bg-popover shadow-md py-1">
											{#each [
												{ v: 'original', l: 'Original' },
												{ v: 'comma', l: '1,234 (Comma)' },
												{ v: 'decimal1', l: '1,234.5 (1 Decimal)' },
												{ v: 'decimal2', l: '1,234.56 (2 Decimal)' },
												{ v: 'percent0', l: '46% (Integer %)' },
												{ v: 'percent', l: '45.54% (Keep Decimals)' },
												{ v: 'percent1', l: '×100 → 45.5% (1 Decimal)' },
												{ v: 'percent2', l: '×100 → 45.54% (2 Decimal)' },
												{ v: 'k', l: '1.2K / 1.2M (Compact)' },
												{ v: 'currency', l: '$1,234.00' },
											] as opt}
												<button type="button" class="flex items-center w-full px-2.5 py-1.5 text-xs hover:bg-accent transition-colors {(chartConfig.yAxisFormat || 'comma') === opt.v ? 'bg-accent/50 font-medium' : ''}" onclick={() => { setConfig({ yAxisFormat: opt.v as MetricFormat }); openDropdown = null; }}>{opt.l}</button>
											{/each}
										</div>
									{/if}
								</div>
							</div>
						{/if}
					</div>
				</div>
			{/if}
		</div>
	</div>
</div>
