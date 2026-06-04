<script lang="ts">
	import {
		SvelteFlow,
		Controls,
		MiniMap,
		Background,
		type Node,
		type Edge,
		type NodeTypes,
		type EdgeTypes,
		type ColorMode
	} from '@xyflow/svelte';
	import '@xyflow/svelte/dist/style.css';

	import { getThemeStore } from '$lib/stores/theme.svelte';
	import type { ERDSchema } from './types';
	import { buildFlowData, getTableKey } from './graphUtils';
	import TableNode from './TableNode.svelte';
	import ClusterNode from './ClusterNode.svelte';
	import ERDEdge from './ERDEdge.svelte';

	interface Props {
		schema: ERDSchema | null;
		loading?: boolean;
	}

	let { schema, loading = false }: Props = $props();

	const themeStore = getThemeStore();

	// Filter state
	let selectedTables = $state<Set<string>>(new Set());
	let searchQuery = $state('');
	let filterOpen = $state(false);

	const nodeTypes: NodeTypes = {
		tableNode: TableNode,
		clusterNode: ClusterNode
	};

	const edgeTypes: EdgeTypes = {
		erdEdge: ERDEdge
	};

	// All available tables for filter list
	let allTables = $derived.by(() => {
		if (!schema?.tables?.length) return [];
		return schema.tables
			.map((t) => ({
				key: getTableKey(t.schemaName, t.name),
				schema: t.schemaName || '',
				table: t.name
			}))
			.sort((a, b) => a.key.localeCompare(b.key));
	});

	// Unique schema names for grouping
	let uniqueSchemas = $derived([...new Set(allTables.map((t) => t.schema))].sort());

	// Filtered table options based on search
	let filteredTableOptions = $derived.by(() => {
		if (!searchQuery) return allTables;
		const q = searchQuery.toLowerCase();
		return allTables.filter(
			(t) => t.table.toLowerCase().includes(q) || t.schema.toLowerCase().includes(q)
		);
	});

	// Filtered schemas based on table selection
	let filteredTables = $derived.by(() => {
		if (!schema?.tables?.length) return [];
		if (selectedTables.size === 0) return schema.tables;
		return schema.tables.filter((t) => selectedTables.has(getTableKey(t.schemaName, t.name)));
	});

	let flowData = $derived.by(() => buildFlowData(filteredTables));
	let flowColorMode = $derived<ColorMode>(themeStore.isDark ? 'dark' : 'light');
	let backgroundPatternColor = $derived(themeStore.isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(15, 23, 42, 0.14)');
	let miniMapBackgroundColor = $derived(themeStore.isDark ? 'hsl(0 0% 12%)' : 'hsl(0 0% 100%)');
	let miniMapMaskColor = $derived(themeStore.isDark ? 'rgba(0, 0, 0, 0.45)' : 'rgba(15, 23, 42, 0.18)');

	let nodes = $state.raw<Node[]>([]);
	let edges = $state.raw<Edge[]>([]);

	$effect(() => {
		nodes = flowData.nodes;
		edges = flowData.edges;
	});

	// Stats
	let tableCount = $derived(nodes.filter((n) => n.type === 'tableNode').length);
	let clusterCount = $derived(nodes.filter((n) => n.type === 'clusterNode').length);
	let relationshipCount = $derived(edges.length);

	function toggleTable(key: string) {
		const next = new Set(selectedTables);
		if (next.has(key)) next.delete(key);
		else next.add(key);
		selectedTables = next;
	}

	function selectSchema(schemaName: string) {
		const next = new Set(selectedTables);
		allTables.filter((t) => t.schema === schemaName).forEach((t) => next.add(t.key));
		selectedTables = next;
	}

	function clearSelection() {
		selectedTables = new Set();
	}

	function miniMapNodeColor(node: Node): string {
		if (node.type === 'clusterNode') return 'transparent';
		return (node.data?.clusterColor as string) || (themeStore.isDark ? '#818cf8' : '#6366f1');
	}

	function handleClickOutside(e: MouseEvent) {
		const target = e.target as HTMLElement;
		if (filterOpen && !target.closest('.filter-popover-wrapper')) {
			filterOpen = false;
		}
	}
</script>

<svelte:window onclick={handleClickOutside} />

<div class="erd-root">
	{#if loading}
		<div class="erd-loading">
			<div class="erd-spinner"></div>
			<span>Loading ERD...</span>
		</div>
	{:else if !schema || filteredTables.length === 0}
		<div class="erd-empty">
			<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" opacity="0.2">
				<line x1="6" y1="3" x2="6" y2="15" />
				<circle cx="18" cy="6" r="3" />
				<circle cx="6" cy="18" r="3" />
				<path d="M18 9a9 9 0 0 1-9 9" />
			</svg>
			<p>
				{#if schema && selectedTables.size > 0}
					No matching tables
				{:else if schema}
					No tables found to visualize
				{:else}
					Select a connection and database to view ERD
				{/if}
			</p>
		</div>
	{:else}
		<!-- Stats bar -->
		<div class="erd-stats-bar">
			<!-- Filter button -->
			<div class="filter-popover-wrapper">
				<button
					class="filter-btn"
					onclick={(e) => { e.stopPropagation(); filterOpen = !filterOpen; }}
				>
					<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
					</svg>
					Filter Tables
					{#if selectedTables.size > 0}
						<span class="filter-badge">{selectedTables.size}</span>
					{/if}
				</button>

				{#if filterOpen}
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<!-- svelte-ignore a11y_click_events_have_key_events -->
				<div class="filter-popover" onclick={(e) => e.stopPropagation()}>
						<div class="filter-search">
							<input
								bind:value={searchQuery}
								placeholder="Search tables..."
								class="filter-input"
							/>
						</div>
						<div class="filter-list">
							{#if filteredTableOptions.length === 0}
								<p class="filter-empty">No tables found.</p>
							{:else}
								{#each uniqueSchemas as schemaName}
									{@const schemaTables = filteredTableOptions.filter((t) => t.schema === schemaName)}
									{#if schemaTables.length > 0}
										<div class="filter-group">
											<div class="filter-group-header">
												<span class="filter-group-name">{schemaName}</span>
												<button class="filter-select-all" onclick={() => selectSchema(schemaName)}>
													Select all
												</button>
											</div>
											{#each schemaTables as table (table.key)}
												<button
													class="filter-item"
													onclick={() => toggleTable(table.key)}
												>
													<span
														class="filter-check"
														class:checked={selectedTables.has(table.key)}
													>
														{#if selectedTables.has(table.key)}
															<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
																<polyline points="20 6 9 17 4 12" />
															</svg>
														{/if}
													</span>
													<span class="filter-item-name">{table.table}</span>
												</button>
											{/each}
										</div>
									{/if}
								{/each}
							{/if}
						</div>
						{#if selectedTables.size > 0}
							<div class="filter-footer">
								<button class="filter-clear" onclick={clearSelection}>
									Clear selection ({selectedTables.size})
								</button>
							</div>
						{/if}
					</div>
				{/if}
			</div>

			<!-- Selected table badges -->
			{#if selectedTables.size > 0 && selectedTables.size <= 3}
				<div class="selected-badges">
					{#each [...selectedTables].slice(0, 3) as key (key)}
						<button class="selected-badge" onclick={() => toggleTable(key)}>
							{key.split('.')[1]}
							<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
								<line x1="18" y1="6" x2="6" y2="18" />
								<line x1="6" y1="6" x2="18" y2="18" />
							</svg>
						</button>
					{/each}
				</div>
			{/if}

			<div class="stats-divider"></div>

			<!-- Stats -->
			<span class="stat-item">
				<span class="stat-dot" style="background: var(--erd-primary); opacity: 0.6;"></span>
				{tableCount} table{tableCount !== 1 ? 's' : ''}
				{#if selectedTables.size > 0}
					<span class="stat-of">(of {allTables.length})</span>
				{/if}
			</span>
			{#if clusterCount > 0}
				<span class="stat-item">
					<span class="stat-dot" style="background: #6366f1; opacity: 0.6;"></span>
					{clusterCount} cluster{clusterCount !== 1 ? 's' : ''}
				</span>
			{/if}
			<span class="stat-item">
				<span class="stat-dot" style="background: #f59e0b; opacity: 0.6;"></span>
				{relationshipCount} relationship{relationshipCount !== 1 ? 's' : ''}
			</span>

			<span class="stats-hint">
				Tables grouped by relationships · Hub tables centered
			</span>
		</div>

		<!-- Flow canvas -->
		<div class="erd-canvas">
			<SvelteFlow
				bind:nodes
				bind:edges
				{nodeTypes}
				{edgeTypes}
				fitView
				fitViewOptions={{ padding: 0.15, maxZoom: 1.2 }}
				minZoom={0.05}
				maxZoom={2}
				nodesDraggable={true}
				nodesConnectable={false}
				elementsSelectable={true}
				defaultEdgeOptions={{ type: 'erdEdge' }}
				proOptions={{ hideAttribution: true }}
				colorMode={flowColorMode}
			>
				<Background patternColor={backgroundPatternColor} gap={24} size={1} />
				<Controls showLock={false} />
				<MiniMap
					nodeColor={miniMapNodeColor}
					bgColor={miniMapBackgroundColor}
					maskColor={miniMapMaskColor}
					pannable
					zoomable
				/>
			</SvelteFlow>
		</div>
	{/if}
</div>

<style>
	.erd-root {
		display: flex;
		flex-direction: column;
		height: 100%;
		width: 100%;
		overflow: hidden;
		--erd-background: hsl(var(--background));
		--erd-foreground: hsl(var(--foreground));
		--erd-card: hsl(var(--card));
		--erd-popover: hsl(var(--popover));
		--erd-muted: hsl(var(--muted));
		--erd-muted-foreground: hsl(var(--muted-foreground));
		--erd-accent: hsl(var(--accent));
		--erd-border: hsl(var(--border));
		--erd-primary: hsl(var(--primary));
	}

	/* Loading */
	.erd-loading {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 100%;
		gap: 10px;
		color: var(--erd-muted-foreground);
		font-size: 13px;
	}

	.erd-spinner {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		border: 2.5px solid var(--erd-border);
		border-top-color: var(--erd-primary);
		animation: spin 0.7s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	/* Empty */
	.erd-empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 100%;
		gap: 8px;
		color: var(--erd-muted-foreground);
		font-size: 13px;
	}

	/* Stats bar */
	.erd-stats-bar {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 6px 16px;
		border-bottom: 1px solid color-mix(in srgb, var(--erd-border) 40%, transparent);
		background: color-mix(in srgb, var(--erd-muted) 30%, transparent);
		font-size: 12px;
		color: var(--erd-muted-foreground);
		flex-shrink: 0;
	}

	/* Filter button */
	.filter-popover-wrapper {
		position: relative;
	}

	.filter-btn {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 4px 10px;
		border: 1px solid var(--erd-border);
		border-radius: 6px;
		background: var(--erd-card);
		color: var(--erd-foreground);
		font-size: 12px;
		cursor: pointer;
		transition: background 0.15s;
		white-space: nowrap;
	}

	.filter-btn:hover {
		background: var(--erd-accent);
	}

	.filter-badge {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 16px;
		height: 16px;
		padding: 0 4px;
		border-radius: 4px;
		background: var(--erd-primary);
		color: white;
		font-size: 10px;
		font-weight: 600;
	}

	/* Filter popover */
	.filter-popover {
		position: absolute;
		top: calc(100% + 4px);
		left: 0;
		width: 300px;
		border: 1px solid var(--erd-border);
		border-radius: 8px;
		background: var(--erd-popover);
		box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
		z-index: 50;
		overflow: hidden;
	}

	.filter-search {
		padding: 8px;
		border-bottom: 1px solid var(--erd-border);
	}

	.filter-input {
		width: 100%;
		padding: 6px 8px;
		border: 1px solid var(--erd-border);
		border-radius: 6px;
		font-size: 12px;
		background: var(--erd-background);
		color: var(--erd-foreground);
		outline: none;
	}

	.filter-input:focus {
		border-color: var(--erd-primary);
	}

	.filter-list {
		max-height: 300px;
		overflow-y: auto;
		padding: 8px;
	}

	.filter-empty {
		text-align: center;
		padding: 16px;
		color: var(--erd-muted-foreground);
		font-size: 13px;
	}

	.filter-group {
		margin-bottom: 12px;
	}

	.filter-group-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 4px;
	}

	.filter-group-name {
		font-size: 11px;
		font-weight: 600;
		color: var(--erd-muted-foreground);
		text-transform: uppercase;
	}

	.filter-select-all {
		font-size: 10px;
		color: var(--erd-primary);
		background: none;
		border: none;
		cursor: pointer;
		padding: 0;
	}

	.filter-select-all:hover {
		text-decoration: underline;
	}

	.filter-item {
		display: flex;
		align-items: center;
		gap: 8px;
		width: 100%;
		padding: 5px 8px;
		border: none;
		border-radius: 4px;
		background: none;
		cursor: pointer;
		font-size: 13px;
		color: var(--erd-foreground);
		text-align: left;
		transition: background 0.1s;
	}

	.filter-item:hover {
		background: var(--erd-accent);
	}

	.filter-check {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 16px;
		height: 16px;
		border: 1px solid var(--erd-primary);
		border-radius: 3px;
		flex-shrink: 0;
		opacity: 0.5;
	}

	.filter-check.checked {
		background: var(--erd-primary);
		color: white;
		opacity: 1;
	}

	.filter-item-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.filter-footer {
		padding: 8px;
		border-top: 1px solid var(--erd-border);
	}

	.filter-clear {
		width: 100%;
		padding: 6px;
		border: none;
		border-radius: 4px;
		background: none;
		color: var(--erd-foreground);
		font-size: 12px;
		cursor: pointer;
		transition: background 0.1s;
	}

	.filter-clear:hover {
		background: var(--erd-accent);
	}

	/* Selected badges */
	.selected-badges {
		display: flex;
		align-items: center;
		gap: 4px;
	}

	.selected-badge {
		display: flex;
		align-items: center;
		gap: 4px;
		height: 20px;
		padding: 0 6px;
		border: none;
		border-radius: 4px;
		background: var(--erd-muted);
		color: var(--erd-foreground);
		font-size: 10px;
		cursor: pointer;
		transition: background 0.1s;
	}

	.selected-badge:hover {
		background: color-mix(in srgb, var(--erd-muted) 80%, transparent);
	}

	/* Stats */
	.stats-divider {
		width: 1px;
		height: 16px;
		background: var(--erd-border);
	}

	.stat-item {
		display: flex;
		align-items: center;
		gap: 6px;
		white-space: nowrap;
	}

	.stat-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
	}

	.stat-of {
		opacity: 0.6;
	}

	.stats-hint {
		margin-left: auto;
		opacity: 0.7;
		font-size: 11px;
	}

	/* Canvas */
	.erd-canvas {
		flex: 1;
		position: relative;
		overflow: hidden;
		background: var(--erd-background);
	}

	/* Svelte Flow overrides */
	.erd-canvas :global(.svelte-flow),
	.erd-canvas :global(.svelte-flow__renderer),
	.erd-canvas :global(.svelte-flow__pane) {
		background: var(--erd-background) !important;
		color: var(--erd-foreground);
	}

	/* Edge labels above table nodes so they don't get hidden behind */
	.erd-canvas :global(.svelte-flow__edgelabel-renderer) {
		z-index: 5 !important;
	}

	.erd-canvas :global(.svelte-flow__controls) {
		background: color-mix(in srgb, var(--erd-card) 90%, transparent) !important;
		border: 1px solid var(--erd-border) !important;
		border-radius: 8px !important;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08) !important;
	}

	.erd-canvas :global(.svelte-flow__controls-button) {
		background: var(--erd-card) !important;
		border-bottom: 1px solid var(--erd-border) !important;
		color: var(--erd-foreground) !important;
	}

	.erd-canvas :global(.svelte-flow__controls-button:hover) {
		background: var(--erd-accent) !important;
	}

	.erd-canvas :global(.svelte-flow__controls-button svg) {
		fill: currentColor;
	}

	.erd-canvas :global(.svelte-flow__minimap) {
		background: color-mix(in srgb, var(--erd-card) 80%, transparent) !important;
		border: 1px solid var(--erd-border) !important;
		border-radius: 8px !important;
	}
</style>
