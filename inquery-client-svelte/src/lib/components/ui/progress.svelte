<script lang="ts">
	import { cn } from '$lib/utils/cn';

	interface Props {
		value?: number;
		max?: number;
		class?: string;
		indicatorClass?: string;
	}

	let { value = 0, max = 100, class: className = '', indicatorClass = '' }: Props = $props();

	let percentage = $derived(Math.min(100, Math.max(0, (value / max) * 100)));
</script>

<div
	role="progressbar"
	aria-valuemin={0}
	aria-valuemax={max}
	aria-valuenow={value}
	class={cn('relative h-4 w-full overflow-hidden rounded-full bg-primary/20', className)}
>
	<div
		class={cn('h-full w-full flex-1 bg-primary transition-all', indicatorClass)}
		style="transform: translateX(-{100 - percentage}%)"
	></div>
</div>
