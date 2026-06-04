<script lang="ts">
	/**
	 * DashboardChartRenderer - Renders charts based on ChartSchema
	 * Supports: ECharts (BAR, LINE, PIE, SCATTER), CARD (metrics), TABLE
	 * Uses shared chartUtils.ts for chart generation to ensure consistency with ChartModal (Edit).
	 */

	import { onMount, onDestroy } from 'svelte';
	import ECharts from '$lib/components/ECharts/ECharts.svelte';
	import type { ChartSchema } from './types';
	import {
		generateChartOption,
		generateChartOptionWithConfig,
		buildCardMetrics as sharedBuildCardMetrics,
		formatValue,
		type ChartType,
		type ChartConfig,
		type MetricFormat,
	} from '$lib/utils/chartUtils';

	interface Props {
		chartSchema?: ChartSchema;
		height?: string;
	}

	let { chartSchema, height = '100%' }: Props = $props();

	// ─── Dark mode detection (for CARD/TABLE theming) ───
	let isDarkMode = $state(false);
	let themeObserver: MutationObserver | null = null;

	function detectDarkMode(): boolean {
		const el = document.documentElement;
		return el.classList.contains('dark') || el.getAttribute('data-theme') === 'dark' || el.getAttribute('theme') === 'dark';
	}

	onMount(() => {
		isDarkMode = detectDarkMode();
		themeObserver = new MutationObserver(() => {
			isDarkMode = detectDarkMode();
		});
		themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme', 'theme'] });
	});

	onDestroy(() => {
		themeObserver?.disconnect();
	});

	// ─── Chart Option Generation (uses shared chartUtils) ───
	function generateOptionFromSchema(schema: ChartSchema): Record<string, unknown> | null {
		if (!schema?.resultData || !schema?.chartType) return null;
		const resultData = Array.isArray(schema.resultData) ? schema.resultData[0] : schema.resultData;
		if (!resultData?.headerList?.length || !resultData?.dataList?.length) return null;

		const ct = schema.chartType.toUpperCase() as ChartType;
		try {
			if (schema.chartConfig && Object.keys(schema.chartConfig).length > 0) {
				return generateChartOptionWithConfig(resultData, ct, schema.chartConfig as ChartConfig);
			}
			return generateChartOption(resultData, ct);
		} catch (e) {
			console.error('Failed to generate chart option from config:', e);
			// Fallback to stored snapshot
			return schema.chartOption || null;
		}
	}

	// ─── Format metric value for CARD display ───
	function formatMetricDisplay(value: unknown, format: string = 'comma'): string {
		if (value === null || value === undefined) return '-';
		const num = Number(value);
		if (isNaN(num)) return String(value);
		// Map CARD-specific formats to MetricFormat
		const fmtMap: Record<string, MetricFormat> = {
			comma: 'comma',
			decimal2: 'decimal2',
			percent: 'percent',
			currency: 'currency',
			compact: 'compact',
		};
		return formatValue(value, fmtMap[format] || 'comma');
	}

	// ─── Build TABLE ───
	function buildTableData(resultData: any) {
		const actual = Array.isArray(resultData) ? resultData[0] : resultData;
		if (!actual?.headerList?.length || !actual?.dataList?.length) return null;

		const rowNumIdx = actual.headerList.findIndex(
			(h: any) => h.name === 'Row Number' || h.dataType === 'INQUERY_ROW_NUMBER'
		);
		const headers = actual.headerList.filter((_: any, i: number) => i !== rowNumIdx);
		const dataList = rowNumIdx >= 0
			? actual.dataList.map((row: any[]) => row.filter((_: any, i: number) => i !== rowNumIdx))
			: actual.dataList;

		return { headers, dataList };
	}

	// ─── Derived state ───
	let chartOption = $derived.by(() => {
		const _theme = isDarkMode; // Track theme for reactivity
		if (!chartSchema) return null;
		// Always generate from resultData + config for consistency with Edit modal
		if (chartSchema.resultData && chartSchema.chartType) {
			return generateOptionFromSchema(chartSchema);
		}
		// Fallback to stored chartOption snapshot (legacy data)
		return chartSchema.chartOption || null;
	});

	let chartType = $derived(chartSchema?.chartType?.toUpperCase() || '');
	let cardMetrics = $derived.by(() => {
		if (chartType !== 'CARD') return [];
		const config = chartSchema?.chartConfig || {};
		const resultData = chartSchema?.resultData
			? (Array.isArray(chartSchema.resultData) ? chartSchema.resultData[0] : chartSchema.resultData)
			: undefined;
		return sharedBuildCardMetrics(resultData, config.metrics);
	});
	let tableData = $derived.by(() => {
		if (chartType !== 'TABLE') return null;
		return buildTableData(chartSchema?.resultData);
	});

	// Table pagination
	const TABLE_PAGE_SIZE = 10;
	let tablePage = $state(0);
	let tablePageCount = $derived(
		tableData ? Math.ceil(tableData.dataList.length / TABLE_PAGE_SIZE) : 0
	);
	let pagedTableData = $derived.by(() => {
		if (!tableData) return [];
		const start = tablePage * TABLE_PAGE_SIZE;
		return tableData.dataList.slice(start, start + TABLE_PAGE_SIZE);
	});

	// Reset page when data changes
	$effect(() => {
		if (tableData) tablePage = 0;
	});

	const valueSizeClasses: Record<string, string> = {
		small: 'text-xl', medium: 'text-3xl', large: 'text-5xl'
	};
	const subheaderSizeClasses: Record<string, string> = {
		small: 'text-xs', medium: 'text-sm', large: 'text-base'
	};
