<script lang="ts">
	import type { NodeProps } from '@xyflow/svelte';

	let { data }: NodeProps = $props();

	let label = $derived(data.label as string);
	let tableCount = $derived(data.tableCount as number);
	let color = $derived(data.color as string);
</script>

<div
	class="cluster-node"
	style="
		background-color: {color}08;
		border-color: {color}30;
		width: 100%;
		height: 100%;
	"
>
	<div
		class="cluster-label"
		style="
			background-color: {color}20;
			color: {color};
		"
	>
		{label} · {tableCount} tables
	</div>
</div>

<style>
	.cluster-node {
		border-radius: 16px;
		border: 2px dashed;
		transition: all 0.2s;
		width: 100%;
		height: 100%;
	}

	.cluster-label {
		position: absolute;
		top: -12px;
		left: 16px;
		padding: 2px 8px;
		border-radius: 9999px;
		font-size: 10px;
		font-weight: 500;
		letter-spacing: 0.5px;
		text-transform: uppercase;
		white-space: nowrap;
	}
</style>
