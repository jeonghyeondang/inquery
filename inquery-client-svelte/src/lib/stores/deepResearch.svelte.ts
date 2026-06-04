/**
 * Deep Research store - Svelte 5 Runes
 * Manages deep research sessions, SSE streaming, and state
 */
import {
	startDeepResearch,
	executeDeepResearchStream,
	getResearchReport,
	getLatestResearchByRoom,
	deleteResearchSession,
	type DeepResearchSession
} from '$lib/service/deepResearch';
import { getBaseURL } from '$lib/service/base';

// Types
export interface ResearchProgress {
	step: string;
	message: string;
	detail?: string;
}

export interface ResearchState {
	isRunning: boolean;
	sessionId: number | null;
	progress: ResearchProgress[];
	currentStep: string;
	report: any | null;
	error: string | null;
	isResearchViewOpen: boolean;
	infographicHtml: string;
	isInfographicGenerating: boolean;
	isInfographicViewOpen: boolean;
}

const defaultResearchState = (): ResearchState => ({
	isRunning: false,
	sessionId: null,
	progress: [],
	currentStep: '',
	report: null,
	error: null,
	isResearchViewOpen: false,
	infographicHtml: '',
	isInfographicGenerating: false,
	isInfographicViewOpen: false
});

// State
let currentResearchRoomId = $state<number | null>(null);
let stateByRoom = $state<Record<number, ResearchState>>({});
const eventSourceByRoom = new Map<number, EventSource>();

function getRoomState(roomId: number | null = currentResearchRoomId): ResearchState {
	if (roomId == null) return defaultResearchState();
	return stateByRoom[roomId] || defaultResearchState();
}

function setRoomState(roomId: number, nextState: ResearchState) {
	stateByRoom = { ...stateByRoom, [roomId]: nextState };
}

function patchRoomState(roomId: number, patch: Partial<ResearchState>) {
	setRoomState(roomId, { ...getRoomState(roomId), ...patch });
}

export function getDeepResearchStore() {
	return {
		get state() { return getRoomState(); },
		get stateByRoom() { return stateByRoom; }
	};
}

export function setCurrentResearchRoom(roomId: number | null) {
	currentResearchRoomId = roomId;
}

export function resetResearch(roomId: number | null = currentResearchRoomId) {
	if (roomId == null) return;
	stopStreaming(roomId);
	setRoomState(roomId, defaultResearchState());
}

export function setResearchViewOpen(open: boolean) {
	if (currentResearchRoomId == null) return;
	patchRoomState(currentResearchRoomId, { isResearchViewOpen: open });
}

/** Restore research report from a saved message (for room switch) */
export function restoreResearchReport(report: any, sessionId?: number | null) {
	if (currentResearchRoomId == null) return;
	patchRoomState(currentResearchRoomId, {
		report,
		sessionId: sessionId ?? null,
		isResearchViewOpen: true,
		isRunning: false,
		error: null
	});
}

export function setInfographicViewOpen(open: boolean) {
	if (currentResearchRoomId == null) return;
	patchRoomState(currentResearchRoomId, { isInfographicViewOpen: open });
}

function addProgress(roomId: number, step: string, message: string, detail?: string) {
	const state = getRoomState(roomId);
	patchRoomState(roomId, {
		progress: [...state.progress, { step, message, detail }],
		currentStep: step
	});
}

export function stopStreaming(roomId: number | null = currentResearchRoomId) {
	if (roomId == null) return;
	const eventSource = eventSourceByRoom.get(roomId);
	if (eventSource) {
		eventSource.close();
		eventSourceByRoom.delete(roomId);
	}
}

