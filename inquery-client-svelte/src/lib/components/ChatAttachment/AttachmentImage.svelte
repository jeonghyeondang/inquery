<!--
	Authenticated <img> wrapper for chat attachments.

	Native <img src=...> can't carry the custom `Inquery` auth header that
	this app uses, so plain URLs to /api/ai/attachments/{id}/thumbnail
	render as broken-image icons. This component fetches the bytes via
	axios (which the interceptor decorates with the auth header), wraps
	them in an object URL, and renders it as a normal <img>. The object
	URL is revoked on unmount.
-->
<script lang="ts">
	import { fetchAttachmentBlobUrl } from '$lib/service/attachment';

	interface Props {
		id: number;
		variant?: 'thumbnail' | 'original';
		alt?: string;
		class?: string;
		loading?: 'lazy' | 'eager';
		onload?: (e: Event) => void;
		onerror?: (e: Event) => void;
	}

	let {
		id,
		variant = 'thumbnail',
		alt = '',
		class: className = '',
		loading = 'lazy',
		onload,
		onerror
	}: Props = $props();

	let url = $state<string | null>(null);
	let failed = $state(false);

	$effect(() => {
		const ac = new AbortController();
		let createdUrl: string | null = null;
		failed = false;
		url = null;

		fetchAttachmentBlobUrl(id, variant, ac.signal).then((blobUrl) => {
			if (ac.signal.aborted) {
				if (blobUrl) URL.revokeObjectURL(blobUrl);
				return;
			}
			if (!blobUrl) {
				failed = true;
				return;
			}
			createdUrl = blobUrl;
			url = blobUrl;
		});

		return () => {
			ac.abort();
			if (createdUrl) URL.revokeObjectURL(createdUrl);
		};
	});
</script>

{#if url && !failed}
	<img src={url} {alt} class={className} {loading} {onload} {onerror} />
{:else if failed}
	<!-- Caller decides what placeholder to show; emit a transparent slot
	     by rendering nothing and letting the surrounding layout collapse
	     onto its file-icon fallback. -->
{/if}
