import axios from 'axios';
import createRequest, { getBaseURL } from './base';

/**
 * Metadata returned by the backend for any chat attachment row.
 * Mirrors {@code AttachmentMetaDTO} on the Java side.
 */
export interface IAttachment {
	id: number;
	chatRoomId?: number | null;
	filename: string;
	mimeType: string;
	sizeBytes: number;
	/** 'image' | 'pdf' | 'office' | 'text' */
	kind: 'image' | 'pdf' | 'office' | 'text';
	hasThumbnail: boolean;
	hasExtractedText: boolean;
	gmtCreate?: string;
}

/** Per-model capability flags exposed by /api/ai/attachments/capabilities. */
export type ModelCapability = 'IMAGE' | 'PDF' | 'AUDIO' | 'VIDEO';
export type ModelCapabilitiesMap = Record<string, ModelCapability[]>;

/** Cap mirrors {@code AiChatAttachmentService.MAX_FILE_SIZE_BYTES}. */
export const MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024;
/** Cap mirrors {@code AiChatAttachmentService.MAX_ATTACHMENTS_PER_MESSAGE}. */
export const MAX_ATTACHMENTS_PER_MESSAGE = 5;

export const ACCEPTED_MIME_TYPES = [
	'image/png',
	'image/jpeg',
	'image/gif',
	'image/webp',
	'application/pdf',
	'application/vnd.openxmlformats-officedocument.presentationml.presentation',
	'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
	'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
	'text/plain',
	'text/markdown',
	'text/csv',
	'application/json',
	'application/xml',
	'application/x-yaml',
	'application/sql'
] as const;

/** Convenience for use as the {@code accept} attribute on file inputs. */
export const ACCEPT_ATTRIBUTE = [
	'image/*',
	'application/pdf',
	'.pptx',
	'.docx',
	'.xlsx',
	'.txt',
	'.md',
	'.markdown',
	'.csv',
	'.tsv',
	'.json',
	'.xml',
	'.yaml',
	'.yml',
	'.sql',
	'.log'
].join(',');

/**
 * Upload a single file. Uses axios directly to bypass the default
 * JSON content-type interceptor and to expose an upload progress
 * callback the input UI can wire to a progress chip.
 */
export async function uploadAttachment(
	file: File,
	options: {
		chatRoomId?: number | null;
		onProgress?: (percent: number) => void;
		signal?: AbortSignal;
	} = {}
): Promise<IAttachment> {
	const form = new FormData();
	form.append('file', file);
	if (options.chatRoomId != null) {
		form.append('chatRoomId', String(options.chatRoomId));
	}

	const token = typeof window !== 'undefined' ? localStorage.getItem('Inquery') : null;
	const resp = await axios.post(`${getBaseURL()}/api/ai/attachments`, form, {
		withCredentials: true,
		headers: {
			'Content-Type': 'multipart/form-data',
			...(token ? { Inquery: token } : {})
		},
		signal: options.signal,
		onUploadProgress: (e) => {
			if (!options.onProgress || !e.total) return;
			options.onProgress(Math.round((e.loaded / e.total) * 100));
		}
	});
	const body = resp.data;
	if (!body || body.success === false) {
		const msg = body?.errorMessage || 'Upload failed';
		const err = new Error(msg);
		(err as unknown as Record<string, unknown>).errorCode = body?.errorCode;
		throw err;
	}
	return body.data as IAttachment;
}

/**
 * Raw backend URL helpers. Do NOT use these for user-visible browser
 * navigation (`<img src>`, `<a href>`, `window.open(url)`) because auth
 * rides on a custom `Inquery` request header that's only added by our
 * axios calls. Native browser fetch/navigation skips that header and the
 * backend returns `common.needLoggedIn`.
 *
 * For previews use {@link fetchAttachmentBlobUrl} / `AttachmentImage`.
 * For "open original" actions use {@link openAttachmentOriginal}.
 */
export function attachmentDownloadUrl(id: number): string {
	return `${getBaseURL()}/api/ai/attachments/${id}`;
}

export function attachmentThumbnailUrl(id: number): string {
	return `${getBaseURL()}/api/ai/attachments/${id}/thumbnail`;
}

/**
 * Authenticated fetch for attachment bytes. Returns an object URL the
 * caller can drop straight into {@code <img src>}. Caller owns the URL
 * and MUST call {@link URL.revokeObjectURL} when the element unmounts
 * to avoid the well-known blob-URL memory leak.
 */
export async function fetchAttachmentBlobUrl(
	id: number,
	variant: 'thumbnail' | 'original' = 'thumbnail',
	signal?: AbortSignal
): Promise<string | null> {
	const token = typeof window !== 'undefined' ? localStorage.getItem('Inquery') : null;
	const path = variant === 'thumbnail' ? `/${id}/thumbnail` : `/${id}`;
	try {
		const resp = await axios.get(`${getBaseURL()}/api/ai/attachments${path}`, {
			withCredentials: true,
			responseType: 'blob',
			headers: token ? { Inquery: token } : undefined,
			signal
		});
		const blob: Blob = resp.data;
		if (!blob || blob.size === 0) return null;
		return URL.createObjectURL(blob);
	} catch (err) {
		if (axios.isCancel(err)) return null;
		return null;
	}
}

/**
 * Opens the original attachment using an authenticated axios request.
 * We synchronously create a blank tab from the user's click so popup
 * blockers allow it, then point that tab at the fetched blob URL.
 */
export async function openAttachmentOriginal(id: number): Promise<void> {
	const opened = typeof window !== 'undefined' ? window.open('', '_blank', 'noopener,noreferrer') : null;
	try {
		const url = await fetchAttachmentBlobUrl(id, 'original');
		if (!url) {
			opened?.close();
			throw new Error('Unable to open attachment');
		}
		if (opened) {
			opened.location.href = url;
		} else if (typeof window !== 'undefined') {
			window.open(url, '_blank', 'noopener,noreferrer');
		}
		// Give the new tab enough time to start reading the blob before
		// releasing it. Immediate revoke can race in Safari/Chromium.
		setTimeout(() => URL.revokeObjectURL(url), 60_000);
	} catch (err) {
		opened?.close();
		throw err;
	}
}

export const getAttachmentMeta = createRequest<{ id: number }, IAttachment>(
	'/api/ai/attachments/:id/meta'
);

export const listAttachmentsForRoom = createRequest<{ roomId: number }, IAttachment[]>(
	'/api/ai/attachments'
);

export const deleteAttachment = createRequest<{ id: number }, void>(
	'/api/ai/attachments/:id',
	{ method: 'delete' }
);

export const getModelCapabilities = createRequest<void, ModelCapabilitiesMap>(
	'/api/ai/attachments/capabilities'
);

/**
 * Returns the capability required to attach {@code file}. Used by the
 * input UI to decide whether the current model can handle it before
 * the user hits send.
 */
export function requiredCapabilityFor(file: File): ModelCapability | null {
	const mt = file.type?.toLowerCase() || '';
	if (mt.startsWith('image/')) return 'IMAGE';
	if (mt === 'application/pdf') return 'PDF';
	return null;
}
