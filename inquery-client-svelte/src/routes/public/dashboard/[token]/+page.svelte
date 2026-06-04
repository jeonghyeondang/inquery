<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { page } from '$app/state';
	import { Card } from '$lib/components/ui';
	import { LayoutDashboard, Loader2, Globe, AlertCircle, Sun, Moon } from 'lucide-svelte';
	import dashboardService, { type IDashboard, type IChart } from '$lib/service/dashboard';
	import { DashboardGrid } from '$lib/components/DashboardGrid';
	import type { IGridItem } from '$lib/stores/dashboard.svelte';
	import { DashboardChartRenderer } from '$lib/components/DashboardChartRenderer';
	import type { ChartSchema } from '$lib/components/DashboardChartRenderer/types';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import { generateChartOption, generateChartOptionWithConfig } from '$lib/utils/chartUtils';

	const GRID_BASE_UNIT = 8;
	const DEFAULT_CHART_WIDTH = 6;
	const DEFAULT_CHART_HEIGHT = 300;

	interface TabItem {
		id: string;
		title: string;
		children: string[];
	}

	interface TabsConfig {
		activeTabId: string;
		tabs: TabItem[];
	}

	interface LayoutItem extends IGridItem {
		chart?: IChart;
		chartSchema?: ChartSchema;
		headerConfig?: { text: string; size: 'small' | 'medium' | 'large'; backgroundColor: string };
		textConfig?: { content: string };
		tabsConfig?: TabsConfig;
	}

	let loading = $state(true);
	let error = $state<string | null>(null);
	let dashboard = $state<IDashboard | null>(null);
	let charts = $state<IChart[]>([]);
	let layoutItems = $state<LayoutItem[]>([]);
	let isDarkMode = $state(false);
	let themeObserver: MutationObserver | null = null;

	const HEADER_BG_COLORS = [
		{ value: 'transparent', label: 'Transparent' },
		{ value: 'white', light: '#ffffff', dark: 'rgba(255,255,255,0.08)' },
		{ value: 'gray', light: 'rgba(0,0,0,0.03)', dark: 'rgba(255,255,255,0.05)' },
		{ value: 'blue', light: '#e6f4ff', dark: '#1a3a5c' },
		{ value: 'purple', light: '#f9f0ff', dark: '#3d2a50' },
		{ value: 'green', light: '#f6ffed', dark: '#1e3a1e' },
		{ value: 'orange', light: '#fff7e6', dark: '#4a3520' },
	];

	function getHeaderBgColor(colorValue: string): string {
		if (colorValue === 'transparent' || !colorValue) return 'transparent';
		if (colorValue.startsWith('#')) {
			const hexMap: Record<string, string> = {
				'#ffffff': 'white', '#e6f4ff': 'blue', '#f9f0ff': 'purple',
				'#f6ffed': 'green', '#fff7e6': 'orange'
			};
			const mapped = hexMap[colorValue.toLowerCase()];
			if (mapped) return getHeaderBgColor(mapped);
			return colorValue;
		}
		const color = HEADER_BG_COLORS.find(c => c.value === colorValue);
		if (!color || !('light' in color)) return 'transparent';
		return isDarkMode ? color.dark! : color.light!;
	}

	function getHeaderSizeClass(size?: string): string {
		switch (size) {
			case 'small': return 'text-sm font-semibold';
			case 'large': return 'text-2xl font-bold';
			default: return 'text-base font-semibold';
		}
	}

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
					id: item.elementId || `chart-${item.chartId}-${Date.now()}`,
					type: 'chart',
					chartId: item.chartId, chart, chartSchema,
					x: item.x || 0, y: item.y || 0,
					width: item.w || DEFAULT_CHART_WIDTH,
					height: item.h ? item.h * GRID_BASE_UNIT * 4 : DEFAULT_CHART_HEIGHT
				});
			}
		});

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
					id: `chart-${chart.id}-${Date.now()}`,
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

	function toggleTheme() {
		isDarkMode = !isDarkMode;
		document.documentElement.classList.toggle('dark', isDarkMode);
		localStorage.setItem('theme', isDarkMode ? 'dark' : 'light');
	}

	onMount(async () => {
		isDarkMode = document.documentElement.classList.contains('dark') ||
			localStorage.getItem('theme') === 'dark' ||
			(!localStorage.getItem('theme') && window.matchMedia('(prefers-color-scheme: dark)').matches);
		document.documentElement.classList.toggle('dark', isDarkMode);

		themeObserver = new MutationObserver(() => {
			isDarkMode = document.documentElement.classList.contains('dark');
		});
		themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });

		const token = page.params.token;
		if (!token) {
			error = 'Invalid share link.';
			loading = false;
			return;
		}

		try {
			const result = await dashboardService.getPublicDashboard({ shareToken: token });
			dashboard = result.dashboard;
			charts = result.charts || [];

			if (dashboard?.schema) {
				try {
					const schema = JSON.parse(dashboard.schema);
					layoutItems = parseSchemaToLayoutItems(schema, charts);
				} catch {
					layoutItems = [];
				}
			}
		} catch (e: any) {
			error = 'This dashboard is not available. It may have been unshared or deleted.';
		} finally {
			loading = false;
		}
	});

	onDestroy(() => {
		themeObserver?.disconnect();
	});
