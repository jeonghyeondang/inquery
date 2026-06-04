<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { X } from 'lucide-svelte';
	import type { Snippet } from 'svelte';

	interface Props {
		open?: boolean;
		side?: 'top' | 'bottom' | 'left' | 'right';
		class?: string;
		onclose?: () => void;
		children?: Snippet;
	}

	let { open = $bindable(false), side = 'right', class: className = '', onclose, children }: Props = $props();

	function handleClose() {
		open = false;
		onclose?.();
	}

	function handleKeydown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') handleClose();
	}

	const sideClasses: Record<string, string> = {
		top: 'inset-x-0 top-0 border-b',
		bottom: 'inset-x-0 bottom-0 border-t',
		left: 'inset-y-0 left-0 h-full w-3/4 border-r sm:max-w-sm',
		right: 'inset-y-0 right-0 h-full w-3/4 border-l sm:max-w-sm'
	};

	const slideClasses: Record<string, string> = {
		top: 'animate-in slide-in-from-top',
		bottom: 'animate-in slide-in-from-bottom',
		left: 'animate-in slide-in-from-left',
		right: 'animate-in slide-in-from-right'
	};
</script>

<svelte:window onkeydown={handleKeydown} />

{#if open}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50">
		<div class="fixed inset-0 bg-black/50 animate-in fade-in-0" onclick={handleClose}></div>
		<div
			class={cn(
				'fixed z-50 gap-4 bg-card p-6 shadow-lg transition ease-in-out',
				sideClasses[side],
				slideClasses[side],
				className
			)}
		>
			{@render children?.()}
			<button
				class="absolute right-4 top-4 rounded-sm opacity-70 ring-offset-background transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
				onclick={handleClose}
			>
				<X size={16} />
				<span class="sr-only">Close</span>
			</button>
		</div>
	</div>
{/if}
