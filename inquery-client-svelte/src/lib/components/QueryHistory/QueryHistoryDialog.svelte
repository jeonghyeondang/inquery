<script lang="ts">
	import { onMount } from 'svelte';
	import {
		X, Search, CheckCircle2, XCircle, Play, Copy, Trash2,
		Clock, Calendar, ChevronDown
	} from 'lucide-svelte';
	import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '$lib/components/ui';
	import historyService from '$lib/service/history';
	import type { IHistoryRecord } from '$lib/service/history';

	interface Props {
		dataSourceId: number | null;
		onclose: () => void;
		onLoadToEditor?: (item: IHistoryRecord) => void;
	}

	let { dataSourceId, onclose, onLoadToEditor }: Props = $props();

	let historyList = $state<IHistoryRecord[]>([]);
	let loading = $state(false);
	let searchQuery = $state('');
	let filterStatus = $state<'all' | 'success' | 'error'>('all');
	let filterType = $state<'all' | 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'DDL'>('all');

	function getQueryType(sql?: string | null): string {
		if (!sql) return 'SQL';
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

	async function loadHistory() {
		if (!dataSourceId) return;
		loading = true;
		try {
			const res = await historyService.getHistoryList({
				pageNo: 1,
				pageSize: 100,
				dataSourceId,
				source: 'WORKSPACE'
			});
			const data = res as any;
			historyList = data?.data || data || [];
		} catch { historyList = []; }
		finally { loading = false; }
	}

	async function handleDelete(id: number | null | undefined) {
		if (!id) return;
		try {
			await historyService.deleteHistory({ id });
			historyList = historyList.filter(h => h.id !== id);
		} catch { /* ignore */ }
	}

	function handleCopy(ddl?: string | null) {
		if (ddl) navigator.clipboard.writeText(ddl);
	}

	function handleLoad(item: IHistoryRecord) {
		onLoadToEditor?.(item);
		onclose();
	}

	onMount(() => { loadHistory(); });

	// Filtered list
	let filteredHistory = $derived.by(() => {
		return historyList.filter(item => {
			// Search filter
			if (searchQuery && !item.ddl?.toLowerCase().includes(searchQuery.toLowerCase())) return false;

			// Status filter
			if (filterStatus !== 'all') {
				const isError = item.status === 'FAIL' || item.status === 'ERROR' || item.status === 'fail';
				if (filterStatus === 'success' && isError) return false;
				if (filterStatus === 'error' && !isError) return false;
			}

			// Type filter
			if (filterType !== 'all') {
				const qType = getQueryType(item.ddl);
				if (filterType === 'DDL') {
					if (!['CREATE', 'ALTER', 'DROP'].includes(qType)) return false;
				} else if (qType !== filterType && !(filterType === 'SELECT' && qType === 'WITH')) {
					return false;
				}
			}

			return true;
		});
	});

	// Group by date
	let groupedHistory = $derived.by(() => {
		const groups: { label: string; items: IHistoryRecord[] }[] = [];
		const today = new Date();
		const yesterday = new Date(today);
		yesterday.setDate(yesterday.getDate() - 1);

		const todayItems: IHistoryRecord[] = [];
		const yesterdayItems: IHistoryRecord[] = [];
		const olderItems: IHistoryRecord[] = [];

		for (const item of filteredHistory) {
			const d = new Date(item.gmtCreate);
			if (d.toDateString() === today.toDateString()) todayItems.push(item);
			else if (d.toDateString() === yesterday.toDateString()) yesterdayItems.push(item);
			else olderItems.push(item);
		}

		if (todayItems.length > 0) groups.push({ label: 'Today', items: todayItems });
		if (yesterdayItems.length > 0) groups.push({ label: 'Yesterday', items: yesterdayItems });
		if (olderItems.length > 0) groups.push({ label: 'Older', items: olderItems });

		return groups;
	});
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->

<!-- Backdrop -->
<div class="fixed inset-0 z-50 bg-black/50 flex items-center justify-center" onclick={onclose}>
	<!-- Dialog -->
	<div
		class="bg-popover border border-border rounded-lg shadow-xl w-[800px] max-h-[80vh] flex flex-col"
		onclick={(e) => e.stopPropagation()}
	>
		<!-- Header -->
		<div class="flex items-center justify-between px-4 py-3 border-b border-border">
			<h2 class="text-sm font-semibold">Query History</h2>
			<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={onclose}>
				<X size={16} />
			</button>
		</div>

		<!-- Filters -->
		<div class="flex items-center gap-2 px-4 py-2 border-b border-border">
			<!-- Search -->
			<div class="flex items-center gap-1.5 rounded-md border border-input bg-background px-2 py-1 flex-1">
				<Search size={14} class="text-muted-foreground shrink-0" />
				<input
					bind:value={searchQuery}
					type="text"
					placeholder="Search queries..."
					class="w-full text-xs bg-transparent focus:outline-none"
				/>
				{#if searchQuery}
					<button class="text-muted-foreground hover:text-foreground" onclick={() => searchQuery = ''}>
						<X size={12} />
					</button>
				{/if}
			</div>

			<!-- Status filter -->
			<DropdownMenu>
				<DropdownMenuTrigger class="h-7 text-xs border border-input bg-background rounded-md px-2 inline-flex items-center gap-1 hover:bg-accent transition-colors">
					{filterStatus === 'all' ? 'All status' : filterStatus === 'success' ? 'Success' : 'Error'}
					<ChevronDown size={12} class="opacity-50" />
				</DropdownMenuTrigger>
				<DropdownMenuContent align="start">
					{#each [{ value: 'all', label: 'All status' }, { value: 'success', label: 'Success' }, { value: 'error', label: 'Error' }] as opt}
						<DropdownMenuItem
							class="text-xs {filterStatus === opt.value ? 'bg-accent font-medium' : ''}"
							onSelect={() => { filterStatus = opt.value as any; }}
						>
							{opt.label}
						</DropdownMenuItem>
					{/each}
				</DropdownMenuContent>
			</DropdownMenu>

			<!-- Type filter -->
			<DropdownMenu>
				<DropdownMenuTrigger class="h-7 text-xs border border-input bg-background rounded-md px-2 inline-flex items-center gap-1 hover:bg-accent transition-colors">
					{filterType === 'all' ? 'All types' : filterType}
					<ChevronDown size={12} class="opacity-50" />
				</DropdownMenuTrigger>
				<DropdownMenuContent align="start">
					{#each [{ value: 'all', label: 'All types' }, { value: 'SELECT', label: 'SELECT' }, { value: 'INSERT', label: 'INSERT' }, { value: 'UPDATE', label: 'UPDATE' }, { value: 'DELETE', label: 'DELETE' }, { value: 'DDL', label: 'DDL' }] as opt}
						<DropdownMenuItem
							class="text-xs {filterType === opt.value ? 'bg-accent font-medium' : ''}"
							onSelect={() => { filterType = opt.value as any; }}
						>
							{opt.label}
						</DropdownMenuItem>
					{/each}
				</DropdownMenuContent>
			</DropdownMenu>

			<span class="text-[10px] text-muted-foreground">{filteredHistory.length} results</span>
		</div>

		<!-- Content -->
		<div class="flex-1 overflow-y-auto max-h-[60vh] px-4 py-2">
			{#if loading}
				<div class="flex items-center justify-center py-12">
					<div class="animate-spin rounded-full h-6 w-6 border-b-2 border-primary"></div>
				</div>
			{:else if groupedHistory.length === 0}
				<p class="text-sm text-muted-foreground text-center py-12">No history found</p>
			{:else}
				{#each groupedHistory as group}
					<!-- Date group label -->
					<div class="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground uppercase mt-3 mb-2 first:mt-0">
						<Calendar size={14} />
						<span>{group.label}</span>
					</div>

					{#each group.items as item (item.id)}
					{@const qType = getQueryType(item.ddl)}
						<div class="flex items-start gap-3 px-3 py-2.5 rounded-md hover:bg-accent/50 transition-colors group border-b border-border/30 last:border-b-0">
							<!-- Status -->
							<div class="pt-0.5 shrink-0">
								{#if item.status === 'FAIL' || item.status === 'ERROR' || item.status === 'fail'}
									<XCircle size={14} class="text-red-500" />
								{:else}
									<CheckCircle2 size={14} class="text-green-500" />
								{/if}
							</div>

							<!-- Main content -->
							<div class="flex-1 min-w-0">
								<!-- Top row: badge + metrics -->
								<div class="flex items-center gap-2 mb-1">
									<span class="text-[10px] px-1.5 rounded-full font-bold h-[18px] leading-[18px] uppercase font-mono tracking-tighter border shrink-0 {getQueryTypeBadgeClass(qType)}">
										{qType}
									</span>

									{#if item.operationRows != null}
										<span class="text-[10px] text-muted-foreground {item.status === 'success' ? 'text-green-600' : ''}">{item.operationRows} rows</span>
									{/if}
									{#if item.useTime != null}
										<span class="text-[10px] text-muted-foreground">{item.useTime}ms</span>
									{/if}
									<div class="flex items-center gap-0.5 text-[10px] text-muted-foreground ml-auto">
										<Clock size={10} class="opacity-70" />
										<span>{formatTimeAgo(item.gmtCreate)}</span>
									</div>
								</div>

								<!-- Query text -->
								<pre class="text-xs font-mono text-foreground/80 whitespace-pre-wrap max-h-24 overflow-hidden">{item.ddl || 'No SQL Text'}</pre>
							</div>

							<!-- Actions -->
							<div class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 shrink-0 pt-0.5">
								<button
									class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
									title="Load in editor"
									onclick={() => handleLoad(item)}
								>
									<Play size={14} />
								</button>
								<button
									class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
									title="Copy query"
									onclick={() => handleCopy(item.ddl)}
								>
									<Copy size={14} />
								</button>
								<button
									class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive"
									title="Delete"
									onclick={() => handleDelete(item.id)}
								>
									<Trash2 size={14} />
								</button>
							</div>
						</div>
					{/each}
				{/each}
			{/if}
		</div>
	</div>
</div>
