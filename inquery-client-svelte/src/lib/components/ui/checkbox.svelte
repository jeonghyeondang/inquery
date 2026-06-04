<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { Check, Minus } from 'lucide-svelte';

	interface Props {
		checked?: boolean | 'indeterminate';
		disabled?: boolean;
		class?: string;
		id?: string;
		onchange?: (e: Event) => void;
	}

	let { checked = $bindable(false), disabled = false, class: className = '', id, onchange }: Props = $props();

	const isChecked = $derived(checked === true);
	const isIndeterminate = $derived(checked === 'indeterminate');
</script>

<button
	type="button"
	role="checkbox"
	aria-checked={isIndeterminate ? 'mixed' : isChecked}
	{id}
	{disabled}
	onclick={() => {
		if (!disabled) {
			checked = isChecked || isIndeterminate ? false : true;
		}
		onchange?.(new Event('change'));
	}}
	class={cn(
		'peer h-4 w-4 shrink-0 rounded-sm border border-primary ring-offset-background',
		'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
		'disabled:cursor-not-allowed disabled:opacity-50',
		(isChecked || isIndeterminate) && 'bg-primary text-primary-foreground',
		className
	)}
>
	{#if isChecked}
		<Check class="h-3.5 w-3.5" />
	{:else if isIndeterminate}
		<Minus class="h-3.5 w-3.5" />
	{/if}
</button>
