<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { X } from 'lucide-svelte';
	import type { Snippet } from 'svelte';

	interface Props {
		open?: boolean;
		class?: string;
		onclose?: () => void;
		children?: Snippet;
	}

	let { open = $bindable(false), class: className = '', onclose, children }: Props = $props();

	function handleOverlayClick() {
		open = false;
		onclose?.();
	}

	function handleKeydown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') {
			open = false;
			onclose?.();
		}
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#if open}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50 flex items-center justify-center">
		<div class="fixed inset-0 bg-black/50 animate-in fade-in-0" onclick={handleOverlayClick}></div>
		<div
			class={cn(
				'relative z-50 grid w-full max-w-lg gap-4 border border-border bg-card p-6 shadow-lg rounded-lg animate-in fade-in-0 zoom-in-95',
				className
			)}
		>
			{@render children?.()}
			<button
				class="absolute right-4 top-4 rounded-sm opacity-70 ring-offset-background transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
				onclick={() => { open = false; onclose?.(); }}
			>
				<X size={16} />
				<span class="sr-only">Close</span>
			</button>
		</div>
	</div>
{/if}
