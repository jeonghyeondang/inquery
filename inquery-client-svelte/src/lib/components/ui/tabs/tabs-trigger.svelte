<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { getContext } from 'svelte';
	import type { Snippet } from 'svelte';

	interface Props {
		value: string;
		disabled?: boolean;
		class?: string;
		children?: Snippet;
	}

	let { value, disabled = false, class: className = '', children }: Props = $props();

	const tabs = getContext<{ value: string; setValue: (v: string) => void }>('tabs');
	let isActive = $derived(tabs.value === value);
</script>

<button
	type="button"
	role="tab"
	aria-selected={isActive}
	{disabled}
	onclick={() => { if (!disabled) tabs.setValue(value); }}
	class={cn(
		'inline-flex items-center justify-center whitespace-nowrap rounded-sm px-3 py-1.5 text-sm font-medium ring-offset-background transition-all',
		'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
		'disabled:pointer-events-none disabled:opacity-50',
		isActive ? 'bg-background text-foreground shadow-sm' : '',
		className
	)}
>
	{@render children?.()}
</button>
