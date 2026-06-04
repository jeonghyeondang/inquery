<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { Card, Button, Separator, DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '$lib/components/ui';
	import {
		LayoutDashboard, Plus, Pencil, Trash2, MoreVertical,
		Download, Camera, Sparkles, X, Type, Minus as DividerIcon,
		AlignLeft, PanelLeftClose, PanelLeftOpen, LayoutGrid,
		RefreshCw, Maximize, Minimize, Timer, Search, Loader2,
		ChevronDown, GripVertical, FileText, Settings, ImageDown,
		PanelTop, MessageSquare, Copy, Check, RotateCcw, Languages,
		Share2, Link, ExternalLink, Globe
	} from 'lucide-svelte';
	import { AISparkleIcon } from '$lib/components/AISparkleIcon';
	import i18n from '$lib/i18n';
	import dashboardService, { type IDashboard, type IChart } from '$lib/service/dashboard';
	import configService from '$lib/service/config';
	import sqlService from '$lib/service/sql';
	import { DashboardGrid } from '$lib/components/DashboardGrid';
	import type { IGridItem } from '$lib/stores/dashboard.svelte';
	import { downloadChartAsPNG } from '$lib/utils/export';
	import { generateChartOption, generateChartOptionWithConfig } from '$lib/utils/chartUtils';
	import { DashboardChartRenderer } from '$lib/components/DashboardChartRenderer';
	import type { ChartSchema } from '$lib/components/DashboardChartRenderer/types';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import { notify } from '$lib/stores/notification.svelte';

	// ─── Grid Constants ───
	const GRID_BASE_UNIT = 8;
	const DEFAULT_CHART_WIDTH = 6;
	const DEFAULT_CHART_HEIGHT = 300;

	// ─── Layout Item Types ───
	interface TabItem {
		id: string;
		title: string;
		children: string[]; // IDs of chart LayoutItems inside this tab
	}

	interface TabsConfig {
		activeTabId: string;
		tabs: TabItem[];
	}

	// Extended IGridItem with full layout support (header, divider, text, chart, tabs)
	interface LayoutItem extends IGridItem {
		chart?: IChart;
		chartSchema?: ChartSchema;
		headerConfig?: { text: string; size: 'small' | 'medium' | 'large'; backgroundColor: string };
		textConfig?: { content: string };
		tabsConfig?: TabsConfig;
	}

	// ─── State ───
	let dashboards = $state<IDashboard[]>([]);
	let activeDashboardId = $state<number | null>(null);
	let activeDashboard = $state<IDashboard | null>(null);
	let layoutItems = $state<LayoutItem[]>([]);
	let loading = $state(true);
	let editMode = $state(false);
	let showLayoutElements = $state(false);

	// Sidebar
	let sidebarCollapsed = $state(false);
	let sidebarTab = $state<'dashboards' | 'charts'>('dashboards');
	let savedCharts = $state<IChart[]>([]);
	let chartSearch = $state('');
	let chartSortBy = $state<'recent' | 'name' | 'type' | 'source'>('recent');
	let sidebarDragging = $state(false);

	// Sidebar resize
	const SIDEBAR_WIDTH_KEY = 'dashboard-sidebar-width';
	const MIN_SIDEBAR_WIDTH = 180;
	const MAX_SIDEBAR_WIDTH = 400;
	let sidebarWidth = $state(220);
	let isSidebarResizing = $state(false);

	// AI Chat side panel
	const AI_CHAT_PANEL_WIDTH_KEY = 'dashboard-ai-chat-panel-width';
	const MIN_AI_CHAT_PANEL_WIDTH = 280;
	const MAX_AI_CHAT_PANEL_WIDTH = 700;
	const DEFAULT_AI_CHAT_PANEL_WIDTH = 400;
	let showAIChat = $state(false);
	let aiChatPanelWidth = $state(DEFAULT_AI_CHAT_PANEL_WIDTH);
	let isAiChatPanelResizing = $state(false);

	// Tabs editing
	let editingTabTitleId = $state<string | null>(null);
	let editingTabTitle = $state('');

	// Create/Edit Dashboard Modal
	let showCreateModal = $state(false);
	let editingDashboard = $state<IDashboard | null>(null);
	let newDashboardName = $state('');
	let newDashboardDesc = $state('');

	// Chart Modal
	let showChartModal = $state(false);
	let editingChart = $state<IChart | null>(null);

	// Chart title editing
	let editingChartId = $state<string | null>(null);
	let editingChartTitle = $state('');

	// Full-screen mode
	let isFullScreen = $state(false);

	// Auto-refresh
	let refreshRule = $state<'NONE' | '1MIN' | '10MIN' | '1HOUR' | '1DAY'>('NONE');
	let refreshInterval = $state<ReturnType<typeof setInterval> | null>(null);
	let refreshing = $state(false);
	let refreshingItemIds = $state<Set<string>>(new Set());
	let showRefreshDropdown = $state(false);

	// AI Summary
	let showSummaryModal = $state(false);
	let summaryContent = $state('');
	let summaryLoading = $state(false);
	let summaryModel = $state<'GEMINI' | 'OPENAI' | 'CLAUDEAI'>('GEMINI');
	let summaryLanguage = $state('en');
	let summaryCopied = $state(false);
	let summaryError = $state('');
	let showModelDropdown = $state(false);
	let showLanguageDropdown = $state(false);
	let aiConfigLoaded = $state(false);
	let aiConfigProvider = $state<string | null>(null);

	// Fast models for each provider (optimized for speed)
	// Kept in sync with the server-side ModelMapper.FAST_MODELS roster.
	const FAST_MODELS: Record<string, string> = {
		OPENAI: 'gpt-5.4-mini',
		GEMINI: 'gemini-3.1-flash-lite',
		CLAUDEAI: 'claude-haiku-4-5',
	};
	const LANGUAGE_OPTIONS = [
		{ value: 'en', label: 'English', flag: '🇺🇸' },
		{ value: 'ko', label: 'Korean', flag: '🇰🇷' },
		{ value: 'ja', label: 'Japanese', flag: '🇯🇵' },
		{ value: 'zh', label: 'Chinese', flag: '🇨🇳' },
	];

	// Chart hover preview
	let hoveredChartId = $state<number | null>(null);
	let previewPosition = $state<{ x: number; y: number }>({ x: 0, y: 0 });

	// Dashboard Modal: refresh rule editing
	let newDashboardRefreshRule = $state<'NONE' | '1MIN' | '10MIN' | '1HOUR' | '1DAY'>('NONE');

	const REFRESH_OPTIONS = [
		{ value: 'NONE', label: 'None' },
		{ value: '1MIN', label: 'Every 1 minute' },
		{ value: '10MIN', label: 'Every 10 minutes' },
		{ value: '1HOUR', label: 'Every 1 hour' },
		{ value: '1DAY', label: 'Every 1 day' },
	] as const;

	// Confirm modal state
	let confirmModal = $state<{ show: boolean; title: string; message: string; onConfirm: () => void }>({
		show: false, title: '', message: '', onConfirm: () => {}
	});
	// Dashboard export loading
	let exportingDashboard = $state(false);

	// Share modal
	let showShareModal = $state(false);
	let shareLoading = $state(false);
	let shareCopied = $state(false);

	// Auto-refresh popover anchor
	let moreMenuBtnEl = $state<HTMLElement | null>(null);

	// Header editing
	let editingHeaderId = $state<string | null>(null);
	let editingHeaderText = $state('');

	// Text editing
	let editingTextId = $state<string | null>(null);
	let editingTextContent = $state('');
	let textEditorTab = $state<'edit' | 'preview'>('edit');

	const refreshIntervals: Record<string, number> = {
		'1MIN': 60000, '10MIN': 600000, '1HOUR': 3600000, '1DAY': 86400000
	};

	const layoutElements = [
		{ type: 'header', label: 'Header', icon: Type },
		{ type: 'divider', label: 'Divider', icon: DividerIcon },
		{ type: 'text', label: 'Text', icon: AlignLeft },
		{ type: 'tabs', label: 'Tabs', icon: PanelTop },
	];

	const HEADER_BG_COLORS = [
		{ value: 'transparent', label: 'Transparent' },
		{ value: 'white', label: 'White', light: '#ffffff', dark: 'rgba(255,255,255,0.08)' },
		{ value: 'gray', label: 'Gray', light: 'rgba(0,0,0,0.03)', dark: 'rgba(255,255,255,0.05)' },
		{ value: 'blue', label: 'Blue', light: '#e6f4ff', dark: '#1a3a5c' },
		{ value: 'purple', label: 'Purple', light: '#f9f0ff', dark: '#3d2a50' },
		{ value: 'green', label: 'Green', light: '#f6ffed', dark: '#1e3a1e' },
		{ value: 'orange', label: 'Orange', light: '#fff7e6', dark: '#4a3520' },
	];

	const DEFAULT_TEXT_CONTENT = `# Header 1\n## Header 2\n### Header 3\n\nThis is a **bold** text and this is *italic* text.\n\n- List item 1\n- List item 2\n- List item 3\n\n> This is a blockquote\n\nInline \`code\` example and a [link](https://example.com).\n\n1. Numbered item 1\n2. Numbered item 2`;

	// Relative time formatter (e.g., "2 hours ago", "3 days ago")
	function timeAgo(dateStr?: string): string {
		if (!dateStr) return '';
		const diff = Date.now() - new Date(dateStr).getTime();
		const seconds = Math.floor(diff / 1000);
		if (seconds < 60) return 'just now';
		const minutes = Math.floor(seconds / 60);
		if (minutes < 60) return `${minutes}m ago`;
		const hours = Math.floor(minutes / 60);
		if (hours < 24) return `${hours}h ago`;
		const days = Math.floor(hours / 24);
		if (days < 30) return `${days}d ago`;
		const months = Math.floor(days / 30);
		if (months < 12) return `${months}mo ago`;
		return `${Math.floor(months / 12)}y ago`;
	}

	// Filtered & sorted saved charts
	let filteredSavedCharts = $derived.by(() => {
		let result = chartSearch
			? savedCharts.filter(c => c.name.toLowerCase().includes(chartSearch.toLowerCase()))
			: [...savedCharts];

		switch (chartSortBy) {
			case 'name':
				result.sort((a, b) => a.name.localeCompare(b.name));
				break;
			case 'type':
				result.sort((a, b) => {
					const typeA = getChartTypeFromSchema(a);
					const typeB = getChartTypeFromSchema(b);
					return typeA.localeCompare(typeB);
				});
				break;
			case 'source':
				result.sort((a, b) => (a.sourceType || '').localeCompare(b.sourceType || ''));
				break;
			case 'recent':
			default:
				// Keep original order (newest first from API)
				break;
		}
		return result;
	});

	function getChartTypeFromSchema(chart: IChart): string {
		try {
			const schema = JSON.parse(chart.schema || '{}');
			return schema.chartType || 'UNKNOWN';
		} catch { return 'UNKNOWN'; }
	}

	function getChartTypeBadge(type: string): { label: string; color: string } {
		const map: Record<string, { label: string; color: string }> = {
			'BAR': { label: 'Bar', color: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300' },
			'LINE': { label: 'Line', color: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300' },
			'PIE': { label: 'Pie', color: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300' },
			'SCATTER': { label: 'Scatter', color: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300' },
			'TABLE': { label: 'Table', color: 'bg-gray-100 text-gray-700 dark:bg-gray-900/30 dark:text-gray-300' },
			'CARD': { label: 'Card', color: 'bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300' },
		};
		return map[type] || { label: type || '?', color: 'bg-muted text-muted-foreground' };
	}

	// ─── Dark Mode Detection ───
	let isDarkMode = $state(false);
	let themeObserver: MutationObserver | null = null;

	function getHeaderBgColor(colorValue: string): string {
		if (colorValue === 'transparent' || !colorValue) return 'transparent';
		// Handle legacy hex values (e.g. '#ffffff', '#e6f4ff')
		if (colorValue.startsWith('#')) {
			// Map legacy hex to semantic key
			const hexMap: Record<string, string> = {
				'#ffffff': 'white', '#e6f4ff': 'blue', '#f9f0ff': 'purple',
				'#f6ffed': 'green', '#fff7e6': 'orange'
			};
			const mapped = hexMap[colorValue.toLowerCase()];
			if (mapped) return getHeaderBgColor(mapped);
			return colorValue; // Return raw hex as fallback
		}
		const color = HEADER_BG_COLORS.find(c => c.value === colorValue);
		if (!color || !('light' in color)) return 'transparent';
		return isDarkMode ? color.dark! : color.light!;
	}

	// ─── Schema Parsing ───
	function parseSchemaToLayoutItems(schema: any, chartList: IChart[]): LayoutItem[] {
		const layout = schema?.layout || [];
		const items: LayoutItem[] = [];
		const chartIdsInLayout = new Set<number>();

		layout.forEach((item: any) => {
			if (item.itemType === 'text') {
				items.push({
					id: item.elementId || `text-${Date.now()}-${Math.random()}`,
					type: 'text', textConfig: item.textConfig || { content: '' },
					x: item.x || 0, y: item.y || 0,
					width: item.w || 12, height: item.h ? item.h * GRID_BASE_UNIT * 4 : 120
				});
			} else if (item.itemType === 'divider') {
				items.push({
					id: item.elementId || `divider-${Date.now()}-${Math.random()}`,
					type: 'divider', x: item.x || 0, y: item.y || 0, width: 12, height: 16
				});
			} else if (item.itemType === 'element') {
				items.push({
					id: item.elementId || `header-${Date.now()}-${Math.random()}`,
					type: 'header',
					headerConfig: item.elementConfig || { text: 'Header', size: 'medium', backgroundColor: 'transparent' },
					x: item.x || 0, y: item.y || 0, width: 12,
					height: item.h ? item.h * GRID_BASE_UNIT * 4 : 48
				});
			} else if (item.itemType === 'tabs') {
				items.push({
					id: item.elementId || `tabs-${Date.now()}-${Math.random()}`,
					type: 'tabs',
					tabsConfig: item.tabsConfig || { activeTabId: 'tab-1', tabs: [{ id: 'tab-1', title: 'Tab 1', children: [] }] },
					x: item.x || 0, y: item.y || 0, width: 12,
					height: item.h ? item.h * GRID_BASE_UNIT * 4 : 300
				});
			} else if (item.chartId) {
				chartIdsInLayout.add(item.chartId);
				const chart = chartList.find(c => c.id === item.chartId);
				let chartSchema: ChartSchema | undefined;
				if (chart?.schema) {
					try {
						chartSchema = JSON.parse(chart.schema);
						if (chart.dataSourceId) chartSchema!.dataSourceId = chart.dataSourceId;
						if (chart.databaseName) chartSchema!.databaseName = chart.databaseName;
					} catch (e) { console.error('Failed to parse chart schema:', e); }
				}
				items.push({
					id: item.elementId || `chart-${item.chartId}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
					type: 'chart',
					chartId: item.chartId, chart, chartSchema,
					x: item.x || 0, y: item.y || 0,
					width: item.w || DEFAULT_CHART_WIDTH,
					height: item.h ? item.h * GRID_BASE_UNIT * 4 : DEFAULT_CHART_HEIGHT
				});
			}
		});

		// Add charts not in layout (newly pinned)
		const maxY = items.length > 0 ? Math.max(...items.map(i => i.y)) + 1 : 0;
		chartList.forEach((chart, index) => {
			if (!chartIdsInLayout.has(chart.id)) {
				let chartSchema: ChartSchema | undefined;
				if (chart.schema) {
					try {
						chartSchema = JSON.parse(chart.schema);
						if (chart.dataSourceId) chartSchema!.dataSourceId = chart.dataSourceId;
						if (chart.databaseName) chartSchema!.databaseName = chart.databaseName;
					} catch (e) { console.error('Failed to parse chart schema:', e); }
				}
				items.push({
					id: `chart-${chart.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
					type: 'chart',
					chartId: chart.id, chart, chartSchema,
					x: 0, y: maxY + index,
					width: DEFAULT_CHART_WIDTH, height: DEFAULT_CHART_HEIGHT
				});
			}
		});

		items.sort((a, b) => a.y !== b.y ? a.y - b.y : a.x - b.x);
		return items;
	}

	// ─── Schema Conversion for Saving ───
	function convertLayoutItemsToSchema(items: LayoutItem[]): any[] {
		return items.map(item => {
			if (item.type === 'text') {
				return { itemType: 'text', elementId: item.id, textConfig: item.textConfig, x: item.x, y: item.y, w: item.width, h: Math.round(item.height / (GRID_BASE_UNIT * 4)) };
			} else if (item.type === 'divider') {
				return { itemType: 'divider', elementId: item.id, x: item.x, y: item.y, w: 12, h: 1 };
			} else if (item.type === 'header') {
				return { itemType: 'element', elementId: item.id, elementConfig: item.headerConfig, x: item.x, y: item.y, w: 12, h: Math.round(item.height / (GRID_BASE_UNIT * 4)) };
			} else if (item.type === 'tabs') {
				return { itemType: 'tabs', elementId: item.id, tabsConfig: item.tabsConfig, x: item.x, y: item.y, w: 12, h: Math.round(item.height / (GRID_BASE_UNIT * 4)) };
			} else {
				return { chartId: item.chartId, elementId: item.id, x: item.x, y: item.y, w: item.width, h: Math.round(item.height / (GRID_BASE_UNIT * 4)) };
			}
		});
	}

	// ─── API Functions ───
	async function fetchDashboards() {
		try {
			const res = await dashboardService.getDashboardList({ pageNo: 1, pageSize: 100 });
			const data = res as any;
			dashboards = data?.data || [];
			if (dashboards.length > 0 && !activeDashboardId) {
				activeDashboardId = dashboards[0].id;
			}
		} catch { dashboards = []; }
	}

	async function fetchDashboardDetails() {
		if (!activeDashboardId) { loading = false; return; }
		loading = true;
		try {
			const dashboard = await dashboardService.getDashboard({ id: activeDashboardId });
			activeDashboard = dashboard;

			let chartList: IChart[] = [];
			if (dashboard?.chartIds?.length) {
				const res = await dashboardService.getChartsByIds({ ids: dashboard.chartIds });
				chartList = Array.isArray(res) ? res : [];
			}

			const schemaData = dashboard?.schema ? JSON.parse(dashboard.schema) : {};
			layoutItems = parseSchemaToLayoutItems(schemaData, chartList);

			// Always sync refresh rule (clear interval if NONE, set if not)
			setRefreshRule((dashboard?.refreshRule as typeof refreshRule) || 'NONE');
		} catch (e) {
			console.error('Failed to fetch dashboard details:', e);
			notify.error('Failed to load dashboard');
			layoutItems = [];
		} finally { loading = false; }
	}

	async function loadSavedCharts() {
		try {
			const res = await dashboardService.getChartList?.({ pageNo: 1, pageSize: 200 });
			savedCharts = Array.isArray(res) ? res as IChart[] : (res as any)?.data || [];
		} catch { savedCharts = []; }
	}

	// ─── Save Layout ───
	async function saveLayout(items?: LayoutItem[]) {
		if (!activeDashboardId) return;
		const toSave = items || layoutItems;
		const layout = convertLayoutItemsToSchema(toSave);
		const chartIds = [...new Set(
			toSave
				.filter(item => item.type === 'chart' && item.chartId)
				.map(item => item.chartId!)
		)];

		try {
			await dashboardService.updateDashboard({
				id: activeDashboardId,
				chartIds,
				schema: JSON.stringify({ layout }),
				refreshRule
			});
		} catch (e) { console.error('Failed to save layout:', e); notify.error('Failed to save layout'); }
	}

	// ─── Dashboard CRUD ───
	let savingDashboard = $state(false);
	async function handleCreateOrUpdateDashboard() {
		if (!newDashboardName.trim() || savingDashboard) return;
		savingDashboard = true;
		try {
			if (editingDashboard) {
				await dashboardService.updateDashboard({
					id: editingDashboard.id,
					name: newDashboardName.trim(),
					description: newDashboardDesc.trim() || undefined,
					refreshRule: newDashboardRefreshRule
				});
				if (editingDashboard.id === activeDashboardId) {
					activeDashboard = {
						...activeDashboard!,
						name: newDashboardName.trim(),
						description: newDashboardDesc.trim(),
						refreshRule: newDashboardRefreshRule
					};
					// Update auto-refresh
					setRefreshRule(newDashboardRefreshRule);
				}
			} else {
				const newId = await dashboardService.createDashboard({
					name: newDashboardName.trim(),
					description: newDashboardDesc.trim() || undefined,
					refreshRule: newDashboardRefreshRule
				});
				if (typeof newId === 'number') activeDashboardId = newId;
			}
			const wasEditing = !!editingDashboard;
			newDashboardName = ''; newDashboardDesc = ''; newDashboardRefreshRule = 'NONE';
			showCreateModal = false; editingDashboard = null;
			await fetchDashboards();
			if (!wasEditing) await fetchDashboardDetails();
			notify.success(wasEditing ? 'Dashboard updated' : 'Dashboard created');
		} catch (e) {
			console.error('Failed to save dashboard:', e);
			notify.error('Failed to save dashboard');
		} finally {
			savingDashboard = false;
		}
	}

	function handleDeleteDashboard(id: number) {
		confirmModal = {
			show: true,
			title: 'Delete Dashboard',
			message: 'Are you sure you want to delete this dashboard? This action cannot be undone.',
			onConfirm: async () => {
				try {
					await dashboardService.deleteDashboard?.({ id });
					if (activeDashboardId === id) {
						activeDashboardId = dashboards.find(d => d.id !== id)?.id || null;
						activeDashboard = null;
					}
					await fetchDashboards();
					if (activeDashboardId) await fetchDashboardDetails();
					notify.success('Dashboard deleted');
				} catch { notify.error('Failed to delete dashboard'); }
				confirmModal.show = false;
			}
		};
	}

	function openEditDashboard(db: IDashboard) {
		editingDashboard = db;
		newDashboardName = db.name;
		newDashboardDesc = db.description || '';
		newDashboardRefreshRule = (db.refreshRule as typeof newDashboardRefreshRule) || 'NONE';
		showCreateModal = true;
	}

	async function selectDashboard(db: IDashboard) {
		activeDashboardId = db.id;
		editMode = false;
		await fetchDashboardDetails();
	}

	// ─── Chart Refresh (SQL Re-execution) ───
	async function refreshChartsData() {
		if (refreshing || !layoutItems.length) return;
		refreshing = true;
		try {
			const chartItems = layoutItems.filter(
				item => item.type === 'chart' && item.chartSchema?.sql && item.chartSchema?.dataSourceId
			);
			if (!chartItems.length) {
				await fetchDashboardDetails();
				return;
			}

			refreshingItemIds = new Set(chartItems.map(item => item.id));
			let successCount = 0;
			const total = chartItems.length;

			await Promise.all(chartItems.map(async (item) => {
				const { sql, dataSourceId, databaseName } = item.chartSchema!;
				try {
					const res = await sqlService.executeSql({
						dataSourceId: dataSourceId!,
						databaseName: databaseName || undefined,
						sql: sql!.trim(),
						pageNo: 1,
						pageSize: 50
					});
					const result = Array.isArray(res) ? res[0] : res;
					if (result?.success !== false) {
						layoutItems = layoutItems.map(li =>
							li.id === item.id
								? { ...li, chartSchema: { ...li.chartSchema, resultData: result } }
								: li
						);
						successCount++;
					}
				} catch { /* skip failed */ }
				finally {
					refreshingItemIds = new Set([...refreshingItemIds].filter(id => id !== item.id));
				}
			}));

			notify.success('Charts refreshed', `${successCount} of ${total} charts updated.`);
		} catch (e) {
			console.error('Refresh failed:', e);
			notify.error('Refresh failed');
		}
		finally {
			refreshing = false;
			refreshingItemIds = new Set();
		}
	}

	// ─── Auto-refresh ───
	function setRefreshRule(rule: typeof refreshRule) {
		refreshRule = rule;
		showRefreshDropdown = false;
		if (refreshInterval) { clearInterval(refreshInterval); refreshInterval = null; }
		if (rule !== 'NONE' && refreshIntervals[rule]) {
			refreshInterval = setInterval(() => refreshChartsData(), refreshIntervals[rule]);
		}
	}

	// ─── Grid Handlers ───
	function handleGridChange(items: IGridItem[]) {
		layoutItems = items as LayoutItem[];
		// Debounced save after grid changes (resize, reorder)
		saveLayout(layoutItems);
	}

	function handleGridRemove(id: string) {
		confirmModal = {
			show: true,
			title: 'Delete Item',
			message: 'Are you sure you want to delete this item from the layout?',
			onConfirm: async () => {
				layoutItems = layoutItems.filter(i => i.id !== id);
				await saveLayout(layoutItems);
				confirmModal.show = false;
			}
		};
	}

	// ─── Edit Mode Toggle with Save ───
	async function toggleEditMode() {
		if (editMode) {
			// Save layout when exiting edit mode
			await saveLayout();
			showLayoutElements = false;
		}
		editMode = !editMode;
	}

	// ─── Add Layout Element ───
	function createLayoutItem(type: string, insertY?: number, insertX = 0, insertWidth?: number): LayoutItem {
		const id = `${type}-${Date.now()}`;
		const maxY = layoutItems.length > 0 ? Math.max(...layoutItems.map(i => i.y)) + 1 : 0;
		const y = insertY ?? maxY;

		if (type === 'header') {
			return {
				id, type: 'header', x: 0, y, width: 12, height: 48,
				headerConfig: { text: 'New Header', size: 'medium', backgroundColor: 'transparent' }
			};
		} else if (type === 'text') {
			return {
				id, type: 'text', x: insertX, y, width: insertWidth ?? 6, height: 200,
				textConfig: { content: DEFAULT_TEXT_CONTENT }
			};
		} else if (type === 'tabs') {
			const tabId = `tab-${Date.now()}`;
			return {
				id, type: 'tabs', x: 0, y, width: 12, height: 300,
				tabsConfig: { activeTabId: tabId, tabs: [{ id: tabId, title: 'Tab 1', children: [] }] }
			};
		} else {
			return { id, type: type as any, x: 0, y, width: 12, height: 16 };
		}
	}

	async function addLayoutElement(type: string) {
		const newItem = createLayoutItem(type);

		layoutItems = [...layoutItems, newItem];
		await saveLayout(layoutItems);

		// Auto-enter edit mode for new headers
		if (type === 'header') {
			editingHeaderId = newItem.id;
			editingHeaderText = 'New Header';
		}
	}

	async function addLayoutElementAt(type: string, insertY: number, insertX?: number, insertWidth?: number) {
		const newItem = createLayoutItem(type, insertY, insertX ?? 0, insertWidth);
		const shifted = insertX === undefined
			? layoutItems.map(item => ({
				...item,
				y: item.y >= insertY ? item.y + 1 : item.y
			}))
			: layoutItems;

		layoutItems = [...shifted, newItem];
		await saveLayout(layoutItems);

		// Auto-enter edit mode for new headers
		if (type === 'header') {
			editingHeaderId = newItem.id;
			editingHeaderText = 'New Header';
		}
	}

	// ─── Add Saved Chart to Dashboard ───
	async function addSavedChartToDashboard(chart: IChart) {
		if (!activeDashboardId) return;

		let chartSchema: ChartSchema | undefined;
		if (chart.schema) {
			try {
				chartSchema = JSON.parse(chart.schema);
				if (chart.dataSourceId) chartSchema!.dataSourceId = chart.dataSourceId;
				if (chart.databaseName) chartSchema!.databaseName = chart.databaseName;
			} catch { /* ignore */ }
		}

		const maxY = layoutItems.length > 0 ? Math.max(...layoutItems.map(i => i.y)) + 1 : 0;
		const newItem: LayoutItem = {
			id: `chart-${chart.id}-${Date.now()}`, type: 'chart',
			chartId: chart.id, chart, chartSchema,
			x: 0, y: maxY, width: DEFAULT_CHART_WIDTH, height: DEFAULT_CHART_HEIGHT
		};

		layoutItems = [...layoutItems, newItem];
		await saveLayout([...layoutItems]);
	}

	// ─── Add Saved Chart at Specific Position (via drag & drop) ───
	let _dropProcessing = false;
	async function addSavedChartAtPosition(chartDataStr: string, insertY: number, insertX?: number, insertWidth?: number) {
		// Prevent duplicate calls from multiple event handlers
		if (_dropProcessing) {
			console.warn('[addSavedChartAtPosition] Blocked duplicate call');
			return;
		}
		_dropProcessing = true;
		setTimeout(() => { _dropProcessing = false; }, 300);

		if (!activeDashboardId) {
			notify.warning('Please select a dashboard first');
			return;
		}

		try {
			const chartData = JSON.parse(chartDataStr);
			const chartId = chartData.chartId;

			// Find the full chart from saved charts
			const chart = savedCharts.find(c => c.id === chartId) || {
				id: chartId,
				name: chartData.name,
				schema: chartData.schema,
				dataSourceId: chartData.dataSourceId,
				databaseName: chartData.databaseName,
				sourceType: chartData.sourceType
			} as IChart;

			let chartSchema: ChartSchema | undefined;
			if (chart.schema) {
				try {
					chartSchema = JSON.parse(chart.schema);
					if (chart.dataSourceId) chartSchema!.dataSourceId = chart.dataSourceId;
					if (chart.databaseName) chartSchema!.databaseName = chart.databaseName;
				} catch { /* ignore */ }
			}

			const shifted = insertX === undefined
				? layoutItems.map(item => ({
					...item,
					y: item.y >= insertY ? item.y + 1 : item.y
				}))
				: layoutItems;

			const newItem: LayoutItem = {
				id: `chart-${chart.id}-${Date.now()}`, type: 'chart',
				chartId: chart.id, chart, chartSchema,
				x: insertX ?? 0, y: insertY, width: insertWidth ?? DEFAULT_CHART_WIDTH, height: DEFAULT_CHART_HEIGHT
			};

			layoutItems = [...shifted, newItem];
			await saveLayout(layoutItems);
		} catch (e) {
			console.error('Failed to add chart at position:', e);
		}
	}

	// ─── Delete Saved Chart ───
	function deleteSavedChart(chartId: number) {
		confirmModal = {
			show: true,
			title: 'Delete Chart',
			message: 'Are you sure you want to delete this saved chart?',
			onConfirm: async () => {
				try {
					await dashboardService.deleteChart({ id: chartId });
					savedCharts = savedCharts.filter(c => c.id !== chartId);
					notify.success('Chart deleted');
				} catch { notify.error('Failed to delete chart'); }
				confirmModal.show = false;
			}
		};
	}

	// ─── Export ───
	async function handleExportChart(element: HTMLElement, name: string, chartType?: string) {
		if (chartType === 'CARD') {
			notify.warning('Export not supported', 'Image export is not available for Card charts.');
			return;
		}
		if (chartType === 'TABLE') {
			notify.warning('Export not supported', 'Image export is not available for Table charts.');
			return;
		}
		try {
			await downloadChartAsPNG(element, name);
			notify.success('Chart exported');
		} catch { notify.error('Export failed'); }
	}

	async function handleExportDashboard() {
		const el = document.getElementById('dashboard-content');
		if (!el || !activeDashboard) { notify.warning('Dashboard is not ready yet.'); return; }
		exportingDashboard = true;
		try {
			// Wait for charts to render
			await new Promise(r => setTimeout(r, 300));

			const bgColor = isDarkMode ? '#1e1e1e' : '#ffffff';

			// Save original styles
			const origScrollTop = el.scrollTop;
			const origOverflow = el.style.overflow;
			const origHeight = el.style.height;
			const origMaxHeight = el.style.maxHeight;
			const origBg = el.style.background;

			// Expand container to show full content
			el.scrollTop = 0;
			el.style.overflow = 'visible';
			el.style.height = 'auto';
			el.style.maxHeight = 'none';
			el.style.background = bgColor;

			// Replace canvas elements with images (html-to-image uses foreignObject which can't capture canvas pixel data)
			const replacedCanvases: Array<{ img: HTMLImageElement; canvas: HTMLCanvasElement; parent: HTMLElement }> = [];
			el.querySelectorAll('canvas').forEach(canvas => {
				try {
					const dataUrl = canvas.toDataURL('image/png');
					const img = document.createElement('img');
					img.src = dataUrl;
					img.style.width = canvas.style.width || `${canvas.width}px`;
					img.style.height = canvas.style.height || `${canvas.height}px`;
					img.style.display = 'block';
					const parent = canvas.parentElement!;
					parent.replaceChild(img, canvas);
					replacedCanvases.push({ img, canvas, parent });
				} catch { /* CORS or tainted canvas */ }
			});

			// Calculate actual content height
			const containerRect = el.getBoundingClientRect();
			let maxBottom = 0;
			el.querySelectorAll('*').forEach(child => {
				const relBottom = child.getBoundingClientRect().bottom - containerRect.top;
				if (relBottom > maxBottom) maxBottom = relBottom;
			});
			const contentHeight = Math.min(Math.max(maxBottom + 48, el.clientHeight), el.scrollHeight);

			const { toPng } = await import('html-to-image');
			const url = await toPng(el, {
				backgroundColor: bgColor,
				pixelRatio: 2,
				width: el.scrollWidth,
				height: contentHeight,
			});

			// Restore canvases
			replacedCanvases.forEach(({ img, canvas, parent }) => {
				parent.replaceChild(canvas, img);
			});

			// Restore original styles
			el.style.overflow = origOverflow;
			el.style.height = origHeight;
			el.style.maxHeight = origMaxHeight;
			el.style.background = origBg;
			el.scrollTop = origScrollTop;

			const link = document.createElement('a');
			link.href = url;
			const safeName = (activeDashboard.name || 'dashboard').replace(/[\\/:*?"<>|]+/g, '-');
			link.download = `${safeName}.png`;
			link.click();

			notify.success('Dashboard exported', 'Image saved successfully.');
		} catch (e) {
			console.error('Failed to export dashboard image:', e);
			notify.error('Export failed', 'Failed to export dashboard as image.');
		} finally {
			exportingDashboard = false;
		}
	}

	// ─── Full-screen toggle ───
	function toggleFullScreen() { isFullScreen = !isFullScreen; }

	// ─── Share ───
	function getShareUrl(): string {
		if (!activeDashboard?.shareToken) return '';
		return `${window.location.origin}/public/dashboard/${activeDashboard.shareToken}`;
	}

	async function handleToggleShare() {
		if (!activeDashboard) return;
		shareLoading = true;
		try {
			if (activeDashboard.isPublic) {
				await dashboardService.disableShare({ id: activeDashboard.id });
				activeDashboard.isPublic = false;
				const idx = dashboards.findIndex(d => d.id === activeDashboard!.id);
				if (idx >= 0) dashboards[idx].isPublic = false;
				notify.success('Share disabled', 'Public link has been deactivated.');
			} else {
				const result = await dashboardService.enableShare({ id: activeDashboard.id });
				activeDashboard.isPublic = true;
				activeDashboard.shareToken = result.shareToken;
				const idx = dashboards.findIndex(d => d.id === activeDashboard!.id);
				if (idx >= 0) {
					dashboards[idx].isPublic = true;
					dashboards[idx].shareToken = result.shareToken;
				}
				notify.success('Share enabled', 'Public link has been activated.');
			}
		} catch (e) {
			console.error('Failed to toggle share:', e);
			notify.error('Share error', 'Failed to update share settings.');
		} finally {
			shareLoading = false;
		}
	}

	async function handleCopyShareLink() {
		const url = getShareUrl();
		if (!url) return;
		try {
			await navigator.clipboard.writeText(url);
			shareCopied = true;
			setTimeout(() => { shareCopied = false; }, 2000);
		} catch {
			notify.error('Copy failed', 'Failed to copy link to clipboard.');
		}
	}

	// ─── AI Summary ───
	async function handleAISummary() {
		if (!activeDashboard) return;
		showSummaryModal = true;
		// Fetch AI config if not loaded yet
		if (!aiConfigLoaded) {
			try {
				const config = await configService.getAiSystemConfig({});
				const cfg = config as any;
				aiConfigProvider = cfg?.aiSqlSource || null;
				if (cfg?.aiSqlSource) {
					summaryModel = cfg.aiSqlSource as typeof summaryModel;
				}
			} catch { aiConfigProvider = null; }
			aiConfigLoaded = true;
		}
	}

	async function generateSummary() {
		summaryLoading = true; summaryContent = ''; summaryCopied = false; summaryError = '';
		try {
			// Compress chart data: only send columns, last 15 rows, and total count (same as React)
			const chartData = layoutItems
				.filter(i => i.type === 'chart')
				.map(i => {
					const resultData = i.chartSchema?.resultData;
					const actual = Array.isArray(resultData) ? resultData[0] : resultData;

					if (!actual?.headerList || !actual?.dataList) {
						return { name: i.chart?.name || 'Untitled', chartType: i.chartSchema?.chartType, sql: i.chartSchema?.sql, data: null };
					}

					// Filter out Row Number column (same as React)
					const columns = actual.headerList
						.filter((h: any) => h.name !== 'Row Number')
						.map((h: any) => h.name);
					const hasRowNumber = actual.headerList[0]?.name === 'Row Number';
					const rows = actual.dataList
						.slice(-15)
						.map((row: any[]) => hasRowNumber ? row.slice(1) : row);

					return {
						name: i.chart?.name || 'Untitled',
						chartType: i.chartSchema?.chartType,
						sql: i.chartSchema?.sql,
						data: { columns, rows, totalRows: actual.dataList.length > 15 ? actual.dataList.length : undefined }
					};
				});

			const langConfig = LANGUAGE_OPTIONS.find(l => l.value === summaryLanguage);
			const res = await dashboardService.summarizeDashboard({
				dashboardName: activeDashboard!.name,
				model: FAST_MODELS[summaryModel] || 'gemini-3.1-flash-lite',
				language: langConfig?.label || 'English',
				charts: chartData
			});
			summaryContent = (res as any)?.summary || 'No summary generated.';
		} catch (err: any) {
			summaryError = `Failed to generate summary${err?.message ? ': ' + err.message : ''}`;
		}
		finally { summaryLoading = false; }
	}

	function copySummary() {
		navigator.clipboard.writeText(summaryContent);
		summaryCopied = true;
		setTimeout(() => summaryCopied = false, 2000);
	}

	// ─── Chart Title Editing ───
	function startEditChartTitle(item: LayoutItem) {
		editingChartId = item.id;
		editingChartTitle = item.chart?.name || '';
	}

	async function saveChartTitle() {
		if (!editingChartId || !editingChartTitle.trim()) {
			editingChartId = null;
			return;
		}
		const item = layoutItems.find(i => i.id === editingChartId);
		if (!item || !item.chartId) { editingChartId = null; return; }

		try {
			await dashboardService.updateChart({ id: item.chartId, name: editingChartTitle.trim() });
			layoutItems = layoutItems.map(i =>
				i.id === editingChartId
					? { ...i, chart: { ...i.chart!, name: editingChartTitle.trim() } }
					: i
			);
		} catch (e) { console.error('Failed to update chart title:', e); notify.error('Failed to update chart title'); }
		editingChartId = null;
	}

	// ─── Chart Edit via Modal ───
	function openEditChart(item: LayoutItem) {
		editingChart = item.chart || null;
		showChartModal = true;
	}

	// ─── Header Editing ───
	function startEditHeader(item: LayoutItem) {
		editingHeaderId = item.id;
		editingHeaderText = item.headerConfig?.text || '';
	}

	async function saveHeader() {
		if (editingHeaderId) {
			layoutItems = layoutItems.map(item =>
				item.id === editingHeaderId
					? { ...item, headerConfig: { ...item.headerConfig!, text: editingHeaderText } }
					: item
			);
			editingHeaderId = null;
			await saveLayout(layoutItems);
		}
	}

	async function setHeaderBgColor(itemId: string, color: string) {
		layoutItems = layoutItems.map(item =>
			item.id === itemId
				? { ...item, headerConfig: { ...item.headerConfig!, backgroundColor: color } }
				: item
		);
		await saveLayout(layoutItems);
	}

	async function setHeaderSize(itemId: string, size: 'small' | 'medium' | 'large') {
		layoutItems = layoutItems.map(item =>
			item.id === itemId
				? { ...item, headerConfig: { ...item.headerConfig!, size } }
				: item
		);
		await saveLayout(layoutItems);
	}

	// ─── Text Editing ───
	function startEditText(item: LayoutItem) {
		editingTextId = item.id;
		editingTextContent = item.textConfig?.content || '';
		textEditorTab = 'edit';
	}

	async function saveText() {
		if (editingTextId) {
			layoutItems = layoutItems.map(item =>
				item.id === editingTextId
					? { ...item, textConfig: { content: editingTextContent } }
					: item
			);
			editingTextId = null;
			await saveLayout(layoutItems);
		}
	}

	// ─── Tabs Functions ───
	async function handleAddTab(tabsItemId: string) {
		const newTabId = `tab-${Date.now()}`;
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				const newTab: TabItem = { id: newTabId, title: `Tab ${item.tabsConfig.tabs.length + 1}`, children: [] };
				return {
					...item,
					tabsConfig: { ...item.tabsConfig, activeTabId: newTabId, tabs: [...item.tabsConfig.tabs, newTab] }
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	async function handleRemoveTab(tabsItemId: string, tabId: string) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				const filtered = item.tabsConfig.tabs.filter(t => t.id !== tabId);
				if (filtered.length === 0) return item; // Keep at least one tab
				const newActiveId = item.tabsConfig.activeTabId === tabId ? filtered[0].id : item.tabsConfig.activeTabId;
				return { ...item, tabsConfig: { ...item.tabsConfig, activeTabId: newActiveId, tabs: filtered } };
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	async function handleTabChange(tabsItemId: string, tabId: string) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return { ...item, tabsConfig: { ...item.tabsConfig, activeTabId: tabId } };
			}
			return item;
		});
		await saveLayout();
	}

	async function handleMoveTab(tabsItemId: string, tabId: string, direction: -1 | 1) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				const tabs = [...item.tabsConfig.tabs];
				const idx = tabs.findIndex(t => t.id === tabId);
				const newIdx = idx + direction;
				if (newIdx < 0 || newIdx >= tabs.length) return item;
				[tabs[idx], tabs[newIdx]] = [tabs[newIdx], tabs[idx]];
				return { ...item, tabsConfig: { ...item.tabsConfig, tabs } };
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	async function handleTabTitleSave(tabsItemId: string, tabId: string) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						tabs: item.tabsConfig.tabs.map(t => t.id === tabId ? { ...t, title: editingTabTitle } : t)
					}
				};
			}
			return item;
		});
		editingTabTitleId = null;
		await saveLayout(layoutItems);
	}

	// Move a chart from the main grid into a tab (hides from grid, adds to tab children)
	async function handleMoveGridChartToTab(tabsItemId: string, tabId: string, chartItemId: string) {
		layoutItems = layoutItems.map(item => {
			// Hide chart from main grid by setting y = -1
			if (item.id === chartItemId) {
				return { ...item, y: -1 };
			}
			// Add to tab children
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						activeTabId: tabId,
						tabs: item.tabsConfig.tabs.map(t =>
							t.id === tabId
								? { ...t, children: [...t.children.filter(c => c !== chartItemId), chartItemId] }
								: t
						)
					}
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	async function handleAddChartToTab(tabsItemId: string, tabId: string, chartItemId: string) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						tabs: item.tabsConfig.tabs.map(t =>
							t.id === tabId
								? { ...t, children: [...t.children.filter(c => c !== chartItemId), chartItemId] }
								: t
						)
					}
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	async function handleRemoveChartFromTab(tabsItemId: string, tabId: string, chartItemId: string) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						tabs: item.tabsConfig.tabs.map(t =>
							t.id === tabId
								? { ...t, children: t.children.filter(c => c !== chartItemId) }
								: t
						)
					}
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	// Reorder charts within a tab via drag-and-drop
	async function handleReorderChartInTab(tabsItemId: string, tabId: string, draggedChartId: string, targetIndex: number) {
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						tabs: item.tabsConfig.tabs.map(t => {
							if (t.id !== tabId) return t;
							const children = t.children.filter(c => c !== draggedChartId);
							children.splice(targetIndex, 0, draggedChartId);
							return { ...t, children };
						})
					}
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	// Move a chart from one tab to another, then switch to the target tab
	async function handleMoveChartToTab(tabsItemId: string, sourceTabId: string, targetTabId: string, chartItemId: string) {
		if (sourceTabId === targetTabId) return;
		layoutItems = layoutItems.map(item => {
			if (item.id === tabsItemId && item.type === 'tabs' && item.tabsConfig) {
				return {
					...item,
					tabsConfig: {
						...item.tabsConfig,
						activeTabId: targetTabId,
						tabs: item.tabsConfig.tabs.map(t => {
							if (t.id === sourceTabId) {
								return { ...t, children: t.children.filter(c => c !== chartItemId) };
							}
							if (t.id === targetTabId) {
								return { ...t, children: [...t.children, chartItemId] };
							}
							return t;
						})
					}
				};
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	// Resize a chart inside a tab
	async function handleTabChartResize(chartItemId: string, newWidth: number, newHeight: number) {
		layoutItems = layoutItems.map(item => {
			if (item.id === chartItemId) {
				return { ...item, width: newWidth, height: newHeight };
			}
			return item;
		});
		await saveLayout(layoutItems);
	}

	// Drop a saved chart from sidebar into a tab, then switch to that tab
	async function handleDropChartIntoTab(tabsItemId: string, tabId: string, chartDataStr: string) {
		try {
			const chartData = JSON.parse(chartDataStr);
			// Check if the chart already exists in layoutItems
			let existingItem = layoutItems.find(i => i.type === 'chart' && i.chartId === chartData.chartId);
			if (!existingItem) {
				// Create a new chart layout item
				const newId = `chart-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
				const newItem: LayoutItem = {
					id: newId,
					type: 'chart',
					x: 0, y: -1, // hidden row (inside tab)
					width: DEFAULT_CHART_WIDTH,
					height: DEFAULT_CHART_HEIGHT,
					chartId: chartData.chartId,
					title: chartData.name,
					chart: { id: chartData.chartId, name: chartData.name, schema: chartData.schema, dataSourceId: chartData.dataSourceId, databaseName: chartData.databaseName } as IChart,
				};
				if (chartData.schema) {
					try {
						const parsed = JSON.parse(chartData.schema);
						if (chartData.dataSourceId) parsed.dataSourceId = chartData.dataSourceId;
						if (chartData.databaseName) parsed.databaseName = chartData.databaseName;
						newItem.chartSchema = parsed;
					} catch { /* ignore */ }
				}
				layoutItems = [...layoutItems, newItem];
				existingItem = newItem;
			}
			// Add to tab and switch to it
			await handleAddChartToTab(tabsItemId, tabId, existingItem.id);
			// Switch active tab to show the dropped chart
			handleTabChange(tabsItemId, tabId);
		} catch (e) {
			console.error('Failed to drop chart into tab:', e);
		}
	}

	// Get chart layout items from tab children IDs
	function getChartsInTab(children: string[]): LayoutItem[] {
		return children
			.map(id => layoutItems.find(i => i.id === id))
			.filter((i): i is LayoutItem => !!i && i.type === 'chart');
	}

	// Get charts NOT inside any tab (for dropdown selection)
	function getAvailableChartsForTab(): LayoutItem[] {
		const chartsInTabs = new Set<string>();
		layoutItems.forEach(item => {
			if (item.type === 'tabs' && item.tabsConfig) {
				item.tabsConfig.tabs.forEach(t => t.children.forEach(c => chartsInTabs.add(c)));
			}
		});
		return layoutItems.filter(i => i.type === 'chart' && !chartsInTabs.has(i.id));
	}

	// ─── Sidebar Resize ───
	function handleSidebarResizeStart(e: MouseEvent) {
		e.preventDefault();
		isSidebarResizing = true;
		const startX = e.clientX;
		const startWidth = sidebarWidth;

		const handleMouseMove = (ev: MouseEvent) => {
			const newWidth = Math.max(MIN_SIDEBAR_WIDTH, Math.min(MAX_SIDEBAR_WIDTH, startWidth + (ev.clientX - startX)));
			sidebarWidth = newWidth;
		};

		const handleMouseUp = () => {
			isSidebarResizing = false;
			localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth));
			window.removeEventListener('mousemove', handleMouseMove);
			window.removeEventListener('mouseup', handleMouseUp);
		};

		window.addEventListener('mousemove', handleMouseMove);
		window.addEventListener('mouseup', handleMouseUp);
	}

	function handleAiChatPanelResizeStart(e: MouseEvent) {
		e.preventDefault();
		isAiChatPanelResizing = true;
		const startX = e.clientX;
		const startWidth = aiChatPanelWidth;

		const handleMouseMove = (ev: MouseEvent) => {
			const diff = startX - ev.clientX;
			const newWidth = Math.max(MIN_AI_CHAT_PANEL_WIDTH, Math.min(MAX_AI_CHAT_PANEL_WIDTH, startWidth + diff));
			aiChatPanelWidth = newWidth;
		};

		const handleMouseUp = () => {
			isAiChatPanelResizing = false;
			localStorage.setItem(AI_CHAT_PANEL_WIDTH_KEY, String(aiChatPanelWidth));
			window.removeEventListener('mousemove', handleMouseMove);
			window.removeEventListener('mouseup', handleMouseUp);
		};

		window.addEventListener('mousemove', handleMouseMove);
		window.addEventListener('mouseup', handleMouseUp);
	}

	// ─── Header Size Classes ───
	function getHeaderSizeClass(size?: string): string {
		switch (size) {
			case 'small': return 'text-sm font-semibold';
			case 'large': return 'text-2xl font-bold';
			default: return 'text-lg font-semibold';
		}
	}

	// ─── Get chart option fallback (generates from config for consistency with Edit modal) ───
	function getChartOption(chart: IChart): Record<string, unknown> | null {
		try {
			if (chart.schema) {
				const parsed = JSON.parse(chart.schema);
				// Prefer dynamic generation from resultData + config
				if (parsed.resultData && parsed.chartType) {
					const rd = Array.isArray(parsed.resultData) ? parsed.resultData[0] : parsed.resultData;
					if (rd?.headerList?.length && rd?.dataList?.length) {
						const ct = parsed.chartType.toUpperCase();
						if (parsed.chartConfig && Object.keys(parsed.chartConfig).length > 0) {
							return generateChartOptionWithConfig(rd, ct, parsed.chartConfig);
						}
						return generateChartOption(rd, ct);
					}
				}
				// Fallback to stored snapshot for legacy data
				return parsed.chartOption || parsed.option || null;
			}
		} catch { /* ignore */ }
		return null;
	}

	// ─── Lifecycle ───
	onMount(async () => {
		// Restore sidebar width from localStorage
		const savedWidth = localStorage.getItem(SIDEBAR_WIDTH_KEY);
		if (savedWidth) sidebarWidth = parseInt(savedWidth, 10);

		const savedAiChatWidth = localStorage.getItem(AI_CHAT_PANEL_WIDTH_KEY);
		if (savedAiChatWidth) {
			const parsed = parseInt(savedAiChatWidth, 10);
			if (!Number.isNaN(parsed)) {
				aiChatPanelWidth = Math.max(MIN_AI_CHAT_PANEL_WIDTH, Math.min(MAX_AI_CHAT_PANEL_WIDTH, parsed));
			}
		}

		isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark' ||
			document.documentElement.classList.contains('dark');

		themeObserver = new MutationObserver((mutations) => {
			mutations.forEach((m) => {
				if (m.attributeName === 'data-theme' || m.attributeName === 'class') {
					isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark' ||
						document.documentElement.classList.contains('dark');
				}
			});
		});
		themeObserver.observe(document.documentElement, { attributes: true });

		await fetchDashboards();
		await loadSavedCharts();
		if (activeDashboardId) await fetchDashboardDetails();
		else loading = false;

		// Listen for chart updates from AI Chat (Pin to Dashboard)
		window.addEventListener('dashboard-chart-updated', handleChartUpdated);
		window.addEventListener('storage', handleStorageChange);
	});

	onDestroy(() => {
		if (refreshInterval) clearInterval(refreshInterval);
		themeObserver?.disconnect();
		if (typeof window !== 'undefined') {
			window.removeEventListener('dashboard-chart-updated', handleChartUpdated);
			window.removeEventListener('storage', handleStorageChange);
		}
	});

	function handleChartUpdated() { fetchDashboardDetails(); }
	function handleStorageChange(e: StorageEvent) {
		if (e.key === 'dashboard-chart-updated') fetchDashboardDetails();
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<svelte:window onkeydown={(e) => {
	if (e.key === 'Escape' && isFullScreen) isFullScreen = false;
	if (e.key === 'Escape' && editingHeaderId) saveHeader();
	if (e.key === 'Escape' && editingTextId) saveText();
	if (e.key === 'Escape' && editingChartId) saveChartTitle();
}} />

<div class="flex h-full w-full bg-background {isFullScreen ? 'fixed inset-0 z-50' : ''} {isSidebarResizing || isAiChatPanelResizing ? 'select-none' : ''}">
	<!-- ═══════ Sidebar ═══════ -->
	{#if !sidebarCollapsed && !isFullScreen}
		<aside class="relative border-r border-border bg-card/50 flex flex-col shrink-0" style="width: {sidebarWidth}px; min-width: {sidebarWidth}px;">
			<!-- Sidebar Header -->
			<div class="flex items-center justify-between px-3 py-2 border-b border-border">
				<div class="flex items-center gap-1">
					<button
						class="px-2 py-1 text-xs font-medium rounded transition-colors
							{sidebarTab === 'dashboards' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'}"
						onclick={() => sidebarTab = 'dashboards'}
					>Dashboards</button>
					<button
						class="px-2 py-1 text-xs font-medium rounded transition-colors
							{sidebarTab === 'charts' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'}"
						onclick={() => sidebarTab = 'charts'}
					>Charts</button>
				</div>
				<div class="flex items-center gap-0.5">
					<button
						class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
						onclick={() => { editingDashboard = null; newDashboardName = ''; newDashboardDesc = ''; newDashboardRefreshRule = 'NONE'; showCreateModal = true; }}
						title="New Dashboard"
					><Plus size={14} /></button>
					<button
						class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
						onclick={() => sidebarCollapsed = true}
						title="Collapse"
					><PanelLeftClose size={14} /></button>
				</div>
			</div>

			<!-- Sidebar Content -->
			<div class="flex-1 overflow-auto py-1">
				{#if sidebarTab === 'dashboards'}
					{#each dashboards as db (db.id)}
						<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
						<div
							class="flex items-center gap-2 px-3 py-2 cursor-pointer transition-colors group relative
								{activeDashboardId === db.id ? 'bg-accent' : 'hover:bg-accent/50'}"
							role="option"
							tabindex="0"
							aria-selected={activeDashboardId === db.id}
							onclick={() => selectDashboard(db)}
						>
							<LayoutDashboard size={14} class="text-muted-foreground shrink-0" />
							<span class="text-sm truncate flex-1">{db.name}</span>
							<div class="opacity-0 group-hover:opacity-100 flex items-center" onclick={(e) => e.stopPropagation()} role="none">
								<DropdownMenu>
									<DropdownMenuTrigger class="p-0.5 rounded hover:bg-accent">
										<MoreVertical size={12} class="text-muted-foreground" />
									</DropdownMenuTrigger>
									<DropdownMenuContent align="end" class="min-w-[120px]">
										<DropdownMenuItem onSelect={() => openEditDashboard(db)} class="text-xs gap-2">
											<Pencil size={12} /><span>Edit</span>
										</DropdownMenuItem>
										<DropdownMenuSeparator />
										<DropdownMenuItem destructive onSelect={() => handleDeleteDashboard(db.id)} class="text-xs gap-2">
											<Trash2 size={12} /><span>Delete</span>
										</DropdownMenuItem>
									</DropdownMenuContent>
								</DropdownMenu>
							</div>
						</div>
					{/each}
					{#if dashboards.length === 0 && !loading}
						<p class="text-xs text-muted-foreground text-center py-8">No dashboards</p>
					{:else if dashboards.length > 0}
						<p class="text-[10px] text-muted-foreground/60 text-center py-2">No more</p>
					{/if}
				{:else}
					<!-- Saved Charts -->
					<div class="px-2 py-1.5 border-b border-border space-y-1.5">
						<div class="flex items-center gap-1.5 rounded-md border border-input bg-background px-2 py-1">
							<Search size={12} class="text-muted-foreground shrink-0" />
							<input
								bind:value={chartSearch}
								type="text"
								placeholder="Search charts..."
								class="w-full text-xs bg-transparent focus:outline-none"
							/>
						</div>
					<div class="flex items-center gap-1">
						<span class="text-[10px] text-muted-foreground shrink-0">Sort:</span>
						{#each [
							{ value: 'recent', label: 'Recent' },
							{ value: 'name', label: 'Name' },
							{ value: 'type', label: 'Type' },
							{ value: 'source', label: 'Source' }
						] as opt}
							<button
								class="text-[10px] px-1.5 py-0.5 rounded transition-colors {chartSortBy === opt.value ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'}"
								onclick={() => chartSortBy = opt.value as typeof chartSortBy}
							>{opt.label}</button>
						{/each}
						<button
							class="ml-auto text-muted-foreground hover:text-foreground transition-colors p-0.5 rounded hover:bg-accent"
							onclick={loadSavedCharts}
							title="Refresh charts"
						>
							<RefreshCw size={11} />
						</button>
					</div>
					</div>
				{#each filteredSavedCharts as chart (chart.id)}
					{@const chartType = getChartTypeFromSchema(chart)}
					{@const badge = getChartTypeBadge(chartType)}
					<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
					<div
						class="relative flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-accent/50 transition-colors text-sm group"
						role="option"
						tabindex="0"
						aria-selected={false}
						draggable="true"
						ondragstart={(e) => {
							if (!e.dataTransfer) return;
							sidebarDragging = true;
							hoveredChartId = null;
							if (!editMode) editMode = true;
							e.dataTransfer.setData('application/dashboard-chart', JSON.stringify({
								chartId: chart.id,
								name: chart.name,
								schema: chart.schema,
								dataSourceId: chart.dataSourceId,
								databaseName: chart.databaseName,
								sourceType: chart.sourceType
							}));
							e.dataTransfer.setData('text/plain', chart.name);
							e.dataTransfer.effectAllowed = 'all';
						}}
						ondragend={() => { setTimeout(() => { sidebarDragging = false; }, 100); }}
						onmouseenter={(e) => {
							const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
							previewPosition = { x: rect.right + 8, y: rect.top };
							hoveredChartId = chart.id;
						}}
						onmouseleave={() => { hoveredChartId = null; }}
						onclick={() => { if (!sidebarDragging) addSavedChartToDashboard(chart); }}
						title="Drag to position or click to add"
					>
						<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
						<div
							class="flex items-center justify-center cursor-grab text-muted-foreground/40 opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity shrink-0"
							role="presentation"
							onclick={(e) => e.stopPropagation()}
						>
							<GripVertical size={12} />
						</div>
						<div class="flex flex-col flex-1 min-w-0">
							<span class="truncate text-sm">{chart.name}</span>
							<div class="flex items-center gap-1 mt-0.5">
								<span class="text-[9px] px-1 py-0 rounded {badge.color} shrink-0">{badge.label}</span>
								{#if chart.sourceType}
									<span class="text-[9px] px-1 py-0 rounded bg-muted text-muted-foreground shrink-0">
										{chart.sourceType === 'AI_CHAT' ? 'AI Chat' : chart.sourceType === 'WORKSPACE' ? 'Query' : 'Dashboard'}
									</span>
								{/if}
								{#if chart.gmtModified}
									<span class="text-[9px] text-muted-foreground/60 shrink-0">{timeAgo(chart.gmtModified)}</span>
								{/if}
							</div>
						</div>
						<button
							class="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive shrink-0"
							onclick={(e) => { e.stopPropagation(); deleteSavedChart(chart.id); }}
							title="Delete"
						><Trash2 size={12} /></button>
					</div>
				{/each}
					{#if filteredSavedCharts.length === 0}
						<p class="text-xs text-muted-foreground text-center py-8">{chartSearch ? 'No matching charts' : 'No saved charts'}</p>
					{/if}
				{/if}
			</div>

			<!-- Sidebar Resize Handle -->
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div
				class="absolute right-0 top-0 h-full w-1 cursor-col-resize hover:bg-primary/30 transition-colors z-10 {isSidebarResizing ? 'bg-primary/30' : ''}"
				onmousedown={handleSidebarResizeStart}
			></div>
		</aside>
	{/if}

	<!-- ═══════ Main Content ═══════ -->
	<div class="flex-1 flex flex-col min-w-0 overflow-auto">
		<!-- Header -->
		<div class="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
			<div class="flex items-center gap-3">
				{#if sidebarCollapsed && !isFullScreen}
					<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => sidebarCollapsed = false}>
						<PanelLeftOpen size={16} />
					</button>
				{/if}
				{#if activeDashboard}
					<LayoutDashboard size={18} class="text-muted-foreground shrink-0" />
					<span class="text-sm font-semibold text-foreground">{activeDashboard.name}</span>
				{:else}
					<span class="text-sm font-semibold text-foreground">Dashboard</span>
				{/if}
				<Button variant="ghost" size="sm" class="gap-1 text-muted-foreground" onclick={() => { editingChart = null; showChartModal = true; }}>
					<Plus size={14} />
					New Chart
				</Button>
			</div>
			<div class="flex gap-1.5 items-center">
				{#if activeDashboard}
					{#if editMode}
						<!-- Edit mode: Layout Elements + Done only -->
						<Button variant="ghost" size="sm" class="gap-1 {showLayoutElements ? 'text-primary bg-primary/10' : 'text-muted-foreground'}" onclick={() => { showLayoutElements = !showLayoutElements; }}>
							<Plus size={14} />
							Layout Elements
						</Button>
					{:else}
						<!-- View mode: AI Chat button only (dropdown moved after Edit) -->
						<Button variant="ghost" size="sm" class="gap-1 {showAIChat ? 'text-primary bg-primary/10' : ''}" onclick={() => showAIChat = !showAIChat} title="AI Chat">
							<AISparkleIcon size={14} />
						</Button>
					{/if}
				{/if}
				{#if activeDashboard}
					<Button
						variant={editMode ? 'default' : 'outline'}
						size="sm"
						onclick={toggleEditMode}
					>
						{editMode ? 'Done' : 'Edit'}
					</Button>
					{#if !editMode}
						<DropdownMenu>
							<DropdownMenuTrigger
								class="inline-flex items-center justify-center rounded-md h-8 w-8 hover:bg-accent hover:text-accent-foreground text-muted-foreground transition-colors"
								title="More actions"
								onclick={(e: MouseEvent) => { moreMenuBtnEl = e.currentTarget as HTMLElement; }}
							>
								<MoreVertical size={14} />
							</DropdownMenuTrigger>
							<DropdownMenuContent align="end" class="min-w-[180px]">
								<DropdownMenuItem onclick={refreshChartsData} disabled={refreshing}>
									{#if refreshing}
										<Loader2 size={14} class="mr-2 animate-spin" />
									{:else}
										<RefreshCw size={14} class="mr-2" />
									{/if}
									Refresh Charts
								</DropdownMenuItem>
								<DropdownMenuItem onclick={handleAISummary}>
									<Sparkles size={14} class="mr-2" />
									AI Summary
								</DropdownMenuItem>
								<DropdownMenuSeparator />
								<DropdownMenuItem onclick={() => showShareModal = true}>
									<Share2 size={14} class="mr-2 {activeDashboard?.isPublic ? 'text-green-600 dark:text-green-400' : ''}" />
									{activeDashboard?.isPublic ? 'Sharing (On)' : 'Share'}
								</DropdownMenuItem>
								<DropdownMenuItem onclick={handleExportDashboard} disabled={exportingDashboard}>
									{#if exportingDashboard}
										<Loader2 size={14} class="mr-2 animate-spin" />
									{:else}
										<Download size={14} class="mr-2" />
									{/if}
									Export as Image
								</DropdownMenuItem>
								<DropdownMenuSeparator />
								<DropdownMenuItem onclick={toggleFullScreen}>
									{#if isFullScreen}
										<Minimize size={14} class="mr-2" />
										Exit Full Screen
									{:else}
										<Maximize size={14} class="mr-2" />
										Full Screen
									{/if}
								</DropdownMenuItem>
								<DropdownMenuItem onclick={() => { setTimeout(() => { showRefreshDropdown = true; }, 100); }}>
									<Timer size={14} class="mr-2 {refreshRule !== 'NONE' ? 'text-primary' : ''}" />
									Auto-refresh
									{#if refreshRule !== 'NONE'}
										<span class="ml-auto text-[10px] text-primary font-medium">{refreshRule}</span>
									{/if}
								</DropdownMenuItem>
								<DropdownMenuSeparator />
								<DropdownMenuItem onclick={() => { if (activeDashboard) openEditDashboard(activeDashboard); }}>
									<Settings size={14} class="mr-2" />
									Dashboard Settings
								</DropdownMenuItem>
							</DropdownMenuContent>
						</DropdownMenu>
					
					{/if}
				{/if}
			</div>
		</div>

		{#if loading}
			<div class="flex-1 flex items-center justify-center">
				<Loader2 class="h-8 w-8 animate-spin text-primary" />
			</div>
		{:else if !activeDashboard}
			<div class="flex flex-col items-center justify-center py-20 text-center">
				<LayoutDashboard class="h-12 w-12 text-muted-foreground/30 mb-4" />
				<h3 class="text-lg font-semibold text-foreground mb-1">No dashboard selected</h3>
				<p class="text-sm text-muted-foreground mb-4">Create a dashboard to get started</p>
				<Button onclick={() => { editingDashboard = null; newDashboardName = ''; showCreateModal = true; }}>
					<Plus size={14} class="mr-1" /> New Dashboard
				</Button>
			</div>
		{:else}
			<!-- ═══════ Content Area (Grid + AI Chat) ═══════ -->
			<div class="flex flex-1 overflow-hidden">
				<!-- Layout Elements Sidebar -->
				{#if showLayoutElements}
					<div class="w-[160px] shrink-0 border-r border-border bg-background">
						<div class="border-b border-border px-3 py-2">
							<span class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
								Layout Elements
							</span>
						</div>
						<div class="p-2 space-y-1">
							{#each layoutElements as el}
								{@const Icon = el.icon}
								<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
								<div
									class="flex items-center gap-2 rounded-md px-2 py-1.5 w-full cursor-grab active:cursor-grabbing transition-colors hover:bg-accent/50 text-left select-none"
									role="button"
									tabindex="0"
									draggable="true"
									ondragstart={(e) => {
										if (!e.dataTransfer) return;
										e.dataTransfer.setData('application/dashboard-item', JSON.stringify({ isNew: true, type: el.type }));
										e.dataTransfer.setData('text/plain', el.type);
										e.dataTransfer.effectAllowed = 'all';
									}}
									onclick={() => addLayoutElement(el.type)}
								>
									<div class="flex items-center justify-center text-muted-foreground">
										<Icon size={16} />
									</div>
									<span class="text-sm text-foreground">{el.label}</span>
								</div>
							{/each}
						</div>
						<div class="px-3 py-2 border-t border-border">
							<p class="text-[10px] text-muted-foreground/60">Drag to position or click to add at bottom</p>
						</div>
					</div>
				{/if}

				<!-- Dashboard Grid -->
				<div
					class="flex-1 overflow-y-auto p-6"
					id="dashboard-content"
				>
						<DashboardGrid
							items={layoutItems.filter(i => i.y >= 0)}
							{editMode}
							onchange={handleGridChange}
							onremove={handleGridRemove}
							ondropnew={(type, insertY, insertX, insertWidth) => addLayoutElementAt(type, insertY, insertX, insertWidth)}
							ondropchart={(chartData, insertY, insertX, insertWidth) => addSavedChartAtPosition(chartData, insertY, insertX, insertWidth)}
						>
							{#snippet children(item, rowItems, gridContainerWidth)}
								{@const layoutItem = item as LayoutItem}

								{#if layoutItem.type === 'header'}
									<!-- ─── Header Element ─── -->
									<div
										class="flex items-center h-full px-5 py-3 rounded-lg"
										style="background-color: {getHeaderBgColor(layoutItem.headerConfig?.backgroundColor || 'transparent')}"
									>
										{#if editMode && editingHeaderId === layoutItem.id}
											<div class="flex items-center gap-2 w-full">
												<!-- svelte-ignore a11y_autofocus -->
												<input
													bind:value={editingHeaderText}
													class="flex-1 bg-transparent border-b border-primary focus:outline-none {getHeaderSizeClass(layoutItem.headerConfig?.size)}"
													onkeydown={(e) => { if (e.key === 'Enter') saveHeader(); }}
													autofocus
												/>
												<button class="text-xs px-2 py-1 rounded bg-primary text-primary-foreground" onclick={saveHeader}>Save</button>
											</div>
										{:else}
											<h3
												class="{getHeaderSizeClass(layoutItem.headerConfig?.size)} text-foreground flex-1 {editMode ? 'cursor-text' : ''}"
												ondblclick={() => editMode && startEditHeader(layoutItem)}
											>
												{layoutItem.headerConfig?.text || 'Header'}
											</h3>
											{#if editMode}
												<div class="flex items-center gap-1 shrink-0 opacity-0 group-hover:opacity-100">
													{#each (['small', 'medium', 'large'] as const) as size}
														<button
															class="px-1.5 py-0.5 text-[10px] rounded transition-colors
																{layoutItem.headerConfig?.size === size ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'}"
															onclick={(e) => { e.stopPropagation(); setHeaderSize(layoutItem.id, size); }}
														>{size[0].toUpperCase()}</button>
													{/each}
													{#each HEADER_BG_COLORS as color}
														<button
															class="w-4 h-4 rounded-full border border-border/50 transition-transform hover:scale-110
																{layoutItem.headerConfig?.backgroundColor === color.value ? 'ring-2 ring-primary ring-offset-1' : ''}"
															style="background-color: {color.value === 'transparent' ? 'transparent' : getHeaderBgColor(color.value)}"
															onclick={(e) => { e.stopPropagation(); setHeaderBgColor(layoutItem.id, color.value); }}
															title={color.label}
														></button>
													{/each}
													<DropdownMenu>
														<DropdownMenuTrigger>
															<button class="p-0.5 rounded hover:bg-accent text-muted-foreground" onclick={(e) => e.stopPropagation()}>
																<MoreVertical size={14} />
															</button>
														</DropdownMenuTrigger>
														<DropdownMenuContent align="end" class="min-w-[100px]">
															<DropdownMenuItem onclick={() => startEditHeader(layoutItem)}>
																<Pencil size={14} class="mr-2" />Edit
															</DropdownMenuItem>
															<DropdownMenuItem class="text-destructive" onclick={() => handleGridRemove(layoutItem.id)}>
																<Trash2 size={14} class="mr-2" />Delete
															</DropdownMenuItem>
														</DropdownMenuContent>
													</DropdownMenu>
												</div>
											{/if}
										{/if}
									</div>

							{:else if layoutItem.type === 'divider'}
								<!-- ─── Divider Element ─── -->
								<div class="group/divider relative flex items-center justify-center h-full px-4 py-2">
									<div class="w-full border-t border-border"></div>
									{#if editMode}
										<button
											class="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded-full hover:bg-destructive/10 text-muted-foreground hover:text-destructive bg-background shadow-sm border border-border opacity-0 group-hover/divider:opacity-100 transition-opacity"
											onclick={() => handleGridRemove(layoutItem.id)}
											title="Delete divider"
										>
											<X size={12} />
										</button>
									{/if}
								</div>

								{:else if layoutItem.type === 'text'}
									<!-- ─── Text/Markdown Element ─── -->
									{#if editMode && editingTextId === layoutItem.id}
										<div class="flex flex-col h-full p-3">
											<div class="flex items-center gap-2 mb-2">
												<button
													class="px-2 py-0.5 text-xs rounded {textEditorTab === 'edit' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}"
													onclick={() => textEditorTab = 'edit'}
												>Edit</button>
												<button
													class="px-2 py-0.5 text-xs rounded {textEditorTab === 'preview' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}"
													onclick={() => textEditorTab = 'preview'}
												>Preview</button>
												<div class="flex-1"></div>
												<button class="text-xs px-2 py-1 rounded bg-muted text-muted-foreground hover:bg-accent" onclick={() => { editingTextId = null; textEditorTab = 'edit'; }}>Cancel</button>
												<button class="text-xs px-2 py-1 rounded bg-primary text-primary-foreground" onclick={() => { saveText(); textEditorTab = 'edit'; }}>Save</button>
											</div>
											{#if textEditorTab === 'edit'}
												<textarea
													bind:value={editingTextContent}
													class="flex-1 w-full bg-transparent border border-input rounded-md p-2 text-sm resize-none focus:outline-none focus:ring-1 focus:ring-ring font-mono"
													placeholder="Write markdown here..."
												></textarea>
										{:else}
											<div class="flex-1 overflow-auto text-sm p-2">
												<MarkdownRenderer content={editingTextContent} />
											</div>
											{/if}
										</div>
								{:else}
								<!-- svelte-ignore a11y_no_static_element_interactions -->
								<div
									class="group/text relative h-full overflow-auto p-4 prose prose-sm dark:prose-invert max-w-none {editMode ? 'cursor-text' : ''}"
									role="textbox"
									tabindex="0"
									aria-readonly={!editMode}
									ondblclick={() => editMode && startEditText(layoutItem)}
								>
										{#if layoutItem.textConfig?.content}
											<MarkdownRenderer content={layoutItem.textConfig.content} />
										{:else}
												<span class="text-muted-foreground italic text-sm">
													{editMode ? 'Double-click to add text...' : 'No content'}
												</span>
											{/if}
										{#if editMode}
											<div class="absolute right-2 top-2 opacity-0 group-hover/text:opacity-100 transition-opacity">
												<DropdownMenu>
													<DropdownMenuTrigger>
														<button class="p-0.5 rounded hover:bg-accent text-muted-foreground bg-background shadow-sm border border-border" onclick={(e) => e.stopPropagation()}>
															<MoreVertical size={14} />
														</button>
													</DropdownMenuTrigger>
													<DropdownMenuContent align="end" class="min-w-[100px]">
														<DropdownMenuItem onclick={() => startEditText(layoutItem)}>
															<Pencil size={14} class="mr-2" />Edit
														</DropdownMenuItem>
														<DropdownMenuItem class="text-destructive" onclick={() => handleGridRemove(layoutItem.id)}>
															<Trash2 size={14} class="mr-2" />Delete
														</DropdownMenuItem>
													</DropdownMenuContent>
												</DropdownMenu>
											</div>
										{/if}
										</div>
									{/if}

								{:else if layoutItem.type === 'tabs'}
									<!-- ─── Tabs Element ─── -->
									{@const config = layoutItem.tabsConfig}
									{#if config}
										<div class="flex flex-col h-full">
											<!-- Tab bar -->
											<div class="flex items-center border-b border-border px-2 shrink-0 overflow-x-auto">
												{#each config.tabs as tab (tab.id)}
													{#if editingTabTitleId === tab.id}
														<!-- svelte-ignore a11y_autofocus -->
														<input
															bind:value={editingTabTitle}
															class="px-3 py-2 text-xs font-medium bg-transparent border-b-2 border-primary focus:outline-none min-w-[60px] max-w-[120px]"
															onblur={() => handleTabTitleSave(layoutItem.id, tab.id)}
															onkeydown={(e) => { if (e.key === 'Enter') handleTabTitleSave(layoutItem.id, tab.id); if (e.key === 'Escape') { editingTabTitleId = null; } }}
															autofocus
														/>
													{:else}
														<!-- svelte-ignore a11y_no_static_element_interactions -->
														<div
															class="flex items-center shrink-0 group/tab"
															ondragover={(e) => {
																const types = e.dataTransfer?.types || [];
																if (types.includes('application/tab-chart-reorder') || types.includes('application/dashboard-chart') || types.includes('application/grid-chart')) {
																	e.preventDefault();
																	e.stopPropagation();
																	if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
																	(e.currentTarget as HTMLElement).classList.add('bg-primary/10', 'rounded');
																}
															}}
															ondragleave={(e) => {
																const el = e.currentTarget as HTMLElement;
																const related = e.relatedTarget as HTMLElement | null;
																if (related && el.contains(related)) return;
																el.classList.remove('bg-primary/10', 'rounded');
															}}
															ondrop={(e) => {
																e.preventDefault();
																e.stopPropagation();
																(e.currentTarget as HTMLElement).classList.remove('bg-primary/10', 'rounded');
																// Handle chart from main grid dragged into tab
																const gridChartData = e.dataTransfer?.getData('application/grid-chart');
																if (gridChartData) {
																	try {
																		const { id: chartItemId } = JSON.parse(gridChartData);
																		handleMoveGridChartToTab(layoutItem.id, tab.id, chartItemId);
																	} catch { /* ignore */ }
																	return;
																}
																// Handle chart reorder from another tab
																const reorderData = e.dataTransfer?.getData('application/tab-chart-reorder');
																if (reorderData) {
																	try {
																		const { chartId, sourceTabId } = JSON.parse(reorderData);
																		if (sourceTabId && sourceTabId !== tab.id) {
																			handleMoveChartToTab(layoutItem.id, sourceTabId, tab.id, chartId);
																		} else if (config.activeTabId !== tab.id) {
																			handleMoveChartToTab(layoutItem.id, config.activeTabId, tab.id, chartId);
																		}
																	} catch { /* ignore */ }
																	return;
																}
																// Handle saved chart drop from sidebar
																const chartDataStr = e.dataTransfer?.getData('application/dashboard-chart');
																if (chartDataStr) {
																	handleDropChartIntoTab(layoutItem.id, tab.id, chartDataStr);
																}
															}}
														>
															<button
																class="px-3 py-2 text-xs font-medium transition-colors relative
																	{config.activeTabId === tab.id
																		? 'text-primary border-b-2 border-primary'
																		: 'text-muted-foreground hover:text-foreground border-b-2 border-transparent'}"
																onclick={() => handleTabChange(layoutItem.id, tab.id)}
																ondblclick={() => {
																	if (editMode) {
																		editingTabTitleId = tab.id;
																		editingTabTitle = tab.title;
																	}
																}}
															>
																{tab.title}
															</button>
															{#if editMode}
																<DropdownMenu>
																	<DropdownMenuTrigger class="p-0.5 rounded opacity-0 group-hover/tab:opacity-100 hover:bg-accent text-muted-foreground">
																		<ChevronDown size={10} />
																	</DropdownMenuTrigger>
																	<DropdownMenuContent align="start" class="min-w-[120px]">
																		<DropdownMenuItem onSelect={() => { editingTabTitleId = tab.id; editingTabTitle = tab.title; }} class="text-xs gap-2">
																			<Pencil size={10} /><span>Rename</span>
																		</DropdownMenuItem>
																		{@const tabIdx = config.tabs.findIndex(t => t.id === tab.id)}
																		{#if tabIdx > 0}
																			<DropdownMenuItem onSelect={() => handleMoveTab(layoutItem.id, tab.id, -1)} class="text-xs gap-2">
																				<span class="w-[10px] text-center">&larr;</span><span>Move Left</span>
																			</DropdownMenuItem>
																		{/if}
																		{#if tabIdx < config.tabs.length - 1}
																			<DropdownMenuItem onSelect={() => handleMoveTab(layoutItem.id, tab.id, 1)} class="text-xs gap-2">
																				<span class="w-[10px] text-center">&rarr;</span><span>Move Right</span>
																			</DropdownMenuItem>
																		{/if}
																		{#if config.tabs.length > 1}
																			<DropdownMenuSeparator />
																			<DropdownMenuItem destructive onSelect={() => handleRemoveTab(layoutItem.id, tab.id)} class="text-xs gap-2">
																				<Trash2 size={10} /><span>Delete</span>
																			</DropdownMenuItem>
																		{/if}
																	</DropdownMenuContent>
																</DropdownMenu>
															{/if}
														</div>
													{/if}
												{/each}
												{#if editMode}
													<button
														class="px-2 py-2 text-xs text-muted-foreground hover:text-foreground shrink-0"
														onclick={() => handleAddTab(layoutItem.id)}
														title="Add Tab"
													>
														<Plus size={12} />
													</button>
													<div class="ml-auto shrink-0">
														<DropdownMenu>
															<DropdownMenuTrigger>
																<button class="p-1 rounded hover:bg-accent text-muted-foreground" title="Tabs options">
																	<MoreVertical size={14} />
																</button>
															</DropdownMenuTrigger>
															<DropdownMenuContent align="end" class="min-w-[120px]">
																<DropdownMenuItem class="text-destructive text-xs gap-2" onclick={() => handleGridRemove(layoutItem.id)}>
																	<Trash2 size={12} />Delete Tabs
																</DropdownMenuItem>
															</DropdownMenuContent>
														</DropdownMenu>
													</div>
												{/if}
											</div>

											<!-- Active tab content -->
											{#if config.tabs.find(t => t.id === config.activeTabId)}
											{@const activeTab = config.tabs.find(t => t.id === config.activeTabId)!}
												{@const chartsInTab = getChartsInTab(activeTab.children)}
												<!-- svelte-ignore a11y_no_static_element_interactions -->
												<div
													class="flex-1 overflow-auto p-3"
													ondragover={(e) => {
														const types = e.dataTransfer?.types || [];
														if (types.includes('application/dashboard-chart') || types.includes('application/tab-chart-reorder') || types.includes('application/grid-chart')) {
															e.preventDefault();
															e.stopPropagation();
															if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
														}
													}}
													ondrop={(e) => {
														e.preventDefault();
														e.stopPropagation();
														// Handle grid chart dragged into tab content
														const gridChartData = e.dataTransfer?.getData('application/grid-chart');
														if (gridChartData && activeTab) {
															try {
																const { id: chartItemId } = JSON.parse(gridChartData);
																handleMoveGridChartToTab(layoutItem.id, activeTab.id, chartItemId);
															} catch { /* ignore */ }
															return;
														}
														const chartDataStr = e.dataTransfer?.getData('application/dashboard-chart');
														if (chartDataStr && activeTab) {
															handleDropChartIntoTab(layoutItem.id, activeTab.id, chartDataStr);
															return;
														}
														const itemData = e.dataTransfer?.getData('application/tab-chart-reorder');
														if (itemData && activeTab) {
															try {
																const { chartId } = JSON.parse(itemData);
																handleReorderChartInTab(layoutItem.id, activeTab.id, chartId, chartsInTab.length);
															} catch { /* ignore */ }
														}
													}}
												>
												{#if chartsInTab.length > 0}
													{@const tabContentW = gridContainerWidth - 26}
													{@const tabColUnit = tabContentW / 12}
													<div class="flex flex-wrap gap-3">
														{#each chartsInTab as chartItem, chartIndex (chartItem.id)}
															{@const chartPixelWidth = Math.max(200, chartItem.width * tabColUnit - 12)}
																<!-- svelte-ignore a11y_no_static_element_interactions -->
																<div
																	id="tabchart-{chartItem.id}"
																	class="relative rounded-lg border border-border bg-background overflow-hidden group/tabchart shrink-0 transition-shadow {editMode ? 'hover:ring-2 hover:ring-primary/30' : ''}"
																	style="width: {chartPixelWidth}px; height: {chartItem.height || 240}px;"
																	draggable={editMode}
																	ondragstart={(e) => {
																		if (!editMode || !e.dataTransfer) return;
																		e.dataTransfer.setData('application/tab-chart-reorder', JSON.stringify({ chartId: chartItem.id, sourceTabsId: layoutItem.id, sourceTabId: activeTab.id }));
																		e.dataTransfer.setData('text/plain', chartItem.chart?.name || '');
																		e.dataTransfer.effectAllowed = 'all';
																	}}
																	ondragover={(e) => {
																		const types = e.dataTransfer?.types || [];
																		if (types.includes('application/tab-chart-reorder') || types.includes('application/dashboard-chart') || types.includes('application/grid-chart')) {
																			e.preventDefault();
																			e.stopPropagation();
																			if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
																		}
																	}}
																	ondrop={(e) => {
																		e.preventDefault();
																		e.stopPropagation();
																		// Handle grid chart dropped onto a tab chart
																		const gridChartData = e.dataTransfer?.getData('application/grid-chart');
																		if (gridChartData && activeTab) {
																			try {
																				const { id: chartItemId } = JSON.parse(gridChartData);
																				handleMoveGridChartToTab(layoutItem.id, activeTab.id, chartItemId);
																			} catch { /* ignore */ }
																			return;
																		}
																		const reorderData = e.dataTransfer?.getData('application/tab-chart-reorder');
																		if (reorderData && activeTab) {
																			try {
																				const { chartId } = JSON.parse(reorderData);
																				if (chartId !== chartItem.id) {
																					handleReorderChartInTab(layoutItem.id, activeTab.id, chartId, chartIndex);
																				}
																			} catch { /* ignore */ }
																			return;
																		}
																		const chartDataStr = e.dataTransfer?.getData('application/dashboard-chart');
																		if (chartDataStr && activeTab) {
																			handleDropChartIntoTab(layoutItem.id, activeTab.id, chartDataStr);
																		}
																	}}
																>
																	{#if editMode}
																		<!-- Drag handle for tab chart -->
																		<div class="absolute top-1 left-1/2 -translate-x-1/2 flex items-center justify-center cursor-grab active:cursor-grabbing z-10 opacity-0 group-hover/tabchart:opacity-60 hover:!opacity-100 transition-opacity">
																			<svg class="w-3 h-3 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
																				<circle cx="8" cy="6" r="1.5"/><circle cx="16" cy="6" r="1.5"/>
																				<circle cx="8" cy="12" r="1.5"/><circle cx="16" cy="12" r="1.5"/>
																				<circle cx="8" cy="18" r="1.5"/><circle cx="16" cy="18" r="1.5"/>
																			</svg>
																		</div>
																	{/if}
																	<div class="flex items-center justify-between border-b border-border px-2 py-1.5 shrink-0">
																		<span class="text-xs font-medium truncate text-foreground">{chartItem.chart?.name || 'Untitled'}</span>
																		<div class="flex items-center gap-0.5 opacity-0 group-hover/tabchart:opacity-100 transition-opacity shrink-0">
																			<DropdownMenu>
																				<DropdownMenuTrigger class="p-0.5 rounded hover:bg-accent text-muted-foreground">
																					<MoreVertical size={12} />
																				</DropdownMenuTrigger>
																				<DropdownMenuContent align="end" class="min-w-[130px]">
																					<DropdownMenuItem onSelect={() => { editingChart = chartItem.chart || null; showChartModal = true; }} class="text-xs gap-2">
																						<Pencil size={10} /><span>Edit</span>
																					</DropdownMenuItem>
																					<DropdownMenuItem
																						onSelect={() => {
																							const el = document.getElementById(`tabchart-${chartItem.id}`);
																							if (el) handleExportChart(el, chartItem.chart?.name || 'chart', chartItem.chartSchema?.chartType);
																							else notify.warning('Chart is not ready yet.');
																						}}
																						class="text-xs gap-2"
																					>
																						<ImageDown size={10} /><span>Export Image</span>
																					</DropdownMenuItem>
																					{#if editMode}
																						<DropdownMenuSeparator />
																						<DropdownMenuItem destructive onSelect={() => activeTab && handleRemoveChartFromTab(layoutItem.id, activeTab.id, chartItem.id)} class="text-xs gap-2">
																							<X size={10} /><span>Remove from Tab</span>
																						</DropdownMenuItem>
																					{/if}
																				</DropdownMenuContent>
																			</DropdownMenu>
																		</div>
																	</div>
																	<div class="h-[calc(100%-32px)]">
																		{#if chartItem.chartSchema}
																			<DashboardChartRenderer chartSchema={chartItem.chartSchema} height="100%" />
																		{/if}
																	</div>

																	{#if editMode}
																		<!-- Right edge resize -->
																		<!-- svelte-ignore a11y_no_static_element_interactions -->
																		<div
																			class="absolute top-0 right-0 w-1.5 h-full cursor-col-resize z-20 hover:bg-primary/20 transition-colors"
																			onmousedown={(e) => {
																				e.preventDefault();
																				e.stopPropagation();
																				const startX = e.clientX;
																				const startW = chartItem.width;
																				const colUnit = (gridContainerWidth - 26) / 12;
																				const move = (ev: MouseEvent) => {
																					const dx = ev.clientX - startX;
																					const colDelta = Math.round(dx / colUnit);
																					const newW = Math.max(2, Math.min(12, startW + colDelta));
																					handleTabChartResize(chartItem.id, newW, chartItem.height || 240);
																				};
																				const up = () => {
																					window.removeEventListener('mousemove', move);
																					window.removeEventListener('mouseup', up);
																				};
																				window.addEventListener('mousemove', move);
																				window.addEventListener('mouseup', up);
																			}}
																		></div>

																		<!-- Bottom edge resize -->
																		<!-- svelte-ignore a11y_no_static_element_interactions -->
																		<div
																			class="absolute bottom-0 left-0 h-1.5 w-full cursor-row-resize z-20 hover:bg-primary/20 transition-colors"
																			onmousedown={(e) => {
																				e.preventDefault();
																				e.stopPropagation();
																				const startY = e.clientY;
																				const startH = chartItem.height || 240;
																				const move = (ev: MouseEvent) => {
																					const dy = ev.clientY - startY;
																					const newH = Math.max(120, Math.round((startH + dy) / 8) * 8);
																					handleTabChartResize(chartItem.id, chartItem.width, newH);
																				};
																				const up = () => {
																					window.removeEventListener('mousemove', move);
																					window.removeEventListener('mouseup', up);
																				};
																				window.addEventListener('mousemove', move);
																				window.addEventListener('mouseup', up);
																			}}
																		></div>

																		<!-- Corner resize -->
																		<!-- svelte-ignore a11y_no_static_element_interactions -->
																		<div
																			class="absolute bottom-0 right-0 w-4 h-4 cursor-se-resize z-20"
																			onmousedown={(e) => {
																				e.preventDefault();
																				e.stopPropagation();
																				const startX = e.clientX;
																				const startY = e.clientY;
																				const startW = chartItem.width;
																				const startH = chartItem.height || 240;
																				const colUnit = (gridContainerWidth - 26) / 12;
																				const move = (ev: MouseEvent) => {
																					const dx = ev.clientX - startX;
																					const dy = ev.clientY - startY;
																					const colDelta = Math.round(dx / colUnit);
																					const newW = Math.max(2, Math.min(12, startW + colDelta));
																					const newH = Math.max(120, Math.round((startH + dy) / 8) * 8);
																					handleTabChartResize(chartItem.id, newW, newH);
																				};
																				const up = () => {
																					window.removeEventListener('mousemove', move);
																					window.removeEventListener('mouseup', up);
																				};
																				window.addEventListener('mousemove', move);
																				window.addEventListener('mouseup', up);
																			}}
																		>
																			<svg class="w-4 h-4 text-muted-foreground/50" viewBox="0 0 24 24" fill="currentColor">
																				<path d="M22 22H20V20H22V22ZM22 18H18V22H16V16H22V18ZM18 14H14V18H12V12H18V14Z"/>
																			</svg>
																		</div>
																	{/if}
																</div>
															{/each}
														</div>
													{:else}
														<!-- Empty tab: drop zone + add button -->
														<!-- svelte-ignore a11y_no_static_element_interactions -->
														<div
															class="flex flex-col items-center justify-center py-8 text-center rounded-lg border-2 border-dashed border-border transition-colors"
															ondragover={(e) => {
																const types = e.dataTransfer?.types || [];
																if (types.includes('application/dashboard-chart') || types.includes('application/tab-chart-reorder') || types.includes('application/grid-chart')) {
																	e.preventDefault();
																	if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
																	(e.currentTarget as HTMLElement).classList.add('border-primary', 'bg-primary/5');
																	(e.currentTarget as HTMLElement).classList.remove('border-border');
																}
															}}
															ondragleave={(e) => {
																(e.currentTarget as HTMLElement).classList.remove('border-primary', 'bg-primary/5');
																(e.currentTarget as HTMLElement).classList.add('border-border');
															}}
															ondrop={(e) => {
																e.preventDefault();
																e.stopPropagation();
																(e.currentTarget as HTMLElement).classList.remove('border-primary', 'bg-primary/5');
																(e.currentTarget as HTMLElement).classList.add('border-border');
																// Handle grid chart dragged into empty tab
																const gridChartData = e.dataTransfer?.getData('application/grid-chart');
																if (gridChartData && activeTab) {
																	try {
																		const { id: chartItemId } = JSON.parse(gridChartData);
																		handleMoveGridChartToTab(layoutItem.id, activeTab.id, chartItemId);
																	} catch { /* ignore */ }
																	return;
																}
																const chartDataStr = e.dataTransfer?.getData('application/dashboard-chart');
																if (chartDataStr && activeTab) {
																	handleDropChartIntoTab(layoutItem.id, activeTab.id, chartDataStr);
																	return;
																}
																const reorderData = e.dataTransfer?.getData('application/tab-chart-reorder');
																if (reorderData && activeTab) {
																	try {
																		const { chartId, sourceTabId } = JSON.parse(reorderData);
																		if (sourceTabId !== activeTab.id) {
																			handleMoveChartToTab(layoutItem.id, sourceTabId, activeTab.id, chartId);
																		}
																	} catch { /* ignore */ }
																}
															}}
														>
															<p class="text-xs text-muted-foreground mb-2">{editMode ? 'Drag charts here or add from below' : 'No charts in this tab'}</p>
															{#if editMode}
																{@const available = getAvailableChartsForTab()}
																{#if available.length > 0}
																	<DropdownMenu>
																		<DropdownMenuTrigger class="inline-flex items-center gap-1 px-3 py-1.5 text-xs rounded-md border border-dashed border-primary/30 text-primary hover:bg-primary/5">
																			<Plus size={12} /> Add Chart
																		</DropdownMenuTrigger>
																		<DropdownMenuContent class="min-w-[180px] max-h-[200px] overflow-auto">
																			{#each available as chartItem}
																				<DropdownMenuItem onSelect={() => activeTab && handleAddChartToTab(layoutItem.id, activeTab.id, chartItem.id)} class="text-xs">
																					{chartItem.chart?.name || chartItem.title || 'Untitled'}
																				</DropdownMenuItem>
																			{/each}
																		</DropdownMenuContent>
																	</DropdownMenu>
																{:else}
																	<p class="text-[10px] text-muted-foreground mb-1">No available charts</p>
																{/if}
																<button
																	class="text-[10px] text-primary hover:underline mt-1"
																	onclick={() => { showChartModal = true; editingChart = null; }}
																>or create a new chart</button>
															{/if}
														</div>
													{/if}
													{#if editMode && chartsInTab.length > 0}
														{@const available = getAvailableChartsForTab()}
														{#if available.length > 0}
															<div class="mt-3 pt-3 border-t border-border">
																<DropdownMenu>
																	<DropdownMenuTrigger class="inline-flex items-center gap-1 px-3 py-1.5 text-xs rounded-md border border-dashed border-primary/30 text-primary hover:bg-primary/5">
																		<Plus size={12} /> Add Chart to Tab
																	</DropdownMenuTrigger>
																	<DropdownMenuContent class="min-w-[180px] max-h-[200px] overflow-auto">
																		{#each available as chartItem}
																			<DropdownMenuItem onSelect={() => activeTab && handleAddChartToTab(layoutItem.id, activeTab.id, chartItem.id)} class="text-xs">
																				{chartItem.chart?.name || chartItem.title || 'Untitled'}
																			</DropdownMenuItem>
																		{/each}
																	</DropdownMenuContent>
																</DropdownMenu>
															</div>
														{/if}
													{/if}
												</div>
											{/if}
										</div>
									{/if}

								{:else if layoutItem.type === 'chart'}
									<!-- ─── Chart Element ─── -->
									{@const chart = layoutItem.chart}
									<div class="flex flex-col h-full group/chart" id="chart-{layoutItem.chartId}">
										<div class="flex items-center justify-between border-b border-border px-3 py-2 shrink-0">
											{#if editingChartId === layoutItem.id}
												<!-- svelte-ignore a11y_autofocus -->
												<input
													bind:value={editingChartTitle}
													class="flex-1 text-sm font-medium bg-transparent border-none focus:outline-none text-foreground"
													onblur={saveChartTitle}
													onkeydown={(e) => { if (e.key === 'Enter') saveChartTitle(); if (e.key === 'Escape') { editingChartId = null; } }}
													autofocus
												/>
											{:else}
												<!-- svelte-ignore a11y_no_static_element_interactions -->
												<span
													class="text-sm font-medium truncate text-foreground {editMode ? 'cursor-pointer hover:text-primary' : ''}"
													role="button"
													tabindex={editMode ? 0 : -1}
													ondblclick={() => editMode && startEditChartTitle(layoutItem)}
													title={editMode ? 'Double-click to edit' : undefined}
												>{chart?.name || layoutItem.title || 'Untitled'}</span>
											{/if}
											<div class="flex items-center gap-0.5 opacity-0 group-hover/chart:opacity-100 transition-opacity shrink-0">
												<DropdownMenu>
													<DropdownMenuTrigger class="p-1 rounded hover:bg-accent text-muted-foreground">
														<MoreVertical size={14} />
													</DropdownMenuTrigger>
													<DropdownMenuContent align="end" class="min-w-[140px]">
														<DropdownMenuItem onSelect={() => openEditChart(layoutItem)} class="text-xs gap-2">
															<Pencil size={12} /><span>Edit</span>
														</DropdownMenuItem>
														<DropdownMenuItem onSelect={() => {
															const el = document.getElementById(`chart-${layoutItem.chartId}`);
															if (el) handleExportChart(el, chart?.name || 'chart', layoutItem.chartSchema?.chartType);
															else notify.warning('Chart is not ready yet.');
														}} class="text-xs gap-2">
															<ImageDown size={12} /><span>Export to image</span>
														</DropdownMenuItem>
														<DropdownMenuSeparator />
														<DropdownMenuItem destructive onSelect={() => handleGridRemove(layoutItem.id)} class="text-xs gap-2">
															<Trash2 size={12} /><span>Delete</span>
														</DropdownMenuItem>
													</DropdownMenuContent>
												</DropdownMenu>
											</div>
										</div>
									<div class="flex-1 min-h-0 px-1 pb-1 relative">
										{#if refreshingItemIds.has(layoutItem.id)}
											<div class="absolute inset-0 z-10 flex items-center justify-center bg-background/60 backdrop-blur-[1px] rounded-md">
												<Loader2 size={20} class="animate-spin text-muted-foreground" />
											</div>
										{/if}
										{#if layoutItem.chartSchema}
											<DashboardChartRenderer chartSchema={layoutItem.chartSchema} height="100%" />
										{:else if chart}
												{@const option = getChartOption(chart)}
												{#if option}
													{#await import('$lib/components/ECharts/ECharts.svelte') then { default: ECharts }}
														<ECharts {option} height="100%" />
													{/await}
												{:else}
													<div class="flex items-center justify-center h-full text-xs text-muted-foreground">No chart data</div>
												{/if}
											{:else}
												<div class="flex items-center justify-center h-full text-xs text-muted-foreground">
													{layoutItem.title || 'Chart'}
												</div>
											{/if}
										</div>
									</div>
								{/if}
							{/snippet}
						</DashboardGrid>
				</div>

				<!-- ═══════ AI Chat Side Panel ═══════ -->
				{#if showAIChat}
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div
						class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isAiChatPanelResizing ? 'bg-primary/30' : ''}"
						onmousedown={handleAiChatPanelResizeStart}
					></div>
					<div
						class="shrink-0 border-l border-border overflow-hidden flex flex-col"
						style="width: {aiChatPanelWidth}px; min-width: {aiChatPanelWidth}px;"
					>
						<div class="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
							<div class="flex items-center gap-2">
								<AISparkleIcon size={16} />
								<span class="text-sm font-semibold">AI Chat</span>
							</div>
							<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => showAIChat = false}>
								<X size={14} />
							</button>
						</div>
						<div class="flex-1 overflow-hidden">
							{#await import('$lib/components/EmbeddedAIChat/EmbeddedAIChat.svelte') then { default: EmbeddedAIChat }}
								<EmbeddedAIChat />
							{:catch}
								<div class="flex flex-col items-center justify-center h-full p-4 text-center">
									<MessageSquare size={24} class="text-muted-foreground/30 mb-3" />
									<p class="text-xs text-muted-foreground mb-2">AI Chat is available on the dedicated AI Chat page</p>
									<a href="/ai-chat" class="text-xs text-primary hover:underline">Go to AI Chat</a>
								</div>
							{/await}
						</div>
					</div>
				{/if}
			</div>
		{/if}
	</div>
</div>

<!-- ═══════ Create/Edit Dashboard Modal ═══════ -->
{#if showCreateModal}
	<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" role="dialog">
		<Card class="w-full max-w-md p-6">
			<div class="flex items-center justify-between mb-4">
				<h2 class="text-lg font-semibold">{editingDashboard ? 'Edit Dashboard' : 'New Dashboard'}</h2>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => { showCreateModal = false; editingDashboard = null; }}>
					<X size={16} />
				</button>
			</div>
			<div class="space-y-3">
				<div class="space-y-1.5">
					<label class="text-sm font-medium" for="dash-name">Name <span class="text-destructive">*</span></label>
					<input
						id="dash-name"
						bind:value={newDashboardName}
						placeholder="Dashboard name"
						class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleCreateOrUpdateDashboard(); } }}
						required
					/>
				</div>
				<div class="space-y-1.5">
					<label class="text-sm font-medium" for="dash-desc">Description</label>
					<textarea
						id="dash-desc"
						bind:value={newDashboardDesc}
						placeholder="Optional description"
						class="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring min-h-[80px]"
					></textarea>
				</div>
				<div class="space-y-1.5">
					<span class="text-sm font-medium">Auto Refresh</span>
					<div class="relative">
						<button
							type="button"
							class="flex items-center justify-between w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
							onclick={() => showRefreshDropdown = !showRefreshDropdown}
						>
							<span>{REFRESH_OPTIONS.find(r => r.value === newDashboardRefreshRule)?.label ?? 'None'}</span>
							<ChevronDown size={14} class="text-muted-foreground" />
						</button>
						{#if showRefreshDropdown}
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div class="fixed inset-0 z-50" onclick={() => showRefreshDropdown = false} role="none"></div>
							<div class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-popover shadow-md py-1">
								{#each REFRESH_OPTIONS as opt}
									<button
										type="button"
										class="flex items-center w-full px-3 py-2 text-sm hover:bg-accent transition-colors {newDashboardRefreshRule === opt.value ? 'bg-accent/50 font-medium' : ''}"
										onclick={() => { newDashboardRefreshRule = opt.value as typeof newDashboardRefreshRule; showRefreshDropdown = false; }}
									>
										{opt.label}
									</button>
								{/each}
							</div>
						{/if}
					</div>
				</div>
			</div>
			<div class="flex gap-2 justify-end mt-6">
				<Button variant="ghost" onclick={() => { showCreateModal = false; editingDashboard = null; }}>Cancel</Button>
				<Button onclick={handleCreateOrUpdateDashboard} disabled={!newDashboardName.trim() || savingDashboard}>{editingDashboard ? 'Save' : 'Create'}</Button>
			</div>
		</Card>
	</div>
{/if}

<!-- ═══════ AI Summary Modal ═══════ -->
{#if showSummaryModal}
	<div class="fixed inset-0 z-50 flex items-center justify-center">
		<div class="absolute inset-0 bg-black/50" onclick={() => { showSummaryModal = false; showModelDropdown = false; showLanguageDropdown = false; }} role="none"></div>
		<Card class="relative w-full max-w-2xl max-h-[80vh] flex flex-col p-6 z-10">
			<div class="flex items-center justify-between mb-4 shrink-0">
				<div class="flex items-center gap-2">
					<Sparkles size={18} class="text-primary" />
					<h2 class="text-lg font-semibold">AI Summary</h2>
				</div>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => showSummaryModal = false}>
					<X size={16} />
				</button>
			</div>

			<!-- Model & Language Selection -->
			<div class="flex items-center gap-3 mb-4 shrink-0 flex-wrap">
				<!-- Model Dropdown (Custom) -->
				<div class="flex items-center gap-2">
					<span class="text-xs font-medium text-muted-foreground whitespace-nowrap">Model</span>
					<div class="relative">
						<button
							type="button"
							class="flex items-center gap-1.5 h-8 rounded-md border border-input bg-background px-2.5 py-1 text-xs hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50 disabled:pointer-events-none"
							disabled={summaryLoading}
							onclick={() => { showModelDropdown = !showModelDropdown; showLanguageDropdown = false; }}
						>
							<span>{{ GEMINI: 'Gemini', OPENAI: 'OpenAI', CLAUDEAI: 'Claude' }[summaryModel]}</span>
							<ChevronDown size={12} class="text-muted-foreground" />
						</button>
						{#if showModelDropdown}
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div class="fixed inset-0 z-50" onclick={() => showModelDropdown = false} role="none"></div>
							<div class="absolute top-full left-0 mt-1 z-50 min-w-[120px] rounded-md border border-border bg-popover shadow-md py-1">
								{#each [{ value: 'GEMINI', label: 'Gemini' }, { value: 'OPENAI', label: 'OpenAI' }, { value: 'CLAUDEAI', label: 'Claude' }] as model}
									<button
										type="button"
										class="flex items-center w-full px-3 py-1.5 text-xs hover:bg-accent transition-colors {summaryModel === model.value ? 'bg-accent/50 font-medium' : ''}"
										onclick={() => { summaryModel = model.value as typeof summaryModel; showModelDropdown = false; }}
									>
										{model.label}
									</button>
								{/each}
							</div>
						{/if}
					</div>
				</div>
				<!-- Language Dropdown (Custom) -->
				<div class="flex items-center gap-2">
					<div class="relative">
						<button
							type="button"
							class="flex items-center gap-1.5 h-8 rounded-md border border-input bg-background px-2.5 py-1 text-xs hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50 disabled:pointer-events-none"
							disabled={summaryLoading}
							onclick={() => { showLanguageDropdown = !showLanguageDropdown; showModelDropdown = false; }}
						>
							<span>{LANGUAGE_OPTIONS.find(l => l.value === summaryLanguage)?.flag ?? ''}</span>
							<span>{LANGUAGE_OPTIONS.find(l => l.value === summaryLanguage)?.label ?? summaryLanguage}</span>
							<ChevronDown size={12} class="text-muted-foreground" />
						</button>
						{#if showLanguageDropdown}
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div class="fixed inset-0 z-50" onclick={() => showLanguageDropdown = false} role="none"></div>
							<div class="absolute top-full left-0 mt-1 z-50 min-w-[140px] rounded-md border border-border bg-popover shadow-md py-1">
								{#each LANGUAGE_OPTIONS as lang}
									<button
										type="button"
										class="flex items-center gap-2 w-full px-3 py-1.5 text-xs hover:bg-accent transition-colors {summaryLanguage === lang.value ? 'bg-accent/50 font-medium' : ''}"
										onclick={() => { summaryLanguage = lang.value; showLanguageDropdown = false; }}
									>
										<span>{lang.flag}</span>
										<span>{lang.label}</span>
									</button>
								{/each}
							</div>
						{/if}
					</div>
				</div>
				<Button variant="outline" size="sm" onclick={generateSummary} disabled={summaryLoading || (aiConfigLoaded && !aiConfigProvider)} class="ml-auto h-8 text-xs gap-1.5">
					{#if summaryLoading}
						<Loader2 size={12} class="animate-spin" />
					{:else}
						<Sparkles size={12} />
					{/if}
					{summaryContent ? 'Regenerate' : 'Generate Summary'}
				</Button>
			</div>

			<!-- Summary Content -->
			{#if summaryError}
				<div class="flex-1 flex flex-col items-center justify-center py-12 gap-3">
					<div class="text-destructive text-sm text-center">{summaryError}</div>
					<Button variant="outline" size="sm" onclick={generateSummary} class="text-xs gap-1.5">
						<RotateCcw size={12} />
						Retry
					</Button>
				</div>
			{:else if summaryLoading}
				<div class="flex-1 flex items-center justify-center py-12">
					<div class="flex flex-col items-center gap-3">
						<Loader2 size={24} class="animate-spin text-primary" />
						<p class="text-sm text-muted-foreground">Analyzing dashboard data...</p>
					</div>
				</div>
			{:else if summaryContent}
				<div class="flex-1 overflow-auto text-sm text-foreground border border-border rounded-lg p-4 bg-muted/30">
					<MarkdownRenderer content={summaryContent} />
				</div>
			{:else if aiConfigLoaded && !aiConfigProvider}
				<div class="flex-1 flex flex-col items-center justify-center py-12 gap-3">
					<div class="text-4xl">⚙️</div>
					<p class="text-sm font-medium text-foreground">No AI provider configured</p>
					<p class="text-xs text-muted-foreground text-center max-w-[300px]">
						Please configure an AI provider (OpenAI, Gemini, or Claude) in Settings to use this feature.
					</p>
				</div>
			{:else}
				<div class="flex-1 flex items-center justify-center py-12">
					<p class="text-sm text-muted-foreground">Click "Generate Summary" to analyze this dashboard.</p>
				</div>
			{/if}

			<!-- Footer -->
			<div class="flex items-center justify-between mt-4 shrink-0">
				<div class="flex items-center gap-3">
					{#if summaryContent && !summaryLoading}
						<Button variant="ghost" size="sm" onclick={copySummary} class="gap-1.5 text-xs">
							{#if summaryCopied}
								<Check size={12} class="text-green-500 dark:text-green-400" />
								<span class="text-green-500 dark:text-green-400">Copied!</span>
							{:else}
								<Copy size={12} />
								Copy
							{/if}
						</Button>
					{/if}
				</div>
				<div class="flex items-center gap-3">
					<span class="text-[10px] text-muted-foreground">
						Analyzing {layoutItems.filter(i => i.type === 'chart').length} chart{layoutItems.filter(i => i.type === 'chart').length !== 1 ? 's' : ''} in "{activeDashboard?.name}"
					</span>
					<Button variant="outline" size="sm" onclick={() => showSummaryModal = false}>Close</Button>
				</div>
			</div>
		</Card>
	</div>
{/if}

<!-- ═══════ Chart Creation/Edit Modal ═══════ -->
{#if showChartModal}
	{#await import('$lib/components/ChartModal/ChartModal.svelte') then { default: ChartModal }}
		<ChartModal
			chart={editingChart}
			onclose={() => { showChartModal = false; editingChart = null; }}
			onsave={async (data) => {
				try {
					if (data.id) {
						// Editing existing chart
						await dashboardService.updateChart({
							id: data.id,
							name: data.name,
							schema: data.schema,
							dataSourceId: data.dataSourceId,
							databaseName: data.databaseName,
						});

						// Optimistic update: immediately reflect changes in the UI
						try {
							const updatedSchema: ChartSchema = JSON.parse(data.schema);
							if (data.dataSourceId) updatedSchema.dataSourceId = data.dataSourceId;
							if (data.databaseName) updatedSchema.databaseName = data.databaseName;
							layoutItems = layoutItems.map(item => {
								if (item.type === 'chart' && item.chartId === data.id) {
									return {
										...item,
										chartSchema: updatedSchema,
										chart: item.chart ? { ...item.chart, name: data.name, schema: data.schema } : item.chart,
									};
								}
								return item;
							});
						} catch { /* ignore parse error */ }
					} else {
						// Creating new chart
						const chartId = await dashboardService.createChart({
							name: data.name,
							schema: data.schema,
							dataSourceId: data.dataSourceId,
							databaseName: data.databaseName,
							sourceType: 'DASHBOARD'
						});

						// Add chart to current dashboard layout
						if (activeDashboardId && typeof chartId === 'number') {
							let chartSchema: ChartSchema | undefined;
							try { chartSchema = JSON.parse(data.schema); } catch { /* ignore */ }

							const maxY = layoutItems.length > 0 ? Math.max(...layoutItems.map(i => i.y)) + 1 : 0;
							const newItem: LayoutItem = {
								id: `chart-${chartId}`, type: 'chart',
								chartId, chartSchema,
								x: 0, y: maxY, width: DEFAULT_CHART_WIDTH, height: DEFAULT_CHART_HEIGHT
							};
							layoutItems = [...layoutItems, newItem];
							await saveLayout([...layoutItems]);
						}
					}

					showChartModal = false;
					editingChart = null;
					await loadSavedCharts();
					if (!data.id) {
						await fetchDashboardDetails();
					}
				} catch (e) { console.error('Failed to save chart:', e); }
			}}
		/>
	{/await}
{/if}

<!-- ═══════ Confirm Modal ═══════ -->
{#if confirmModal.show}
	<div class="fixed inset-0 z-50 flex items-center justify-center">
		<div class="absolute inset-0 bg-black/50" onclick={() => confirmModal.show = false} role="none"></div>
		<Card class="relative w-full max-w-sm p-6 z-10">
			<div class="flex items-center gap-3 mb-4">
				<div class="flex items-center justify-center w-10 h-10 rounded-full bg-destructive/10">
					<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-destructive"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
				</div>
				<div>
					<h3 class="text-sm font-semibold text-foreground">{confirmModal.title}</h3>
					<p class="text-xs text-muted-foreground mt-0.5">{confirmModal.message}</p>
				</div>
			</div>
			<div class="flex gap-2 justify-end">
				<Button variant="ghost" size="sm" onclick={() => confirmModal.show = false}>Cancel</Button>
				<Button variant="destructive" size="sm" onclick={confirmModal.onConfirm}>Delete</Button>
			</div>
		</Card>
	</div>
{/if}

<!-- ═══════ Auto-refresh Popover ═══════ -->
{#if showRefreshDropdown}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-40" role="presentation" onclick={() => showRefreshDropdown = false}></div>
	<div
		class="fixed z-50 min-w-[160px] rounded-md border border-border bg-popover p-1 shadow-lg"
		style="top: {moreMenuBtnEl ? moreMenuBtnEl.getBoundingClientRect().bottom + 4 : 60}px; right: {moreMenuBtnEl ? window.innerWidth - moreMenuBtnEl.getBoundingClientRect().right : 16}px;"
	>
		<div class="px-2 py-1.5 text-[11px] text-muted-foreground font-semibold">Auto-refresh interval</div>
		{#each [{ value: 'NONE', label: 'Off' }, { value: '1MIN', label: 'Every 1 min' }, { value: '10MIN', label: 'Every 10 min' }, { value: '1HOUR', label: 'Every 1 hour' }, { value: '1DAY', label: 'Every 1 day' }] as opt}
			<button
				class="flex items-center justify-between w-full px-3 py-1.5 text-xs rounded-sm hover:bg-accent text-left transition-colors
					{refreshRule === opt.value ? 'text-primary font-medium' : ''}"
				onclick={() => setRefreshRule(opt.value as typeof refreshRule)}
			>
				{opt.label}
				{#if refreshRule === opt.value}
					<Check size={12} class="text-primary" />
				{/if}
			</button>
		{/each}
	</div>
{/if}

<!-- ═══════ Share Modal ═══════ -->
{#if showShareModal && activeDashboard}
	<div class="fixed inset-0 z-50 flex items-center justify-center">
		<div class="absolute inset-0 bg-black/50" onclick={() => showShareModal = false} role="none"></div>
		<Card class="relative w-full max-w-md p-0 z-10 overflow-hidden">
			<div class="flex items-center justify-between p-4 border-b border-border">
				<div class="flex items-center gap-2">
					<Globe size={16} class="text-muted-foreground" />
					<h3 class="text-sm font-semibold text-foreground">Share Dashboard</h3>
				</div>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => showShareModal = false}>
					<X size={14} />
				</button>
			</div>
			<div class="p-4 space-y-4">
				<div class="flex items-center justify-between">
					<div>
						<p class="text-sm font-medium text-foreground">Public Access</p>
						<p class="text-xs text-muted-foreground mt-0.5">Anyone with the link can view this dashboard (read-only)</p>
					</div>
					<button
						class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring {activeDashboard.isPublic ? 'bg-green-500' : 'bg-muted-foreground/30'}"
						onclick={handleToggleShare}
						disabled={shareLoading}
						role="switch"
						aria-checked={activeDashboard.isPublic}
					>
						{#if shareLoading}
							<span class="absolute inset-0 flex items-center justify-center">
								<Loader2 size={12} class="animate-spin text-white" />
							</span>
						{:else}
							<span class="inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform {activeDashboard.isPublic ? 'translate-x-6' : 'translate-x-1'}" />
						{/if}
					</button>
				</div>

				{#if activeDashboard.isPublic && activeDashboard.shareToken}
					<div class="space-y-2">
						<label class="text-xs font-medium text-muted-foreground">Share Link</label>
						<div class="flex items-center gap-2">
							<div class="flex-1 flex items-center gap-2 bg-muted/50 rounded-md border border-border px-3 py-2 min-w-0">
								<Link size={12} class="shrink-0 text-muted-foreground" />
								<span class="text-xs text-foreground truncate">{getShareUrl()}</span>
							</div>
							<Button variant="outline" size="sm" class="shrink-0 gap-1" onclick={handleCopyShareLink}>
								{#if shareCopied}
									<Check size={12} class="text-green-500" />
									<span class="text-xs">Copied</span>
								{:else}
									<Copy size={12} />
									<span class="text-xs">Copy</span>
								{/if}
							</Button>
						</div>
					</div>
					<div class="flex gap-2">
						<Button variant="outline" size="sm" class="gap-1 flex-1" onclick={() => window.open(getShareUrl(), '_blank')}>
							<ExternalLink size={12} />
							Open Public View
						</Button>
					</div>
				{/if}

				<div class="bg-muted/30 rounded-md p-3 border border-border/50">
					<p class="text-xs text-muted-foreground leading-relaxed">
						Public dashboards are <strong class="text-foreground">read-only</strong>. Viewers cannot edit, refresh data, or access the underlying SQL queries. The shared view shows the most recent snapshot of your dashboard data.
					</p>
				</div>
			</div>
		</Card>
	</div>
{/if}

<!-- ═══════ Chart Hover Preview (Fixed position, outside overflow container) ═══════ -->
{#if hoveredChartId != null}
	{@const hoveredChart = savedCharts.find(c => c.id === hoveredChartId)}
	{#if hoveredChart?.schema}
		{@const previewSchema = (() => { try { return JSON.parse(hoveredChart.schema); } catch { return null; } })()}
		{#if previewSchema}
			{@const clampedY = Math.min(previewPosition.y, (typeof window !== 'undefined' ? window.innerHeight : 800) - 280)}
			<div
				class="fixed z-[9999] pointer-events-none"
				style="left: {previewPosition.x}px; top: {clampedY}px;"
			>
				<Card class="w-[360px] h-[260px] overflow-hidden shadow-xl border">
					<DashboardChartRenderer
						chartSchema={previewSchema}
						height="260px"
					/>
				</Card>
			</div>
		{/if}
	{/if}
{/if}
