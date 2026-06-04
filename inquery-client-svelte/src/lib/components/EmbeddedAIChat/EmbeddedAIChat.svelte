<script lang="ts">
	import { onMount, onDestroy, tick } from 'svelte';
	import { Button } from '$lib/components/ui';
	import {
		DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem
	} from '$lib/components/ui';
	import {
		SendHorizontal, Loader2, Copy, Check, Plus, ChevronDown, ChevronUp, ChevronsUpDown,
		Square, MessageSquare, Trash2, Database, Clock, Play, AlertCircle, Code,
		ExternalLink, X, BarChart3, TrendingUp, PieChart as PieChartIcon, Table2, CircleDot, Pin,
		Expand, Settings, ImageDown, Download, ThumbsUp, ThumbsDown, Pencil
	} from 'lucide-svelte';
	import { guessChartType, generateChartOptionWithConfig, inferChartConfig, buildInitialChartConfig, buildCardMetrics, formatMetricValue, formatValue, type ChartType, type ChartConfig, type MetricFormat } from '$lib/utils/chartUtils';
	import { downloadTableAsCSV, downloadTableAsJSON, downloadInsertSQL, downloadChartAsPNG } from '$lib/utils/export';
	import { AISparkleIcon } from '$lib/components/AISparkleIcon';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import {
		getAIChatStore, fetchChatRooms, sendMessage, stopStreaming,
		setCurrentRoom, setOnRoomSwitch, removeChatRoom, executeQuery, updateQuerySql,
		handleFeedback as storeFeedback, renameChatRoom, sendClarification,
		sendDisambiguationChoice, updateMessageById, generateTitleFromSql,
		type IMessage, type IChatRoom, type IDisambiguationOption, type IQueryResult
	} from '$lib/stores/aiChat.svelte';
	import connectionService from '$lib/service/connection';
	import dashboardService from '$lib/service/dashboard';
	import type { IConnectionListItem } from '$lib/types/connection';
	import { databaseMap } from '$lib/types/database';
	import { syncIntelliSenseForConnection } from '$lib/utils/intellisense/unified-provider';
	import { getCopyContext } from '$lib/utils/clipboardContext';
	import { getWorkspaceStore, setPendingSql } from '$lib/stores/workspace.svelte';
	import ToolApproval from '$lib/components/ToolApproval/ToolApproval.svelte';
	import PythonOutput from '$lib/components/ToolApproval/PythonOutput.svelte';
	import message from '$lib/utils/message';

	const chat = getAIChatStore();
	const ws = getWorkspaceStore();

	// Manual mode is intentionally hidden from the workspace chat — Auto is
	//   the only supported interactive mode here. Deep Research is shown as
	//   disabled with a pointer to the dedicated AI Chat page. The
	//   `selectedMode` type still allows 'manual' so legacy persisted values
	//   in localStorage can be migrated without compile errors.
	const modeOptions = [
		{ value: 'auto', label: 'Auto', desc: 'Auto-execute generated SQL' },
		{ value: 'deep', label: 'Deep Research', desc: 'Available in AI Chat page', disabled: true },
	];

	let inputEl = $state<HTMLDivElement | null>(null);
	let hasContent = $state(false);
	let messagesContainer = $state<HTMLDivElement | null>(null);
	let isComposing = $state(false);
	let copiedId = $state<string | null>(null);
	let copiedSqlId = $state<string | null>(null);

	// Code context chips (pasted code from editor)
	interface CodeContext {
		id: string;
		label: string;
		content: string;
		lineCount: number;
		displayRange: string;
	}
	let codeContexts = $state<CodeContext[]>([]);

	// Connection
	let connections = $state<IConnectionListItem[]>([]);
	let selectedDataSourceId = $state<number | undefined>(undefined);
	let showConnectionDropdown = $state(false);

	// Model (always inquery-agent; backend decides actual model)
	let selectedModel = $state('inquery-agent');

	// Mode
	let selectedMode = $state<'auto' | 'manual'>('auto');
	let showModeDropdown = $state(false);

	// Panels
	let showHistory = $state(false);

	// Room editing
	let editingRoomId = $state<number | null>(null);
	let editingTitle = $state('');

	// Executing timers
	let executingTimers = $state<Record<string, number>>({});
	let timerIntervals: Record<string, ReturnType<typeof setInterval>> = {};

	// Feedback state
	let feedbackSent = $state<Record<string, 'up' | 'down'>>({});

	// Chart state
	let chartTypes = $state<Record<string, ChartType>>({});
	let showChart = $state<Record<string, boolean>>({});
	let chartConfigs = $state<Record<string, ChartConfig>>({});

	// Column sorting
	let columnSort = $state<Record<string, { col: number; dir: 'asc' | 'desc' }>>({});

	// Column formats
	let columnFormats = $state<Record<string, Record<string, MetricFormat>>>({});
	let formatDropdownOpen = $state<string | null>(null);

	// Dark mode detection
	let isDarkMode = $state(false);
	$effect(() => {
		if (typeof window !== 'undefined') {
			isDarkMode = document.documentElement.classList.contains('dark');
			const observer = new MutationObserver(() => {
				isDarkMode = document.documentElement.classList.contains('dark');
			});
			observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
			return () => observer.disconnect();
		}
	});

	// Pin to workspace
	function handlePinToWorkspace(sql: string) {
		setPendingSql(sql);
	}

	// Pin to Dashboard
	let pinToDashboardOpen = $state(false);
	let pinToDashboardData = $state<{ name: string; schema: string; dataSourceId?: number; databaseName?: string; type?: string } | null>(null);
	let pinDashboardList = $state<{ id: number; name: string }[]>([]);
	let pinSelectedDashboardId = $state<number | undefined>(undefined);
	let pinSaving = $state(false);
	let pngExporting = $state<string | null>(null);

	async function handlePinToDashboard(msg: IMessage, queryIndex: number) {
		const query = msg.queries?.[queryIndex];
		const resultData = query?.result;
		const chartKey = `${msg.id}-q${queryIndex}`;
		const activeChartType = chartTypes[chartKey] || query?.recommendedChart || (resultData ? guessChartType(resultData) : null) || 'BAR';
		const sql = query?.sql || '';
		const chartName = query?.title || 'Untitled Chart';
		const chartConfig = chartConfigs[chartKey] || (resultData ? buildInitialChartConfig(resultData, activeChartType as ChartType, query) : {});

		const selectedConn = connections?.find(c => c.id === selectedDataSourceId);
		const schema = JSON.stringify({ chartType: activeChartType, sql, resultData, chartConfig });

		pinToDashboardData = {
			name: chartName,
			schema,
			dataSourceId: selectedConn?.id,
			databaseName: (selectedConn as any)?.databaseName || '',
			type: selectedConn?.type,
		};

		try {
			const res = await dashboardService.getDashboardList({ pageNo: 1, pageSize: 100 });
			pinDashboardList = (res as any)?.data?.map((d: any) => ({ id: d.id, name: d.name })) || [];
		} catch { pinDashboardList = []; }

		pinSelectedDashboardId = undefined;
		pinToDashboardOpen = true;
	}

	async function handleSavePinToDashboard() {
		if (!pinSelectedDashboardId || !pinToDashboardData) return;
		pinSaving = true;
		try {
			const chartId = await dashboardService.createChart({
				name: pinToDashboardData.name,
				schema: pinToDashboardData.schema,
				dataSourceId: pinToDashboardData.dataSourceId,
				type: pinToDashboardData.type,
				sourceType: 'AI_CHAT',
			});
			const dashboard = await dashboardService.getDashboard({ id: pinSelectedDashboardId });
			const currentChartIds = (dashboard as any)?.chartIds || [];
			await dashboardService.updateDashboard({
				id: pinSelectedDashboardId,
				chartIds: [...currentChartIds, chartId],
			});
			message.success('Chart added to dashboard');
			pinToDashboardOpen = false;
		} catch (err) {
			console.error('Failed to pin chart:', err);
		} finally {
			pinSaving = false;
		}
	}

	// Maximize content modal
	let maximizedContent = $state<{
		type: 'table' | 'chart' | 'card';
		data?: IQueryResult;
		chartOption?: any;
		chartType?: string;
		chartConfig?: ChartConfig;
		resultData?: IQueryResult;
		title: string;
	} | null>(null);
	let maximizedChartType = $state<ChartType>('BAR');

	// Chart settings modal
	let chartSettingsOpen = $state(false);
	let chartSettingsData = $state<{ msgId: string; queryIndex: number; resultData: any; chartType: string; chartConfig?: ChartConfig } | null>(null);

	// Derived
	let selectedConn = $derived(connections.find(c => c.id === selectedDataSourceId));
	let selectedConnIcon = $derived(selectedConn ? getDbIcon(selectedConn.type || '') : null);
	let selectedModeOpt = $derived(modeOptions.find(m => m.value === selectedMode));
	let connDatabaseName = $derived((selectedConn as any)?.databaseName as string | undefined);
	let connSchemaName = $derived((selectedConn as any)?.schemaName as string | undefined);

	function getThinkingTitle(msg: any): string {
		const steps = msg?.thinkingSteps || [];
		const active = steps.findLast?.((s: any) => s.status === 'running') || steps[steps.length - 1];
		return active?.title || '';
	}

	function getDbIcon(type: string): string | null {
		const info = databaseMap[type?.toUpperCase()];
		return info?.img || null;
	}

	/** Strip markdown syntax from title strings */
	function stripMarkdown(text: string): string {
		return text
			.replace(/^#{1,6}\s+/gm, '')
			.replace(/\*\*(.+?)\*\*/g, '$1')
			.replace(/\*(.+?)\*/g, '$1')
			.replace(/`(.+?)`/g, '$1')
			.replace(/~~(.+?)~~/g, '$1')
			.replace(/\[(.+?)\]\(.+?\)/g, '$1')
			.trim();
	}

	// Sort data list
	function sortDataList(dataList: any[][], sortKey: string): any[][] {
		const sort = columnSort[sortKey];
		if (!sort) return dataList;
		const { col, dir } = sort;
		return [...dataList].sort((a, b) => {
			const va = a[col], vb = b[col];
			if (va == null && vb == null) return 0;
			if (va == null) return 1;
			if (vb == null) return -1;
			const na = Number(va), nb = Number(vb);
			if (!isNaN(na) && !isNaN(nb)) return dir === 'asc' ? na - nb : nb - na;
			return dir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
		});
	}

	function toggleSort(sortKey: string, colIdx: number) {
		const current = columnSort[sortKey];
		if (current?.col === colIdx) {
			if (current.dir === 'asc') {
				columnSort = { ...columnSort, [sortKey]: { col: colIdx, dir: 'desc' } };
			} else {
				const { [sortKey]: _, ...rest } = columnSort;
				columnSort = rest;
			}
		} else {
			columnSort = { ...columnSort, [sortKey]: { col: colIdx, dir: 'asc' } };
		}
	}

	// Chart statistics
	function calculateChartStats(res: IQueryResult): { sum: number | null; avg: number | null; min: number | null; max: number | null } | null {
		if (!res?.dataList?.length || !res?.headerList?.length) return null;
		let numColIdx = -1;
		for (let i = 0; i < res.headerList.length; i++) {
			const h = res.headerList[i];
			const name = typeof h === 'string' ? h : h.name;
			if (name === 'Row Number' || name === '#') continue;
			const sampleVal = res.dataList[0]?.[i];
			if (typeof sampleVal === 'number' || (typeof sampleVal === 'string' && !isNaN(Number(sampleVal)) && sampleVal.trim() !== '')) {
				numColIdx = i;
				break;
			}
		}
		if (numColIdx === -1) {
			const lastIdx = res.headerList.length - 1;
			const val = res.dataList[0]?.[lastIdx];
			if (typeof val === 'number' || (typeof val === 'string' && !isNaN(Number(val)))) {
				numColIdx = lastIdx;
			}
		}
		if (numColIdx === -1) return null;
		const nums = res.dataList.map(row => Number(row[numColIdx])).filter(n => !isNaN(n));
		if (nums.length === 0) return null;
		const sum = nums.reduce((a, b) => a + b, 0);
		return { sum, avg: sum / nums.length, min: Math.min(...nums), max: Math.max(...nums) };
	}

	function formatStatNumber(n: number): string {
		if (Math.abs(n) >= 1e9) return (n / 1e9).toFixed(1) + 'B';
		if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(1) + 'M';
		if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'K';
		return Number.isInteger(n) ? n.toString() : n.toFixed(2);
	}

	function getDataSourceOptions() {
		const conn = connections?.find(c => c.id === selectedDataSourceId);
		return {
			dataSourceId: selectedDataSourceId ?? ws.currentConnection?.id,
			databaseName: (conn as any)?.databaseName as string | undefined,
			schemaName: (conn as any)?.schemaName as string | undefined,
			connectionList: connections as any[],
			selectedDatabase: selectedDataSourceId ? String(selectedDataSourceId) : undefined,
		};
	}

	async function handleSend() {
		if (chat.isStreaming) return;
		const { fullMessage } = extractInputContent();
		if (!fullMessage) return;

		if (inputEl) inputEl.innerHTML = '';
		codeContexts = [];
		hasContent = false;
		showHistory = false;
		const dsOpts = getDataSourceOptions();
		await sendMessage(fullMessage, { ...dsOpts, executionMode: selectedMode, model: selectedModel });
		await tick();
		scrollToBottom();
	}

	async function handleNewChat() {
		await setCurrentRoom(null);
		showHistory = false;
		if (typeof localStorage !== 'undefined') {
			localStorage.removeItem('embedded-chat-last-room-id');
		}
	}

	async function handleSelectRoom(roomId: number) {
		await setCurrentRoom(roomId);
		showHistory = false;
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('embedded-chat-last-room-id', String(roomId));
		}
	}

	async function handleDeleteRoom(e: MouseEvent, roomId: number) {
		e.stopPropagation();
		await removeChatRoom(roomId);
	}

	// Room rename
	function handleStartEdit(room: IChatRoom) {
		editingRoomId = room.id;
		editingTitle = room.title;
	}

	async function handleSaveEdit() {
		if (editingRoomId && editingTitle.trim()) {
			await renameChatRoom(editingRoomId, editingTitle.trim());
			editingRoomId = null;
		}
	}

	// Feedback
	async function handleFeedback(msgId: string, type: 'up' | 'down') {
		feedbackSent = { ...feedbackSent, [msgId]: type };
		await storeFeedback(msgId, type);
	}

	// Clarification
	async function handleClarification(msgId: string, response: string) {
		updateMessageById(msgId, { needsClarification: false });
		await sendClarification(response, response, {
			...getDataSourceOptions(),
			executionMode: selectedMode,
			model: selectedModel,
		});
		await tick();
		scrollToBottom();
	}

	function scrollToBottom() {
		if (messagesContainer) {
			messagesContainer.scrollTop = messagesContainer.scrollHeight;
		}
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey && !isComposing) {
			e.preventDefault();
			handleSend();
		}
	}

	function copyText(text: string, id: string) {
		navigator.clipboard.writeText(text);
		copiedId = id;
		setTimeout(() => copiedId = null, 2000);
	}

	function copySql(sql: string, queryId: string) {
		navigator.clipboard.writeText(sql);
		copiedSqlId = queryId;
		setTimeout(() => copiedSqlId = null, 2000);
	}

	async function handleRunQuery(msgId: string, queryIndex: number) {
		const userMessages = chat.messages.filter(m => m.role === 'user');
		const lastUserMsg = userMessages[userMessages.length - 1];
		await executeQuery(msgId, queryIndex, {
			...getDataSourceOptions(),
			model: selectedModel,
			originalUserQuery: lastUserMsg?.content,
		});
		await tick();
		scrollToBottom();
	}

	async function handleDisambiguation(msgId: string, option: IDisambiguationOption) {
		updateMessageById(msgId, { needsDisambiguation: false });
		await sendDisambiguationChoice(option, {
			...getDataSourceOptions(),
			executionMode: selectedMode,
			model: selectedModel,
		});
		await tick();
		scrollToBottom();
	}

	function updateHasContent() {
		if (!inputEl) { hasContent = false; return; }
		const text = inputEl.textContent?.trim() || '';
		hasContent = text.length > 0 || inputEl.querySelector('.code-chip') !== null;
	}

	function handleInputChange() {
		updateHasContent();
	}

	function handlePaste(e: ClipboardEvent) {
		const text = e.clipboardData?.getData('text/plain');
		if (!text) return;
		e.preventDefault();

		const lines = text.split('\n');
		if (lines.length >= 3) {
			const chipId = crypto.randomUUID();
			const copyCtx = getCopyContext();
			const label = copyCtx?.language
				? copyCtx.language.charAt(0).toUpperCase() + copyCtx.language.slice(1)
				: detectCodeLanguage(text);
			const lineCount = lines.filter(l => l.trim()).length;
			const displayRange = copyCtx
				? `${copyCtx.startLine}-${copyCtx.endLine}`
				: `${lineCount} lines`;
			codeContexts = [...codeContexts, { id: chipId, label, content: text.trim(), lineCount, displayRange }];
			insertChipAtCursor(chipId, label, displayRange, text.trim());
		} else {
			insertTextAtCursor(text);
		}
		updateHasContent();
	}

	function insertTextAtCursor(text: string) {
		const sel = window.getSelection();
		if (!sel || !sel.rangeCount) return;
		const range = sel.getRangeAt(0);
		range.deleteContents();
		const textNode = document.createTextNode(text);
		range.insertNode(textNode);
		range.setStartAfter(textNode);
		range.setEndAfter(textNode);
		sel.removeAllRanges();
		sel.addRange(range);
	}

	const CODE_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
	const X_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';

	function insertChipAtCursor(id: string, label: string, displayRange: string, content: string) {
		if (!inputEl) return;
		inputEl.focus();

		const sel = window.getSelection();
		let range: Range;
		if (sel && sel.rangeCount > 0 && inputEl.contains(sel.anchorNode)) {
			range = sel.getRangeAt(0);
		} else {
			range = document.createRange();
			range.selectNodeContents(inputEl);
			range.collapse(false);
		}
		range.deleteContents();

		const contentLines = content.split('\n');
		const chip = document.createElement('span');
		chip.className = 'code-chip';
		chip.contentEditable = 'false';
		chip.dataset.chipId = id;
		chip.title = contentLines.slice(0, 5).join('\n') + (contentLines.length > 5 ? '\n...' : '');
		chip.innerHTML = `${CODE_ICON_SVG}<span class="chip-label">${label}</span><span class="chip-meta">(${displayRange})</span><button class="chip-remove" data-remove-chip="${id}">${X_ICON_SVG}</button>`;

		const spaceAfter = document.createTextNode('\u00A0');
		range.insertNode(spaceAfter);
		range.insertNode(chip);

		range.setStartAfter(spaceAfter);
		range.setEndAfter(spaceAfter);
		if (sel) {
			sel.removeAllRanges();
			sel.addRange(range);
		}
	}

	function handleInputClick(e: MouseEvent) {
		const removeBtn = (e.target as HTMLElement).closest('[data-remove-chip]') as HTMLElement | null;
		if (!removeBtn) return;
		e.preventDefault();
		const chipId = removeBtn.dataset.removeChip;
		if (chipId) {
			const chipEl = inputEl?.querySelector(`[data-chip-id="${chipId}"]`);
			if (chipEl) {
				const next = chipEl.nextSibling;
				if (next?.nodeType === Node.TEXT_NODE && next.textContent === '\u00A0') next.remove();
				chipEl.remove();
			}
			codeContexts = codeContexts.filter(c => c.id !== chipId);
			updateHasContent();
		}
	}

	function extractInputContent(): { fullMessage: string } {
		if (!inputEl) return { fullMessage: '' };
		let result = '';

		function processNode(node: Node) {
			if (node.nodeType === Node.TEXT_NODE) {
				result += node.textContent || '';
			} else if (node instanceof HTMLElement) {
				if (node.classList.contains('code-chip')) {
					const ctx = codeContexts.find(c => c.id === node.dataset.chipId);
					if (ctx) result += `\n\`\`\`${ctx.label.toLowerCase()}\n${ctx.content}\n\`\`\`\n`;
				} else if (node.tagName === 'BR') {
					result += '\n';
				} else {
					if ((node.tagName === 'DIV' || node.tagName === 'P') && result.length > 0 && !result.endsWith('\n')) result += '\n';
					for (const child of node.childNodes) processNode(child);
				}
			}
		}

		for (const child of inputEl.childNodes) processNode(child);
		return { fullMessage: result.trim() };
	}

	function detectCodeLanguage(text: string): string {
		const upper = text.toUpperCase();
		if (/\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|WITH|FROM\s+\w+|WHERE|JOIN)\b/.test(upper)) return 'SQL';
		if (/\b(function|const|let|var|import|export|=>)\b/.test(text)) return 'JavaScript';
		if (/\b(def |class |import |from |print\(|self\.)\b/.test(text)) return 'Python';
		if (/\b(public|private|protected|class |void |String |int |return )\b/.test(text)) return 'Java';
		return 'Code';
	}

	function parseMessageCodeBlocks(content: string): { chips: { label: string; displayRange: string; preview: string }[]; text: string } {
		const chips: { label: string; displayRange: string; preview: string }[] = [];
		let remaining = content;

		while (remaining.startsWith('```')) {
			const endIdx = remaining.indexOf('```', 3);
			if (endIdx === -1) break;

			const block = remaining.substring(3, endIdx);
			const nlIdx = block.indexOf('\n');
			const lang = nlIdx > 0 ? block.substring(0, nlIdx).trim() : '';
			const code = nlIdx > 0 ? block.substring(nlIdx + 1).trim() : block.trim();
			const codeLines = code.split('\n');

			chips.push({
				label: lang ? lang.charAt(0).toUpperCase() + lang.slice(1) : 'Code',
				displayRange: `${codeLines.length} lines`,
				preview: codeLines.slice(0, 4).join('\n') + (codeLines.length > 4 ? '\n...' : ''),
			});

			remaining = remaining.substring(endIdx + 3).replace(/^\n+/, '');
		}

		return { chips, text: remaining.trim() };
	}

	// Auto-scroll: scroll to bottom only when content height actually grows
	$effect(() => {
		const container = messagesContainer;
		if (!container) return;

		let scrollRAF: number | null = null;
		let prevScrollHeight = container.scrollHeight;

		function scheduleScroll() {
			if (scrollRAF) return;
			scrollRAF = requestAnimationFrame(() => {
				scrollRAF = null;
				if (!container) return;
				const { scrollTop, scrollHeight, clientHeight } = container;
				const heightGrew = scrollHeight > prevScrollHeight;
				const wasNearBottom = prevScrollHeight - scrollTop - clientHeight < 100;
				prevScrollHeight = scrollHeight;
				if (heightGrew && wasNearBottom) {
					container.scrollTo({ top: scrollHeight, behavior: 'instant' });
				}
			});
		}

		const observer = new MutationObserver(scheduleScroll);
		observer.observe(container, { childList: true, subtree: true, characterData: true });

		return () => {
			observer.disconnect();
			if (scrollRAF) cancelAnimationFrame(scrollRAF);
		};
	});

	// Save database selection & sync IntelliSense
	$effect(() => {
		if (selectedDataSourceId && typeof localStorage !== 'undefined') {
			localStorage.setItem('embedded-chat-selected-db', String(selectedDataSourceId));
		}
		if (selectedDataSourceId && connections.length > 0) {
			const conn = connections.find(c => c.id === selectedDataSourceId);
			if (conn) {
				syncIntelliSenseForConnection({
					dataSourceId: conn.id,
					databaseType: conn.type,
					alias: conn.alias,
					supportSchema: conn.supportSchema,
				});
			}
		}
	});

	// Save execution mode
	$effect(() => {
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('embedded-chat-execution-mode', selectedMode);
		}
	});

	// Manage executing timers
	$effect(() => {
		for (const msg of chat.messages) {
			if (!msg.queries) continue;
			for (let qi = 0; qi < msg.queries.length; qi++) {
				const q = msg.queries[qi];
				const key = `${msg.id}-q${qi}`;
				if (q.isExecuting && !timerIntervals[key]) {
					executingTimers[key] = 0;
					timerIntervals[key] = setInterval(() => {
						executingTimers = { ...executingTimers, [key]: (executingTimers[key] || 0) + 1 };
					}, 1000);
				} else if (!q.isExecuting && timerIntervals[key]) {
					clearInterval(timerIntervals[key]);
					delete timerIntervals[key];
				}
			}
		}
	});

	// Auto-focus input when streaming finishes
	let prevStreaming = $state(false);
	$effect(() => {
		if (prevStreaming && !chat.isStreaming) {
			tick().then(() => inputEl?.focus());
		}
		prevStreaming = chat.isStreaming;
	});

	onDestroy(() => {
		Object.values(timerIntervals).forEach(clearInterval);
	});

	onMount(async () => {
		// Register room switch callback
		setOnRoomSwitch(() => {});

		await fetchChatRooms();

		// Restore last room
		if (typeof localStorage !== 'undefined') {
			const lastRoomId = localStorage.getItem('embedded-chat-last-room-id');
			if (lastRoomId && chat.chatRooms.some(r => r.id === Number(lastRoomId))) {
				await setCurrentRoom(Number(lastRoomId));
			} else if (chat.chatRooms.length > 0) {
				await setCurrentRoom(chat.chatRooms[0].id);
			}
		}

		try {
			const res = await connectionService.getList({ pageNo: 1, pageSize: 1000 });
			connections = (res as any)?.data || [];
			if (connections.length > 0 && !selectedDataSourceId) {
				const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('embedded-chat-selected-db') : null;
				if (saved && connections.some(c => String(c.id) === saved)) {
					selectedDataSourceId = Number(saved);
				} else {
					selectedDataSourceId = connections[0].id;
				}
			}
		} catch { /* ignore */ }

		// Restore execution mode. Manual mode is no longer exposed in this
		//   chat (see modeOptions above); coerce any legacy persisted value
		//   to 'auto' so users who had manual selected previously land on a
		//   visible option without a hidden state.
		if (typeof localStorage !== 'undefined') {
			const savedMode = localStorage.getItem('embedded-chat-execution-mode');
			if (savedMode === 'auto') {
				selectedMode = 'auto';
			} else if (savedMode === 'manual') {
				selectedMode = 'auto';
			}
		}
	});

	// Close format dropdown on outside click
	onMount(() => {
		const handleGlobalClick = () => { if (formatDropdownOpen) formatDropdownOpen = null; };
		document.addEventListener('click', handleGlobalClick);
		return () => document.removeEventListener('click', handleGlobalClick);
	});