</script>

{#if chartType === 'CARD'}
	<!-- CARD / Metrics Renderer -->
	{@const config = chartSchema?.chartConfig || {}}
	{@const fmt = config.yAxisFormat || 'comma'}
	{@const valueSize = config.metricValueSize || 'medium'}
	{@const subheaderSize = config.subheaderSize || 'medium'}
	{@const subheader = config.subheader}
	{@const numericMetrics = cardMetrics.filter(m => m.isNumeric)}
	{@const items = numericMetrics.length > 0 ? numericMetrics : cardMetrics}

	{#if items.length === 0}
		<div class="flex h-full w-full items-center justify-center text-muted-foreground text-sm" style="height: {height}">
			No data
		</div>
	{:else}
		<div
			class="grid h-full w-full place-items-center gap-4 p-4"
			style="grid-template-columns: repeat({Math.min(items.length, 4)}, 1fr); height: {height}"
		>
			{#each items as m}
				<div class="flex flex-col items-center justify-center gap-1 text-center">
					<div class="font-bold text-foreground tabular-nums {valueSizeClasses[valueSize] || 'text-3xl'}">
						{m.isNumeric ? formatMetricDisplay(m.raw, fmt) : String(m.raw ?? '-')}
					</div>
					<div class="text-xs text-muted-foreground font-medium">{m.name}</div>
					{#if subheader}
						<div class="text-muted-foreground {subheaderSizeClasses[subheaderSize] || 'text-sm'}">
							{subheader}
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}

{:else if chartType === 'TABLE'}
	<!-- TABLE Renderer -->
	{#if tableData}
		<div class="flex flex-col h-full" style="height: {height}">
			<div class="flex-1 overflow-auto p-2">
				<table class="w-full text-xs border-collapse">
					<thead class="sticky top-0 z-10">
						<tr class="border-b border-border">
							{#each tableData.headers as header}
								<th class="px-3 py-2 text-left font-semibold text-muted-foreground uppercase tracking-wider whitespace-nowrap bg-muted/30">
									{header.name}
								</th>
							{/each}
						</tr>
					</thead>
					<tbody>
						{#each pagedTableData as row, rowIdx}
							<tr class="border-b border-border/50 hover:bg-muted/20 transition-colors {rowIdx % 2 === 0 ? '' : 'bg-muted/10'}">
								{#each row as cell}
									<td class="px-3 py-1.5 whitespace-nowrap max-w-[200px] truncate" title={String(cell ?? '')}>
										{cell ?? '-'}
									</td>
								{/each}
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
			{#if tablePageCount > 1}
				<div class="flex items-center justify-between px-3 py-1.5 border-t border-border bg-muted/20 shrink-0">
					<span class="text-[10px] text-muted-foreground">
						{tablePage * TABLE_PAGE_SIZE + 1}–{Math.min((tablePage + 1) * TABLE_PAGE_SIZE, tableData.dataList.length)} of {tableData.dataList.length}
					</span>
					<div class="flex items-center gap-1">
						<button
							class="px-1.5 py-0.5 text-[10px] rounded border border-border hover:bg-accent disabled:opacity-30 disabled:cursor-not-allowed"
							onclick={() => tablePage = Math.max(0, tablePage - 1)}
							disabled={tablePage === 0}
						>&laquo; Prev</button>
						<span class="text-[10px] text-muted-foreground px-1">{tablePage + 1}/{tablePageCount}</span>
						<button
							class="px-1.5 py-0.5 text-[10px] rounded border border-border hover:bg-accent disabled:opacity-30 disabled:cursor-not-allowed"
							onclick={() => tablePage = Math.min(tablePageCount - 1, tablePage + 1)}
							disabled={tablePage >= tablePageCount - 1}
						>Next &raquo;</button>
					</div>
				</div>
			{/if}
		</div>
	{:else}
		<div class="flex h-full w-full items-center justify-center text-muted-foreground text-sm" style="height: {height}">
			No data
		</div>
	{/if}

{:else}
	<!-- ECharts Renderer (BAR, LINE, PIE, SCATTER, etc.) -->
	{#if chartOption}
		<ECharts option={chartOption} {height} theme="auto" />
	{:else}
		<div class="flex h-full w-full items-center justify-center text-muted-foreground text-sm" style="height: {height}">
			No data
		</div>
	{/if}
{/if}
