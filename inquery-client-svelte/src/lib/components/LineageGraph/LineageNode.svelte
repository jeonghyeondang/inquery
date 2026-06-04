<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';
	import type { ExpandDirection } from './lineageLayout';

	let { data }: NodeProps = $props();

	let label = $derived(data.label as string);
	let uniqueId = $derived(data.uniqueId as string);
	let resourceType = $derived(data.resourceType as string);
	let rawDescription = $derived(data.description as string);
	let description = $derived.by(() => {
		if (!rawDescription) return '';
		const trimmed = rawDescription.replace(/^dbt:\s*/i, '').trim();
		return trimmed;
	});
	let materialization = $derived(data.materialization as string | null);
	let schema = $derived(data.schema as string);
	let database = $derived(data.database as string);
	let focused = $derived(data.focused as boolean);
	let compiledSql = $derived(data.compiledSql as string);
	let hiddenUpstream = $derived(data.hiddenUpstream as number);
	let hiddenDownstream = $derived(data.hiddenDownstream as number);
	let expandedUpstream = $derived(data.expandedUpstream as boolean);
	let expandedDownstream = $derived(data.expandedDownstream as boolean);
	let onToggle = $derived(data.onToggle as ((id: string, dir: ExpandDirection) => void) | undefined);

	let showSql = $state(false);

	let typeColor = $derived.by(() => {
		switch (resourceType) {
			case 'source': return '#ef4444';
			case 'seed': return '#f59e0b';
			case 'snapshot': return '#8b5cf6';
			default: {
				switch (materialization) {
					case 'view': return '#06b6d4';
					case 'incremental': return '#22c55e';
					case 'ephemeral': return '#a855f7';
					default: return '#3b82f6';
				}
			}
		}
	});

	let typeLabel = $derived.by(() => {
		if (resourceType === 'source') return 'SOURCE';
		if (resourceType === 'seed') return 'SEED';
		if (resourceType === 'snapshot') return 'SNAP';
		if (materialization) return materialization.toUpperCase();
		return 'MODEL';
	});
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<div class="lineage-node" class:focused>
	<Handle type="target" position={Position.Left} class="lineage-handle" />
	<Handle type="source" position={Position.Right} class="lineage-handle" />

	<div class="side-elements">
		{#if hiddenUpstream > 0}
			<button type="button" class="expand-contract-btn upstream"
				onclick={(e) => { e.stopPropagation(); onToggle?.(uniqueId, 'upstream'); }}>
				<span class="ec-btn-inner">
					<svg class="ec-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 6 9 12 15 18"/></svg>
					<span class="ec-count">{hiddenUpstream}</span>
				</span>
			</button>
		{:else if expandedUpstream && !focused}
			<button type="button" class="expand-contract-btn upstream" aria-label="Collapse upstream"
				onclick={(e) => { e.stopPropagation(); onToggle?.(uniqueId, 'upstream'); }}>
				<span class="ec-btn-inner">
					<svg class="ec-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 6 15 12 9 18"/></svg>
				</span>
			</button>
		{/if}

		{#if hiddenDownstream > 0}
			<button type="button" class="expand-contract-btn downstream"
				onclick={(e) => { e.stopPropagation(); onToggle?.(uniqueId, 'downstream'); }}>
				<span class="ec-btn-inner">
					<span class="ec-count">{hiddenDownstream}</span>
					<svg class="ec-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
				</span>
			</button>
		{:else if expandedDownstream}
			<button type="button" class="expand-contract-btn downstream" aria-label="Collapse downstream"
				onclick={(e) => { e.stopPropagation(); onToggle?.(uniqueId, 'downstream'); }}>
				<span class="ec-btn-inner">
					<svg class="ec-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
				</span>
			</button>
		{/if}
	</div>

	{#if focused}
		<div class="home-pill">
			<svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12.97 2.59a1.5 1.5 0 0 0-1.94 0l-7.5 6.363A1.5 1.5 0 0 0 3 10.097V19.5A1.5 1.5 0 0 0 4.5 21h4.75a.75.75 0 0 0 .75-.75v-4.5a.75.75 0 0 1 .75-.75h2.5a.75.75 0 0 1 .75.75v4.5c0 .414.336.75.75.75h4.75a1.5 1.5 0 0 0 1.5-1.5v-9.403a1.5 1.5 0 0 0-.53-1.144l-7.5-6.363Z"/></svg>
			Focus
		</div>
	{/if}

	<div class="card-wrapper">
		<div class="type-indicator" style="background: {typeColor};"></div>
		<div class="card-body">
			<div class="top-row">
				<span class="type-badge" style="color: {typeColor};">{typeLabel}</span>
				{#if database || schema}
					<span class="context-path">{database || ''}{database && schema ? ' / ' : ''}{schema || ''}</span>
				{/if}
			</div>
			<div class="card-name" title={label}>{label}</div>
			{#if description}
				<div class="card-desc" title={description}>{description}</div>
			{/if}
		</div>
	</div>

	{#if compiledSql}
		<button type="button" class="sql-toggle-row" onclick={() => { showSql = !showSql; }}>
			<span class="sql-toggle-text">SQL</span>
			<svg class="sql-toggle-icon" class:open={showSql} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
		</button>
	{/if}

	{#if showSql && compiledSql}
		<div class="sql-panel">
			<pre class="sql-code">{compiledSql}</pre>
		</div>
	{/if}
</div>

<style>
	.lineage-node {
		position: relative;
		width: 320px;
		min-height: 90px;
		background-color: hsl(var(--card, 0 0% 100%));
		border-radius: 12px;
		border: 1px solid hsl(var(--border, 214 32% 91%));
		box-shadow: 0px 1px 2px 0px color-mix(in srgb, hsl(var(--foreground, 222 84% 5%)) 7%, transparent);
		display: flex;
		align-items: center;
		flex-direction: column;
		overflow: visible;
		cursor: pointer;
		transition: border-color 0.15s, box-shadow 0.15s;
	}

	.lineage-node.focused {
		border-color: hsl(var(--primary, 221 83% 53%));
		box-shadow: 0 0 4px 4px color-mix(in srgb, hsl(var(--primary, 221 83% 53%)) 12%, transparent);
	}

	.lineage-node:hover {
		box-shadow: 0px 2px 8px color-mix(in srgb, hsl(var(--foreground, 222 84% 5%)) 12%, transparent);
	}

	:global(.lineage-handle) {
		background: transparent !important;
		border: none !important;
		width: 1px !important;
		height: 1px !important;
	}

	.side-elements {
		position: absolute;
		top: 26px;
		width: 100%;
		left: 0;
		z-index: 10;
		pointer-events: none;
	}

	.expand-contract-btn {
		pointer-events: auto;
		position: absolute;
		transform: translateY(-50%);
		background-color: hsl(var(--card, 0 0% 100%));
		color: hsl(var(--primary, 221 83% 53%));
		cursor: pointer;
		border: none;
		padding: 0;
		font: inherit;
		border-radius: 4px;
		box-shadow: 0px 1px 2px 0px color-mix(in srgb, hsl(var(--foreground, 222 84% 5%)) 7%, transparent);
		display: flex;
		align-items: center;
		overflow: hidden;
	}

	.expand-contract-btn.upstream {
		right: calc(100% + 10px);
		transform: translateY(-50%);
	}

	.expand-contract-btn.downstream {
		left: calc(100% + 10px);
		transform: translateY(-50%);
	}

	.ec-btn-inner {
		display: flex;
		align-items: center;
		padding: 4px;
		line-height: 0;
		font-size: 12px;
		transition: background-color 0.15s;
	}

	.ec-btn-inner:hover {
		background-color: hsl(var(--muted, 210 40% 96%));
	}

	.ec-icon {
		width: 18px;
		height: 18px;
		flex-shrink: 0;
	}

	.ec-count {
		font-weight: 600;
		line-height: 1;
		padding: 0 1px;
	}

	

	.home-pill {
		position: absolute;
		top: -20px;
		left: 12px;
		z-index: -1;
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 0 6px;
		background-color: hsl(var(--muted, 210 40% 96%));
		border: 1px solid hsl(var(--border, 214 32% 91%));
		border-top-left-radius: 4px;
		border-top-right-radius: 4px;
		color: hsl(var(--primary, 221 83% 53%));
		font-size: 11px;
		font-weight: 600;
		line-height: 20px;
	}

	.card-wrapper {
		display: flex;
		width: 100%;
		gap: 0;
		border-radius: 12px;
		overflow: hidden;
	}

	.type-indicator {
		width: 4px;
		flex-shrink: 0;
	}

	.card-body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		justify-content: center;
		padding: 8px 10px;
		gap: 1px;
	}

	.top-row {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 12px;
		max-height: 20px;
	}

	.type-badge {
		font-weight: 700;
		font-size: 10px;
		letter-spacing: 0.3px;
		flex-shrink: 0;
	}

	.context-path {
		color: hsl(var(--muted-foreground, 215 16% 47%));
		font-size: 11px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.card-name {
		font-size: 14px;
		font-weight: 600;
		color: hsl(var(--foreground, 222 84% 5%));
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		line-height: 1.4;
	}

	.card-desc {
		font-size: 12px;
		font-weight: 400;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		line-height: 1.4;
		overflow: hidden;
		text-overflow: ellipsis;
		display: -webkit-box;
		-webkit-line-clamp: 1;
		-webkit-box-orient: vertical;
		margin-top: 2px;
	}

	.sql-toggle-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		width: 100%;
		padding: 0 10px;
		border: none;
		font: inherit;
		background: transparent;
		min-height: 27px;
		max-height: 35px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		font-weight: 600;
		font-size: 12px;
		letter-spacing: -0.06px;
		line-height: 1.5;
		cursor: pointer;
		border-top: 1px solid hsl(var(--border, 214 32% 91%));
		border-bottom-left-radius: 12px;
		border-bottom-right-radius: 12px;
		transition: background-color 0.15s;
	}

	.sql-toggle-row:hover {
		background-color: hsl(var(--muted, 210 40% 96%));
	}

	.sql-toggle-text {
		font-size: 11px;
	}

	.sql-toggle-icon {
		width: 14px;
		height: 14px;
		color: hsl(var(--muted-foreground, 215 16% 47%));
		transition: transform 0.2s;
	}

	.sql-toggle-icon.open {
		transform: rotate(180deg);
	}

	.sql-panel {
		width: 100%;
		padding: 0 10px 8px;
		max-height: 180px;
		overflow: auto;
	}

	.sql-code {
		font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
		font-size: 10px;
		line-height: 1.5;
		color: hsl(var(--foreground, 222 84% 5%));
		background: hsl(var(--muted, 210 40% 96%));
		border: 1px solid hsl(var(--border, 214 32% 91%));
		border-radius: 6px;
		padding: 8px;
		margin: 0;
		white-space: pre-wrap;
		word-break: break-word;
	}
</style>
