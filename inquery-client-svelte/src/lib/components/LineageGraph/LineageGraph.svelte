<script lang="ts">
	import {
		SvelteFlow,
		Controls,
		MiniMap,
		Background,
		type Node,
		type Edge,
		type NodeTypes
	} from '@xyflow/svelte';
	import '@xyflow/svelte/dist/style.css';

	import type { ILineageGraph } from '$lib/service/catalog';
	import {
		buildLineageFlowData, buildVisibleGraph, filterGraphByTable,
		type DirectionalExpand, type ExpandDirection
	} from './lineageLayout';
	import LineageNode from './LineageNode.svelte';
	import { getThemeStore } from '$lib/stores/theme.svelte';

	interface Props {
		graph: ILineageGraph | null;
		loading?: boolean;
		focusTable?: string;
		focusDatabase?: string;
		focusSchema?: string;
		// Backend lineage detection status. When the graph is empty, we use this to
		// disambiguate between "still detecting", "detection failed", "no data found",
		// and "never run yet" so the empty state never contradicts the toolbar above.
		detectionState?: 'RUNNING' | 'COMPLETED' | 'FAILED' | null;
		detectionError?: string;
	}

	let {
		graph,
		loading = false,
		focusTable,
		focusDatabase,
		focusSchema,
		detectionState = null,
		detectionError = ''
	}: Props = $props();

	const themeStore = getThemeStore();
	let flowColorMode = $derived<'light' | 'dark'>(themeStore.isDark ? 'dark' : 'light');

	let expandedNodes = $state<Map<string, DirectionalExpand>>(new Map());
	let positionCacheRef = new Map<string, { x: number; y: number }>();
	let shouldFitView = $state(true);

	$effect(() => {
		if (focusTable || graph) {
			expandedNodes = new Map();
			positionCacheRef = new Map();
			shouldFitView = true;
		}
	});

	function toggleNode(nodeId: string, direction: ExpandDirection) {
		const next = new Map(expandedNodes);
		const current = next.get(nodeId) || { upstream: false, downstream: false };
		next.set(nodeId, {
			...current,
			[direction]: !current[direction]
		});
		expandedNodes = next;
		shouldFitView = false;
	}

	const nodeTypes: NodeTypes = {
		lineageNode: LineageNode as any
	};

	let hasGraph = $derived(!!graph?.nodes?.length);
	let hasFocus = $derived(!!focusTable);

	let flowData = $derived.by(() => {
		if (!hasGraph || !hasFocus) return { nodes: [], edges: [] };

		const tableGraph = filterGraphByTable(graph!, focusTable!, focusDatabase, focusSchema);

		const { graph: visibleGraph, hiddenCounts } = buildVisibleGraph(
			tableGraph, focusTable!, expandedNodes, focusDatabase, focusSchema
		);

		return buildLineageFlowData(
			visibleGraph.nodes, visibleGraph.edges,
			focusTable!, hiddenCounts, expandedNodes, toggleNode,
			positionCacheRef
		);
	});

	let nodes = $state.raw<Node[]>([]);
	let edges = $state.raw<Edge[]>([]);

	$effect(() => {
		nodes = flowData.nodes;
		edges = flowData.edges;

		for (const n of flowData.nodes) {
			positionCacheRef.set(n.id, n.position);
		}
	});

	let sourceCount = $derived(nodes.filter((n) => (n.data?.resourceType as string) === 'source').length);
	let modelCount = $derived(nodes.filter((n) => (n.data?.resourceType as string) !== 'source').length);
	let edgeCount = $derived(edges.length);

	function miniMapNodeColor(node: Node): string {
		const type = node.data?.resourceType as string;
		switch (type) {
			case 'source': return '#ef4444';
			case 'seed': return '#f59e0b';
			case 'snapshot': return '#8b5cf6';
			default: return '#3b82f6';
		}
	}
</script>

