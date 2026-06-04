<script lang="ts">
	import { DropdownMenu } from 'bits-ui';

	interface Props {
		open?: boolean;
		onOpenChange?: (open: boolean) => void;
		children?: import('svelte').Snippet;
	}

	let { open = $bindable(false), onOpenChange, children }: Props = $props();
</script>

<DropdownMenu.Root bind:open {onOpenChange}>
	{@render children?.()}
</DropdownMenu.Root>
