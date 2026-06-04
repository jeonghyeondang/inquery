import axios from 'axios';
import { getBaseURL } from './base';

export interface IReferenceDocumentMeta {
	id: number;
	filename: string;
	mimeType: string;
	kind: string;
	sizeBytes: number;
	indexStatus: string;
	indexError?: string | null;
	chunkCount?: number;
	gmtCreate?: string;
}

export interface IReferenceDocumentList {
	documents: IReferenceDocumentMeta[];
	usedBytes: number;
	quotaBytes: number;
}

async function authHeaders(): Promise<Record<string, string>> {
	const token = typeof window !== 'undefined' ? localStorage.getItem('Inquery') || '' : '';
	return token ? { Inquery: token } : {};
}

async function listDocuments(): Promise<IReferenceDocumentList> {
	const res = await axios.get(`${getBaseURL()}/api/config/ai/documents`, {
		headers: await authHeaders()
	});
	if (res.data && !res.data.success) {
		throw new Error(res.data.errorMessage || 'Failed to load documents');
	}
	return res.data.data ?? { documents: [], usedBytes: 0, quotaBytes: 0 };
}

async function uploadDocument(file: File): Promise<IReferenceDocumentMeta> {
	const formData = new FormData();
	formData.append('file', file);
	const res = await axios.post(`${getBaseURL()}/api/config/ai/documents`, formData, {
		headers: {
			'Content-Type': 'multipart/form-data',
			...(await authHeaders())
		}
	});
	if (res.data && !res.data.success) {
		throw new Error(res.data.errorMessage || 'Upload failed');
	}
	return res.data.data;
}

async function downloadDocument(id: number, filename: string): Promise<void> {
	const res = await axios.get(`${getBaseURL()}/api/config/ai/documents/${id}`, {
		headers: await authHeaders(),
		responseType: 'blob'
	});
	const url = window.URL.createObjectURL(new Blob([res.data]));
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	a.remove();
	window.URL.revokeObjectURL(url);
}

async function deleteDocument(id: number): Promise<void> {
	const res = await axios.delete(`${getBaseURL()}/api/config/ai/documents/${id}`, {
		headers: await authHeaders()
	});
	if (res.data && !res.data.success) {
		throw new Error(res.data.errorMessage || 'Delete failed');
	}
}

async function reindexDocument(id: number): Promise<IReferenceDocumentMeta> {
	const res = await axios.post(`${getBaseURL()}/api/config/ai/documents/${id}/reindex`, {}, {
		headers: await authHeaders()
	});
	if (res.data && !res.data.success) {
		throw new Error(res.data.errorMessage || 'Reindex failed');
	}
	return res.data.data;
}

export default {
	listDocuments,
	uploadDocument,
	downloadDocument,
	deleteDocument,
	reindexDocument
};