export async function startResearch(params: {
	question: string;
	chatRoomId: number;
	dataSourceId?: number;
	databaseName?: string;
	schemaName?: string;
	researchPlan?: any;
	onReportReady?: (report: any, sessionId: number, chatRoomId: number) => void;
}): Promise<void> {
	const roomId = params.chatRoomId;
	currentResearchRoomId = roomId;
	resetResearch(roomId);
	patchRoomState(roomId, { isRunning: true, isResearchViewOpen: true, progress: [] });

	try {
		// Step 1: Start research session
		addProgress(roomId, 'planning', 'Starting research session...');
		const result = await startDeepResearch({
			question: params.question,
			chatRoomId: params.chatRoomId,
			dataSourceId: params.dataSourceId,
			databaseName: params.databaseName,
			schemaName: params.schemaName,
			researchPlanJson: params.researchPlan ? JSON.stringify(params.researchPlan) : undefined
		});

		const sessionId = (result as any)?.sessionId;
		if (!sessionId) {
			patchRoomState(roomId, { isRunning: false, error: 'Failed to start research session' });
			return;
		}
		patchRoomState(roomId, { sessionId });

		// Step 2: Connect SSE stream
		addProgress(roomId, 'planning', 'Connecting to research stream...');
		const eventSource = executeDeepResearchStream({
			sessionId,
			dataSourceId: params.dataSourceId,
			databaseName: params.databaseName,
			schemaName: params.schemaName
		});
		eventSourceByRoom.set(roomId, eventSource);

		eventSource.onmessage = (event) => {
			try {
				const data = JSON.parse(event.data);
				handleSSEEvent(roomId, data);
			} catch { /* ignore parse errors */ }
		};

		eventSource.addEventListener('planning', (e: any) => {
			addProgress(roomId, 'planning', 'Creating research plan...', e.data);
		});

		eventSource.addEventListener('web_search', (e: any) => {
			addProgress(roomId, 'web_search', `Web search: ${e.data}`, e.data);
		});

		eventSource.addEventListener('heartbeat', () => {
			// keepalive - no action needed
		});

		eventSource.addEventListener('question', (e: any) => {
			addProgress(roomId, 'question', 'Generating research questions...', e.data);
		});

		eventSource.addEventListener('query', (e: any) => {
			addProgress(roomId, 'query', 'Generating SQL queries...', e.data);
		});

		eventSource.addEventListener('executing', (e: any) => {
			addProgress(roomId, 'executing', 'Executing queries...', e.data);
		});

		eventSource.addEventListener('reflection', (e: any) => {
			addProgress(roomId, 'reflection', 'Analyzing results...', e.data);
		});

		eventSource.addEventListener('iteration', (e: any) => {
			addProgress(roomId, 'iteration', 'Running next iteration...', e.data);
		});

		eventSource.addEventListener('synthesizing', (e: any) => {
			addProgress(roomId, 'synthesizing', 'Synthesizing findings...', e.data);
		});

		eventSource.addEventListener('finalizing', (e: any) => {
			addProgress(roomId, 'finalizing', 'Finalizing report...', e.data);
		});

		eventSource.addEventListener('report', (e: any) => {
			try {
				const report = JSON.parse(e.data);
				patchRoomState(roomId, { report });
				if (sessionId && params.onReportReady) {
					params.onReportReady(report, sessionId, roomId);
				}
			} catch { /* ignore */ }
			addProgress(roomId, 'report', 'Report generated');
		});

		eventSource.addEventListener('complete', () => {
			patchRoomState(roomId, { isRunning: false });
			stopStreaming(roomId);
			addProgress(roomId, 'complete', 'Research complete');
			// Fetch final report
			if (sessionId) {
				getResearchReport({ sessionId }).then(report => {
					if (report) patchRoomState(roomId, { report });
				}).catch(() => {});
			}
		});

		eventSource.addEventListener('error', (e: any) => {
			let errorMsg = 'Research failed';
			try { errorMsg = e.data || errorMsg; } catch { /* ignore */ }
			patchRoomState(roomId, { isRunning: false, error: errorMsg });
			stopStreaming(roomId);
		});

		eventSource.onerror = () => {
			if (getRoomState(roomId).isRunning) {
				patchRoomState(roomId, { isRunning: false, error: 'Connection lost' });
			}
			stopStreaming(roomId);
		};

	} catch (e: any) {
		patchRoomState(roomId, { isRunning: false, error: e?.message || 'Failed to start research' });
	}
}

function handleSSEEvent(roomId: number, data: any) {
	if (data.type && data.message) {
		addProgress(roomId, data.type, data.message, data.detail);
	}
}

export async function generateInfographic(onComplete?: (html: string) => void): Promise<void> {
	const roomId = currentResearchRoomId;
	if (roomId == null) return;
	const state = getRoomState(roomId);
	if (!state.sessionId) return;
	patchRoomState(roomId, { isInfographicGenerating: true, infographicHtml: '' });

	const token = typeof localStorage !== 'undefined' ? localStorage.getItem('Inquery') || '' : '';
	const params = new URLSearchParams();
	params.append('sessionId', String(state.sessionId));
	if (token) params.append('satoken', token);

	const infoES = new EventSource(`${getBaseURL()}/api/ai/deep-research/infographic/stream?${params.toString()}`);
	let html = '';

	infoES.addEventListener('html_chunk', (e: any) => {
		try {
			const chunk = JSON.parse(e.data);
			html += chunk;
		} catch {
			html += e.data;
		}
		patchRoomState(roomId, { infographicHtml: html });
	});

	infoES.addEventListener('complete', () => {
		patchRoomState(roomId, { isInfographicGenerating: false });
		infoES.close();
		if (html.length > 100 && onComplete) onComplete(html);
	});

	infoES.addEventListener('error', () => {
		patchRoomState(roomId, { isInfographicGenerating: false });
		infoES.close();
	});

	infoES.onerror = () => {
		patchRoomState(roomId, { isInfographicGenerating: false });
		infoES.close();
	};
}

