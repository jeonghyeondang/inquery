<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		type?: string;
		value?: string;
		placeholder?: string;
		disabled?: boolean;
		class?: string;
		id?: string;
		autocomplete?: any;
		oninput?: (e: Event) => void;
		onkeydown?: (e: KeyboardEvent) => void;
	}

	let { type = 'text', value = $bindable(''), placeholder = '', disabled = false, class: className = '', id, autocomplete, oninput, onkeydown }: Props = $props();
</script>

<input
	{type}
	{id}
	bind:value
	{placeholder}
	{disabled}
	{autocomplete}
	{oninput}
	{onkeydown}
	class={cn(
		'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
		className
	)}
/>
