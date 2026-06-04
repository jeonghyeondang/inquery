<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		[key: string]: any;
	}

	let { class: className = '', ...restProps }: Props = $props();
</script>

<DropdownMenu.Separator
	class={cn('-mx-1 my-1 h-px bg-border', className)}
	{...restProps}
/>
