<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils/cn';

	type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline';

	interface Props {
		variant?: BadgeVariant;
		class?: string;
		children: Snippet;
	}

	const variantClasses: Record<BadgeVariant, string> = {
		default: 'border-transparent bg-primary text-primary-foreground',
		secondary: 'border-transparent bg-secondary text-secondary-foreground',
		destructive: 'border-transparent bg-destructive text-destructive-foreground',
		outline: 'text-foreground'
	};

	let { variant = 'default', class: className = '', children }: Props = $props();
</script>

<div class={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors', variantClasses[variant], className)}>
	{@render children()}
</div>
