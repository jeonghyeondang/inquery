<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		children?: import('svelte').Snippet;
		[key: string]: any;
	}

	let { class: className = '', children, ...restProps }: Props = $props();
</script>

<DropdownMenu.GroupHeading
	class={cn('px-2 py-1.5 text-sm font-semibold', className)}
	{...restProps}
>
	{@render children?.()}
</DropdownMenu.GroupHeading>