export async function loadExistingResearch(chatRoomId: number): Promise<void> {
	currentResearchRoomId = chatRoomId;
	try {
		const session = await getLatestResearchByRoom({ chatRoomId });
		if (session && (session as any)?.id) {
			const s = session as DeepResearchSession;
			patchRoomState(chatRoomId, {
				sessionId: s.id,
				isResearchViewOpen: true,
				isRunning: s.status === 'RUNNING'
			});
			if (s.reportJson) {
				try {
					patchRoomState(chatRoomId, { report: JSON.parse(s.reportJson) });
				} catch { /* ignore */ }
			}
		}
	} catch { /* ignore */ }
}

export async function deleteCurrentResearch(): Promise<boolean> {
	const roomId = currentResearchRoomId;
	if (roomId == null) return false;
	const state = getRoomState(roomId);
	if (!state.sessionId) return false;
	try {
		await deleteResearchSession({ sessionId: state.sessionId });
		resetResearch(roomId);
		return true;
	} catch {
		return false;
	}
}

// Export functions for research report
export async function exportResearchAsPDF(): Promise<void> {
	const state = getRoomState();
	if (!state.report) return;
	const report = state.report;

	const { Marked } = await import('marked');
	const marked = new Marked();

	const escapeHtml = (s: string) =>
		s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

	// Build sections HTML using marked for proper markdown rendering
	let sectionsHtml = '';
	if (report.sections) {
		for (const section of report.sections) {
			sectionsHtml += `<div class="section">`;
			sectionsHtml += `<h2>${escapeHtml(section.title || '')}</h2>`;
			if (section.content) {
				sectionsHtml += `<div class="content">${marked.parse(section.content)}</div>`;
			}
			sectionsHtml += `</div>`;
		}
	}

	let sourcesHtml = '';
	if (report.citations?.length || report.webSources?.length) {
		sourcesHtml = '<div class="section sources"><h2>Sources</h2><ul>';
		if (report.citations) {
			for (const c of report.citations) {
				sourcesHtml += `<li><strong>${escapeHtml(c.table || '')}</strong> — ${escapeHtml(c.description || '')}</li>`;
			}
		}
		if (report.webSources) {
			for (const s of report.webSources) {
				sourcesHtml += `<li><a href="${escapeHtml(s.url || '')}">${escapeHtml(s.title || s.domain || '')}</a></li>`;
			}
		}
		sourcesHtml += '</ul></div>';
	}

	const html = `<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; color: #1a1a2e; background: #fff; line-height: 1.7; font-size: 13px; }

  /* Title */
  h1 { font-size: 24px; font-weight: 700; margin-bottom: 24px; color: #0f172a; border-bottom: 2px solid #e2e8f0; padding-bottom: 12px; }

  /* Sections */
  .section { margin-bottom: 20px; }
  .section h2 { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid #f1f5f9; page-break-after: avoid; break-after: avoid; }

  /* Content typography */
  .content p { margin-bottom: 8px; }
  .content ul, .content ol { padding-left: 20px; margin-bottom: 8px; }
  .content li { margin-bottom: 3px; }
  .content li > ul, .content li > ol { margin-top: 3px; margin-bottom: 0; }
  .content strong { font-weight: 600; color: #0f172a; }
  .content em { font-style: italic; }
  .content h3 { font-size: 14px; font-weight: 600; margin: 14px 0 6px; color: #1e293b; page-break-after: avoid; break-after: avoid; }
  .content h4 { font-size: 13px; font-weight: 600; margin: 10px 0 4px; color: #334155; page-break-after: avoid; break-after: avoid; }
  .content blockquote { border-left: 3px solid #cbd5e1; padding: 8px 12px; color: #64748b; margin: 8px 0; background: #f8fafc; border-radius: 0 4px 4px 0; }
  .content blockquote p { margin-bottom: 4px; }
  .content blockquote p:last-child { margin-bottom: 0; }
  .content hr { border: none; border-top: 1px solid #e2e8f0; margin: 16px 0; }
  .content a { color: #3b82f6; text-decoration: none; }

  /* Inline code */
  .content code { background: #f1f5f9; padding: 1px 4px; border-radius: 3px; font-size: 11px; font-family: 'SF Mono', Menlo, Consolas, monospace; }

  /* Code blocks */
  .content pre { background: #f8fafc; padding: 12px 16px; border-radius: 6px; margin: 10px 0; overflow-x: auto; border: 1px solid #e2e8f0; page-break-inside: avoid; break-inside: avoid; }
  .content pre code { background: none; padding: 0; font-size: 11px; line-height: 1.5; }

  /* Tables */
  table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 12px; page-break-inside: auto; }
  thead { display: table-header-group; background: #f8fafc; }
  th { padding: 6px 10px; text-align: left; font-weight: 600; border-bottom: 2px solid #e2e8f0; color: #475569; }
  td { padding: 5px 10px; border-bottom: 1px solid #f1f5f9; }
  tr { page-break-inside: avoid; break-inside: avoid; }

  /* Sources */
  .sources ul { list-style: none; padding: 0; }
  .sources li { padding: 4px 0; border-bottom: 1px solid #f1f5f9; font-size: 12px; }
  .sources a { color: #3b82f6; text-decoration: none; }

  /* Footer */
  .doc-footer { margin-top: 32px; padding-top: 12px; border-top: 1px solid #e2e8f0; font-size: 10px; color: #94a3b8; text-align: center; }

  /* Pagination rules */
  p, li { orphans: 3; widows: 3; }
  h1, h2, h3, h4 { page-break-after: avoid; break-after: avoid; }
</style></head><body>
<h1>${escapeHtml(report.title || 'Research Report')}</h1>
${sectionsHtml}
${sourcesHtml}
<div class="doc-footer">Generated by Inquery Deep Research</div>
</body></html>`;

	try {
		const response = await fetch(`${getBaseURL()}/api/v1/export/pdf`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ html })
		});

		if (!response.ok) {
			throw new Error(`PDF generation failed with status ${response.status}`);
		}

		const blob = new Blob([await response.arrayBuffer()], { type: 'application/pdf' });
		const downloadUrl = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = downloadUrl;
		a.download = `research_report_${Date.now()}.pdf`;
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		setTimeout(() => URL.revokeObjectURL(downloadUrl), 30000);
	} catch (error) {
		console.error('Research report PDF export failed:', error);
	}
}

