<!--
	Renders attachment chips inside a chat bubble (user message or
	assistant turn). Per kind:

	- image: thumbnail card → click opens a lightbox preview
	- pdf: thumbnail card (first page) → click opens the full PDF in
	  a new tab via the download endpoint
	- text: filename + size with a download link

	Each card carries a "..." menu with two actions:
	  • Open / download the original
	  • Re-attach to the next message (emits the `onReattach`
	    callback so the parent route can push it into
	    pendingAttachments)
-->
<script lang="ts">
	import { Download, MoreHorizontal, RotateCcw, X } from 'lucide-svelte';
	import {
		openAttachmentOriginal,
		type IAttachment
	} from '$lib/service/attachment';
	import message from '$lib/utils/message';
	import i18n from '$lib/i18n';
	import { getReattachHandler } from '$lib/stores/aiChatAttachments.svelte';
	import AttachmentImage from './AttachmentImage.svelte';
	import AttachmentIcon from './AttachmentIcon.svelte';

	interface Props {
		attachments: IAttachment[];
		align?: 'start' | 'end';
	}

	let { attachments, align = 'start' }: Props = $props();

	let lightboxAtt = $state<IAttachment | null>(null);
	let openMenuForId = $state<number | null>(null);

	function formatSize(bytes: number) {
		if (bytes < 1024) return `${bytes}B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
		return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
	}

	async function openOriginal(att: IAttachment) {
		if (att.kind === 'image') {
			lightboxAtt = att;
			return;
		}
		try {
			await openAttachmentOriginal(att.id);
		} catch (err) {
			message.error(err instanceof Error ? err.message : i18n('aichat.attachment.card.openFailed'));
		}
	}

	function reattach(att: IAttachment) {
		const handler = getReattachHandler();
		if (!handler) return;
		handler(att);
	}
</script>

<div class="flex flex-wrap gap-2 {align === 'end' ? 'justify-end' : 'justify-start'}">
	{#each attachments as att (att.id)}
		<div
			class="group relative flex items-center gap-2 rounded-md border border-border bg-background
				px-2 py-1.5 transition-colors hover:bg-accent/40 max-w-[260px]"
		>
			{#if att.kind === 'image' && att.hasThumbnail}
				<button
					type="button"
					onclick={() => openOriginal(att)}
					class="block"
					title={att.filename}
				>
					<AttachmentImage
						id={att.id}
						alt={att.filename}
						class="h-10 w-10 rounded object-cover"
					/>
				</button>
			{:else}
				<button
					type="button"
					onclick={() => openOriginal(att)}
					class="block"
					title={att.filename}
				>
					<AttachmentIcon
						filename={att.filename}
						mimeType={att.mimeType}
						kind={att.kind}
						class="h-6 w-6"
					/>
				</button>
			{/if}

			<div class="min-w-0">
				<div class="max-w-[160px] truncate text-xs font-medium text-foreground">
					{att.filename}
				</div>
				<div class="text-[10px] text-muted-foreground uppercase">
					{att.kind} · {formatSize(att.sizeBytes)}
				</div>
			</div>

			<div class="relative ml-1">
				<button
					type="button"
					onclick={() => (openMenuForId = openMenuForId === att.id ? null : att.id)}
					class="rounded p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-accent
						hover:text-foreground group-hover:opacity-100"
					aria-label={i18n('aichat.attachment.card.menu')}
				>
					<MoreHorizontal class="h-3 w-3" />
				</button>
				{#if openMenuForId === att.id}
					<!-- svelte-ignore a11y_click_events_have_key_events -->
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div
						class="absolute right-0 top-full z-20 mt-1 w-44 rounded-md border border-border bg-popover
							p-1 shadow-md"
						onclick={(e) => e.stopPropagation()}
					>
						<button
							type="button"
							class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-xs hover:bg-accent"
							onclick={() => {
								openMenuForId = null;
								void openOriginal(att);
							}}
						>
							<Download class="h-3 w-3" />
							{i18n('aichat.attachment.card.openOriginal')}
						</button>
						<button
							type="button"
							class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-xs hover:bg-accent"
							onclick={() => {
								reattach(att);
								openMenuForId = null;
							}}
						>
							<RotateCcw class="h-3 w-3" />
							{i18n('aichat.attachment.card.reattach')}
						</button>
					</div>
				{/if}
			</div>
		</div>
	{/each}
</div>

{#if lightboxAtt}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-[100] flex items-center justify-center bg-black/80 p-6"
		onclick={(e) => {
			if (e.target === e.currentTarget) lightboxAtt = null;
		}}
	>
		<button
			type="button"
			class="absolute right-4 top-4 rounded-full bg-white/10 p-2 text-white hover:bg-white/20"
			onclick={() => (lightboxAtt = null)}
			aria-label={i18n('aichat.attachment.card.close')}
		>
			<X class="h-5 w-5" />
		</button>
		<AttachmentImage
			id={lightboxAtt.id}
			variant="original"
			alt={lightboxAtt.filename}
			class="max-h-full max-w-full rounded shadow-2xl"
		/>
	</div>
{/if}
