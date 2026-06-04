<!--
	Drawer-style panel that lists every attachment uploaded into the
	current chat room. Users open it from a button next to the
	paperclip in the composer; clicking a row re-attaches that file to
	the next message (same flow as the per-message "Re-attach" menu).

	The list is meta only — bytes are fetched lazily when the user
	actually clicks the original.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { RotateCcw, X, RefreshCw, Trash2 } from 'lucide-svelte';
	import {
		listAttachmentsForRoom,
		deleteAttachment,
		type IAttachment
	} from '$lib/service/attachment';
	import AttachmentImage from './AttachmentImage.svelte';
	import AttachmentIcon from './AttachmentIcon.svelte';
	import i18n from '$lib/i18n';
	import confirmDialog from '$lib/utils/confirmDialog';

	interface Props {
		roomId: number | null;
		open: boolean;
		onClose: () => void;
		onReattach: (att: IAttachment) => void;
	}

	let { roomId, open, onClose, onReattach }: Props = $props();

	let items = $state<IAttachment[]>([]);
	let loading = $state(false);
	let error = $state<string | null>(null);

	async function load() {
		if (!roomId) return;
		loading = true;
		error = null;
		try {
			items = await listAttachmentsForRoom({ roomId });
		} catch (e) {
			error = e instanceof Error ? e.message : i18n('aichat.attachment.library.loadFailed');
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (open && roomId) {
			void load();
		}
	});

	async function handleDelete(att: IAttachment) {
		const confirmed = await confirmDialog({
			title: i18n('aichat.attachment.library.delete'),
			message: i18n('aichat.attachment.library.deleteConfirm', att.filename),
			confirmText: i18n('common.button.delete'),
			variant: 'destructive'
		});
		if (!confirmed) return;
		try {
			await deleteAttachment({ id: att.id });
			items = items.filter((x) => x.id !== att.id);
		} catch (e) {
			// errorLevel: 'toast' default surfaces this; no extra alert needed
			console.error(e);
		}
	}

	function formatSize(bytes: number) {
		if (bytes < 1024) return `${bytes}B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
		return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-40 bg-black/40"
		onclick={(e) => {
			if (e.target === e.currentTarget) onClose();
		}}
	>
		<aside
			class="fixed right-0 top-0 z-50 flex h-full w-[360px] flex-col border-l border-border
				bg-background shadow-2xl"
		>
			<header class="flex items-center justify-between border-b border-border px-4 py-3">
				<div>
					<h3 class="text-sm font-semibold text-foreground">{i18n('aichat.attachment.library.title')}</h3>
					<p class="text-[10px] text-muted-foreground">
						{i18n('aichat.attachment.library.subtitle')}
					</p>
				</div>
				<div class="flex items-center gap-1">
					<button
						type="button"
						class="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
						onclick={() => void load()}
						title={i18n('aichat.attachment.library.refresh')}
					>
						<RefreshCw class="h-4 w-4" />
					</button>
					<button
						type="button"
						class="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
						onclick={onClose}
						aria-label={i18n('aichat.attachment.library.close')}
					>
						<X class="h-4 w-4" />
					</button>
				</div>
			</header>

			<div class="flex-1 overflow-auto p-3">
				{#if loading}
					<p class="px-2 py-6 text-center text-xs text-muted-foreground">{i18n('aichat.attachment.library.loading')}</p>
				{:else if error}
					<p class="px-2 py-6 text-center text-xs text-red-500">{error}</p>
				{:else if items.length === 0}
					<p class="px-2 py-6 text-center text-xs text-muted-foreground">
						{i18n('aichat.attachment.library.empty')}
					</p>
				{:else}
					<ul class="flex flex-col gap-2">
						{#each items as att (att.id)}
							<li
								class="group flex items-center gap-2 rounded-md border border-border bg-card p-2
									transition-colors hover:bg-accent/40"
							>
								{#if att.kind === 'image' && att.hasThumbnail}
									<AttachmentImage
										id={att.id}
										alt={att.filename}
										class="h-12 w-12 rounded object-cover"
									/>
								{:else}
									<AttachmentIcon
										filename={att.filename}
										mimeType={att.mimeType}
										kind={att.kind}
										class="h-7 w-7"
									/>
								{/if}

								<div class="min-w-0 flex-1">
									<div class="truncate text-xs font-medium text-foreground">{att.filename}</div>
									<div class="text-[10px] uppercase text-muted-foreground">
										{att.kind} · {formatSize(att.sizeBytes)}
									</div>
								</div>

								<button
									type="button"
									class="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground
										opacity-0 transition-opacity group-hover:opacity-100"
									onclick={() => onReattach(att)}
									title={i18n('aichat.attachment.library.reattach')}
								>
									<RotateCcw class="h-3.5 w-3.5" />
								</button>
								<button
									type="button"
									class="rounded p-1.5 text-muted-foreground hover:bg-red-50 hover:text-red-600
										opacity-0 transition-opacity group-hover:opacity-100"
									onclick={() => void handleDelete(att)}
									title={i18n('aichat.attachment.library.delete')}
								>
									<Trash2 class="h-3.5 w-3.5" />
								</button>
							</li>
						{/each}
					</ul>
				{/if}
			</div>
		</aside>
	</div>
{/if}