</script>

<div class="flex flex-col h-full">
	<!-- Header: Mode selector (left) + actions (right) -->
	<div class="flex items-center justify-between h-10 px-3 shrink-0">
		<!-- Mode Selector -->
		<div class="relative">
			<button
				class="flex items-center gap-1.5 px-2 h-7 rounded-lg cursor-pointer hover:bg-accent transition-colors select-none"
				onclick={() => showModeDropdown = !showModeDropdown}
			>
				<span class="text-xs font-medium text-foreground">{selectedModeOpt?.label || 'Auto'}</span>
				<ChevronDown size={12} class="text-muted-foreground" />
			</button>
			{#if showModeDropdown}
				<!-- svelte-ignore a11y_click_events_have_key_events -->
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div class="fixed inset-0 z-40" onclick={() => showModeDropdown = false}></div>
				<div class="absolute left-0 top-full mt-1 z-50 bg-popover border border-border rounded-md shadow-lg py-1 min-w-[200px]">
				{#each modeOptions as mode}
					{#if mode.disabled}
						<a
							href="/ai-chat"
							class="flex items-center w-full px-3 py-2 text-[12px] hover:bg-accent transition-colors text-left rounded-sm opacity-60"
							onclick={() => showModeDropdown = false}
						>
							<div class="flex-1">
								<div class="font-medium text-foreground">{mode.label}</div>
								<div class="text-muted-foreground text-[11px]">{mode.desc}</div>
							</div>
							<ExternalLink size={12} class="ml-auto text-muted-foreground shrink-0" />
						</a>
					{:else}
						<button
							class="flex items-center w-full px-3 py-2 text-[12px] hover:bg-accent transition-colors text-left rounded-sm
								{selectedMode === mode.value ? 'bg-accent' : ''}"
							onclick={() => { selectedMode = mode.value as typeof selectedMode; showModeDropdown = false; }}
						>
							<div class="flex-1">
								<div class="font-medium text-foreground">{mode.label}</div>
								<div class="text-muted-foreground text-[11px]">{mode.desc}</div>
							</div>
							{#if selectedMode === mode.value}
								<Check size={12} class="ml-auto text-primary shrink-0" />
							{/if}
						</button>
					{/if}
				{/each}
				</div>
			{/if}
		</div>

		<!-- Actions: History + New -->
		<div class="flex items-center gap-0.5">
			<div class="relative">
				<button
					class="p-1.5 rounded-md transition-colors {showHistory ? 'text-foreground bg-accent' : 'text-muted-foreground hover:text-foreground hover:bg-accent'}"
					onclick={() => showHistory = !showHistory}
					title="Chat history"
				>
					<Clock size={14} />
				</button>
				{#if showHistory}
					<!-- svelte-ignore a11y_click_events_have_key_events -->
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div class="fixed inset-0 z-40" onclick={() => showHistory = false}></div>
					<div class="absolute right-0 top-full mt-1 z-50 w-64 max-h-80 bg-popover border border-border rounded-md shadow-lg overflow-hidden">
						<div class="px-3 py-2 text-[12px] text-muted-foreground font-medium border-b border-border">Recent Chats</div>
						<div class="max-h-64 overflow-auto py-1">
							{#if chat.chatRooms.length === 0}
								<div class="px-3 py-4 text-[12px] text-muted-foreground text-center">No chat history</div>
							{:else}
							{#each chat.chatRooms.slice(0, 20) as room (room.id)}
								<!-- svelte-ignore a11y_click_events_have_key_events -->
								<!-- svelte-ignore a11y_no_static_element_interactions -->
								<div
									class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] hover:bg-accent transition-colors cursor-pointer group
										{chat.currentRoomId === room.id ? 'bg-accent' : ''}"
									onclick={() => handleSelectRoom(room.id)}
								>
									{#if editingRoomId === room.id}
										<!-- svelte-ignore a11y_autofocus -->
										<input
											class="flex-1 text-[12px] bg-transparent border-b border-primary outline-none"
											bind:value={editingTitle}
											autofocus
											onkeydown={(e) => { if (e.key === 'Enter') handleSaveEdit(); if (e.key === 'Escape') editingRoomId = null; }}
											onclick={(e) => e.stopPropagation()}
										/>
										<button class="p-0.5 rounded hover:bg-accent text-primary shrink-0" onclick={(e) => { e.stopPropagation(); handleSaveEdit(); }}>
											<Check size={10} />
										</button>
									{:else}
										<span class="flex-1 truncate">{room.title || 'Untitled'}</span>
										<button
											class="p-0.5 rounded hover:bg-accent text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
											onclick={(e) => { e.stopPropagation(); handleStartEdit(room); }}
											title="Rename"
										>
											<Pencil size={10} />
										</button>
										<button
											class="p-0.5 rounded hover:bg-destructive/20 text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
											onclick={(e) => handleDeleteRoom(e, room.id)}
											title="Delete"
										>
											<Trash2 size={10} />
										</button>
									{/if}
								</div>
							{/each}
							{/if}
						</div>
					</div>
				{/if}
			</div>
			<button
				class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
				onclick={handleNewChat}
				title="New chat"
			>
				<Plus size={14} />
			</button>
		</div>
	</div>

	<!-- Messages -->
		<div bind:this={messagesContainer} class="flex-1 overflow-y-auto p-3 space-y-6 border-t border-border">
			{#if !chat.messages || chat.messages.length === 0}
				<div class="flex flex-col items-center justify-center h-full text-center px-4">
					<AISparkleIcon size={28} class="text-primary/40 mb-3" />
					<p class="text-xs font-medium text-foreground mb-1">Ask anything about your data</p>
					<p class="text-[12px] text-muted-foreground">I can help you query databases, analyze data, and create charts.</p>
				</div>
			{:else}
			{#each chat.messages as msg (msg.id)}
				{#if msg.role === 'user'}
					{@const msgParts = parseMessageCodeBlocks(msg.content)}
					<div class="flex justify-end">
						<div class="max-w-[85%] rounded-lg bg-primary text-primary-foreground px-3 py-2 text-[12px]">
							{#if msgParts.chips.length > 0}
								<div class="flex flex-wrap gap-1 mb-1.5">
									{#each msgParts.chips as chip}
										<span
											class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-primary-foreground/15 text-[12px]"
											title={chip.preview}
										>
											<Code size={10} />
											{chip.label} ({chip.displayRange})
										</span>
									{/each}
								</div>
								{msgParts.text}
							{:else}
								{msg.content}
							{/if}
						</div>
					</div>
					{:else}
						<div class="flex gap-2">
							<div class="shrink-0 mt-1">
								<AISparkleIcon size={16} class="text-primary" />
							</div>
							<div class="flex-1 min-w-0">
								<div class="text-xs text-foreground">
									{#if msg.isStreaming && !msg.content}
										<div class="flex items-center gap-1.5 text-muted-foreground">
											<Loader2 size={12} class="animate-spin" />
											{#if getThinkingTitle(msg)}
												<span>{getThinkingTitle(msg)}</span>
											{/if}
										</div>
									{:else}
										<MarkdownRenderer content={msg.content || ''} dataSourceId={selectedDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} compact />
									{/if}
								</div>
								<!-- Query editors -->
								{#if msg.queries && msg.queries.length > 0}
								<div class="mt-5 space-y-7">
								{#each msg.queries as query, qi (`${msg.id}-q${qi}`)}
									{@const queryId = `${msg.id}-q${qi}`}
									<div>
									{#if query.title}
										<div class="mb-0.5 text-[12px] font-semibold text-foreground">
											<MarkdownRenderer content={query.title} dataSourceId={selectedDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} compact />
										</div>
									{/if}
									<div class="relative rounded-md overflow-hidden border border-border bg-card mt-3">
										<div class="flex items-center justify-between px-2 py-0.5 border-b border-border bg-muted/30">
											<span class="text-[11px] font-medium text-muted-foreground/70 flex items-center gap-0.5">
												<Code size={10} class="opacity-60" />SQL
											</span>
											<div class="flex items-center gap-0.5">
												<button class="p-0.5 rounded hover:bg-accent text-muted-foreground hover:text-foreground" title="Copy SQL"
													onclick={() => copySql(query.sql, queryId)}>
													{#if copiedSqlId === queryId}
														<Check size={10} class="text-green-500" />
													{:else}
														<Copy size={10} />
													{/if}
												</button>
												<button class="p-0.5 rounded hover:bg-accent text-muted-foreground hover:text-foreground" title="Run query"
													onclick={() => handleRunQuery(msg.id, qi)} disabled={query.isExecuting}>
													{#if query.isExecuting}
														<Loader2 size={10} class="animate-spin" />
													{:else}
														<Play size={10} />
													{/if}
												</button>
												<button class="p-0.5 rounded hover:bg-accent text-muted-foreground hover:text-foreground" title="Pin to workspace"
													onclick={() => handlePinToWorkspace(query.sql)}>
													<Pin size={10} />
												</button>
											</div>
										</div>
										{#if query.isExecuting}
											<div class="absolute inset-0 bg-background/50 flex items-center justify-center z-10">
												<div class="flex items-center gap-1 text-[11px] text-muted-foreground">
													<Loader2 size={10} class="animate-spin" />
													<span>Executing...</span>
												</div>
											</div>
										{/if}
										{#await import('$lib/components/MonacoEditor') then { MonacoEditor }}
											<div style="height: {Math.min(200, Math.max(32, query.sql.split('\n').reduce((s, l) => s + Math.max(1, Math.ceil(l.length / 40)), 0) * 13 + 8))}px;">
												<MonacoEditor
													value={query.sql}
													language="sql"
													readOnly={query.isExecuting || false}
													onchange={(val) => updateQuerySql(msg.id, qi, val)}
													options={{ fontSize: 11, lineNumbersMinChars: 2, lineDecorationsWidth: 0, glyphMargin: false, wordWrap: 'on' }}
													class="border-0"
												/>
											</div>
										{:catch}
											<pre class="p-1.5 text-[11px] text-foreground overflow-x-auto font-mono"><code>{query.sql}</code></pre>
										{/await}
									</div>
									{#if query.explanation}
										<div class="mt-3 px-2 py-2.5 rounded-md bg-muted/50 text-[12px] text-muted-foreground leading-relaxed">
												<MarkdownRenderer content={query.explanation} dataSourceId={selectedDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} compact />
											</div>
										{/if}
									</div>
									{/each}
								</div>

								<!-- Query execution results - stacked BELOW all queries -->
								{#each msg.queries as query, qi (`${msg.id}-r${qi}`)}
									{#if query.isExecuting && !query.result}
										{@const cKey = `${msg.id}-q${qi}`}
										<div class="mt-6 rounded-xl border border-border/40 bg-card shadow-sm overflow-hidden">
											<div class="flex items-center justify-between px-3 py-2 bg-muted/20 border-b border-border/30">
												<span class="text-[11px] font-medium text-foreground/80 truncate">
													{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))} Result
												</span>
											</div>
											<div class="flex items-center justify-center gap-2 py-8 text-muted-foreground">
												<Loader2 size={14} class="animate-spin" />
												<span class="text-xs">Executing query{#if executingTimers[cKey]}... {executingTimers[cKey]}s{/if}</span>
											</div>
										</div>
									{/if}
									{#if query.result}
										{@const res = query.result}
										{@const cKey = `${msg.id}-q${qi}`}
										{@const autoType = query.recommendedChart || guessChartType(res)}
										{@const activeChart = chartTypes[cKey] || autoType}
										{@const effConfig = chartConfigs[cKey] || buildInitialChartConfig(res, activeChart as ChartType, query)}
										{@const chartOpt = activeChart !== 'TABLE' && activeChart !== 'CARD' ? generateChartOptionWithConfig(res, activeChart as ChartType, effConfig) : null}
										{@const chartStats = calculateChartStats(res)}
										{@const rowNumIndices = res.headerList.reduce((acc, h, i) => { if (String(h.name || h) === 'Row Number') acc.push(i); return acc; }, [] as number[])}
										{@const filteredHeaders = res.headerList.filter((_, i) => !rowNumIndices.includes(i))}
										{@const filteredDataList = rowNumIndices.length > 0 ? res.dataList.map(row => row.filter((_, i) => !rowNumIndices.includes(i))) : res.dataList}
										{@const inlineSortKey = `inline-${msg.id}-q${qi}`}
										{@const sortedDataList = sortDataList(filteredDataList, inlineSortKey)}
										{@const inlineSort = columnSort[inlineSortKey]}
										<div class="mt-5 rounded-lg border border-border/40 bg-card shadow-sm overflow-hidden">
											<!-- Result Header -->
											<div class="flex items-center justify-between px-2 py-1.5 bg-muted/20 border-b border-border/30">
												<span class="text-[11px] font-medium text-foreground/80 truncate">
													{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))} Result
												</span>
												<div class="flex items-center gap-0.5">
													<button
														class="p-0.5 rounded-md hover:bg-accent/60 text-muted-foreground hover:text-foreground transition-colors"
														title="Pin to workspace"
														onclick={() => handlePinToWorkspace(query.sql)}
													>
														<Pin size={10} />
													</button>
												</div>
											</div>

											{#if res.success === false && res.message}
												<div class="px-2 py-1.5 bg-destructive/5">
													<div class="flex items-center gap-1 text-[11px] text-destructive">
														<AlertCircle size={10} />
														<span class="break-all">{res.message}</span>
													</div>
												</div>
											{:else if res.headerList && res.dataList}
												<!-- Result metadata + export -->
												<div>
													<div class="flex items-center justify-between px-2 py-1 bg-muted/10">
														<span class="text-[10px] text-muted-foreground/70">
															{res.dataList.length} rows{#if query.executionTime} · {query.executionTime}ms{/if}
														</span>
														<div class="flex items-center gap-0.5">
															<button
																class="px-1 py-0.5 rounded text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																onclick={() => downloadTableAsCSV(filteredHeaders, filteredDataList)}
																title="Export CSV"
															><Download size={8} class="inline mr-0.5" />CSV</button>
															<button
																class="px-1 py-0.5 rounded text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																onclick={() => downloadTableAsJSON(filteredHeaders, filteredDataList)}
																title="Export JSON"
															><Download size={8} class="inline mr-0.5" />JSON</button>
															<button
																class="px-1 py-0.5 rounded text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																onclick={() => downloadInsertSQL('query_result', filteredHeaders, filteredDataList)}
																title="Export SQL INSERT"
															><Download size={8} class="inline mr-0.5" />SQL</button>
															<button
																class="px-0.5 py-0.5 rounded text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																onclick={() => maximizedContent = { type: 'table', data: { ...res, headerList: filteredHeaders, dataList: filteredDataList }, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) }}
																title="Maximize table"
															><Expand size={8} /></button>
														</div>
													</div>
													<!-- Result table -->
													<div class="max-h-[160px] overflow-auto relative">
														<table class="w-full text-[11px]">
															<thead class="sticky top-0 z-[2]">
																<tr class="bg-muted/60 dark:bg-muted/60">
																	<th class="px-1 py-0.5 text-left font-medium text-muted-foreground whitespace-nowrap border-b border-border w-6 bg-muted/60 dark:bg-muted/60">#</th>
																	{#each filteredHeaders as h, colIdx}
																		{@const colName = String(h.name || h)}
																		{@const fmtKey = `${msg.id}-${colName}`}
																		<th class="px-1.5 py-0.5 text-left font-medium text-muted-foreground whitespace-nowrap border-b border-border relative bg-muted/60 dark:bg-muted/60">
																			<div class="flex items-center gap-0.5">
																				<button
																					class="hover:text-foreground cursor-pointer select-none inline-flex items-center gap-0.5"
																					onclick={() => toggleSort(inlineSortKey, colIdx)}
																					title="Sort column"
																				>
																					{colName}
																					{#if inlineSort?.col === colIdx}
																						{#if inlineSort.dir === 'asc'}
																							<ChevronUp size={8} class="text-primary" />
																						{:else}
																							<ChevronDown size={8} class="text-primary" />
																						{/if}
																					{:else}
																						<ChevronsUpDown size={8} class="opacity-30" />
																					{/if}
																				</button>
																				<button
																					class="opacity-40 hover:opacity-100"
																					onclick={(e) => { e.stopPropagation(); formatDropdownOpen = formatDropdownOpen === fmtKey ? null : fmtKey; }}
																					title="Format column"
																				>
																					<ChevronDown size={7} />
																				</button>
																			</div>
																			{#if formatDropdownOpen === fmtKey}
																				<!-- svelte-ignore a11y_no_static_element_interactions -->
																				<div
																					class="absolute left-0 top-full mt-0.5 bg-popover border border-border rounded-md shadow-lg z-50 min-w-[140px] py-0.5"
																					onmousedown={(e) => e.stopPropagation()}
																				>
																					{#each [
																						{ key: 'original', label: 'Original' },
																						{ key: 'comma', label: ',d (1,234)' },
																						{ key: 'decimal1', label: ',.1f (1,234.5)' },
																						{ key: 'decimal2', label: ',.2f (1,234.56)' },
																						{ key: 'percent', label: '% (40%)' },
																						{ key: 'percent1', label: ',.1f%' },
																						{ key: 'percent2', label: ',.2f%' },
																						{ key: 'compact', label: '.1s (1.2k)' },
																					] as fmt}
																						<button
																							class="w-full px-2 py-0.5 text-left text-[10px] hover:bg-accent flex items-center justify-between"
																							onclick={() => {
																								columnFormats = {
																									...columnFormats,
																									[msg.id]: { ...(columnFormats[msg.id] || {}), [colName]: fmt.key as MetricFormat }
																								};
																								formatDropdownOpen = null;
																							}}
																						>
																							<span>{fmt.label}</span>
																							{#if (columnFormats[msg.id]?.[colName] || 'original') === fmt.key}
																								<Check size={8} class="text-primary" />
																							{/if}
																						</button>
																					{/each}
																				</div>
																			{/if}
																		</th>
																	{/each}
																</tr>
															</thead>
															<tbody>
																{#each sortedDataList.slice(0, 50) as row, ri}
																	<tr class="border-b border-border/50 hover:bg-accent/30 transition-colors">
																		<td class="px-1 py-0.5 text-muted-foreground/50 text-[10px]">{ri + 1}</td>
																		{#each row as cell, colIdx}
																			{@const colName = String(filteredHeaders[colIdx]?.name || filteredHeaders[colIdx] || '')}
																			{@const colFmt = columnFormats[msg.id]?.[colName] || 'original'}
																			<td class="px-1.5 py-0.5 whitespace-nowrap max-w-[150px] truncate text-foreground" title={String(cell ?? 'NULL')}>
																				{#if cell === null || cell === undefined}
																					<span class="text-muted-foreground/50 italic">NULL</span>
																				{:else if colFmt !== 'original' && !isNaN(Number(cell)) && cell !== ''}
																					{formatValue(cell, colFmt)}
																				{:else}
																					{cell}
																				{/if}
																			</td>
																		{/each}
																	</tr>
																{/each}
															</tbody>
														</table>
														{#if filteredDataList.length > 50}
															<div class="px-1.5 py-0.5 text-[10px] text-muted-foreground text-center border-t border-border">Showing 50 of {filteredDataList.length} rows</div>
														{/if}
													</div>
												</div>
											{/if}
										</div>

										<!-- Chart Visualization (separate card) -->
										{#if res.headerList && res.dataList}
											<div class="mt-3.5 rounded-md border border-border overflow-hidden">
												<div class="flex items-center justify-between px-1.5 py-0.5 bg-muted/10">
													<div class="flex items-center gap-0.5">
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'BAR' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'BAR' as ChartType; showChart[cKey] = true; }}><BarChart3 size={9} />Bar</button>
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'LINE' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'LINE' as ChartType; showChart[cKey] = true; }}><TrendingUp size={9} />Line</button>
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'PIE' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'PIE' as ChartType; showChart[cKey] = true; }}><PieChartIcon size={9} />Pie</button>
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'SCATTER' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'SCATTER' as ChartType; showChart[cKey] = true; }}><CircleDot size={9} />Scatter</button>
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'TABLE' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'TABLE' as ChartType; showChart[cKey] = true; }}><Table2 size={9} />Table</button>
														<button class="px-1 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChart === 'CARD' ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent'}"
															onclick={() => { chartTypes[cKey] = 'CARD' as ChartType; showChart[cKey] = true; }}><BarChart3 size={9} />Card</button>
													</div>
													<div class="flex items-center gap-0.5">
														{#if chartOpt || activeChart === 'CARD' || activeChart === 'TABLE'}
															<button
																class="px-1 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent disabled:opacity-50"
																disabled={pngExporting === cKey}
																onclick={async () => {
																	const chartEl = document.querySelector(`[data-chart-key="${cKey}"]`);
																	if (!chartEl) return;
																	pngExporting = cKey;
																	try {
																		await downloadChartAsPNG(chartEl as HTMLElement, `chart-${cKey}`);
																	} finally {
																		pngExporting = null;
																	}
																}}
																title="Export as PNG"
															>{#if pngExporting === cKey}<Loader2 size={8} class="inline mr-0.5 animate-spin" />{:else}<ImageDown size={8} class="inline mr-0.5" />{/if}PNG</button>
															<button
																class="px-0.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																onclick={() => {
																	if (activeChart === 'TABLE') {
																		maximizedContent = { type: 'table', data: res, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) };
																	} else if (activeChart === 'CARD') {
																		maximizedContent = { type: 'card', chartConfig: effConfig, resultData: res, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) };
																	} else {
																		maximizedContent = { type: 'chart', chartOption: chartOpt, chartType: activeChart, chartConfig: effConfig, resultData: res, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) };
																		maximizedChartType = activeChart as ChartType;
																	}
																}}
																title="Maximize"
															><Expand size={8} /></button>
														{/if}
														<button
															class="px-0.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
															onclick={() => {
																const existingConfig = chartConfigs[cKey];
																const inferred = buildInitialChartConfig(res, activeChart as ChartType, query);
																const finalConfig = existingConfig || inferred;
																chartSettingsData = { msgId: msg.id, queryIndex: qi, resultData: res, chartType: activeChart, chartConfig: finalConfig };
																chartSettingsOpen = true;
															}}
															title="Chart settings"
														><Settings size={8} /></button>
														<button
															class="px-0.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
															onclick={() => handlePinToDashboard(msg, qi)}
															title="Pin to dashboard"
														><Pin size={8} /></button>
													</div>
												</div>
												{#if activeChart === 'TABLE'}
													<!-- Table View in chart area -->
													{@const tblRowNumIndices = res.headerList.reduce((acc: number[], h: any, i: number) => { if (String(h.name || h) === 'Row Number') acc.push(i); return acc; }, [] as number[])}
													{@const tblHeaders = res.headerList.filter((_: any, i: number) => !tblRowNumIndices.includes(i))}
													{@const tblData = tblRowNumIndices.length > 0 ? res.dataList.map((row: any[]) => row.filter((_: any, i: number) => !tblRowNumIndices.includes(i))) : res.dataList}
													{@const tblSortKey = `chart-table-${msg.id}-q${qi}`}
													{@const tblSorted = sortDataList(tblData, tblSortKey)}
													{@const tblSort = columnSort[tblSortKey]}
													<div class="max-h-[200px] overflow-auto" data-chart-key={cKey}>
														<table class="w-full text-[10px]">
															<thead class="sticky top-0 z-[2]">
																<tr class="bg-muted/50 dark:bg-muted/50">
																	<th class="px-2 py-1.5 text-left text-[10px] font-medium text-muted-foreground/70 border-b border-border/30 whitespace-nowrap w-6">#</th>
																	{#each tblHeaders as h, ci}
																		{@const colName = String(h.name || h)}
																		<th class="px-2 py-1.5 text-left text-[10px] font-medium text-muted-foreground/70 border-b border-border/30 whitespace-nowrap">
																			<button class="hover:text-foreground cursor-pointer select-none inline-flex items-center gap-0.5" onclick={() => toggleSort(tblSortKey, ci)} title="Sort column">
																				{colName}
																				{#if tblSort?.col === ci}
																					{#if tblSort.dir === 'asc'}<ChevronUp size={8} class="text-primary" />{:else}<ChevronDown size={8} class="text-primary" />{/if}
																				{:else}
																					<ChevronsUpDown size={8} class="opacity-30" />
																				{/if}
																			</button>
																		</th>
																	{/each}
																</tr>
															</thead>
															<tbody>
																{#each tblSorted.slice(0, 100) as row, ri}
																	<tr class="border-b border-border/20 hover:bg-muted/10 transition-colors">
																		<td class="px-2 py-1 text-[10px] text-muted-foreground/50">{ri + 1}</td>
																		{#each row as cell}
																			<td class="px-2 py-1 text-foreground whitespace-nowrap max-w-[200px] truncate" title={String(cell ?? 'NULL')}>
																				{#if cell === null || cell === undefined}
																					<span class="text-muted-foreground/40 italic">NULL</span>
																				{:else}
																					{cell}
																				{/if}
																			</td>
																		{/each}
																	</tr>
																{/each}
															</tbody>
														</table>
													</div>
												{:else if activeChart === 'CARD'}
													{@const cardMetrics = buildCardMetrics(res, effConfig.metrics)}
													{@const displayMetrics = cardMetrics}
													<div class="grid grid-cols-2 gap-1.5 p-1.5" data-chart-key={cKey}>
														{#each displayMetrics as metric}
															<div class="border border-border bg-background rounded-md p-2 flex flex-col">
																<div class="text-[10px] font-semibold text-muted-foreground uppercase truncate">{metric.name}</div>
																<div class="mt-0.5 text-xs font-bold text-foreground truncate">
																	{metric.isNumeric ? formatMetricValue(metric.raw) : String(metric.raw ?? '-')}
																</div>
															</div>
														{/each}
													</div>
												{:else if (showChart[cKey] ?? (autoType !== 'TABLE' && autoType !== 'CARD')) && chartOpt}
													{#await import('$lib/components/ECharts/ECharts.svelte') then { default: ECharts }}
														<div class="p-1.5" data-chart-key={cKey}>
															<ECharts option={chartOpt} height="180px" theme={isDarkMode ? 'dark' : undefined} />
														</div>
													{/await}
												{/if}
												<!-- Chart Stats Footer -->
												{#if chartStats && res.dataList.length > 0}
													<div class="flex items-center gap-2 px-1.5 py-0.5 border-t border-border/50 text-[10px] text-muted-foreground">
														<span>{res.dataList.length} rows</span>
														{#if chartStats.sum !== null}
															<span>Sum: {formatStatNumber(chartStats.sum)}</span>
														{/if}
														{#if chartStats.avg !== null}
															<span>Avg: {formatStatNumber(chartStats.avg)}</span>
														{/if}
														{#if chartStats.min !== null}
															<span>Min: {formatStatNumber(chartStats.min)}</span>
														{/if}
														{#if chartStats.max !== null}
															<span>Max: {formatStatNumber(chartStats.max)}</span>
														{/if}
													</div>
												{/if}
											</div>
										{/if}

										<!-- Interpretation -->
										{#if query.isInterpreting}
											<div class="mt-3.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
												<Loader2 size={10} class="animate-spin" />
												<span>Analyzing results...</span>
											</div>
										{:else if query.interpretation}
											<div class="mt-3.5 text-[12px] text-foreground leading-relaxed">
												<MarkdownRenderer content={query.interpretation} dataSourceId={selectedDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} compact />
											</div>
										{/if}
									{/if}
								{/each}
								{/if}
							<!-- Disambiguation Buttons -->
							{#if msg.needsDisambiguation && msg.disambiguationOptions}
								<div class="mt-2 space-y-1.5">
									<p class="text-[11px] text-muted-foreground font-medium">Which one do you mean?</p>
									<div class="flex flex-col gap-1">
										{#each msg.disambiguationOptions as opt}
											<button
												class="flex flex-col items-start px-3 py-2 rounded-lg border border-border bg-card hover:border-primary/50 hover:bg-primary/5 transition-all text-left"
												onclick={() => handleDisambiguation(msg.id, opt)}
											>
												<span class="text-[12px] font-medium text-foreground">{opt.label}</span>
												{#if opt.refinedQuery && opt.refinedQuery !== opt.label}
													<span class="text-[11px] text-muted-foreground mt-0.5 line-clamp-1">{opt.refinedQuery}</span>
												{/if}
											</button>
										{/each}
									</div>
								</div>
							{/if}
						<!-- Clarification -->
						{#if msg.needsClarification && msg.clarificationOptions}
							<div class="mt-2 space-y-1.5">
								<div class="flex flex-col gap-1">
									{#each msg.clarificationOptions as opt}
										{@const clarificationText = typeof opt === 'string' ? opt : opt.label}
										{@const clarificationQuery = typeof opt === 'string' ? opt : opt.query}
										<button
											class="flex flex-col items-start px-3 py-2 rounded-lg border border-border bg-card hover:border-primary/50 hover:bg-primary/5 transition-all text-left"
											onclick={() => handleClarification(msg.id, clarificationQuery)}
										>
											<span class="text-[12px] font-medium text-foreground">{clarificationText}</span>
										</button>
									{/each}
								</div>
							</div>
						{/if}
						<!-- Tool Approval -->
						{#if msg.toolApproval}
							<ToolApproval msgId={msg.id} approval={msg.toolApproval} />
						{/if}

						<!-- Python Output -->
						{#if msg.pythonOutput}
							<PythonOutput output={msg.pythonOutput} />
						{/if}

						<!-- Actions: Copy + Feedback -->
						{#if msg.content && !msg.isStreaming}
							<div class="flex items-center gap-0.5 mt-1">
								<button
									class="p-1 rounded hover:bg-accent text-muted-foreground"
									onclick={() => copyText(msg.content || '', msg.id)}
									title="Copy"
								>
									{#if copiedId === msg.id}
										<Check size={12} class="text-green-500 dark:text-green-400" />
									{:else}
										<Copy size={12} />
									{/if}
								</button>
								<button
									class="p-1 rounded hover:bg-accent transition-colors {feedbackSent[msg.id] === 'up' ? 'text-green-500' : 'text-muted-foreground'}"
									onclick={() => handleFeedback(msg.id, 'up')}
									title="Helpful"
								>
									<ThumbsUp size={12} />
								</button>
								<button
									class="p-1 rounded hover:bg-accent transition-colors {feedbackSent[msg.id] === 'down' ? 'text-red-500' : 'text-muted-foreground'}"
									onclick={() => handleFeedback(msg.id, 'down')}
									title="Not helpful"
								>
									<ThumbsDown size={12} />
								</button>
							</div>
						{/if}
							</div>
						</div>
					{/if}
				{/each}

			{#if chat.isStreaming && (!chat.messages.length || chat.messages[chat.messages.length - 1].role !== 'assistant')}
				<div class="flex gap-2">
					<div class="shrink-0 mt-1">
						<AISparkleIcon size={16} class="text-primary" />
					</div>
					<div class="flex items-center gap-1.5 text-xs text-muted-foreground">
						<Loader2 size={12} class="animate-spin" />
						{#if getThinkingTitle(chat.messages?.[chat.messages.length - 1])}
							<span>{getThinkingTitle(chat.messages?.[chat.messages.length - 1])}</span>
						{/if}
					</div>
				</div>
			{/if}
			{/if}
		</div>

	<!-- Input Area (ai-chat style: textarea + db selector below) -->
	<div class="border-t border-border px-3 pb-3 pt-2 shrink-0">
		<div class="rounded-xl border border-border bg-card flex flex-col gap-1 px-3 py-2.5">
			<!-- Contenteditable Input (supports inline code chips) -->
			<div class="relative">
				{#if !hasContent}
				<div class="absolute inset-0 text-xs text-muted-foreground pointer-events-none select-none leading-[1.6]" style="padding-top: calc((36px - 1em * 1.6) / 2);">
					Ask about your data...
				</div>
			{/if}
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div
				bind:this={inputEl}
				contenteditable={!chat.isStreaming}
				role="textbox"
				tabindex="0"
				class="text-xs text-foreground focus:outline-none min-h-[36px] max-h-[100px] overflow-y-auto whitespace-pre-wrap break-words leading-[1.6]" style="padding-top: calc((36px - 1em * 1.6) / 2);"
					onkeydown={handleKeydown}
					oninput={handleInputChange}
					onpaste={handlePaste}
					onclick={handleInputClick}
					oncompositionstart={() => isComposing = true}
					oncompositionend={() => isComposing = false}
				></div>
			</div>
			<!-- DB selector (left) + Send button (right) -->
			<div class="flex items-center gap-2">
				<!-- DB Selector -->
				<div class="relative">
					<button
						class="flex items-center gap-1.5 h-7 px-2 rounded-md text-[12px] hover:bg-accent transition-colors text-muted-foreground"
						onclick={() => showConnectionDropdown = !showConnectionDropdown}
					>
						{#if selectedConn}
							{#if selectedConnIcon}
								<img src={selectedConnIcon} alt="" class="w-4 h-4 object-contain" />
							{/if}
							<span class="truncate max-w-[100px] text-foreground">{selectedConn.alias}</span>
						{:else}
							<Database size={14} />
							<span>DB</span>
						{/if}
						<ChevronDown size={10} />
					</button>
					{#if showConnectionDropdown}
						<!-- svelte-ignore a11y_click_events_have_key_events -->
						<!-- svelte-ignore a11y_no_static_element_interactions -->
						<div class="fixed inset-0 z-40" onclick={() => showConnectionDropdown = false}></div>
						<div class="absolute left-0 bottom-full mb-1 z-50 min-w-[200px] bg-popover border border-border rounded-md shadow-lg py-1 max-h-[200px] overflow-auto">
							{#each connections as conn (conn.id)}
								{@const icon = getDbIcon(conn.type || '')}
								<button
									class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] hover:bg-accent transition-colors text-left rounded-sm
										{selectedDataSourceId === conn.id ? 'bg-accent' : ''}"
									onclick={() => { selectedDataSourceId = conn.id; showConnectionDropdown = false; }}
								>
									{#if icon}
										<img src={icon} alt="" class="w-4 h-4 object-contain shrink-0" />
									{:else}
										<Database size={14} class="text-muted-foreground shrink-0" />
									{/if}
									<span class="truncate">{conn.alias}</span>
									{#if selectedDataSourceId === conn.id}
										<Check size={12} class="ml-auto text-primary shrink-0" />
									{/if}
								</button>
							{/each}
						</div>
					{/if}
				</div>

				<div class="flex-1"></div>

				<!-- Send / Stop -->
				{#if chat.isStreaming}
					<Button size="sm" variant="destructive" class="h-7 px-3 text-[12px]" onclick={() => stopStreaming()}>
						Stop
					</Button>
				{:else}
					<button
						class="h-7 w-7 rounded-md flex items-center justify-center bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
						onclick={handleSend}
						disabled={!hasContent}
					>
						<SendHorizontal size={14} />
					</button>
				{/if}
			</div>
		</div>
	</div>
</div>


<!-- Maximized Content Modal -->
{#if maximizedContent}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
		onkeydown={(e) => { if (e.key === 'Escape') maximizedContent = null; }}
		onclick={(e) => { if (e.target === e.currentTarget) maximizedContent = null; }}
	>
		<div class="bg-background rounded-xl shadow-2xl border border-border w-[90vw] h-[85vh] flex flex-col overflow-hidden">
			<!-- Header -->
			<div class="flex items-center justify-between px-5 py-3 border-b border-border bg-muted/30">
				<h3 class="text-xs font-semibold text-foreground truncate">{maximizedContent.title}</h3>
				<button
					class="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
					onclick={() => maximizedContent = null}
					title="Close"
				>
					<X size={18} />
				</button>
			</div>

			<!-- Body -->
			<div class="flex-1 overflow-hidden p-4 flex flex-col">
				{#if maximizedContent.type === 'table' && maximizedContent.data}
					{@const tData = maximizedContent.data}
					{@const maxSortKey = 'maximize-table'}
					{@const maxSort = columnSort[maxSortKey]}
					{@const maxSorted = sortDataList(tData.dataList, maxSortKey)}
					<!-- Toolbar -->
					<div class="flex items-center justify-between mb-3 flex-shrink-0">
						<span class="text-xs text-muted-foreground">{tData.dataList.length} rows</span>
						<div class="flex items-center gap-2">
							<button
								class="px-2 py-1 rounded text-[12px] text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadTableAsCSV(tData.headerList, maxSorted)}
								title="Export CSV"
							><Download size={12} />CSV</button>
							<button
								class="px-2 py-1 rounded text-[12px] text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadTableAsJSON(tData.headerList, maxSorted)}
								title="Export JSON"
							><Download size={12} />JSON</button>
							<button
								class="px-2 py-1 rounded text-[12px] text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadInsertSQL('query_result', tData.headerList, maxSorted)}
								title="Export SQL INSERT"
							><Download size={12} />SQL</button>
						</div>
					</div>
					<!-- Table -->
					<div class="flex-1 overflow-auto rounded-lg border border-border">
						<table class="w-full text-xs border-collapse">
							<thead class="sticky top-0 z-10">
								<tr class="bg-muted dark:bg-muted">
									<th class="px-3 py-2 text-left text-[12px] font-semibold text-muted-foreground border-b-2 border-border whitespace-nowrap w-10 bg-muted dark:bg-muted">#</th>
									{#each tData.headerList as header, ci}
										{@const colName = String(header.name || header)}
										<th class="px-3 py-2 text-left text-[12px] font-semibold text-muted-foreground border-b-2 border-border whitespace-nowrap bg-muted dark:bg-muted">
											<button
												class="hover:text-foreground cursor-pointer select-none inline-flex items-center gap-1"
												onclick={() => toggleSort(maxSortKey, ci)}
												title="Sort column"
											>
												{colName}
												{#if maxSort?.col === ci}
													{#if maxSort.dir === 'asc'}
														<ChevronUp size={12} class="text-primary" />
													{:else}
														<ChevronDown size={12} class="text-primary" />
													{/if}
												{:else}
													<ChevronsUpDown size={12} class="opacity-30" />
												{/if}
											</button>
										</th>
									{/each}
								</tr>
							</thead>
							<tbody>
								{#each maxSorted as row, ri}
									<tr class="border-b border-border/30 hover:bg-muted/20 transition-colors">
										<td class="px-3 py-1.5 text-[12px] text-muted-foreground">{ri + 1}</td>
										{#each row as cell}
											<td class="px-3 py-1.5 text-foreground whitespace-nowrap max-w-[400px] truncate" title={String(cell ?? 'NULL')}>
												{#if cell === null || cell === undefined}
													<span class="text-muted-foreground/50 italic">NULL</span>
												{:else}
													{cell}
												{/if}
											</td>
										{/each}
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{:else if maximizedContent.type === 'chart'}
					<!-- Chart type switcher -->
					{#if maximizedContent.resultData}
						<div class="flex items-center gap-1 mb-3">
							{#each ['BAR', 'LINE', 'PIE', 'SCATTER'] as ct}
								<button
									class="px-2 py-1 rounded text-[12px] transition-colors {maximizedChartType === ct ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent border border-border'}"
									onclick={() => { maximizedChartType = ct as ChartType; }}
								>{ct}</button>
							{/each}
						</div>
					{/if}
					{@const maxEffectiveConfig = maximizedContent.chartConfig || (maximizedContent.resultData ? inferChartConfig(maximizedContent.resultData, maximizedChartType) : {})}
					{@const maxChartOpt = maximizedContent.resultData ? generateChartOptionWithConfig(maximizedContent.resultData, maximizedChartType, maxEffectiveConfig) : maximizedContent.chartOption}
					{#if maxChartOpt}
						{#await import('$lib/components/ECharts/ECharts.svelte') then { default: ECharts }}
							<ECharts option={maxChartOpt} height="calc(100% - 40px)" theme={isDarkMode ? 'dark' : undefined} />
						{/await}
					{/if}
				{:else if maximizedContent.type === 'card' && maximizedContent.resultData}
					{@const maxCardConfig = maximizedContent.chartConfig || inferChartConfig(maximizedContent.resultData, 'CARD')}
					{@const maxCardMetrics = buildCardMetrics(maximizedContent.resultData, maxCardConfig.metrics)}
					<div class="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4 p-4">
						{#each maxCardMetrics as metric}
							<div class="border border-border bg-background rounded-lg p-5 min-h-[100px] flex flex-col justify-between">
								<div class="text-xs font-semibold text-muted-foreground uppercase tracking-wide truncate">{metric.name}</div>
								<div class="mt-2 text-3xl font-bold text-foreground truncate">
									{metric.isNumeric ? formatMetricValue(metric.raw) : String(metric.raw ?? '-')}
								</div>
							</div>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	</div>
{/if}

<!-- Pin to Dashboard Modal -->
{#if pinToDashboardOpen}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
		onkeydown={(e) => { if (e.key === 'Escape') pinToDashboardOpen = false; }}
		onclick={(e) => { if (e.target === e.currentTarget) pinToDashboardOpen = false; }}
	>
		<div class="bg-background rounded-xl shadow-2xl border border-border w-[440px] flex flex-col overflow-hidden">
			<div class="flex items-center justify-between px-5 py-3 border-b border-border">
				<h3 class="text-xs font-semibold text-foreground">Pin to Dashboard</h3>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => pinToDashboardOpen = false}>
					<X size={16} />
				</button>
			</div>
			<div class="p-5">
				{#if pinDashboardList.length === 0}
					<p class="text-xs text-muted-foreground text-center py-4">No dashboards found. Create one first.</p>
				{:else}
					<DropdownMenu>
						<DropdownMenuTrigger class="w-full px-3 py-2 rounded-lg border border-input bg-background text-foreground text-xs text-left inline-flex items-center justify-between">
							{pinDashboardList.find(d => d.id === pinSelectedDashboardId)?.name || 'Select a dashboard'}
							<ChevronDown size={14} class="text-muted-foreground" />
						</DropdownMenuTrigger>
						<DropdownMenuContent align="start" class="w-[var(--bits-dropdown-menu-trigger-width)]">
							{#each pinDashboardList as db}
								<DropdownMenuItem
									class={pinSelectedDashboardId === db.id ? 'bg-accent font-medium' : ''}
									onSelect={() => { pinSelectedDashboardId = db.id; }}
								>
									{db.name}
								</DropdownMenuItem>
							{/each}
						</DropdownMenuContent>
					</DropdownMenu>
				{/if}
			</div>
			<div class="flex justify-end gap-2 px-5 py-3 border-t border-border">
				<button
					class="px-4 py-2 rounded-lg text-xs text-muted-foreground hover:bg-accent"
					onclick={() => pinToDashboardOpen = false}
				>Cancel</button>
				<button
					class="px-4 py-2 rounded-lg text-xs bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
					disabled={!pinSelectedDashboardId || pinSaving}
					onclick={handleSavePinToDashboard}
				>
					{pinSaving ? 'Saving...' : 'OK'}
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- Chart Settings Modal -->
{#if chartSettingsOpen && chartSettingsData}
	{#await import('$lib/components/ChartModal/ChartModal.svelte') then { default: ChartModal }}
		<ChartModal
			onclose={() => { chartSettingsOpen = false; chartSettingsData = null; }}
			onsave={(data) => {
				if (chartSettingsData) {
					const key = `${chartSettingsData.msgId}-q${chartSettingsData.queryIndex}`;
					try {
						const schema = JSON.parse(data.schema);
						if (schema.chartType) {
							chartTypes[key] = schema.chartType as ChartType;
							showChart[key] = true;
						}
						if (schema.chartConfig) {
							chartConfigs[key] = schema.chartConfig as ChartConfig;
						}
						const msg = chat.messages.find(m => m.id === chartSettingsData!.msgId);
						if (msg?.queries?.[chartSettingsData!.queryIndex]) {
							const query = msg.queries[chartSettingsData!.queryIndex];
							if (query.result) {
								(query as any).chartConfig = schema.chartConfig;
								(query as any).chartType = schema.chartType;
							}
						}
					} catch { /* ignore */ }
				}
				chartSettingsOpen = false;
				chartSettingsData = null;
			}}
			initialSql={(() => {
				const msg = chat.messages.find(m => m.id === chartSettingsData?.msgId);
				return msg?.queries?.[chartSettingsData?.queryIndex ?? 0]?.sql || '';
			})()}
			initialResultData={chartSettingsData.resultData}
			initialChartType={chartSettingsData.chartType as ChartType}
			initialChartConfig={chartSettingsData.chartConfig}
			initialDataSourceId={selectedDataSourceId}
			initialName={(() => {
				const msg = chat.messages.find(m => m.id === chartSettingsData?.msgId);
				const query = msg?.queries?.[chartSettingsData?.queryIndex ?? 0];
				return stripMarkdown(query?.title || generateTitleFromSql(query?.sql || '', chartSettingsData?.queryIndex ?? 0));
			})()}
			sourceType="ai-chat"
		/>
	{/await}
{/if}

<style>
	:global(.code-chip) {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 2px 4px 2px 8px;
		margin: 1px 2px;
		border-radius: 8px;
		font-size: 12px;
		line-height: 1.5;
		vertical-align: middle;
		user-select: none;
		white-space: nowrap;
		cursor: default;
		background: hsl(var(--accent));
		border: 1px solid hsl(var(--border) / 0.5);
	}
	:global(.code-chip .chip-label) {
		font-weight: 500;
		color: hsl(var(--foreground));
	}
	:global(.code-chip .chip-meta) {
		color: hsl(var(--muted-foreground));
	}
	:global(.code-chip .chip-remove) {
		display: flex;
		align-items: center;
		padding: 2px;
		border-radius: 4px;
		cursor: pointer;
		color: hsl(var(--muted-foreground));
		border: none;
		background: none;
		transition: all 0.15s;
	}
	:global(.code-chip .chip-remove:hover) {
		background: hsl(var(--muted-foreground) / 0.2);
		color: hsl(var(--foreground));
	}
</style>
