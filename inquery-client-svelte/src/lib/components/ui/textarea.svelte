<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		value?: string;
		placeholder?: string;
		disabled?: boolean;
		rows?: number;
		class?: string;
		id?: string;
		oninput?: (e: Event) => void;
	}

	let { value = $bindable(''), placeholder = '', disabled = false, rows = 3, class: className = '', id, oninput }: Props = $props();
</script>

<textarea
	{id}
	bind:value
	{placeholder}
	{disabled}
	{rows}
	{oninput}
	class={cn(
		'flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background',
		'placeholder:text-muted-foreground',
		'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
		'disabled:cursor-not-allowed disabled:opacity-50',
		className
	)}
></textarea>
