<script lang="ts">
	import type { Snippet } from 'svelte';

	export interface ContextMenuItem {
		label: string;
		icon?: any;
		iconClass?: string;
		action: () => void;
		separator?: boolean;
		disabled?: boolean;
		destructive?: boolean;
	}

	interface Props {
		items: ContextMenuItem[];
		x: number;
		y: number;
		onclose: () => void;
	}

	let { items, x, y, onclose }: Props = $props();

	function handleClick(item: ContextMenuItem) {
		if (item.disabled) return;
		item.action();
		onclose();
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') onclose();
	}
</script>

<svelte:window onkeydown={handleKeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="fixed inset-0 z-[100]" onclick={onclose}>
	<div
		class="absolute z-[101] min-w-[160px] rounded-md border border-border bg-popover p-1 shadow-lg animate-in fade-in-0 zoom-in-95"
		style="left: {x}px; top: {y}px;"
		onclick={(e) => e.stopPropagation()}
	>
		{#each items as item}
			{#if item.separator}
				<div class="h-px bg-border my-1"></div>
			{:else}
				<button
					class="flex items-center gap-2 w-full px-2 py-1.5 text-xs rounded-sm transition-colors text-left
						{item.disabled ? 'opacity-50 cursor-not-allowed' : 'hover:bg-accent cursor-pointer'}
						{item.destructive ? 'text-destructive' : ''}"
					onclick={() => handleClick(item)}
					disabled={item.disabled}
				>
					{#if item.icon}
						{@const Icon = item.icon}
						<Icon size={14} class="shrink-0 {item.iconClass || ''}" />
					{/if}
					<span>{item.label}</span>
				</button>
			{/if}
		{/each}
	</div>
</div>
