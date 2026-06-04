<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { setContext } from 'svelte';
	import type { Snippet } from 'svelte';

	interface Props {
		value?: string;
		class?: string;
		onValueChange?: (value: string) => void;
		children?: Snippet;
	}

	let { value = $bindable(''), class: className = '', onValueChange, children }: Props = $props();

	setContext('tabs', {
		get value() { return value; },
		setValue(v: string) {
			value = v;
			onValueChange?.(v);
		}
	});
</script>

<div class={cn('', className)}>
	{@render children?.()}
</div>
