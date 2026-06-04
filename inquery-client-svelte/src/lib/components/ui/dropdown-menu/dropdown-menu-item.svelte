<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		disabled?: boolean;
		destructive?: boolean;
		onSelect?: (e: Event) => void;
		children?: import('svelte').Snippet;
		[key: string]: any;
	}

	let { class: className = '', disabled = false, destructive = false, onSelect, children, ...restProps }: Props = $props();
</script>

<DropdownMenu.Item
	{disabled}
	{onSelect}
	class={cn(
		'relative flex cursor-default select-none items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none transition-colors',
		'focus:bg-accent focus:text-accent-foreground',
		'data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
		destructive && 'text-destructive focus:text-destructive',
		className
	)}
	{...restProps}
>
	{@render children?.()}
</DropdownMenu.Item>
