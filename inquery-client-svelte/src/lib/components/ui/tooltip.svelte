<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import type { Snippet } from 'svelte';

	interface Props {
		content: string;
		side?: 'top' | 'bottom' | 'left' | 'right';
		class?: string;
		children?: Snippet;
	}

	let { content, side = 'top', class: className = '', children }: Props = $props();
	let visible = $state(false);
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
	class="relative inline-flex"
	onmouseenter={() => visible = true}
	onmouseleave={() => visible = false}
	onfocusin={() => visible = true}
	onfocusout={() => visible = false}
>
	{@render children?.()}
	{#if visible && content}
		<div
			role="tooltip"
			class={cn(
				'absolute z-50 overflow-hidden rounded-md border border-border bg-popover px-3 py-1.5 text-sm text-popover-foreground shadow-md animate-in fade-in-0 zoom-in-95',
				side === 'top' && 'bottom-full left-1/2 -translate-x-1/2 mb-2',
				side === 'bottom' && 'top-full left-1/2 -translate-x-1/2 mt-2',
				side === 'left' && 'right-full top-1/2 -translate-y-1/2 mr-2',
				side === 'right' && 'left-full top-1/2 -translate-y-1/2 ml-2',
				className
			)}
		>
			{content}
		</div>
	{/if}
</div>
