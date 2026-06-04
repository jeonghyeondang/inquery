<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { getContext } from 'svelte';
	import type { Snippet } from 'svelte';

	interface Props {
		value: string;
		class?: string;
		children?: Snippet;
	}

	let { value, class: className = '', children }: Props = $props();

	const tabs = getContext<{ value: string; setValue: (v: string) => void }>('tabs');
	let isActive = $derived(tabs.value === value);
</script>

{#if isActive}
	<div
		role="tabpanel"
		class={cn(
			'mt-2 ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
			className
		)}
	>
		{@render children?.()}
	</div>
{/if}
