<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';
	import type { ERDColumn } from './types';

	let { data }: NodeProps = $props();

	let label = $derived(data.label as string);
	let schemaName = $derived(data.schemaName as string);
	let columns = $derived(data.columns as ERDColumn[]);
	let isHub = $derived(data.isHub as boolean);
	let clusterColor = $derived(data.clusterColor as string);
	let relationshipCount = $derived(data.relationshipCount as number);
</script>

<div
	class="table-node"
	class:hub={isHub}
	style="
		border-color: {isHub ? clusterColor : 'hsl(var(--border))'};
	"
>
	<!-- Header -->
	<div
		class="table-header"
		style="
			background: {isHub
				? `linear-gradient(135deg, ${clusterColor}15, ${clusterColor}08)`
				: 'hsl(var(--muted))'};
			border-color: {isHub ? `${clusterColor}30` : 'hsl(var(--border))'};
		"
	>
		<div class="header-content">
			{#if isHub}
				<svg class="hub-icon" style="color: {clusterColor};" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
					<line x1="6" y1="3" x2="6" y2="15" />
					<circle cx="18" cy="6" r="3" />
					<circle cx="6" cy="18" r="3" />
					<path d="M18 9a9 9 0 0 1-9 9" />
				</svg>
			{/if}
			<div class="header-text">
				<div
					class="table-name"
					class:hub-name={isHub}
					style={isHub ? `color: ${clusterColor};` : ''}
				>
					{label}
				</div>
				<div class="table-meta">
					<span>{schemaName}</span>
					{#if relationshipCount > 0}
						<span class="meta-sep">·</span>
						<span style="color: {clusterColor}90;">
							{relationshipCount} relation{relationshipCount !== 1 ? 's' : ''}
						</span>
					{/if}
				</div>
			</div>
		</div>
	</div>

	<!-- Columns -->
	<div class="columns-list">
		{#each columns as col (col.name)}
			<div class="col-row">
				<!-- Target handle: only PK columns -->
				{#if col.isPrimaryKey}
					<Handle
						type="target"
						position={Position.Left}
						id={`${col.name}-target`}
						class="col-handle col-handle-pk"
					/>
				{/if}

				<!-- Source handle: only FK columns -->
				{#if col.foreignKey}
					<Handle
						type="source"
						position={Position.Right}
						id={`${col.name}-source`}
						class="col-handle col-handle-fk"
						style="background-color: {clusterColor};"
					/>
				{/if}

				<!-- Column icon -->
				{#if col.isPrimaryKey}
					<svg class="col-icon pk-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
					</svg>
				{:else if col.foreignKey}
					<svg class="col-icon" style="color: {clusterColor};" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
					</svg>
				{:else}
					<svg class="col-icon muted-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
						<rect x="3" y="3" width="18" height="18" rx="2" />
						<path d="M3 9h18" />
						<path d="M3 15h18" />
						<path d="M9 3v18" />
					</svg>
				{/if}

				<span class="col-name" class:pk-name={col.isPrimaryKey}>{col.name}</span>
				<span class="col-type">{col.dataType}</span>

				{#if !col.isNullable && !col.isPrimaryKey}
					<span class="col-notnull">*</span>
				{/if}
			</div>
		{/each}
	</div>
</div>

<style>
	.table-node {
		background: hsl(var(--card));
		border: 1px solid hsl(var(--border));
		border-radius: 8px;
		min-width: 220px;
		overflow: hidden;
		box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08), 0 1px 2px rgba(0, 0, 0, 0.06);
		transition: all 0.15s;
	}

	.table-node.hub {
		border-width: 2px;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1), 0 2px 4px rgba(0, 0, 0, 0.06);
	}

	:global(.dark) .table-node {
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.28), 0 2px 8px rgba(0, 0, 0, 0.2);
	}

	.table-header {
		padding: 8px 12px;
		border-bottom: 1px solid hsl(var(--border));
	}

	.header-content {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.hub-icon {
		flex-shrink: 0;
	}

	.header-text {
		flex: 1;
		min-width: 0;
	}

	.table-name {
		font-size: 13px;
		font-weight: 600;
		color: hsl(var(--foreground));
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.table-name.hub-name {
		font-weight: 700;
	}

	.table-meta {
		display: flex;
		align-items: center;
		gap: 4px;
		font-size: 10px;
		color: hsl(var(--muted-foreground));
		margin-top: 1px;
	}

	.meta-sep {
		opacity: 0.4;
	}

	.columns-list {
		padding: 4px 0;
		max-height: 300px;
		overflow-y: auto;
	}

	.col-row {
		position: relative;
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 5px 12px;
		font-size: 12px;
		transition: background-color 0.1s;
	}

	.col-row:hover {
		background: color-mix(in srgb, hsl(var(--muted)) 70%, transparent);
	}

	.col-icon {
		flex-shrink: 0;
	}

	.pk-icon {
		color: #f59e0b;
	}

	.muted-icon {
		color: hsl(var(--muted-foreground));
		opacity: 0.4;
	}

	.col-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: hsl(var(--foreground));
	}

	.col-name.pk-name {
		font-weight: 500;
	}

	.col-type {
		font-size: 10px;
		font-family: monospace;
		color: hsl(var(--muted-foreground));
		opacity: 0.7;
		flex-shrink: 0;
	}

	.col-notnull {
		color: rgba(248, 113, 113, 0.8);
		font-size: 10px;
		font-weight: 700;
		flex-shrink: 0;
	}

	:global(.col-handle) {
		width: 10px !important;
		height: 10px !important;
		border: 2px solid hsl(var(--background)) !important;
	}

	:global(.col-handle-pk) {
		background-color: #f59e0b !important;
	}

	:global(.col-handle-fk) {
		border: 2px solid hsl(var(--background)) !important;
	}
</style>
