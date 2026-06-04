<script lang="ts">
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';

	onMount(() => {
		goto('/connections');
	});
</script>

<div class="flex items-center justify-center min-h-screen">
	<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
</div>
