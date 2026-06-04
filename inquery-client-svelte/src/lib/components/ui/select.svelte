<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { ChevronDown } from 'lucide-svelte';

	interface Props {
		value?: string;
		disabled?: boolean;
		class?: string;
		id?: string;
		onchange?: (e: Event) => void;
		children?: import('svelte').Snippet;
	}

	let { value = $bindable(''), disabled = false, class: className = '', id, onchange, children }: Props = $props();
</script>

<div class="relative">
	<select
		{id}
		bind:value
		{disabled}
		{onchange}
		class={cn(
			'flex h-10 w-full appearance-none rounded-md border border-input bg-background px-3 py-2 pr-8 text-sm ring-offset-background',
			'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
			'disabled:cursor-not-allowed disabled:opacity-50',
			className
		)}
	>
		{@render children?.()}
	</select>
	<ChevronDown class="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
</div>
