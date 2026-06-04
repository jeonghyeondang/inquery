<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils/cn';

	interface Props {
		class?: string;
		children: Snippet;
		onclick?: (e: MouseEvent) => void;
	}

	let { class: className = '', children, onclick }: Props = $props();
</script>

{#if onclick}
<div {onclick} role="button" tabindex="0" onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') onclick?.(e as unknown as MouseEvent); }} class={cn('rounded-lg border bg-card text-card-foreground shadow-sm', className)}>
	{@render children()}
</div>
{:else}
<div class={cn('rounded-lg border bg-card text-card-foreground shadow-sm', className)}>
	{@render children()}
</div>
{/if}
