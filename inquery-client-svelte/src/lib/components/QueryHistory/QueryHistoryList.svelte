<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import {
		ChevronRight, Clock, CheckCircle, XCircle, MoreHorizontal,
		Play, Copy, Trash2, History
	} from 'lucide-svelte';
	import ContextMenu from '$lib/components/ContextMenu/ContextMenu.svelte';
	import type { ContextMenuItem } from '$lib/components/ContextMenu/ContextMenu.svelte';
	import historyService from '$lib/service/history';
	import type { IHistoryRecord } from '$lib/service/history';

	interface Props {
		dataSourceId: number | null;
		onLoadToEditor?: (item: IHistoryRecord) => void;
	}

	let { dataSourceId, onLoadToEditor }: Props = $props();

	let historyList = $state<IHistoryRecord[]>([]);
	let isExpanded = $state(true);
	let showDialog = $state(false);
	let itemContextMenu = $state<{ x: number; y: number; items: ContextMenuItem[] } | null>(null);

	function getQueryType(sql?: string | null): string {
		if (!sql) return 'SQL';
		// Strip leading comments (-- line comments and /* block comments */)
		const stripped = sql.replace(/^(\s*(--[^\n]*\n|\/\*[\s\S]*?\*\/))+/g, '').trim().toUpperCase();
		const types = ['SELECT', 'WITH', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'MERGE', 'EXPLAIN'];
		for (const t of types) {
			if (stripped.startsWith(t)) return t;
		}
		return 'SQL';
	}

	function getQueryTypeBadgeClass(type: string): string {
		switch (type) {
			case 'SELECT': case 'WITH':
				return 'bg-blue-500/15 text-blue-500 border-blue-500/20';
			case 'INSERT': case 'MERGE':
				return 'bg-green-500/15 text-green-500 border-green-500/20';
			case 'UPDATE':
				return 'bg-yellow-500/15 text-yellow-500 border-yellow-500/20';
			case 'DELETE':
				return 'bg-red-500/15 text-red-500 border-red-500/20';
			case 'CREATE': case 'ALTER': case 'DROP':
				return 'bg-purple-500/15 text-purple-500 border-purple-500/20';
			case 'EXPLAIN':
				return 'bg-cyan-500/15 text-cyan-500 border-cyan-500/20';
			default:
				return 'bg-gray-500/15 text-gray-500 border-gray-500/20';
		}
	}

	function formatTimeAgo(dateString: string): string {
		const now = Date.now();
		const then = new Date(dateString).getTime();
		const diffMs = now - then;
		const diffMin = Math.floor(diffMs / 60000);
		if (diffMin < 1) return 'just now';
		if (diffMin < 60) return `${diffMin}m ago`;
		const diffHr = Math.floor(diffMin / 60);
		if (diffHr < 24) return `${diffHr}h ago`;
		const diffDay = Math.floor(diffHr / 24);
		return `${diffDay}d ago`;
	}

	function truncateQuery(query?: string | null, maxLength = 50): string {
		if (!query) return '';
		const normalized = query.replace(/\s+/g, ' ').trim();
		return normalized.length > maxLength ? normalized.slice(0, maxLength) + '...' : normalized;
	}

	async function loadHistory() {
		if (!dataSourceId) return;
		try {
			const res = await historyService.getHistoryList({
				pageNo: 1,
				pageSize: 20,
				dataSourceId,
				source: 'WORKSPACE'
			});
			const data = res as any;
			historyList = data?.data || data || [];
		} catch { historyList = []; }
	}

	async function handleDelete(id: number | null | undefined) {
		if (!id) return;
		try {
			await historyService.deleteHistory({ id });
			historyList = historyList.filter(h => h.id !== id);
		} catch { /* ignore */ }
	}

	async function handleClearAll() {
		if (!dataSourceId) return;
		try {
			await historyService.clearHistory({ dataSourceId });
			historyList = [];
		} catch { /* ignore */ }
	}

	function handleCopy(ddl?: string | null) {
		if (ddl) navigator.clipboard.writeText(ddl);
	}

	function handleLoad(item: IHistoryRecord) {
		onLoadToEditor?.(item);
	}

	// Listen for query-executed events
	function onQueryExecuted() {
		loadHistory();
	}

	onMount(() => {
		loadHistory();
		window.addEventListener('query-executed', onQueryExecuted);
	});

	onDestroy(() => {
		if (typeof window !== 'undefined') {
			window.removeEventListener('query-executed', onQueryExecuted);
		}
	});

	// Reload when dataSourceId changes
	$effect(() => {
		if (dataSourceId) loadHistory();
	});

	let displayList = $derived(historyList.slice(0, 10));
	let hasMore = $derived(historyList.length > 10);

	// Hover preview state
	let hoveredItem = $state<IHistoryRecord | null>(null);
	let showTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let hideTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let previewPos = $state<{ x: number; y: number } | null>(null);
	let isOverPreview = $state(false);

	function handleItemMouseEnter(e: MouseEvent, item: IHistoryRecord) {
		// Cancel any pending hide
		if (hideTimeout) { clearTimeout(hideTimeout); hideTimeout = null; }
		if (showTimeout) clearTimeout(showTimeout);
		const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
		showTimeout = setTimeout(() => {
			hoveredItem = item;
			previewPos = { x: rect.right + 8, y: rect.top };
		}, 300);
	}

	function handleItemMouseLeave() {
		if (showTimeout) { clearTimeout(showTimeout); showTimeout = null; }
		// Delay hiding so the user can move the mouse to the preview
		hideTimeout = setTimeout(() => {
			if (!isOverPreview) {
				hoveredItem = null;
				previewPos = null;
			}
		}, 200);
	}

	function handlePreviewMouseEnter() {
		isOverPreview = true;
		if (hideTimeout) { clearTimeout(hideTimeout); hideTimeout = null; }
	}

	function handlePreviewMouseLeave() {
		isOverPreview = false;
		hoveredItem = null;
		previewPos = null;
	}

	function formatSqlForPreview(sql?: string | null): string {
		if (!sql) return '';
		return sql.trim();
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="flex flex-col h-full bg-transparent">
	<!-- Header -->
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="flex items-center justify-between px-3 py-2 hover:bg-accent/50 transition-colors w-full text-left cursor-pointer"
		role="button"
		tabindex="0"
		onclick={() => isExpanded = !isExpanded}
		onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); isExpanded = !isExpanded; } }}
	>
		<div class="flex items-center gap-1.5">
			<ChevronRight size={14} class="text-muted-foreground transition-transform {isExpanded ? 'rotate-90' : ''}" />
			<History size={14} class="text-muted-foreground" />
			<span class="text-xs font-medium">History</span>
			{#if historyList.length > 0}
				<span class="text-[10px] bg-muted text-muted-foreground rounded-full px-1.5 leading-4">{historyList.length}</span>
			{/if}
		</div>
		{#if historyList.length > 0}
			<button
				class="p-0.5 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive"
				onclick={(e) => { e.stopPropagation(); handleClearAll(); }}
				title="Clear all history"
			>
				<Trash2 size={12} />
			</button>
		{/if}
	</div>

	<!-- Content -->
	{#if isExpanded}
		<div class="flex-1 overflow-y-auto pb-2">
			{#if displayList.length === 0}
				<p class="text-[11px] text-muted-foreground text-center py-4">No query history</p>
			{:else}
				{#each displayList as item (item.id)}
					{@const qType = getQueryType(item.ddl)}
					<div
						class="flex items-center px-3 py-1.5 hover:bg-accent/50 transition-colors group"
						onmouseenter={(e) => handleItemMouseEnter(e, item)}
						onmouseleave={handleItemMouseLeave}
						oncontextmenu={(e) => {
							e.preventDefault();
							itemContextMenu = {
								x: e.clientX, y: e.clientY,
								items: [
									{ label: 'Load in editor', icon: Play, action: () => handleLoad(item) },
									{ label: 'Copy query', icon: Copy, action: () => handleCopy(item.ddl) },
									{ label: '', action: () => {}, separator: true },
									{ label: 'Delete', icon: Trash2, destructive: true, action: () => handleDelete(item.id) },
								]
							};
						}}
					>
						<!-- Status icon -->
						{#if item.status === 'success'}
							<CheckCircle size={12} class="text-green-500 shrink-0 mr-1.5" />
						{:else if item.status === 'fail' || item.status === 'FAIL' || item.status === 'ERROR'}
							<XCircle size={12} class="text-red-500 shrink-0 mr-1.5" />
						{:else}
							<CheckCircle size={12} class="text-muted-foreground shrink-0 mr-1.5" />
						{/if}

						<!-- Query type badge -->
						<span class="text-[10px] px-1.5 rounded-full mr-2 font-bold h-[18px] leading-[18px] uppercase font-mono tracking-tighter border shrink-0 {getQueryTypeBadgeClass(qType)}">
							{qType}
						</span>

						<!-- Query text -->
						<span class="flex-1 font-mono text-[11px] text-muted-foreground whitespace-nowrap overflow-hidden text-ellipsis min-w-0">
							{truncateQuery(item.ddl)}
						</span>

						<!-- Time + metrics -->
						<div class="flex items-center gap-1 text-[10px] text-muted-foreground shrink-0 ml-2">
							{#if item.operationRows != null}
								<span class={item.status === 'success' ? 'text-green-600' : ''}>{item.operationRows}r</span>
								<span class="opacity-50">·</span>
							{/if}
							{#if item.useTime != null}
								<span>{item.useTime}ms</span>
								<span class="opacity-50">·</span>
							{/if}
							<Clock size={10} class="opacity-70" />
							<span>{formatTimeAgo(item.gmtCreate)}</span>
						</div>

						<!-- Three dots button → opens same context menu -->
						<button
							class="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-accent ml-1 shrink-0"
							onclick={(e) => {
								e.stopPropagation();
								const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
								itemContextMenu = {
									x: rect.right, y: rect.bottom,
									items: [
										{ label: 'Load in editor', icon: Play, action: () => handleLoad(item) },
										{ label: 'Copy query', icon: Copy, action: () => handleCopy(item.ddl) },
										{ label: '', action: () => {}, separator: true },
										{ label: 'Delete', icon: Trash2, destructive: true, action: () => handleDelete(item.id) },
									]
								};
							}}
						>
							<MoreHorizontal size={14} class="text-muted-foreground" />
						</button>
					</div>
				{/each}

				{#if hasMore}
					<button
						class="w-full text-center text-xs text-primary hover:text-primary/80 py-2"
						onclick={() => showDialog = true}
					>
						View all history ({historyList.length})
					</button>
				{/if}
			{/if}
		</div>
	{/if}
</div>

<!-- Full History Dialog -->
{#if showDialog}
	{#await import('./QueryHistoryDialog.svelte') then { default: QueryHistoryDialog }}
		<QueryHistoryDialog
			{dataSourceId}
			onclose={() => showDialog = false}
			{onLoadToEditor}
		/>
	{/await}
{/if}

{#if itemContextMenu}
	<ContextMenu
		items={itemContextMenu.items}
		x={itemContextMenu.x}
		y={itemContextMenu.y}
		onclose={() => itemContextMenu = null}
	/>
{/if}

<!-- SQL Query Preview Tooltip -->
{#if hoveredItem && previewPos && hoveredItem.ddl}
	{@const previewSql = formatSqlForPreview(hoveredItem.ddl)}
	{@const previewType = getQueryType(hoveredItem.ddl)}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed z-[9999] max-w-[420px] min-w-[240px] rounded-lg border border-border bg-popover shadow-xl animate-in fade-in-0 zoom-in-95 duration-150"
		style="left: {previewPos.x}px; top: {previewPos.y}px;"
		role="tooltip"
		onmouseenter={handlePreviewMouseEnter}
		onmouseleave={handlePreviewMouseLeave}
	>
		<!-- Tooltip header -->
		<div class="flex items-center gap-2 px-3 py-2 border-b border-border/50">
			<span class="text-[10px] px-1.5 rounded-full font-bold h-[18px] leading-[18px] uppercase font-mono tracking-tighter border shrink-0 {getQueryTypeBadgeClass(previewType)}">
				{previewType}
			</span>
			{#if hoveredItem.status === 'success'}
				<CheckCircle size={12} class="text-green-500" />
			{:else if hoveredItem.status === 'fail' || hoveredItem.status === 'FAIL' || hoveredItem.status === 'ERROR'}
				<XCircle size={12} class="text-red-500" />
			{/if}
			<span class="text-[10px] text-muted-foreground ml-auto">
				{#if hoveredItem.useTime != null}{hoveredItem.useTime}ms{/if}
				{#if hoveredItem.operationRows != null} · {hoveredItem.operationRows} rows{/if}
			</span>
		</div>
		<!-- SQL content -->
		<pre class="px-3 py-2.5 text-[11px] font-mono leading-relaxed text-foreground whitespace-pre-wrap break-all max-h-[200px] overflow-y-auto">{previewSql}</pre>
	</div>
{/if}
