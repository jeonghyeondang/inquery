<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		src?: string;
		alt?: string;
		fallback?: string;
		class?: string;
	}

	let { src, alt = '', fallback = '', class: className = '' }: Props = $props();
	let imgError = $state(false);
</script>

<span class={cn('relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full', className)}>
	{#if src && !imgError}
		<img
			{src}
			{alt}
			class="aspect-square h-full w-full"
			onerror={() => imgError = true}
		/>
	{:else}
		<span class="flex h-full w-full items-center justify-center rounded-full bg-muted text-sm font-medium">
			{fallback || alt?.charAt(0)?.toUpperCase() || '?'}
		</span>
	{/if}
</span>