</script>

<svelte:head>
	<title>{dashboard?.name ? `${dashboard.name} - Inquery` : 'Shared Dashboard - Inquery'}</title>
</svelte:head>

<div class="flex flex-col h-screen bg-background text-foreground">
	<!-- Header -->
	<header class="flex items-center justify-between h-12 px-6 border-b border-border bg-background shrink-0">
		<div class="flex items-center gap-3">
			<Globe size={16} class="text-primary" />
			<span class="text-sm font-semibold text-foreground">
				{dashboard?.name || 'Shared Dashboard'}
			</span>
			<span class="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground font-medium">
				READ-ONLY
			</span>
		</div>
		<div class="flex items-center gap-2">
			<button
				class="p-1.5 rounded-md hover:bg-accent text-muted-foreground transition-colors"
				onclick={toggleTheme}
				title={isDarkMode ? 'Switch to light mode' : 'Switch to dark mode'}
			>
				{#if isDarkMode}
					<Sun size={14} />
				{:else}
					<Moon size={14} />
				{/if}
			</button>
			<a
				href="/"
				class="text-xs text-muted-foreground hover:text-foreground transition-colors"
			>
				Powered by Inquery
			</a>
		</div>
	</header>

	<!-- Content -->
	{#if loading}
		<div class="flex-1 flex items-center justify-center">
			<div class="flex flex-col items-center gap-3">
				<Loader2 class="h-8 w-8 animate-spin text-primary" />
				<p class="text-sm text-muted-foreground">Loading dashboard...</p>
			</div>
		</div>
	{:else if error}
		<div class="flex-1 flex items-center justify-center">
			<Card class="p-8 max-w-sm text-center">
				<div class="flex justify-center mb-4">
					<div class="w-12 h-12 rounded-full bg-destructive/10 flex items-center justify-center">
						<AlertCircle size={24} class="text-destructive" />
					</div>
				</div>
				<h2 class="text-lg font-semibold text-foreground mb-2">Dashboard Not Available</h2>
				<p class="text-sm text-muted-foreground">{error}</p>
			</Card>
		</div>
	{:else if dashboard && layoutItems.length > 0}
		<div class="flex-1 overflow-y-auto p-6">
			<DashboardGrid
				items={layoutItems.filter(i => i.y >= 0)}
				editMode={false}
			>
				{#snippet children(item, rowItems, gridContainerWidth)}
					{@const layoutItem = item as LayoutItem}

					{#if layoutItem.type === 'header'}
						<div
							class="flex items-center h-full px-5 py-3 rounded-lg"
							style="background-color: {getHeaderBgColor(layoutItem.headerConfig?.backgroundColor || 'transparent')}"
						>
							<h3 class="{getHeaderSizeClass(layoutItem.headerConfig?.size)} text-foreground flex-1">
								{layoutItem.headerConfig?.text || 'Header'}
							</h3>
						</div>

					{:else if layoutItem.type === 'divider'}
						<div class="flex items-center justify-center h-full px-4 py-2">
							<div class="w-full border-t border-border"></div>
						</div>

					{:else if layoutItem.type === 'text'}
						<div class="h-full overflow-auto p-4 prose prose-sm dark:prose-invert max-w-none">
							{#if layoutItem.textConfig?.content}
								<MarkdownRenderer content={layoutItem.textConfig.content} />
							{:else}
								<span class="text-muted-foreground italic text-sm">No content</span>
							{/if}
						</div>

					{:else if layoutItem.type === 'tabs'}
						{@const config = layoutItem.tabsConfig}
						{#if config}
							<div class="flex flex-col h-full">
								<div class="flex items-center border-b border-border px-2 shrink-0 overflow-x-auto">
									{#each config.tabs as tab (tab.id)}
										<button
											class="px-3 py-2 text-xs font-medium whitespace-nowrap transition-colors border-b-2
												{config.activeTabId === tab.id
													? 'border-primary text-primary'
													: 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'}"
											onclick={() => {
												if (config) config.activeTabId = tab.id;
											}}
										>
											{tab.title}
										</button>
									{/each}
								</div>
								<div class="flex-1 overflow-auto p-2">
									{#each config.tabs as tab (tab.id)}
										{#if config.activeTabId === tab.id}
											{#if tab.children.length === 0}
												<div class="flex items-center justify-center h-full text-muted-foreground text-sm">
													No charts in this tab
												</div>
											{:else}
												<div class="grid grid-cols-2 gap-3 p-1">
													{#each tab.children as childId}
														{@const childItem = layoutItems.find(i => i.id === childId) as LayoutItem | undefined}
														{#if childItem?.chartSchema}
															<div class="border border-border rounded-lg overflow-hidden bg-card">
																{#if childItem.chart?.name}
																	<div class="px-3 py-1.5 border-b border-border bg-muted/30">
																		<span class="text-xs font-medium text-foreground truncate">{childItem.chart.name}</span>
																	</div>
																{/if}
																<div style="height: 220px">
																	<DashboardChartRenderer chartSchema={childItem.chartSchema} height="100%" />
																</div>
															</div>
														{/if}
													{/each}
												</div>
											{/if}
										{/if}
									{/each}
								</div>
							</div>
						{/if}

					{:else if layoutItem.type === 'chart'}
						<div class="flex flex-col h-full rounded-lg border border-border bg-card overflow-hidden">
							{#if layoutItem.chart?.name}
								<div class="flex items-center justify-between px-3 py-1.5 border-b border-border bg-muted/30 shrink-0">
									<span class="text-xs font-medium text-foreground truncate">{layoutItem.chart.name}</span>
								</div>
							{/if}
							<div class="flex-1 min-h-0">
								{#if layoutItem.chartSchema}
									<DashboardChartRenderer chartSchema={layoutItem.chartSchema} height="100%" />
								{:else}
									<div class="flex items-center justify-center h-full text-muted-foreground text-sm">
										No data
									</div>
								{/if}
							</div>
						</div>
					{/if}
				{/snippet}
			</DashboardGrid>
		</div>
	{:else}
		<div class="flex-1 flex items-center justify-center">
			<div class="flex flex-col items-center gap-3 text-center">
				<LayoutDashboard class="h-12 w-12 text-muted-foreground/30" />
				<h3 class="text-lg font-semibold text-foreground">Empty Dashboard</h3>
				<p class="text-sm text-muted-foreground">This dashboard doesn't have any content yet.</p>
			</div>
		</div>
	{/if}
</div>
