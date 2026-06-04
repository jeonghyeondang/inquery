<script lang="ts">
	import { Popover } from 'bits-ui';

	interface Props {
		open?: boolean;
		onOpenChange?: (open: boolean) => void;
		children?: import('svelte').Snippet;
	}

	let { open = $bindable(false), onOpenChange, children }: Props = $props();
</script>

<Popover.Root bind:open {onOpenChange}>
	{@render children?.()}
</Popover.Root>