export function exportResearchAsMarkdown(): void {
	const state = getRoomState();
	if (!state.report) return;
	const report = state.report;
	let md = `# ${report.title || 'Research Report'}\n\n`;
	if (report.sections) {
		for (const section of report.sections) {
			md += `## ${section.title || 'Section'}\n\n`;
			md += `${section.content || ''}\n\n`;
		}
	}
	if (report.mdContent) {
		md = report.mdContent;
	}

	const blob = new Blob([md], { type: 'text/markdown;charset=utf-8;' });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = `research_report_${Date.now()}.md`;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

export async function exportResearchAsDOCX(): Promise<void> {
	const state = getRoomState();
	if (!state.report) return;
	const report = state.report;

	const { Document, Packer, Paragraph, TextRun, HeadingLevel } = await import('docx');
	const { saveAs } = await import('file-saver');

	const children: InstanceType<typeof Paragraph>[] = [];

	// Title
	children.push(
		new Paragraph({
			children: [new TextRun({ text: report.title || 'Research Report', bold: true, size: 36 })],
			heading: HeadingLevel.TITLE,
			spacing: { after: 400 }
		})
	);

	// Sections
	if (report.sections) {
		for (const section of report.sections) {
			children.push(
				new Paragraph({
					children: [new TextRun({ text: section.title || '', bold: true, size: 28 })],
					heading: HeadingLevel.HEADING_1,
					spacing: { before: 400, after: 200 }
				})
			);

			const paragraphs = (section.content || '').split('\n').filter((p: string) => p.trim());
			for (const para of paragraphs) {
				children.push(
					new Paragraph({
						children: [new TextRun({ text: para, size: 22 })],
						spacing: { after: 120 }
					})
				);
			}
		}
	}

	const doc = new Document({ sections: [{ children }] });
	const blob = await Packer.toBlob(doc);
	const safeTitle = (report.title || 'research_report').replace(/[\\/:*?"<>|]+/g, '-');
	saveAs(blob, `${safeTitle}.docx`);
}
