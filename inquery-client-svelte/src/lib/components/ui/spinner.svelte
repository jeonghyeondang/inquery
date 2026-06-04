<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		size?: 'sm' | 'md' | 'lg';
		class?: string;
	}

	const sizeClasses = { sm: 'h-4 w-4', md: 'h-6 w-6', lg: 'h-8 w-8' };

	let { size = 'md', class: className = '' }: Props = $props();
</script>

<div class={cn('animate-spin rounded-full border-2 border-current border-t-transparent', sizeClasses[size], className)}></div>
