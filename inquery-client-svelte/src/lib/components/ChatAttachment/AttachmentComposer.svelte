<!--
	Inline composer for AI-chat attachments.

	Owned by the chat input area. Surfaces:
	  • a paperclip button that opens the native file picker
	  • a full-area drop zone (only visible while dragging files over the
	    chat input)
	  • paste-from-clipboard support for screenshots
	  • per-file progress chips with cancel + retry
	  • capability pre-check against the currently selected model — if the
	    model can't handle the kind of file the user just dropped, we
	    show an inline warning. The server still auto-switches on send
	    (silent + toast), so this is purely informational.

	The parent gets the resolved attachments via `bind:attachments` once
	uploads finish.
-->
<script lang="ts">
	import { Paperclip, X, Loader2, AlertCircle } from 'lucide-svelte';
	import {
		uploadAttachment,
		ACCEPT_ATTRIBUTE,
		MAX_FILE_SIZE_BYTES,
		MAX_ATTACHMENTS_PER_MESSAGE,
		requiredCapabilityFor,
		type IAttachment,
		type ModelCapabilitiesMap
	} from '$lib/service/attachment';
	import AttachmentImage from './AttachmentImage.svelte';
	import AttachmentIcon from './AttachmentIcon.svelte';
	import message from '$lib/utils/message';
	import i18n from '$lib/i18n';

	interface Props {
		attachments: IAttachment[];
		chatRoomId?: number | null;
		currentModel?: string;
		capabilities?: ModelCapabilitiesMap;
		disabled?: boolean;
	}

	let {
		attachments = $bindable([]),
		chatRoomId = null,
		currentModel,
		capabilities,
		disabled = false
	}: Props = $props();

	interface IPending {
		id: string;
		filename: string;
		sizeBytes: number;
		kind: 'image' | 'pdf' | 'office' | 'text';
		progress: number;
		error?: string;
		abort: AbortController;
	}

	let pending = $state<IPending[]>([]);
	let isDragging = $state(false);
	let fileInputEl: HTMLInputElement | null = $state(null);

	const OFFICE_MIME_PREFIXES = [
		'application/vnd.openxmlformats-officedocument.',
		'application/vnd.ms-' // legacy formats — backend rejects these,
		// but the early classification at least surfaces a typed
		// rejection rather than "unsupported" for visually-correct files.
	];

	function classifyKind(file: File): 'image' | 'pdf' | 'office' | 'text' | null {
		const mt = (file.type || '').toLowerCase();
		if (mt.startsWith('image/') && mt !== 'image/svg+xml') return 'image';
		if (mt === 'image/svg+xml') return 'text';
		if (mt === 'application/pdf') return 'pdf';
		if (OFFICE_MIME_PREFIXES.some((p) => mt.startsWith(p))) return 'office';
		if (mt.startsWith('text/') || mt === 'application/json' || mt === 'application/xml'
			|| mt === 'application/sql' || mt === 'application/x-yaml') {
			return 'text';
		}
		// Fallback by extension — browser sometimes leaves Content-Type empty.
		const lower = file.name.toLowerCase();
		if (/\.(png|jpe?g|gif|webp)$/.test(lower)) return 'image';
		if (lower.endsWith('.pdf')) return 'pdf';
		if (/\.(pptx|docx|xlsx)$/.test(lower)) return 'office';
		if (/\.(txt|md|markdown|csv|tsv|log|json|sql|ya?ml|xml|svg)$/.test(lower)) return 'text';
		return null;
	}

	function modelSupports(cap: 'IMAGE' | 'PDF' | 'AUDIO' | 'VIDEO') {
		// Frontend pre-check is informational only; the backend owns the
		// final decision (incl. silent auto-switch on send). The frontend
		// often holds a placeholder like `inquery-agent` that the backend
		// resolves into a real model based on which API keys are
		// registered, so we MUST treat any model that isn't explicitly in
		// the matrix as "unknown — let the server decide" rather than
		// blocking the user with a misleading "unsupported" warning.
		if (!currentModel || !capabilities) return true;
		const caps = capabilities[currentModel];
		if (!caps) return true;
		return caps.includes(cap);
	}

	async function addFiles(files: File[]) {
		if (disabled) return;
		for (const file of files) {
			if (attachments.length + pending.length >= MAX_ATTACHMENTS_PER_MESSAGE) {
				message.warning(i18n('aichat.attachment.composer.maxCount', MAX_ATTACHMENTS_PER_MESSAGE));
				return;
			}
			const kind = classifyKind(file);
			if (!kind) {
				message.error(i18n('aichat.attachment.composer.unsupportedType', file.name));
				continue;
			}
			if (file.size > MAX_FILE_SIZE_BYTES) {
				message.error(
					i18n('aichat.attachment.composer.tooLarge', file.name, Math.floor(MAX_FILE_SIZE_BYTES / 1024 / 1024))
				);
				continue;
			}
			const cap = requiredCapabilityFor(file);
			if (cap && !modelSupports(cap)) {
				// Informational only — server will auto-switch.
				message.info(
					cap === 'PDF'
						? i18n('aichat.attachment.composer.modelPdf')
						: i18n('aichat.attachment.composer.modelImage')
				);
			}

			const localId = `up-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
			const abort = new AbortController();
			const entry: IPending = {
				id: localId,
				filename: file.name,
				sizeBytes: file.size,
				kind,
				progress: 0,
				abort
			};
			pending = [...pending, entry];

			try {
				const uploaded = await uploadAttachment(file, {
					chatRoomId,
					signal: abort.signal,
					onProgress: (p) => {
						pending = pending.map((x) => (x.id === localId ? { ...x, progress: p } : x));
					}
				});
				pending = pending.filter((x) => x.id !== localId);
				attachments = [...attachments, uploaded];
			} catch (err) {
				const msg = err instanceof Error ? err.message : i18n('aichat.attachment.composer.uploadFailed');
				if (abort.signal.aborted) {
					pending = pending.filter((x) => x.id !== localId);
				} else {
					pending = pending.map((x) => (x.id === localId ? { ...x, error: msg } : x));
				}
			}
		}
	}

	function onFileInput(e: Event) {
		const input = e.target as HTMLInputElement;
		if (!input.files || input.files.length === 0) return;
		void addFiles(Array.from(input.files));
		input.value = '';
	}

	function onDrop(e: DragEvent) {
		e.preventDefault();
		isDragging = false;
		if (!e.dataTransfer) return;
		const files = Array.from(e.dataTransfer.files);
		if (files.length === 0) return;
		void addFiles(files);
	}

	function onDragOver(e: DragEvent) {
		if (disabled) return;
		// Show the drop zone only while the user is actively dragging
		// something that contains files (not just text).
		if (e.dataTransfer?.types.includes('Files')) {
			e.preventDefault();
			isDragging = true;
		}
	}

	function onDragLeave(e: DragEvent) {
		if (e.currentTarget === e.target) {
			isDragging = false;
		}
	}

	export function onPaste(e: ClipboardEvent) {
		if (disabled) return;
		const items = e.clipboardData?.items;
		if (!items) return;
		const files: File[] = [];
		for (const item of items) {
			if (item.kind === 'file') {
				const f = item.getAsFile();
				if (f) files.push(f);
			}
		}
		if (files.length > 0) {
			e.preventDefault();
			void addFiles(files);
		}
	}

	function removeAttachment(id: number) {
		attachments = attachments.filter((a) => a.id !== id);
	}

	function cancelPending(localId: string) {
		const entry = pending.find((p) => p.id === localId);
		entry?.abort.abort();
		pending = pending.filter((p) => p.id !== localId);
	}

	function formatSize(bytes: number) {
		if (bytes < 1024) return `${bytes}B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
		return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
	}

	// Expose triggerPicker / dragHandlers as a single object via $derived
	// so the parent can wire them onto its own container.
	export function triggerPicker() {
		fileInputEl?.click();
	}

	export const dragHandlers = {
		ondragover: onDragOver,
		ondrop: onDrop,
		ondragleave: onDragLeave
	};
</script>

<input
	type="file"
	multiple
	accept={ACCEPT_ATTRIBUTE}
	bind:this={fileInputEl}
	onchange={onFileInput}
	class="hidden"
	disabled={disabled}
/>

{#if isDragging && !disabled}
	<div
		class="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-lg
			border-2 border-dashed border-blue-400 bg-blue-50/90 dark:bg-blue-950/80"
	>
		<p class="text-sm font-medium text-blue-700 dark:text-blue-300">
			{i18n('aichat.attachment.composer.dropHint')}
		</p>
	</div>
{/if}

{#if attachments.length > 0 || pending.length > 0}
	<div class="flex flex-wrap gap-2 px-3 pt-2">
		{#each attachments as att (att.id)}
			<div
				class="group relative flex items-center gap-2 rounded-md border border-gray-200
					bg-gray-50 px-2 py-1.5 dark:border-gray-700 dark:bg-gray-800"
			>
				{#if att.kind === 'image' && att.hasThumbnail}
					<AttachmentImage
						id={att.id}
						alt={att.filename}
						class="h-8 w-8 rounded object-cover"
					/>
				{:else}
					<AttachmentIcon
						filename={att.filename}
						mimeType={att.mimeType}
						kind={att.kind}
						class="h-4 w-4"
					/>
				{/if}
				<div class="min-w-0">
					<div class="max-w-[140px] truncate text-xs font-medium text-gray-800 dark:text-gray-200">
						{att.filename}
					</div>
					<div class="text-[10px] text-gray-500">{formatSize(att.sizeBytes)}</div>
				</div>
				<button
					type="button"
					onclick={() => removeAttachment(att.id)}
					class="ml-1 rounded p-0.5 text-gray-400 opacity-0 transition-opacity hover:bg-gray-200
						hover:text-gray-700 group-hover:opacity-100 dark:hover:bg-gray-700"
					aria-label={i18n('aichat.attachment.composer.remove')}
				>
					<X class="h-3 w-3" />
				</button>
			</div>
		{/each}

		{#each pending as p (p.id)}
			<div
				class="flex items-center gap-2 rounded-md border border-gray-200 bg-gray-50 px-2 py-1.5
					dark:border-gray-700 dark:bg-gray-800"
			>
				{#if p.error}
					<AlertCircle class="h-4 w-4 text-red-500" />
				{:else}
					<Loader2 class="h-4 w-4 animate-spin text-blue-500" />
				{/if}
				<div class="min-w-0">
					<div class="max-w-[140px] truncate text-xs font-medium text-gray-800 dark:text-gray-200">
						{p.filename}
					</div>
					<div class="text-[10px] text-gray-500">
						{p.error ? p.error : `${p.progress}% • ${formatSize(p.sizeBytes)}`}
					</div>
				</div>
				<button
					type="button"
					onclick={() => cancelPending(p.id)}
					class="ml-1 rounded p-0.5 text-gray-400 hover:bg-gray-200 hover:text-gray-700
						dark:hover:bg-gray-700"
					aria-label={i18n('aichat.attachment.composer.cancelUpload')}
				>
					<X class="h-3 w-3" />
				</button>
			</div>
		{/each}
	</div>
{/if}

<style>
	/* Component-local styles intentionally minimal — Tailwind handles the layout. */
</style>
