<script lang="ts">
	import { Popover } from 'bits-ui';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		sideOffset?: number;
		align?: 'start' | 'center' | 'end';
		side?: 'top' | 'right' | 'bottom' | 'left';
		children?: import('svelte').Snippet;
		[key: string]: any;
	}

	let { class: className = '', sideOffset = 4, align = 'start', side = 'bottom', children, ...restProps }: Props = $props();
</script>

<Popover.Portal>
	<Popover.Content
		{sideOffset}
		{align}
		{side}
		class={cn(
			'z-50 w-72 rounded-md border border-border/50 bg-popover p-2 text-popover-foreground shadow-md outline-none',
			'data-[state=open]:animate-in data-[state=closed]:animate-out',
			'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
			'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
			'data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2',
			'data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2',
			className
		)}
		{...restProps}
	>
		{@render children?.()}
	</Popover.Content>
</Popover.Portal>