<div class="lineage-root">
	{#if loading}
		<div class="lineage-loading">
			<div class="lineage-spinner"></div>
			<span>Loading lineage graph...</span>
		</div>
	{:else if !graph || !hasGraph}
		{#if detectionState === 'RUNNING'}
			<div class="lineage-empty">
				<div class="lineage-spinner"></div>
				<p class="empty-title">Detecting Lineage…</p>
				<p class="empty-desc">We're scanning your data source. This may take a moment.</p>
			</div>
		{:else if detectionState === 'FAILED'}
			<div class="lineage-empty">
				<div class="empty-icon-wrapper empty-icon-error">
					<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="12" cy="12" r="10" />
						<line x1="12" y1="8" x2="12" y2="12" />
						<line x1="12" y1="16" x2="12.01" y2="16" />
					</svg>
				</div>
				<p class="empty-title">Detection Failed</p>
				<p class="empty-desc">
					{detectionError || 'Something went wrong while detecting lineage.'}
					<br />Click "Re-detect" above to retry.
				</p>
			</div>
		{:else if detectionState === 'COMPLETED'}
			<div class="lineage-empty">
				<div class="empty-icon-wrapper">
					<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="5" cy="6" r="2" />
						<circle cx="12" cy="12" r="2" />
						<circle cx="19" cy="6" r="2" />
						<circle cx="19" cy="18" r="2" />
						<line x1="7" y1="6" x2="10" y2="11" />
						<line x1="17" y1="7" x2="14" y2="11" />
						<line x1="14" y1="13" x2="17" y2="17" />
					</svg>
				</div>
				<p class="empty-title">No Lineage Detected</p>
				<p class="empty-desc">No table-to-table dependencies were found for this data source.</p>
			</div>
		{:else}
			<div class="lineage-empty">
				<div class="empty-icon-wrapper">
					<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="5" cy="6" r="2" />
						<circle cx="12" cy="12" r="2" />
						<circle cx="19" cy="6" r="2" />
						<circle cx="19" cy="18" r="2" />
						<line x1="7" y1="6" x2="10" y2="11" />
						<line x1="17" y1="7" x2="14" y2="11" />
						<line x1="14" y1="13" x2="17" y2="17" />
					</svg>
				</div>
				<p class="empty-title">Lineage Not Built Yet</p>
				<p class="empty-desc">Click "Re-detect" above to build the lineage graph for this data source.</p>
			</div>
		{/if}
	{:else if !hasFocus}
		<div class="lineage-empty">
			<div class="empty-icon-wrapper">
				<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
					<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
					<polyline points="9 22 9 12 15 12 15 22" />
				</svg>
			</div>
			<p class="empty-title">Select a Table</p>
			<p class="empty-desc">Choose a table from the left panel to view its data lineage.</p>
		</div>
	{:else if nodes.length === 0}
		{#if detectionState === 'RUNNING'}
			<div class="lineage-empty">
				<div class="lineage-spinner"></div>
				<p class="empty-title">Detecting Lineage…</p>
				<p class="empty-desc">
					Scanning the data source. <strong>{focusTable}</strong>'s lineage will appear when detection completes.
				</p>
			</div>
		{:else}
			<div class="lineage-empty">
				<div class="empty-icon-wrapper">
					<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
						<circle cx="5" cy="6" r="2" />
						<circle cx="12" cy="12" r="2" />
						<circle cx="19" cy="6" r="2" />
						<circle cx="19" cy="18" r="2" />
						<line x1="7" y1="6" x2="10" y2="11" />
						<line x1="17" y1="7" x2="14" y2="11" />
						<line x1="14" y1="13" x2="17" y2="17" />
					</svg>
				</div>
				<p class="empty-title">No Lineage Found</p>
				<p class="empty-desc">No lineage data was detected for <strong>{focusTable}</strong>.</p>
			</div>
		{/if}
	{:else}
		<div class="lineage-stats-bar">
			<span class="stat-item">
				<span class="stat-dot" style="background: #ef4444;"></span>
				{sourceCount} source{sourceCount !== 1 ? 's' : ''}
			</span>
			<span class="stat-item">
				<span class="stat-dot" style="background: #3b82f6;"></span>
				{modelCount} model{modelCount !== 1 ? 's' : ''}
			</span>
			<span class="stat-item">
				<span class="stat-dot" style="background: #f59e0b;"></span>
				{edgeCount} edge{edgeCount !== 1 ? 's' : ''}
			</span>
			<span class="stats-hint">
				Left → Right: Source → Staging → Mart
			</span>
		</div>

		<div class="lineage-canvas">
			<SvelteFlow
				bind:nodes
				bind:edges
				{nodeTypes}
				fitView={shouldFitView}
				fitViewOptions={{ maxZoom: 1, duration: 0 }}
				minZoom={0.3}
				maxZoom={5}
				nodesDraggable={true}
				nodesConnectable={false}
				elementsSelectable={true}
				selectNodesOnDrag={false}
				proOptions={{ hideAttribution: true }}
				colorMode={flowColorMode}
			>
				<Background patternColor={themeStore.isDark ? '#444' : '#ccc'} gap={24} size={1} />
				<Controls showLock={false} />
				<MiniMap
					nodeColor={miniMapNodeColor}
					maskColor={themeStore.isDark ? 'rgba(30,30,30,0.7)' : 'rgba(255,255,255,0.7)'}
					pannable
					zoomable
				/>
			</SvelteFlow>
		</div>

		<div class="lineage-legend">
			<span class="legend-item"><span class="legend-dot" style="background: #ef4444;"></span> Source</span>
			<span class="legend-item"><span class="legend-dot" style="background: #3b82f6;"></span> Model (table)</span>
			<span class="legend-item"><span class="legend-dot" style="background: #06b6d4;"></span> Model (view)</span>
			<span class="legend-item"><span class="legend-dot" style="background: #22c55e;"></span> Model (incremental)</span>
			<span class="legend-item"><span class="legend-dot" style="background: #f59e0b;"></span> Seed</span>
			<span class="legend-item"><span class="legend-dot" style="background: #8b5cf6;"></span> Snapshot</span>
		</div>
	{/if}
</div>

<style>
	.lineage-root {
		display: flex;
		flex-direction: column;
		height: 100%;
		width: 100%;
		overflow: hidden;
	}

	.lineage-loading {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 100%;
		gap: 10px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		font-size: 13px;
	}

	.lineage-spinner {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		border: 2.5px solid hsl(var(--border, 214 32% 91%));
		border-top-color: hsl(var(--primary, 221 83% 53%));
		animation: spin 0.7s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.lineage-empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 100%;
		gap: 6px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
	}

	.empty-icon-wrapper {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 64px;
		height: 64px;
		border-radius: 16px;
		background: hsl(var(--muted, 210 40% 96%));
		color: hsl(var(--muted-foreground, 215 16% 47%));
		margin-bottom: 8px;
	}

	.empty-icon-wrapper.empty-icon-error {
		background: hsl(var(--destructive, 0 84% 60%) / 0.12);
		color: hsl(var(--destructive, 0 84% 60%));
	}

	.lineage-empty .lineage-spinner {
		margin-bottom: 12px;
	}

	.empty-title {
		font-size: 18px;
		font-weight: 600;
		color: hsl(var(--foreground, 222 84% 5%));
		margin: 0;
	}

	.empty-desc {
		font-size: 14px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		margin: 0 0 4px;
	}

	

	.lineage-stats-bar {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 6px 16px;
		border-bottom: 1px solid color-mix(in srgb, hsl(var(--border, 214 32% 91%)) 40%, transparent);
		background: color-mix(in srgb, hsl(var(--muted, 210 40% 96%)) 30%, transparent);
		font-size: 12px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		flex-shrink: 0;
	}

	.stat-item {
		display: flex;
		align-items: center;
		gap: 5px;
		white-space: nowrap;
	}

	.stat-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		opacity: 0.7;
	}

	.stats-hint {
		margin-left: auto;
		opacity: 0.7;
		font-size: 11px;
	}

	.lineage-canvas {
		flex: 1;
		position: relative;
		overflow: hidden;
	}

	.lineage-canvas :global(.svelte-flow__node:not(.dragging)) {
		transition: transform 250ms ease-in-out;
	}

	.lineage-canvas :global(.svelte-flow__controls) {
		background: color-mix(in srgb, hsl(var(--background, 0 0% 100%)) 90%, transparent) !important;
		border: 1px solid hsl(var(--border, 214 32% 91%)) !important;
		border-radius: 8px !important;
		box-shadow: 0 2px 8px color-mix(in srgb, hsl(var(--foreground, 222 84% 5%)) 8%, transparent) !important;
	}

	.lineage-canvas :global(.svelte-flow__controls button) {
		background: hsl(var(--background, 0 0% 100%)) !important;
		color: hsl(var(--foreground, 222 84% 5%)) !important;
		border-color: hsl(var(--border, 214 32% 91%)) !important;
	}

	.lineage-canvas :global(.svelte-flow__controls button:hover) {
		background: hsl(var(--accent, 210 40% 96%)) !important;
	}

	.lineage-canvas :global(.svelte-flow__minimap) {
		background: color-mix(in srgb, hsl(var(--background, 0 0% 100%)) 80%, transparent) !important;
		border: 1px solid hsl(var(--border, 214 32% 91%)) !important;
		border-radius: 8px !important;
	}

	.lineage-legend {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 5px 16px;
		border-top: 1px solid color-mix(in srgb, hsl(var(--border, 214 32% 91%)) 40%, transparent);
		background: color-mix(in srgb, hsl(var(--muted, 210 40% 96%)) 20%, transparent);
		font-size: 11px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		flex-shrink: 0;
	}

	.legend-item {
		display: flex;
		align-items: center;
		gap: 4px;
		white-space: nowrap;
	}

	.legend-dot {
		width: 8px;
		height: 8px;
		border-radius: 2px;
	}
</style>
