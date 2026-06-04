<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		checked?: boolean;
		disabled?: boolean;
		class?: string;
		id?: string;
		title?: string;
		size?: 'default' | 'small';
		onchange?: (e: Event) => void;
	}

	let { checked = $bindable(false), disabled = false, class: className = '', id, title, size = 'default', onchange }: Props = $props();

	const isSmall = $derived(size === 'small');
</script>

<button
	type="button"
	role="switch"
	aria-checked={checked}
	{id}
	{disabled}
	{title}
	onclick={() => { if (!disabled) checked = !checked; onchange?.(new Event('change')); }}
	class={cn(
		'peer inline-flex shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors',
		'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
		'disabled:cursor-not-allowed disabled:opacity-50',
		isSmall ? 'h-3.5 w-6' : 'h-6 w-11',
		checked ? 'bg-primary' : 'bg-input',
		className
	)}
>
	<span
		class={cn(
			'pointer-events-none block rounded-full bg-background shadow-lg ring-0 transition-transform',
			isSmall ? 'h-2.5 w-2.5' : 'h-5 w-5',
			checked
				? (isSmall ? 'translate-x-2.5' : 'translate-x-5')
				: 'translate-x-0'
		)}
	></span>
</button>
