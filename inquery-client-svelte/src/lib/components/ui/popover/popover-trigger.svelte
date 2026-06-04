<script lang="ts">
	import { Popover } from 'bits-ui';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		children?: import('svelte').Snippet;
		[key: string]: any;
	}

	let { class: className = '', children, ...restProps }: Props = $props();
</script>

<Popover.Trigger class={cn('outline-none', className)} {...restProps}>
	{@render children?.()}
</Popover.Trigger>
