<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import { initTheme } from '$lib/stores/theme.svelte';
	import { NotificationToast } from '$lib/components/NotificationToast';

	let { children } = $props();

	onMount(() => {
		initTheme();
	});
</script>

<svelte:head>
	<title>Inquery</title>
	<meta name="description" content="AI-powered database query assistant" />
</svelte:head>

{@render children()}
<NotificationToast />
