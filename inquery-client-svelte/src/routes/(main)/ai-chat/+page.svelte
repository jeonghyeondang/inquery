<script lang="ts">
	import { onMount, tick, onDestroy } from 'svelte';
	import { page } from '$app/state';
	import {
		Button, Spinner, Popover, PopoverTrigger, PopoverContent,
		DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator
	} from '$lib/components/ui';
	import {
		X, Plus, Microscope, Check, ChevronDown, ChevronUp, ChevronsUpDown, ChevronRight,
		PanelLeftClose, PanelLeftOpen, Database, SendHorizontal,
		Pencil, Trash2, Copy, Play, Pin, ThumbsUp, ThumbsDown, Expand,
		Clock, Calendar, Loader2, AlertCircle, FileCode, Code, Download,
		Search, ImageDown, CircleDot, Settings, Paperclip,
		BarChart3, TrendingUp, PieChart as PieChartIcon, Table2, Maximize2,
		Ellipsis, Globe, FileText
	} from 'lucide-svelte';
	import { AISparkleIcon } from '$lib/components/AISparkleIcon';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import {
		getAIChatStore, fetchChatRooms, setCurrentRoom, setOnRoomSwitch, sendMessage, stopStreaming,
		executeQuery, updateQuerySql, handleFeedback as storeFeedback, renameChatRoom, removeChatRoom,
		sendClarification, sendDisambiguationChoice, interpretResults, addMessage, updateMessageById,
		updateMessageByIdInRoom, getMessagesForRoom, generateTitleFromSql, ensureChatRoom,
		type IChatRoom, type IMessage, type IQuery, type IQueryResult, type IResearchReport, type IResearchPlan, type IDisambiguationOption,
		type ISuggestedFollowUp
	} from '$lib/stores/aiChat.svelte';
	import { getWorkspaceStore, setPendingSql } from '$lib/stores/workspace.svelte';
	import { getDeepResearchStore, startResearch, setResearchViewOpen, restoreResearchReport, setCurrentResearchRoom } from '$lib/stores/deepResearch.svelte';
	import { classifyDeepResearch } from '$lib/service/deepResearch';
	import { saveMessage as saveMessageApi, updateMessage as updateMessageApi } from '$lib/service/aiChat';
	import { getUserStore } from '$lib/stores/user.svelte';
	import { ResearchProgressPanel, ResearchReportView } from '$lib/components/DeepResearch';
	import ToolApproval from '$lib/components/ToolApproval/ToolApproval.svelte';
	import PythonOutput from '$lib/components/ToolApproval/PythonOutput.svelte';
	import { downloadTableAsCSV, downloadTableAsJSON, downloadInsertSQL, downloadChartAsPNG } from '$lib/utils/export';
	import { generateChartOption, generateChartOptionWithConfig, guessChartType, buildCardMetrics, formatMetricValue, formatValue, formatCellDisplay, inferChartConfig, buildInitialChartConfig, type ChartType, type ChartConfig, type MetricFormat } from '$lib/utils/chartUtils';
	import connectionService from '$lib/service/connection';
	import dashboardService from '$lib/service/dashboard';
	import catalogService from '$lib/service/catalog';
	import type { IConnectionListItem } from '$lib/types/connection';
	import { databaseMap } from '$lib/types/database';
	import { LangType } from '$lib/types/constants';
	import { syncIntelliSenseForConnection } from '$lib/utils/intellisense/unified-provider';
	import message from '$lib/utils/message';
	import i18n, { currentLang } from '$lib/i18n';
	import { formatSql } from '$lib/utils/sqlFormat';
	import AttachmentComposer from '$lib/components/ChatAttachment/AttachmentComposer.svelte';
	import AttachmentLibraryPanel from '$lib/components/ChatAttachment/AttachmentLibraryPanel.svelte';
	import { getModelCapabilities, type IAttachment, type ModelCapabilitiesMap } from '$lib/service/attachment';
	import { setReattachHandler } from '$lib/stores/aiChatAttachments.svelte';

	const chat = getAIChatStore();
	const ws = getWorkspaceStore();
	const research = getDeepResearchStore();

	let inputValue = $state('');
	let messagesContainer = $state<HTMLDivElement | null>(null);
	let isComposing = $state(false);
	let textareaEl = $state<HTMLTextAreaElement | null>(null);

	// AI-chat attachments: pending uploads + finalised list bound from
	// the AttachmentComposer below. Model capabilities are cached on
	// mount so the composer can warn before send (auto-switch happens
	// server-side regardless).
	let pendingAttachments = $state<IAttachment[]>([]);
	let modelCapabilities = $state<ModelCapabilitiesMap | undefined>(undefined);
	let attachmentComposerRef = $state<AttachmentComposer | null>(null);
	let isLibraryOpen = $state(false);

	// Plan editing state
	let editingPlanMsgId = $state<string | null>(null);
	let editingSteps = $state<IResearchPlan['steps']>([]);

	// Embedded mode (when used in workspace right panel via iframe)
	let isEmbedded = $derived(page.url?.searchParams?.get('embedded') === 'true');

	// Sidebar
	let isSidebarCollapsed = $state(false);
	let sidebarWidth = $state(280);
	let isResizingSidebar = $state(false);

	// Research panel resize
	let researchPanelWidth = $state(640);
	let isResizingResearchPanel = $state(false);

	// Room editing
	let editingRoomId = $state<number | null>(null);
	let editingTitle = $state('');

	// Room dropdown menu
	let dropdownOpenRoomId = $state<number | null>(null);

	// Date grouping for chat rooms
	function getDateGroup(dateStr?: string): string {
		if (!dateStr) return 'Older';
		const date = new Date(dateStr);
		const now = new Date();
		const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
		const yesterday = new Date(today);
		yesterday.setDate(yesterday.getDate() - 1);
		const sevenDaysAgo = new Date(today);
		sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 7);
		const thirtyDaysAgo = new Date(today);
		thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

		if (date >= today) return i18n('aichat.dateGroup.today');
		if (date >= yesterday) return i18n('aichat.dateGroup.yesterday');
		if (date >= sevenDaysAgo) return i18n('aichat.dateGroup.last7');
		if (date >= thirtyDaysAgo) return i18n('aichat.dateGroup.last30');
		const localeTag =
			currentLang === LangType.KO_KR ? 'ko-KR'
				: currentLang === LangType.JA_JP ? 'ja-JP'
					: currentLang === LangType.TR_TR ? 'tr-TR'
						: 'en-US';
		return date.toLocaleDateString(localeTag, { month: 'long', year: 'numeric' });
	}

	let groupedChatRooms = $derived.by(() => {
		const groups: { label: string; rooms: IChatRoom[] }[] = [];
		const groupMap = new Map<string, IChatRoom[]>();
		const groupOrder: string[] = [];

		for (const room of chat.chatRooms) {
			const group = getDateGroup(room.updatedAt || room.createdAt);
			if (!groupMap.has(group)) {
				groupMap.set(group, []);
				groupOrder.push(group);
			}
			groupMap.get(group)!.push(room);
		}

		for (const label of groupOrder) {
			groups.push({ label, rooms: groupMap.get(label)! });
		}
		return groups;
	});

	// Database selector
	let connections = $state<IConnectionListItem[]>([]);
	let selectedDatabase = $state<string | undefined>(undefined);
	let showDbDropdown = $state(false); // managed by Popover

	// DB selector search
	let dbSearchQuery = $state('');

	// Model selector
	let selectedModel = $state('inquery-agent'); // Always inquery-agent; backend decides actual model
	let executionMode = $state<'auto' | 'deep' | 'manual'>('auto');
	let showModelDropdown = $state(false); // managed by Popover

	// Research panel (no longer a fixed pixel width; uses flex-grow ratio instead)

	// Copy states
	let copiedSqlId = $state<string | null>(null);
	let copiedMsgId = $state<string | null>(null);

	// Executing timers
	let executingTimers = $state<Record<string, number>>({});
	let timerIntervals: Record<string, ReturnType<typeof setInterval>> = {};

	// Date range custom input
	let dateRangeCustom = $state<Record<string, string>>({});
	let showDateRangeInput = $state<Record<string, boolean>>({});

	// SQL editor modal
	let editingSql = $state<{ msgId: string; queryIndex: number; sql: string } | null>(null);

	// Chart state per query
	let chartTypes = $state<Record<string, ChartType>>({});
	let showChart = $state<Record<string, boolean>>({});
	let chartConfigs = $state<Record<string, ChartConfig>>({});

	// Pin to Dashboard modal
	let pinToDashboardOpen = $state(false);
	let pinToDashboardData = $state<{ name: string; schema: string; dataSourceId?: number; databaseName?: string; type?: string } | null>(null);
	let pinDashboardList = $state<{ id: number; name: string }[]>([]);
	let pinSelectedDashboardId = $state<number | undefined>(undefined);
	let pinSaving = $state(false);
	let pngExporting = $state<string | null>(null);

	// Maximize content modal (table or chart)
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

	// Column formats per message: { [msgId]: { [colName]: MetricFormat } }
	let columnFormats = $state<Record<string, Record<string, MetricFormat>>>({});
	let formatDropdownOpen = $state<string | null>(null); // "msgId-colName"

	// Column sorting: { [contextKey]: { col: number; dir: 'asc' | 'desc' } }
	let columnSort = $state<Record<string, { col: number; dir: 'asc' | 'desc' }>>({});

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
				// Remove sort (third click)
				const { [sortKey]: _, ...rest } = columnSort;
				columnSort = rest;
			}
		} else {
			columnSort = { ...columnSort, [sortKey]: { col: colIdx, dir: 'asc' } };
		}
	}

	// Chart settings modal
	let chartSettingsOpen = $state(false);
	let chartSettingsData = $state<{ msgId: string; queryIndex: number; resultData: any; chartType: string; chartConfig?: ChartConfig } | null>(null);

	// Infographic view state
	let isInfographicViewOpen = $state(false);
	let infographicViewHtml = $state('');
	let isInfographicGenerating = $state(false);

	const modeOptions = [
		{ value: 'auto', label: i18n('aichat.mode.auto.label'), desc: i18n('aichat.mode.auto.desc') },
		{ value: 'deep', label: i18n('aichat.mode.deep.label'), desc: i18n('aichat.mode.deep.desc') }
	];

	const suggestions = [
		'Show me the top 10 tables by row count',
		'What are the most recent orders?',
		'Find duplicate records in the users table',
		'Show monthly revenue trend'
	];

	const dateRangeOptions = [
		{ label: i18n('aichat.dateRange.today'), suffix: ` ${i18n('aichat.dateRange.suffix.today')}` },
		{ label: i18n('aichat.dateRange.yesterday'), suffix: ` ${i18n('aichat.dateRange.suffix.yesterday')}` },
		{ label: i18n('aichat.dateRange.last7'), suffix: ` ${i18n('aichat.dateRange.suffix.last7')}` },
		{ label: i18n('aichat.dateRange.last30'), suffix: ` ${i18n('aichat.dateRange.suffix.last30')}` },
		{ label: i18n('aichat.dateRange.all'), suffix: ` ${i18n('aichat.dateRange.suffix.all')}` }
	];

	onMount(async () => {
		// Register room switch callback for research restore
		setOnRoomSwitch((msgs) => {
			const msgWithReport = msgs.find(m => m.researchReport);
			if (msgWithReport?.researchReport) {
				restoreResearchReport(msgWithReport.researchReport, msgWithReport.researchSessionId);
			}
		});

		// Cache the per-model capability matrix so the attachment
		// composer can warn the user before send (server still
		// auto-switches if anything slips through).
		try {
			modelCapabilities = await getModelCapabilities();
		} catch (err) {
			console.warn('Failed to load attachment capability matrix', err);
		}

		// Card-list re-attach menu items push back into the composer
		// via a tiny shared registry. De-duplicate by id so the user
		// can't end up with the same attachment listed twice.
		setReattachHandler((att) => {
			if (pendingAttachments.some((a) => a.id === att.id)) return;
			pendingAttachments = [...pendingAttachments, att];
		});

		await fetchChatRooms();

		// Restore last selected room, or auto-select most recent
		if (typeof localStorage !== 'undefined') {
			const lastRoomId = localStorage.getItem('ai-chat-last-room-id');
			if (lastRoomId && chat.chatRooms.some(r => r.id === Number(lastRoomId))) {
				setCurrentResearchRoom(Number(lastRoomId));
				await setCurrentRoom(Number(lastRoomId));
			} else if (chat.chatRooms.length > 0) {
				setCurrentResearchRoom(chat.chatRooms[0].id);
				await setCurrentRoom(chat.chatRooms[0].id);
			}
		}

		try {
			const res = await connectionService.getList({ pageNo: 1, pageSize: 1000 });
			connections = (res as any)?.data || [];
			if (connections.length > 0 && !selectedDatabase) {
				const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('ai-chat-selected-database') : null;
				if (saved && connections.some(c => String(c.id) === saved)) {
					selectedDatabase = saved;
				} else {
					selectedDatabase = String(connections[0].id);
				}
			}
		} catch { /* ignore */ }

		if (typeof localStorage !== 'undefined') {
			const savedWidth = localStorage.getItem('ai-chat-sidebar-width');
			if (savedWidth) sidebarWidth = Number(savedWidth);
			const savedResearchWidth = localStorage.getItem('ai-chat-research-panel-width');
			if (savedResearchWidth) researchPanelWidth = Number(savedResearchWidth);
			const collapsed = localStorage.getItem('ai-chat-sidebar-collapsed');
			if (collapsed) isSidebarCollapsed = collapsed === 'true';
			// Restore execution mode
			const savedMode = localStorage.getItem('ai-chat-execution-mode');
			if (savedMode && ['auto', 'deep'].includes(savedMode)) {
				executionMode = savedMode as 'auto' | 'deep' | 'manual';
			} else if (savedMode === 'manual') {
				executionMode = 'auto';
			}
			// selectedModel is always 'inquery-agent'; no need to restore
		// (research panel now uses flex-grow ratio, no saved width needed)
	}

	});

	// Close format dropdown on outside click
	onMount(() => {
		const handleGlobalClick = () => { if (formatDropdownOpen) formatDropdownOpen = null; };
		document.addEventListener('click', handleGlobalClick);
		return () => document.removeEventListener('click', handleGlobalClick);
	});

	onDestroy(() => {
		Object.values(timerIntervals).forEach(clearInterval);
		setReattachHandler(null);
	});

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
		if (selectedDatabase && typeof localStorage !== 'undefined') {
			localStorage.setItem('ai-chat-selected-database', selectedDatabase);
		}
		if (selectedDatabase && connections.length > 0) {
			const conn = connections.find(c => String(c.id) === String(selectedDatabase));
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

	// Derive selected connection info for DDL tooltip in MarkdownRenderer
	let selectedConn = $derived(connections?.find(c => String(c.id) === String(selectedDatabase)));
	let connDataSourceId = $derived(selectedConn ? Number(selectedConn.id) : undefined);
	let connDatabaseName = $derived((selectedConn as any)?.databaseName as string | undefined);
	let connSchemaName = $derived((selectedConn as any)?.schemaName as string | undefined);

	// Save model/mode selection
	$effect(() => {
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('ai-chat-execution-mode', executionMode);
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
			tick().then(() => textareaEl?.focus());
		}
		prevStreaming = chat.isStreaming;
	});

	function getDataSourceOptions() {
		const selectedConn = connections?.find(c => String(c.id) === String(selectedDatabase));
		return {
			dataSourceId: selectedDatabase ? Number(selectedDatabase) : ws.currentConnection?.id,
			databaseName: (selectedConn as any)?.databaseName as string | undefined,
			schemaName: (selectedConn as any)?.schemaName as string | undefined,
			connectionList: connections as any[],
			selectedDatabase,
		};
	}

	async function handleSend() {
		const trimmed = inputValue.trim();
		if (!trimmed || chat.isStreaming) return;
		inputValue = '';

		const dsOpts = getDataSourceOptions();

		// If Deep Research mode, go through classification flow instead of SSE
		if (executionMode === 'deep') {
			inputValue = trimmed; // restore for handleDeepResearch
			await handleDeepResearch();
			return;
		}

		const attachmentsForSend = pendingAttachments.slice();
		pendingAttachments = [];
		await sendMessage(trimmed, {
			...dsOpts,
			executionMode,
			model: selectedModel,
			...(attachmentsForSend.length > 0 ? { attachments: attachmentsForSend } : {})
		});
		tick().then(() => textareaEl?.focus());
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey && !isComposing) {
			e.preventDefault();
			handleSend();
		}
	}

	function handleSuggestion(text: string) {
		inputValue = text;
		handleSend();
	}

	function handleNewChat() {
		setCurrentRoom(null);
		setCurrentResearchRoom(null);
		if (typeof localStorage !== 'undefined') {
			localStorage.removeItem('ai-chat-last-room-id');
		}
	}

	function handleSelectRoom(room: IChatRoom) {
		setCurrentResearchRoom(room.id);
		setCurrentRoom(room.id);
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('ai-chat-last-room-id', String(room.id));
		}
	}

	/** Build serialized content with __meta__ separator (matches aiChat.svelte.ts serializeMessageContent format) */
	function buildMetaContent(content: string, meta: Record<string, unknown>): string {
		if (Object.keys(meta).length > 0) {
			return content + '__meta__' + JSON.stringify(meta);
		}
		return content;
	}

	async function handleDeepResearch() {
		const trimmed = inputValue.trim();
		if (!trimmed) return;
		inputValue = '';

		// Ensure chat room exists before deep research (create one if needed)
		const roomId = await ensureChatRoom(trimmed);
		setCurrentResearchRoom(roomId);
		const user = getUserStore().curUser;
		const userId = user?.id ?? 1;
		const selConn = connections?.find(c => String(c.id) === String(selectedDatabase));

		// Add user message + persist
		const userMsg: IMessage = {
			id: `user-${Date.now()}`, role: 'user', content: trimmed, timestamp: Date.now(), chatRoomId: roomId
		};
		addMessage(userMsg);
		saveMessageApi({ chatRoomId: roomId, role: 'user', content: trimmed, userId })
			.then(dbId => { if (dbId) updateMessageByIdInRoom(roomId, userMsg.id, { dbId }); })
			.catch(err => console.error('Failed to save user message:', err));

		// Add thinking placeholder
		const assistantMsg: IMessage = {
			id: `assistant-${Date.now()}`, role: 'assistant', content: '', timestamp: Date.now(),
			chatRoomId: roomId,
			isThinking: true, thinkingSteps: [{ title: 'Classifying research request...', status: 'running' }],
		};
		addMessage(assistantMsg);

		try {
			// Step 1: Classify the deep research request
			const classification = await classifyDeepResearch({
				question: trimmed,
				conversationHistory: chat.messages.slice(-10).map(m => `${m.role}: ${m.content}`).join('\n'),
				dataSourceId: selConn?.id,
				databaseName: (selConn as any)?.databaseName,
				schemaName: (selConn as any)?.schemaName,
			});

			// If clarification needed
			if (classification.needsClarification && classification.clarificationQuestion) {
				updateMessageByIdInRoom(roomId, assistantMsg.id, {
					content: classification.clarificationQuestion,
					isThinking: false,
					thinkingSteps: undefined,
					needsClarification: true,
					clarificationOptions: (classification as any).clarificationOptions,
				});
				// Persist clarification message (format: content__meta__JSON)
				const serialized = buildMetaContent(classification.clarificationQuestion, {
					needsClarification: true,
					clarificationOptions: (classification as any).clarificationOptions,
				});
				saveMessageApi({ chatRoomId: roomId, role: 'assistant', content: serialized, userId })
					.then(dbId => { if (dbId) updateMessageByIdInRoom(roomId, assistantMsg.id, { dbId }); })
					.catch(err => console.error('Failed to save clarification message:', err));
				return;
			}

			// If research plan exists, show plan card
			if (classification.researchPlan) {
				const researchPlan = {
					title: classification.researchPlan.title,
					steps: (classification.researchPlan.steps || []).map(s => ({ label: s.title, description: s.details })),
					estimatedTime: classification.researchPlan.estimatedTime,
					buttonLabel: classification.buttonLabel || 'Start Research',
				};
				updateMessageByIdInRoom(roomId, assistantMsg.id, {
					content: trimmed,
					isThinking: false,
					thinkingSteps: undefined,
					isResearchPlan: true,
					researchPlan,
				});
				// Persist research plan message (format: content__meta__JSON)
				const serialized = buildMetaContent(trimmed, {
					isResearchPlan: true,
					researchPlan,
				});
				saveMessageApi({ chatRoomId: roomId, role: 'assistant', content: serialized, userId })
					.then(dbId => { if (dbId) updateMessageByIdInRoom(roomId, assistantMsg.id, { dbId }); })
					.catch(err => console.error('Failed to save research plan message:', err));
				return;
			}

			// Fallback: no plan generated
			updateMessageByIdInRoom(roomId, assistantMsg.id, {
				content: 'Failed to generate research plan. Please try again with more details.',
				isThinking: false,
				thinkingSteps: undefined,
			});
		} catch (err) {
			console.error('[Deep Research] Classification failed:', err);
			updateMessageByIdInRoom(roomId, assistantMsg.id, {
				content: 'Failed to start Deep Research. Please check your configuration.',
				isThinking: false,
				thinkingSteps: undefined,
			});
		}
	}

	// Room management
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

	async function handleDeleteRoom(roomId: number) {
		await removeChatRoom(roomId);
	}

	// Query execution
	async function handleRunQuery(msgId: string, queryIndex: number) {
		const userMessages = chat.messages.filter(m => m.role === 'user');
		const lastUserMsg = userMessages[userMessages.length - 1];

		const selector = `[data-query-result="${msgId}-${queryIndex}"]`;
		const observer = new MutationObserver(() => {
			const el = document.querySelector(selector);
			if (el) {
				observer.disconnect();
				el.scrollIntoView({ behavior: 'smooth', block: 'start' });
			}
		});
		if (messagesContainer) {
			observer.observe(messagesContainer, { childList: true, subtree: true });
		}

		await executeQuery(msgId, queryIndex, {
			...getDataSourceOptions(),
			model: selectedModel,
			originalUserQuery: lastUserMsg?.content
		});

		observer.disconnect();
	}

	// Pin to workspace - navigates to workspace with pending SQL
	let pinSuccessMessage = $state<string | null>(null);
	function handlePinToWorkspace(sql: string) {
		setPendingSql(sql);
		pinSuccessMessage = 'SQL pinned to workspace';
		setTimeout(() => pinSuccessMessage = null, 2000);
	}

	// Pin to Dashboard
	async function handlePinToDashboard(msg: IMessage, queryIndex?: number) {
		const query = queryIndex !== undefined ? msg.queries?.[queryIndex] : undefined;
		const resultData = query?.result || msg.resultData;
		const chartKey = queryIndex !== undefined ? `${msg.id}-q${queryIndex}` : msg.id;
		const activeChartType = chartTypes[chartKey] || query?.recommendedChart || guessChartType(resultData) || 'BAR';
		const chartType = activeChartType;
		const sql = query?.sql || msg.generatedSql || '';
		const chartName = query?.title || 'Untitled Chart';
		const chartConfig = chartConfigs[chartKey] || buildInitialChartConfig(resultData, activeChartType as ChartType, query);

		const selectedConn = connections?.find(c => String(c.id) === String(selectedDatabase));
		const schema = JSON.stringify({ chartType, sql, resultData, chartConfig });

		pinToDashboardData = {
			name: chartName,
			schema,
			dataSourceId: selectedConn?.id,
			databaseName: (selectedConn as any)?.databaseName || '',
			type: selectedConn?.type,
		};

		// Load dashboards
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
			pinSuccessMessage = 'Chart added to dashboard';
			setTimeout(() => pinSuccessMessage = null, 2000);
			pinToDashboardOpen = false;
		} catch (err) {
			console.error('Failed to pin chart:', err);
		} finally {
			pinSaving = false;
		}
	}

	// Sidebar resize
	function handleSidebarResize(e: MouseEvent) {
		isResizingSidebar = true;
		const startX = e.clientX;
		const startW = sidebarWidth;
		function onMouseMove(ev: MouseEvent) {
			sidebarWidth = Math.max(200, Math.min(600, startW + (ev.clientX - startX)));
		}
		function onMouseUp() {
			isResizingSidebar = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
			if (typeof localStorage !== 'undefined') {
				localStorage.setItem('ai-chat-sidebar-width', String(sidebarWidth));
			}
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	function toggleSidebar() {
		isSidebarCollapsed = !isSidebarCollapsed;
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('ai-chat-sidebar-collapsed', String(isSidebarCollapsed));
		}
	}

	// Research panel resize
	function handleResearchPanelResize(e: MouseEvent) {
		isResizingResearchPanel = true;
		const startX = e.clientX;
		const startW = researchPanelWidth;
		function onMouseMove(ev: MouseEvent) {
			researchPanelWidth = Math.max(360, Math.min(900, startW - (ev.clientX - startX)));
		}
		function onMouseUp() {
			isResizingResearchPanel = false;
			window.removeEventListener('mousemove', onMouseMove);
			window.removeEventListener('mouseup', onMouseUp);
			if (typeof localStorage !== 'undefined') {
				localStorage.setItem('ai-chat-research-panel-width', String(researchPanelWidth));
			}
		}
		window.addEventListener('mousemove', onMouseMove);
		window.addEventListener('mouseup', onMouseUp);
	}

	async function copySql(sql: string, queryId: string) {
		await navigator.clipboard.writeText(sql);
		copiedSqlId = queryId;
		setTimeout(() => copiedSqlId = null, 2000);
	}

	// Calculate chart statistics for a query result
	function calculateChartStats(res: IQueryResult): { sum: number | null; avg: number | null; min: number | null; max: number | null } | null {
		if (!res?.dataList?.length || !res?.headerList?.length) return null;
		// Find the first numeric column (skip row numbers and dates)
		let numColIdx = -1;
		for (let i = 0; i < res.headerList.length; i++) {
			const h = res.headerList[i];
			const name = typeof h === 'string' ? h : h.name;
			if (name === 'Row Number' || name === '#') continue;
			// Check if column has numeric values
			const sampleVal = res.dataList[0]?.[i];
			if (typeof sampleVal === 'number' || (typeof sampleVal === 'string' && !isNaN(Number(sampleVal)) && sampleVal.trim() !== '')) {
				numColIdx = i;
				break;
			}
		}
		if (numColIdx === -1) {
			// Try last column
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
		return {
			sum,
			avg: sum / nums.length,
			min: Math.min(...nums),
			max: Math.max(...nums)
		};
	}

	function formatStatNumber(n: number): string {
		if (Math.abs(n) >= 1e9) return (n / 1e9).toFixed(1) + 'B';
		if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(1) + 'M';
		if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'K';
		return Number.isInteger(n) ? n.toString() : n.toFixed(2);
	}

	function currentThinkingTitle(msg: IMessage, fallback = 'Preparing results...'): string {
		const steps = msg.thinkingSteps || [];
		return steps.length > 0 ? steps[steps.length - 1].title : fallback;
	}

	// Clarification handler
	async function handleClarification(msgId: string, clarifiedQuery: string) {
		if (executionMode === 'deep') {
			inputValue = clarifiedQuery;
			await handleDeepResearch();
			return;
		}
		await sendClarification('', clarifiedQuery, { ...getDataSourceOptions(), executionMode, model: selectedModel });
	}

	// Disambiguation handler
	async function handleDisambiguation(msgId: string, option: IDisambiguationOption) {
		updateMessageById(msgId, { needsDisambiguation: false });
		await sendDisambiguationChoice(option, {
			...getDataSourceOptions(),
			executionMode,
			model: selectedModel,
		});
	}

	// Date range handler
	async function handleDateRange(msgId: string, suffix: string) {
		const msg = chat.messages.find(m => m.id === msgId);
		if (!msg) return;
		// Find the original user question (previous user message)
		const msgIdx = chat.messages.indexOf(msg);
		let userQuestion = '';
		for (let i = msgIdx - 1; i >= 0; i--) {
			if (chat.messages[i].role === 'user') {
				userQuestion = chat.messages[i].content;
				break;
			}
		}
		const modeForFollowUp = msg.executionMode || executionMode;
		await sendMessage(`${userQuestion}${suffix}`, { ...getDataSourceOptions(), executionMode: modeForFollowUp, model: selectedModel });
	}

	async function handleSuggestedFollowUp(msg: IMessage, followUp: ISuggestedFollowUp) {
		if (!followUp.question || chat.isStreaming) return;
		const modeForFollowUp = msg.executionMode || executionMode;
		await sendMessage(followUp.question, { ...getDataSourceOptions(), executionMode: modeForFollowUp, model: selectedModel });
	}

	// Research completion card click handler
	function handleViewResearchReport(report: IResearchReport, sessionId?: number) {
		// Set the report in the deep research store and open the panel
		restoreResearchReport(report, sessionId);
	}

	// Infographic card click handler
	function handleViewInfographic(html: string) {
		infographicViewHtml = html;
		isInfographicViewOpen = true;
		isInfographicGenerating = false;
	}

	// Infographic generation complete — save as chat message
	function handleInfographicComplete(html: string) {
		const roomId = chat.currentRoomId;
		if (!roomId) return;

		const msg: IMessage = {
			id: crypto.randomUUID(),
			role: 'assistant',
			content: 'Infographic created successfully. Click below to view.',
			timestamp: Date.now(),
			infographicHtml: html,
			isInfographicCard: true,
		};
		addMessage(msg);

		const user = getUserStore().curUser;
		const serialized = buildMetaContent(msg.content, {
			isInfographicCard: true,
			infographicHtml: html,
		});
		saveMessageApi({
			chatRoomId: roomId,
			role: 'assistant',
			content: serialized,
			userId: user?.id ?? 1,
		}).catch((err) => console.error('Failed to save infographic message:', err));
	}

	// Research report completed — add completion message to chat + persist
	function handleResearchReportReady(report: any, sessionId: number, reportRoomId?: number) {
		const roomId = reportRoomId ?? chat.currentRoomId;
		if (!roomId) return;

		// Multi-language completion messages
		const completionMessages: Record<string, string> = {
			ko: 'Research complete. Feel free to ask follow-up questions or request changes.',
			en: 'Research complete. Feel free to ask follow-up questions or request changes.',
			ja: '調査が完了しました。追加の質問や変更のリクエストをお気軽にどうぞ。',
			zh: '研究完成。请随时提出后续问题或请求修改。',
			es: 'Investigación completa. No dude en hacer preguntas de seguimiento o solicitar cambios.',
			fr: 'Recherche terminée. N\'hésitez pas à poser des questions de suivi ou à demander des modifications.',
			de: 'Recherche abgeschlossen. Stellen Sie gerne Folgefragen oder fordern Sie Änderungen an.',
		};
		const lang = report.language || 'en';
		const completionContent = completionMessages[lang] || completionMessages['en'];

		// Update the plan message with report data
		const roomMessages = getMessagesForRoom(roomId);
		const planMsg = roomMessages.find(m => m.isResearchPlan && !m.researchReport);
		if (planMsg) {
			updateMessageByIdInRoom(roomId, planMsg.id, { researchReport: report, researchSessionId: sessionId });
			// Persist plan message update (now has dbId from handleDeepResearch save)
			if (planMsg.dbId) {
				const updatedPlanMsg = { ...planMsg, researchReport: report, researchSessionId: sessionId };
				const updatedSerialized = buildMetaContent(updatedPlanMsg.content, {
					isResearchPlan: true,
					researchPlan: updatedPlanMsg.researchPlan,
					researchSessionId: sessionId,
					researchReport: report,
				});
				updateMessageApi({ id: planMsg.dbId, content: updatedSerialized })
					.catch(err => console.error('Failed to update plan message:', err));
			}
		}

		// Add completion message with report card
		const msg: IMessage = {
			id: crypto.randomUUID(),
			role: 'assistant',
			content: completionContent,
			timestamp: Date.now(),
			chatRoomId: roomId,
			researchReport: report,
			researchSessionId: sessionId,
		};
		addMessage(msg);

		// Save completion message to DB
		const user = getUserStore().curUser;
		const serialized = buildMetaContent(completionContent, {
			researchReport: report,
			researchSessionId: sessionId,
		});
		saveMessageApi({
			chatRoomId: roomId,
			role: 'assistant',
			content: serialized,
			userId: user?.id ?? 1,
		}).then(dbId => { if (dbId) updateMessageByIdInRoom(roomId, msg.id, { dbId }); })
			.catch(err => console.error('Failed to save completion message:', err));
	}

	function formatDate(timestamp: number) {
		return new Date(timestamp).toLocaleDateString('en-US', {
			month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true
		});
	}

	function formatTime(timestamp: number) {
		return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	}

	/** Strip markdown syntax from title strings (##, **, *, `, etc.) */
	function stripMarkdown(text: string): string {
		return text
			.replace(/^#{1,6}\s+/gm, '')  // ## headings
			.replace(/\*\*(.+?)\*\*/g, '$1')  // **bold**
			.replace(/\*(.+?)\*/g, '$1')  // *italic*
			.replace(/`(.+?)`/g, '$1')  // `code`
			.replace(/~~(.+?)~~/g, '$1')  // ~~strikethrough~~
			.replace(/\[(.+?)\]\(.+?\)/g, '$1')  // [link](url)
			.trim();
	}

	// Detect dark mode for chart theme
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

	let selectedModeOpt = $derived(modeOptions.find(m => m.value === executionMode));

	let selectedDbInfo = $derived.by(() => {
		if (!selectedDatabase) return null;
		const conn = connections.find(c => String(c.id) === selectedDatabase);
		if (!conn) return null;
		return { conn, dbInfo: databaseMap[conn.type] };
	});
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="flex h-full w-full relative" class:select-none={isResizingSidebar || isResizingResearchPanel}>
	<!-- Chat Sidebar (hidden in embedded mode) — ChatGPT-style -->
	{#if !isSidebarCollapsed && !isEmbedded}
		<aside class="flex flex-col shrink-0 bg-sidebar border-r border-border/30" style="width: {sidebarWidth}px;">
			<!-- Sidebar Header -->
			<div class="px-3 py-3 flex items-center justify-between">
				<button onclick={toggleSidebar} class="text-sidebar-foreground/60 hover:text-sidebar-foreground rounded-lg hover:bg-sidebar-accent p-1.5 transition-colors" title={i18n('aichat.sidebar.collapse')}>
					<PanelLeftClose size={18} />
				</button>
				<button onclick={handleNewChat} class="text-sidebar-foreground/60 hover:text-sidebar-foreground rounded-lg hover:bg-sidebar-accent p-1.5 transition-colors" title={i18n('aichat.newchat.button')}>
					<Plus size={18} />
				</button>
			</div>

			<!-- Chat Room List -->
			<div class="flex-1 overflow-auto px-2 pb-3">
				{#each groupedChatRooms as group}
					<div class="mb-1">
						<div class="px-2 py-2">
							<span class="text-xs font-medium text-sidebar-foreground/50">{group.label}</span>
						</div>
						{#each group.rooms as room (room.id)}
							<div
								class="relative group flex items-center rounded-lg cursor-pointer transition-colors
									{chat.currentRoomId === room.id ? 'bg-sidebar-accent' : 'hover:bg-sidebar-accent/50'}"
								onclick={() => { if (editingRoomId !== room.id) handleSelectRoom(room); }}
							>
								{#if editingRoomId === room.id}
									<input
										class="text-sm w-full bg-background text-foreground border border-input rounded-md px-2 py-1.5 mx-1 my-0.5"
										bind:value={editingTitle}
										onblur={handleSaveEdit}
										onkeydown={(e) => { if (e.key === 'Enter') (e.target as HTMLElement).blur(); if (e.key === 'Escape') { editingRoomId = null; } }}
										onclick={(e) => e.stopPropagation()}
									/>
								{:else}
									<div class="flex-1 min-w-0 px-2 py-2.5 pr-8">
										<p class="text-[13px] truncate text-sidebar-foreground">{room.title}</p>
									</div>
									<!-- Action button on hover -->
									<div class="absolute right-1 top-0 bottom-0 flex items-center opacity-0 group-hover:opacity-100 transition-opacity {dropdownOpenRoomId === room.id ? '!opacity-100' : ''}">
										<DropdownMenu open={dropdownOpenRoomId === room.id} onOpenChange={(open) => { dropdownOpenRoomId = open ? room.id : null; }}>
											<DropdownMenuTrigger class="p-1 rounded-md hover:bg-sidebar-accent text-sidebar-foreground/60 hover:text-sidebar-foreground" onclick={(e: Event) => e.stopPropagation()}>
												<Ellipsis size={16} />
											</DropdownMenuTrigger>
											<DropdownMenuContent align="start" side="bottom" class="w-40">
												<DropdownMenuItem onSelect={() => { handleStartEdit(room); dropdownOpenRoomId = null; }}>
													<Pencil size={14} class="mr-2" />
													<span>{i18n('aichat.rename')}</span>
												</DropdownMenuItem>
												<DropdownMenuSeparator />
												<DropdownMenuItem destructive onSelect={() => { handleDeleteRoom(room.id); dropdownOpenRoomId = null; }}>
													<Trash2 size={14} class="mr-2" />
													<span>{i18n('aichat.delete')}</span>
												</DropdownMenuItem>
											</DropdownMenuContent>
										</DropdownMenu>
									</div>
								{/if}
							</div>
						{/each}
					</div>
				{/each}
			</div>
		</aside>

		<!-- Sidebar Resize Handle -->
		<div
			class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isResizingSidebar ? 'bg-primary/30' : ''}"
			onmousedown={handleSidebarResize}
		></div>
	{/if}

	<!-- Chat Area -->
	<div class="flex-1 flex flex-col min-w-0">
		<!-- Header - ChatGPT-style: only model/mode selector -->
		<div class="flex items-center justify-between h-14 px-6 shrink-0">
			<div class="flex items-center gap-2">
				{#if isSidebarCollapsed && !isEmbedded}
					<button class="text-muted-foreground hover:text-foreground p-1 rounded hover:bg-accent" onclick={toggleSidebar} title={i18n('aichat.sidebar.open')}>
						<PanelLeftOpen size={18} />
					</button>
				{/if}

				<!-- Mode Selector (Auto / Deep Research) -->
				<Popover bind:open={showModelDropdown}>
					<PopoverTrigger class="flex items-center gap-2 px-2 h-8 rounded-lg cursor-pointer hover:bg-accent transition-colors select-none">
						<span class="text-base font-medium text-foreground">
							{selectedModeOpt?.label || 'Auto'}
						</span>
						<ChevronDown size={14} class="text-muted-foreground" />
					</PopoverTrigger>
					<PopoverContent align="start" class="min-w-[260px] p-1">
						{#each modeOptions as mode}
							<button
								class="flex items-center w-full px-3 py-2 text-sm hover:bg-accent transition-colors text-left rounded-sm
									{executionMode === mode.value ? 'bg-accent' : ''}"
								onclick={() => { executionMode = mode.value as any; showModelDropdown = false; }}
							>
								<div class="flex-1">
									<div class="font-medium">{mode.label}</div>
									<div class="text-muted-foreground text-xs">{mode.desc}</div>
								</div>
								{#if executionMode === mode.value}<Check size={14} class="ml-auto text-primary shrink-0" />{/if}
							</button>
						{/each}
					</PopoverContent>
				</Popover>
			</div>

			<!-- Embedded mode actions (right side) -->
			{#if isEmbedded}
				<div class="flex items-center gap-1">
					<!-- History dropdown -->
					<Popover>
						<PopoverTrigger class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors" title={i18n('aichat.history.tooltip')}>
							<Clock size={16} />
						</PopoverTrigger>
						<PopoverContent align="end" class="w-64 max-h-80 overflow-auto p-1">
							<div class="px-2 py-1.5 text-xs text-muted-foreground font-medium">{i18n('aichat.history.recent')}</div>
							{#each chat.chatRooms.slice(0, 20) as room (room.id)}
								<button
									class="flex items-center w-full px-3 py-1.5 text-xs hover:bg-accent transition-colors text-left rounded-sm truncate
										{chat.currentRoomId === room.id ? 'bg-accent' : ''}"
									onclick={() => handleSelectRoom(room)}
								>
									<span class="truncate">{room.title}</span>
								</button>
							{/each}
						</PopoverContent>
					</Popover>
					<button
						class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
						onclick={handleNewChat}
						title={i18n('aichat.newchat.button')}
					>
						<Plus size={16} />
					</button>
					<button
						class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
						onclick={() => { if (typeof window !== 'undefined') window.parent?.postMessage({ type: 'close-ai-chat' }, '*'); }}
						title={i18n('aichat.close')}
					>
						<X size={16} />
					</button>
				</div>
			{/if}
		</div>

		<!-- Messages -->
		<div bind:this={messagesContainer} class="flex-1 overflow-auto hide-scrollbar">
			{#if chat.isLoadingMessages}
				<!-- Loading messages -->
				<div class="flex flex-col items-center justify-center h-full text-muted-foreground gap-3">
					<Loader2 size={24} class="animate-spin text-primary" />
					<p class="text-sm">{i18n('aichat.loading.messages')}</p>
				</div>
			{:else if chat.messages.length === 0}
				<!-- Empty state -->
				<div class="flex flex-col items-center justify-center h-full text-muted-foreground">
					<div class="mb-5 opacity-30"><AISparkleIcon size={80} /></div>
					<p class="text-base font-normal">{i18n('aichat.empty.start')}</p>
				</div>
			{:else}
				<!-- Messages list (ChatGPT-style: 65% width, centered, both sides left-aligned) -->
				<div class="w-[65%] mx-auto flex flex-col gap-5 px-2.5 py-10">
					{#each chat.messages as msg (msg.id)}
						{#if msg.role === 'user'}
							<!-- User Message -->
							<div class="flex flex-col max-w-[75%] gap-1.5 items-end">
								{#if msg.attachments && msg.attachments.length > 0}
									{#await import('$lib/components/ChatAttachment/AttachmentCardList.svelte') then { default: AttachmentCardList }}
										<AttachmentCardList attachments={msg.attachments} align="end" />
									{/await}
								{/if}
								{#if msg.content}
									<div class="w-fit rounded-xl bg-muted px-4 py-2.5">
										<p class="text-sm whitespace-pre-wrap text-foreground">{msg.content}</p>
									</div>
								{/if}
							</div>
						{:else}
							<!-- Assistant Message -->
							<div class="flex flex-col w-full group">
								{#if msg.modelSwitched}
									<div class="px-4 pt-1">
										<span
											class="inline-flex items-center gap-1 rounded-full bg-blue-50 dark:bg-blue-950/50 px-2 py-0.5 text-[10px] font-medium text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800/50"
											title={i18n('aichat.model.autoSwitched.title', msg.modelSwitched.from, msg.modelSwitched.to, msg.modelSwitched.reason)}
										>
											<AISparkleIcon size={10} />
											{i18n('aichat.model.autoSwitched', msg.modelSwitched.to)}
										</span>
									</div>
								{/if}
								{#if msg.attachments && msg.attachments.length > 0}
									<div class="px-4 pt-1">
										{#await import('$lib/components/ChatAttachment/AttachmentCardList.svelte') then { default: AttachmentCardList }}
											<AttachmentCardList attachments={msg.attachments} align="start" />
										{/await}
									</div>
								{/if}
								<div class="px-4 py-1">
									<!-- Research Plan Card -->
									{#if msg.isResearchPlan && msg.researchPlan}
										{@const plan = msg.researchPlan}
										{@const dr = getDeepResearchStore()}
										{@const isEditingPlan = editingPlanMsgId === msg.id}
										<div class="rounded-xl border border-border bg-card p-5 max-w-lg space-y-4">
											<h4 class="text-base font-semibold text-foreground">{plan.title}</h4>
											{#if isEditingPlan}
												<ol class="space-y-2">
													{#each editingSteps as step, idx}
														<li class="flex items-start gap-2 text-sm border border-border rounded-lg p-2.5 bg-background">
															<div class="flex flex-col gap-0.5 shrink-0 pt-0.5">
																<button class="p-0.5 rounded hover:bg-accent disabled:opacity-30" disabled={idx === 0} onclick={() => { const s = editingSteps.splice(idx, 1)[0]; editingSteps.splice(idx - 1, 0, s); editingSteps = [...editingSteps]; }}><ChevronUp size={12} /></button>
																<button class="p-0.5 rounded hover:bg-accent disabled:opacity-30" disabled={idx === editingSteps.length - 1} onclick={() => { const s = editingSteps.splice(idx, 1)[0]; editingSteps.splice(idx + 1, 0, s); editingSteps = [...editingSteps]; }}><ChevronDown size={12} /></button>
															</div>
															<div class="flex-1 min-w-0 space-y-1.5">
																<input type="text" class="w-full text-sm font-medium bg-transparent border-b border-border/50 focus:border-primary outline-none pb-0.5 text-foreground" bind:value={step.label} placeholder={i18n('aichat.research.stepTitle')} />
																<input type="text" class="w-full text-xs bg-transparent border-b border-border/50 focus:border-primary outline-none pb-0.5 text-muted-foreground" bind:value={step.description} placeholder={i18n('aichat.research.stepDesc')} />
															</div>
															<button class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive shrink-0" disabled={editingSteps.length <= 1} onclick={() => { editingSteps = editingSteps.filter((_, i) => i !== idx); }}><Trash2 size={14} /></button>
														</li>
													{/each}
												</ol>
												<button class="flex items-center gap-1.5 text-xs text-primary hover:text-primary/80 transition-colors" onclick={() => { editingSteps = [...editingSteps, { label: '', description: '', icon: 'search', source: 'database' }]; }}>
													<Plus size={14} />
													Add Step
												</button>
												<div class="flex gap-2">
													<button class="flex-1 py-2 rounded-lg text-sm font-medium border border-border bg-card text-foreground hover:bg-accent flex items-center justify-center gap-2 transition-colors" onclick={() => { editingPlanMsgId = null; }}>
														<X size={14} />
														Cancel
													</button>
													<button class="flex-1 py-2 rounded-lg text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 flex items-center justify-center gap-2" onclick={() => { const validSteps = editingSteps.filter(s => s.label.trim()); if (validSteps.length === 0) return; msg.researchPlan = { ...plan, steps: validSteps.map(s => ({ ...s })) }; editingPlanMsgId = null; }}>
														<Check size={14} />
														Save Plan
													</button>
												</div>
											{:else}
												<!-- View Mode -->
											{#if plan.steps?.length > 0}
												<ol class="space-y-2.5">
													{#each plan.steps as step}
														<li class="flex items-start gap-2.5 text-sm">
															<span class="flex-shrink-0 w-7 h-7 rounded-full bg-primary/10 text-primary flex items-center justify-center">
																{#if step.icon === 'search'}
																	<Search class="h-3.5 w-3.5" />
																{:else if step.icon === 'globe'}
																	<Globe class="h-3.5 w-3.5" />
																{:else if step.icon === 'chart'}
																	<BarChart3 class="h-3.5 w-3.5" />
																{:else if step.icon === 'document'}
																	<FileText class="h-3.5 w-3.5" />
																{:else if step.icon === 'clock'}
																	<Clock class="h-3.5 w-3.5" />
																{:else}
																	<Search class="h-3.5 w-3.5" />
																{/if}
															</span>
															<div class="flex-1 min-w-0">
																<div class="flex items-center gap-2">
																	<span class="text-foreground font-medium">{step.label}</span>
																</div>
																{#if step.description}
																	<p class="text-xs text-muted-foreground mt-0.5">{step.description}</p>
																{/if}
															</div>
														</li>
													{/each}
												</ol>
											{/if}
											{#if plan.estimatedTime}
												<div class="flex items-center gap-2.5 text-sm text-muted-foreground pt-1">
													<span class="flex-shrink-0 w-7 h-7 rounded-full bg-muted flex items-center justify-center">
														<Clock class="h-3.5 w-3.5" />
													</span>
													<span>{plan.estimatedTime}</span>
												</div>
											{/if}
											<div class="flex gap-2">
											<button
												class="flex-1 py-2 rounded-lg text-sm font-medium border border-border bg-card text-foreground hover:bg-accent disabled:opacity-50 flex items-center justify-center gap-2 transition-colors"
												disabled={dr.state.isRunning || !!msg.researchReport}
												onclick={() => {
													editingSteps = plan.steps.map(s => ({ ...s }));
														editingPlanMsgId = msg.id;
												}}
											>
												<Pencil size={14} />
												{plan.editPlanLabel || 'Edit Plan'}
											</button>
											<button
												class="flex-1 py-2 rounded-lg text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 flex items-center justify-center gap-2"
												disabled={dr.state.isRunning || !!msg.researchReport || !!msg.researchSessionId}
												onclick={() => {
													const roomId = chat.currentRoomId;
													if (!roomId) return;
													const userMsg = chat.messages.filter(m => m.role === 'user').pop();
													if (userMsg) {
														const selConn = connections?.find(c => String(c.id) === String(selectedDatabase));
														startResearch({
															question: userMsg.content,
															dataSourceId: selConn?.id,
															databaseName: (selConn as any)?.databaseName,
															schemaName: (selConn as any)?.schemaName,
															chatRoomId: roomId,
															researchPlan: msg.researchPlan,
															onReportReady: handleResearchReportReady,
														});
													}
												}}
											>
												{#if dr.state.isRunning}
													<Loader2 size={14} class="animate-spin" />
													Running...
												{:else}
													<Microscope size={14} />
													{plan.buttonLabel || 'Start Research'}
												{/if}
											</button>
											</div>
											{/if}
										</div>

									<!-- Research Completion Card -->
									{:else if !msg.isResearchPlan && msg.researchReport}
										<div class="space-y-2">
											{#if msg.content}
												<div class="text-sm text-foreground">{msg.content}</div>
											{/if}
											<button
												class="flex items-center gap-3 px-4 py-3 rounded-xl bg-card border border-border hover:border-primary hover:shadow-md transition-all max-w-sm cursor-pointer group"
												onclick={() => handleViewResearchReport(msg.researchReport!, msg.researchSessionId)}
											>
												<div class="w-10 h-10 rounded-xl bg-muted flex items-center justify-center text-muted-foreground group-hover:text-primary flex-shrink-0">
													<Search size={20} />
												</div>
												<div class="flex-1 min-w-0 text-left">
													<div class="text-sm font-medium text-foreground truncate">{msg.researchReport.title}</div>
													<div class="text-xs text-muted-foreground">{formatDate(msg.timestamp)}</div>
												</div>
											</button>
										</div>
									{:else if msg.isInfographicCard && msg.infographicHtml}
										<!-- Infographic Completion Card -->
										<div class="space-y-2">
											{#if msg.content}
												<div class="text-sm text-foreground">{msg.content}</div>
											{/if}
											<button
												class="flex items-center gap-3 px-4 py-3 rounded-xl bg-card border border-border hover:border-primary hover:shadow-md transition-all max-w-sm cursor-pointer group"
												onclick={() => handleViewInfographic(msg.infographicHtml!)}
											>
												<div class="w-10 h-10 rounded-xl bg-muted flex items-center justify-center text-muted-foreground group-hover:text-primary flex-shrink-0">
													<ImageDown size={20} />
												</div>
												<div class="flex-1 min-w-0 text-left">
													<div class="text-sm font-medium text-foreground truncate">{i18n('aichat.infographic')}</div>
													<div class="text-xs text-muted-foreground">{formatDate(msg.timestamp)}</div>
												</div>
											</button>
										</div>
									{:else}
									<!-- Thinking steps -->
									{#if msg.thinkingSteps && msg.thinkingSteps.length > 0 && (!msg.queries || msg.queries.length === 0)}
										{@const step = msg.thinkingSteps[msg.thinkingSteps.length - 1]}
										<div class="mb-3">
											<div class="thinking-step-row flex items-center gap-2 text-xs text-muted-foreground">
												{#if step.status === 'running' && msg.isThinking}
													<div class="thinking-spinner"></div>
												{:else}
													<Check class="h-3 w-3 text-green-500" />
												{/if}
												<span>{step.title}</span>
											</div>
										</div>
									{:else if msg.isThinking && (!msg.queries || msg.queries.length === 0)}
										<div class="flex items-center gap-2 text-sm text-muted-foreground mb-2">
											<div class="loading-dots">
												<span></span>
												<span></span>
												<span></span>
											</div>
										</div>
									{/if}

									<!-- Content: streaming vs completed -->
									{#if msg.isStreaming && msg.streamingContent && (!msg.queries || msg.queries.length === 0)}
										<!-- During streaming (no queries yet): show raw markdown -->
										<div class="text-sm text-foreground">
											<MarkdownRenderer content={msg.streamingContent} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
										</div>
									{:else if msg.isStreaming && msg.queries && msg.queries.length > 0}
										<!-- Overview is shown inside the queries container below -->
									{:else if !msg.isStreaming && !msg.queries?.length}
										<!-- Completed message with no queries: show content as markdown -->
										{#if msg.content}
											<div class="text-sm text-foreground">
												<MarkdownRenderer content={msg.generatedSql ? msg.content.replace(/```(?:sql)?[\s\S]*?```\n*/gi, '').trim() : msg.content} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
											</div>
										{/if}
									{/if}

									<!-- Overview (shown in queries container when queries exist) -->
									{#if msg.overview && !msg.queries?.length}
										<div class="mt-2 p-2.5 rounded-lg bg-primary/5 border border-primary/10 text-sm text-foreground">
											<MarkdownRenderer content={msg.overview} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
										</div>
									{/if}

									<!-- Schema Cards -->
									{#if msg.schemaInfo && Array.isArray(msg.schemaInfo) && msg.schemaInfo.length > 0}
										<div class="mt-3">
											{#await import('$lib/components/SchemaCard/SchemaCard.svelte') then { default: SchemaCard }}
												{#each msg.schemaInfo as table, idx (table.tableName + idx)}
													<SchemaCard
														tableName={table.tableName}
														schemaName={table.schemaName}
														databaseName={table.databaseName}
														columns={table.columns || []}
														comment={table.tableDescription || (table as any).comment}
														defaultExpanded={msg.schemaInfo.length <= 3}
													/>
												{/each}
											{/await}
										</div>
									{/if}

								<!-- Disambiguation Buttons -->
								{#if msg.needsDisambiguation && msg.disambiguationOptions}
									<div class="mt-3 space-y-2">
										<p class="text-xs text-muted-foreground font-medium">{i18n('aichat.disambiguation.title')}</p>
										<div class="flex flex-col gap-1.5">
											{#each msg.disambiguationOptions as opt}
												<button
													class="flex flex-col items-start px-4 py-2.5 rounded-lg border border-border bg-card hover:border-primary/50 hover:bg-primary/5 transition-all text-left"
													onclick={() => handleDisambiguation(msg.id, opt)}
												>
													<span class="text-sm font-medium text-foreground">{opt.label}</span>
													{#if opt.refinedQuery && opt.refinedQuery !== opt.label}
														<span class="text-xs text-muted-foreground mt-0.5 line-clamp-1">{opt.refinedQuery}</span>
													{/if}
												</button>
											{/each}
										</div>
									</div>
								{/if}

								<!-- Clarification Buttons -->
								{#if msg.needsClarification && msg.clarificationOptions}
										<div class="mt-3 space-y-2">
											<p class="text-xs text-muted-foreground font-medium">{i18n('aichat.recommended.below')}</p>
											<div class="flex flex-col gap-1.5">
												{#each msg.clarificationOptions as opt}
													<button
														class="flex flex-col items-start px-4 py-2.5 rounded-lg border border-border bg-card hover:border-primary/50 hover:bg-primary/5 transition-all text-left"
														onclick={() => handleClarification(msg.id, opt.query)}
													>
														<span class="text-sm font-medium text-foreground">{opt.label}</span>
														{#if opt.query && opt.query !== opt.label}
															<span class="text-xs text-muted-foreground mt-0.5 line-clamp-1">{opt.query}</span>
														{/if}
													</button>
												{/each}
											</div>
										</div>
									{/if}

									<!-- Date Range Buttons -->
									{#if msg.needsDateRange}
										<div class="mt-3 space-y-2">
											<p class="text-xs text-muted-foreground font-medium flex items-center gap-1">
												<Calendar size={12} />
												Select a date range:
											</p>
											<div class="flex flex-wrap gap-2">
												{#each dateRangeOptions as opt}
													<button
														class="px-3 py-1.5 rounded-full border border-border bg-muted/50 text-xs hover:bg-accent transition-colors"
														onclick={() => handleDateRange(msg.id, opt.suffix)}
													>
														{opt.label}
													</button>
												{/each}
												{#if showDateRangeInput[msg.id]}
													<input
														class="px-3 py-1 rounded-full border border-input bg-background text-xs w-40"
														placeholder={i18n('aichat.dateRange.placeholder')}
														bind:value={dateRangeCustom[msg.id]}
														onkeydown={(e) => { if (e.key === 'Enter' && dateRangeCustom[msg.id]) handleDateRange(msg.id, ` ${dateRangeCustom[msg.id]}`); }}
													/>
												{:else}
													<button
														class="px-3 py-1.5 rounded-full border border-dashed border-border text-xs text-muted-foreground hover:bg-accent transition-colors"
														onclick={() => showDateRangeInput = { ...showDateRangeInput, [msg.id]: true }}
													>
														Custom...
													</button>
												{/if}
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

								<!-- Multi-aspect analysis (grid of complementary aspect cards + synthesis) -->
								{#if msg.queries && msg.queries.length > 0 && msg.multiAspect}
									<div class="mt-3 flex flex-col gap-4">
										{#if msg.synthesisGoal}
											<div class="text-sm font-medium text-foreground/85 flex items-center gap-1.5">
												<TrendingUp size={14} class="opacity-70" />
												<span>{i18n('aichat.synthesis.title', msg.synthesisGoal)}</span>
											</div>
										{/if}
										<div class="grid gap-4" style="grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));">
											{#each msg.queries as query, qi}
												{@const chartKey = `${msg.id}-q${qi}`}
												{@const aspectTitle = stripMarkdown(query.title || `Aspect ${qi + 1}`)}
												<div class="flex flex-col rounded-xl border border-border/60 bg-card overflow-hidden">
													<div class="flex items-center justify-between px-3 py-2 border-b border-border/40 bg-muted/20">
														<span class="text-xs font-semibold text-foreground/80 truncate" title={aspectTitle}>
															{aspectTitle}
														</span>
														{#if query.aspectId}
															<span class="text-[10px] text-muted-foreground/60 uppercase tracking-wide">{query.aspectId}</span>
														{/if}
													</div>

													{#if query.aspectErrorMessage}
														<div class="px-3 py-4 text-xs text-destructive flex items-start gap-2">
															<AlertCircle size={12} class="mt-[2px] flex-shrink-0" />
															<div>
																<div class="font-medium mb-1">{i18n('aichat.aspect.queryFailed')}</div>
																<div class="text-muted-foreground">{query.aspectErrorMessage}</div>
															</div>
														</div>
													{:else}
														{@const res = query.result}
														{#if res?.headerList && res?.dataList}
															<!-- Chart (if non-table recommendation) -->
															{@const backendChart = query.recommendedChart}
															{@const autoType = backendChart || guessChartType(res)}
															{@const activeChartType = chartTypes[chartKey] || autoType}
															{@const savedConfig = chartConfigs[chartKey]}
															{@const effectiveConfig = savedConfig || buildInitialChartConfig(res, activeChartType as ChartType, query)}
															{@const chartOpt = activeChartType !== 'TABLE' && activeChartType !== 'CARD' ? generateChartOptionWithConfig(res, activeChartType, effectiveConfig) : null}
															{#if activeChartType !== 'TABLE'}
																<div class="border-b border-border/30">
																	<div class="flex items-center justify-between px-2 py-1 bg-muted/10 gap-2 flex-wrap">
																		<div class="flex items-center gap-0.5 flex-wrap">
																			<button class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'BAR' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}" onclick={() => { chartTypes[chartKey] = 'BAR'; showChart[chartKey] = true; }} title={i18n('aichat.chart.bar')}><BarChart3 size={10} />{i18n('aichat.chart.bar.short')}</button>
																			<button class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'LINE' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}" onclick={() => { chartTypes[chartKey] = 'LINE'; showChart[chartKey] = true; }} title={i18n('aichat.chart.line')}><TrendingUp size={10} />{i18n('aichat.chart.line.short')}</button>
																			<button class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'PIE' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}" onclick={() => { chartTypes[chartKey] = 'PIE'; showChart[chartKey] = true; }} title={i18n('aichat.chart.pie')}><PieChartIcon size={10} />{i18n('aichat.chart.pie.short')}</button>
																			<button class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'SCATTER' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}" onclick={() => { chartTypes[chartKey] = 'SCATTER'; showChart[chartKey] = true; }} title={i18n('aichat.chart.scatter')}><CircleDot size={10} />{i18n('aichat.chart.scatter.short')}</button>
																			<button class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'CARD' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}" onclick={() => { chartTypes[chartKey] = 'CARD'; showChart[chartKey] = true; }} title={i18n('aichat.chart.card')}><BarChart3 size={10} />{i18n('aichat.chart.card.short')}</button>
																		</div>
																		<div class="flex items-center gap-0.5">
																			{#if chartOpt || activeChartType === 'CARD'}
																				<button
																					class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent disabled:opacity-50"
																					disabled={pngExporting === chartKey}
																					onclick={async () => {
																						const chartEl = document.querySelector(`[data-chart-key="${chartKey}"]`);
																						if (!chartEl) return;
																						pngExporting = chartKey;
																						try {
																							await downloadChartAsPNG(chartEl as HTMLElement, `chart-${chartKey}`);
																						} finally {
																							pngExporting = null;
																						}
																					}}
																					title={i18n('aichat.chart.exportPng')}
																				>{#if pngExporting === chartKey}<Loader2 size={10} class="inline mr-0.5 animate-spin" />{:else}<ImageDown size={10} class="inline mr-0.5" />{/if}PNG</button>
																				<button
																					class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																					onclick={() => {
																						if (activeChartType === 'CARD') {
																							maximizedContent = { type: 'card', chartConfig: effectiveConfig, resultData: res, title: aspectTitle };
																						} else {
																							maximizedContent = { type: 'chart', chartOption: chartOpt, chartType: activeChartType, chartConfig: effectiveConfig, resultData: res, title: aspectTitle };
																							maximizedChartType = activeChartType as ChartType;
																						}
																					}}
																					title={i18n('aichat.chart.maximize')}
																				><Expand size={10} /></button>
																			{/if}
																			<button
																				class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																				onclick={() => {
																					const existingConfig = chartConfigs[chartKey];
																					const inferred = buildInitialChartConfig(res, activeChartType as ChartType, query);
																					const finalConfig = existingConfig || inferred;
																					chartSettingsData = { msgId: msg.id, queryIndex: qi, resultData: res, chartType: activeChartType, chartConfig: finalConfig };
																					chartSettingsOpen = true;
																				}}
																				title={i18n('aichat.chart.settings')}
																			><Settings size={10} /></button>
																			<button
																				class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																				onclick={() => handlePinToDashboard(msg, qi)}
																				title={i18n('aichat.chart.pinDashboard')}
																			><Pin size={10} /></button>
																		</div>
																	</div>
																	{#if activeChartType === 'CARD'}
																		{@const cardMetrics = buildCardMetrics(res, effectiveConfig.metrics)}
																		<div class="grid grid-cols-[repeat(auto-fit,minmax(140px,1fr))] gap-2 p-2" data-chart-key={chartKey}>
																			{#each cardMetrics as metric}
																				<div class="border border-border bg-background rounded-md p-2">
																					<div class="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide truncate">{metric.name}</div>
																					<div class="mt-1 text-base font-bold text-foreground truncate">
																						{metric.isNumeric ? formatMetricValue(metric.raw) : String(metric.raw ?? '-')}
																					</div>
																				</div>
																			{/each}
																		</div>
																	{:else if chartOpt}
																		{#await import('$lib/components/ECharts/ECharts.svelte')}
																			<div class="flex items-center justify-center h-[220px] text-xs text-muted-foreground" data-chart-key={chartKey}>
																				<Loader2 size={14} class="animate-spin" />
																			</div>
																		{:then { default: ECharts }}
																			<div class="p-2" data-chart-key={chartKey}>
																				<ECharts option={chartOpt} height="220px" theme={isDarkMode ? 'dark' : undefined} />
																			</div>
																		{/await}
																	{/if}
																</div>
															{/if}

															<!-- Result table preview (limited rows) -->
															{@const rowNumIndices = res.headerList.reduce((acc, h, i) => { if (String(h.name || h) === 'Row Number') acc.push(i); return acc; }, [] as number[])}
															{@const filteredHeaders = res.headerList.filter((_, i) => !rowNumIndices.includes(i))}
															{@const filteredDataList = rowNumIndices.length > 0 ? res.dataList.map(row => row.filter((_, i) => !rowNumIndices.includes(i))) : res.dataList}
															<div>
																<div class="flex items-center justify-between px-3 py-1 bg-muted/10 border-b border-border/30">
																	<span class="text-[10px] text-muted-foreground/70">{res.dataList.length} rows</span>
																	<button
																		class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent"
																		onclick={() => maximizedContent = { type: 'table', data: { ...res, headerList: filteredHeaders, dataList: filteredDataList }, title: aspectTitle }}
																		title={i18n('aichat.table.maximize')}
																	><Expand size={10} /></button>
																</div>
																<div class="max-h-[180px] overflow-auto">
																	<table class="w-full text-[11px]">
																		<thead class="sticky top-0 z-[2]">
																			<tr class="bg-muted/40">
																				{#each filteredHeaders as h}
																					<th class="px-2 py-1 text-left font-medium text-muted-foreground/70 border-b border-border/30 whitespace-nowrap">{String(h.name || h)}</th>
																				{/each}
																			</tr>
																		</thead>
																		<tbody>
															{#each filteredDataList.slice(0, 25) as row}
																<tr class="border-b border-border/10 hover:bg-accent/30">
																	{#each row as cell}
																		<td class="px-2 py-1 whitespace-nowrap max-w-[160px] truncate text-foreground/85" title={String(cell ?? 'NULL')}>
																			{#if cell === null || cell === undefined}
																				<span class="text-muted-foreground/50 italic">{i18n('aichat.table.null')}</span>
																			{:else}
																				{formatCellDisplay(cell)}
																			{/if}
																		</td>
																	{/each}
																</tr>
															{/each}
																		</tbody>
																	</table>
																</div>
																{#if filteredDataList.length > 25}
																	<div class="px-2 py-1 text-[10px] text-muted-foreground/60 border-t border-border/20">
																		Showing 25 of {filteredDataList.length} rows · maximize for more
																	</div>
																{/if}
															</div>
														{/if}

														<!-- Per-aspect 1-2 sentence insight -->
														{#if query.aspectInsight}
															<div class="px-3 py-2 text-[12px] leading-relaxed text-foreground/85 border-t border-border/30 bg-muted/10">
																{query.aspectInsight}
															</div>
														{/if}

														<!-- Collapsed SQL (read-only, dialect-aware formatting) -->
														<details class="border-t border-border/30 bg-muted/5">
															<summary class="px-3 py-1.5 text-[10px] text-muted-foreground/70 cursor-pointer hover:text-foreground select-none flex items-center gap-1">
																<Code size={10} class="opacity-60" />
																<span>{i18n('aichat.sql.label')}</span>
															</summary>
															<pre class="px-3 py-2 text-[11px] text-foreground/85 overflow-x-auto font-mono leading-relaxed bg-card border-t border-border/20"><code>{formatSql(query.sql, (selectedConn as any)?.type)}</code></pre>
															{#if query.aspectReason}
																<div class="px-3 py-2 text-[10px] text-muted-foreground border-t border-border/20 italic">
																	Why this aspect: {query.aspectReason}
																</div>
															{/if}
														</details>
													{/if}
												</div>
											{/each}
										</div>

										{#if msg.synthesis}
											<!-- Synthesis text — same minimal look as a single-query interpretation -->
											<div class="mt-6 text-sm text-foreground">
												<MarkdownRenderer content={msg.synthesis} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
											</div>
										{/if}
									</div>
								{:else if msg.queries && msg.queries.length > 0}
									<!-- Single-query (legacy) view -->
									<div class="mt-3 flex flex-col gap-0">
										<!-- Overview before queries -->
										{#if msg.overview && msg.overview.trim()}
											<div class="mb-4 text-sm leading-relaxed text-muted-foreground">
												<MarkdownRenderer content={msg.overview} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
											</div>
										{/if}
										{#each msg.queries as query, qi}
											{@const queryId = `${msg.id}-q${qi}`}
											<div class="flex flex-col">
												{#if !query.visualizationOnly}
												<!-- Separator between queries -->
												{#if qi > 0}
													<div class="query-separator my-6"></div>
													{#if query.suggestion}
														<div class="mb-3 px-3 py-2 rounded-md bg-muted/30 text-[13px] italic text-muted-foreground">
															<MarkdownRenderer content={query.suggestion} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
														</div>
													{/if}
												{/if}

												<!-- Query Title -->
												<div class="mb-2">
													<span class="text-sm font-semibold text-foreground/80">
														{#if query.title}
															<MarkdownRenderer content={query.title} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
														{:else}
															{generateTitleFromSql(query.sql, qi)}
														{/if}
													</span>
												</div>

												<!-- SQL Code with syntax highlighting -->
												<div class="relative rounded-lg overflow-hidden border border-border bg-card">
													<!-- SQL Header: title + action buttons -->
													<div class="flex items-center justify-between px-3 py-1 border-b border-border bg-muted/30">
														<span class="text-[11px] font-medium text-muted-foreground/70 flex items-center gap-1">
															<Code size={11} class="opacity-60" />SQL
														</span>
														<div class="flex items-center gap-1">
															<button
																class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
																title={i18n('aichat.sql.copy')}
																onclick={() => copySql(query.sql, queryId)}
															>
																{#if copiedSqlId === queryId}
																	<Check size={12} class="text-green-500" />
																{:else}
																	<Copy size={12} />
																{/if}
															</button>
															<button
																class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
																title={i18n('aichat.sql.run')}
																onclick={() => handleRunQuery(msg.id, qi)}
																disabled={query.isExecuting}
															>
																{#if query.isExecuting}
																	<Loader2 size={12} class="animate-spin" />
																{:else}
																	<Play size={12} />
																{/if}
															</button>
															<button
																class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground"
																title={i18n('aichat.sql.pin.workspace')}
																onclick={() => handlePinToWorkspace(query.sql)}
															>
																<Pin size={12} />
															</button>
														</div>
													</div>

													{#if query.isExecuting}
														<div class="absolute inset-0 bg-background/50 flex items-center justify-center z-10">
															<div class="flex items-center gap-2 text-xs text-muted-foreground">
																<Loader2 size={14} class="animate-spin" />
																<span>Executing... {executingTimers[queryId] || 0}s</span>
															</div>
														</div>
													{/if}
													{#await import('$lib/components/MonacoEditor') then { MonacoEditor }}
														<div style="height: {Math.max(60, (query.sql.split('\n').length + 1) * 20 + 24)}px;">
															<MonacoEditor
																value={query.sql}
																language="sql"
																readOnly={query.isExecuting || false}
																onchange={(val) => updateQuerySql(msg.id, qi, val)}
																class="border-0"
															/>
														</div>
													{:catch}
														<pre class="p-3 text-xs text-foreground overflow-x-auto font-mono leading-relaxed"><code>{query.sql}</code></pre>
													{/await}
												</div>

												<!-- Explanation -->
												{#if query.explanation}
													<div class="mt-10 p-3 px-4 rounded-lg bg-muted/50 border border-border/50 text-[13px] leading-relaxed text-muted-foreground query-explanation">
														<MarkdownRenderer content={query.explanation} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
													</div>
												{/if}
												{/if}
											</div>
										{/each}
									</div>

									<!-- Query execution results - stacked BELOW all queries -->
									{#each msg.queries as query, qi}
										{#if msg.isStreaming && !query.result && !query.visualizationOnly && (msg.executionMode || executionMode) !== 'manual' && qi === 0}
											<div class="mt-10 rounded-xl border border-border/40 bg-card shadow-sm overflow-hidden">
												<div class="flex items-center justify-between px-4 py-2.5 bg-muted/20 border-b border-border/30">
													<span class="text-xs font-medium text-foreground/80 truncate">
														{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))} Result
													</span>
												</div>
												<div class="flex items-center justify-center gap-2.5 py-12 text-muted-foreground">
													<Loader2 size={16} class="animate-spin" />
													<span class="text-sm">{currentThinkingTitle(msg)}</span>
												</div>
											</div>
										{/if}
										{#if query.isExecuting && !query.result}
											{@const queryId = `${msg.id}-q${qi}`}
											<div class="mt-10 rounded-xl border border-border/40 bg-card shadow-sm overflow-hidden">
												<div class="flex items-center justify-between px-4 py-2.5 bg-muted/20 border-b border-border/30">
													<span class="text-xs font-medium text-foreground/80 truncate">
														{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))} Result
													</span>
												</div>
												<div class="flex items-center justify-center gap-2.5 py-12 text-muted-foreground">
													<Loader2 size={16} class="animate-spin" />
													<span class="text-sm">Executing query{#if executingTimers[queryId]}... {executingTimers[queryId]}s{/if}</span>
												</div>
											</div>
										{/if}
										{#if query.result}
											{@const res = query.result}
											{@const queryId = `${msg.id}-q${qi}`}
											{#if !query.visualizationOnly}
											<div data-query-result="{msg.id}-{qi}" class="mt-10 rounded-xl border border-border/40 bg-card shadow-sm overflow-hidden">
												<!-- Result Header -->
												<div class="flex items-center justify-between px-4 py-2.5 bg-muted/20 border-b border-border/30">
													<span class="text-xs font-medium text-foreground/80 truncate">
														{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))} Result
													</span>
													<div class="flex items-center gap-1">
														<button
															class="p-1 rounded-md hover:bg-accent/60 text-muted-foreground hover:text-foreground transition-colors"
															title={i18n('aichat.sql.pin.workspace')}
															onclick={() => handlePinToWorkspace(query.sql)}
														>
															<Pin size={12} />
														</button>
													</div>
												</div>

												{#if res.success === false && res.message}
													<!-- Error Result -->
													<div class="px-3 py-2 bg-destructive/5">
														<div class="flex items-center gap-2 text-xs text-destructive">
															<AlertCircle size={12} />
															<span>{res.message}</span>
														</div>
													</div>
												{:else if res.headerList && res.dataList}
													<!-- Filter out "Row Number" column (already shown as # row index) -->
													{@const rowNumIndices = res.headerList.reduce((acc, h, i) => { if (String(h.name || h) === 'Row Number') acc.push(i); return acc; }, [] as number[])}
													{@const filteredHeaders = res.headerList.filter((_, i) => !rowNumIndices.includes(i))}
													{@const filteredDataList = rowNumIndices.length > 0 ? res.dataList.map(row => row.filter((_, i) => !rowNumIndices.includes(i))) : res.dataList}
													{@const inlineSortKey = `inline-${msg.id}-q${qi}`}
													{@const sortedDataList = sortDataList(filteredDataList, inlineSortKey)}
													{@const inlineSort = columnSort[inlineSortKey]}
													<!-- Table -->
													<div>
														<div class="flex items-center justify-between px-4 py-1.5 bg-muted/10">
															<span class="text-[11px] text-muted-foreground/70">
																{res.dataList.length} rows
																{#if query.executionTime}· {query.executionTime}ms{/if}
															</span>
															<div class="flex items-center gap-0.5">
																<button
																	class="px-2 py-1 rounded-md text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																	onclick={() => downloadTableAsCSV(filteredHeaders, filteredDataList)}
																	title={i18n('aichat.table.exportCsv')}
																><Download size={10} class="inline mr-0.5" />CSV</button>
																<button
																	class="px-2 py-1 rounded-md text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																	onclick={() => downloadTableAsJSON(filteredHeaders, filteredDataList)}
																	title={i18n('aichat.table.exportJson')}
																><Download size={10} class="inline mr-0.5" />JSON</button>
																<button
																	class="px-2 py-1 rounded-md text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																	onclick={() => downloadInsertSQL('query_result', filteredHeaders, filteredDataList)}
																	title={i18n('aichat.table.exportSql')}
																><Download size={10} class="inline mr-0.5" />SQL</button>
																<button
																	class="px-1.5 py-1 rounded-md text-[10px] text-muted-foreground/60 hover:text-foreground hover:bg-accent/50 transition-colors"
																	onclick={() => maximizedContent = { type: 'table', data: { ...res, headerList: filteredHeaders, dataList: filteredDataList }, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) }}
																	title={i18n('aichat.table.maximize')}
																><Expand size={10} /></button>
															</div>
														</div>
														<div class="max-h-[250px] overflow-auto relative">
															<table class="w-full text-xs">
																<thead class="sticky top-0 z-[2]">
																	<tr class="bg-muted/50 dark:bg-muted/50">
																		<th class="px-3 py-2 text-left text-[11px] font-medium text-muted-foreground/70 border-b border-border/30 whitespace-nowrap w-8">#</th>
																		{#each filteredHeaders as h, colIdx}
																			{@const colName = String(h.name || h)}
																			{@const fmtKey = `${msg.id}-${colName}`}
																			<th class="px-3 py-2 text-left text-[11px] font-medium text-muted-foreground/70 border-b border-border/30 whitespace-nowrap relative bg-muted/50 dark:bg-muted/50">
																				<div class="flex items-center gap-1">
																					<button
																						class="hover:text-foreground cursor-pointer select-none inline-flex items-center gap-0.5"
																						onclick={() => toggleSort(inlineSortKey, colIdx)}
																						title={i18n('aichat.table.sortColumn')}
																					>
																						{colName}
																						{#if inlineSort?.col === colIdx}
																							{#if inlineSort.dir === 'asc'}
																								<ChevronUp size={10} class="text-primary" />
																							{:else}
																								<ChevronDown size={10} class="text-primary" />
																							{/if}
																						{:else}
																							<ChevronsUpDown size={10} class="opacity-30" />
																						{/if}
																					</button>
																					<button
																						class="opacity-40 hover:opacity-100"
																						onclick={(e) => { e.stopPropagation(); formatDropdownOpen = formatDropdownOpen === fmtKey ? null : fmtKey; }}
																						title={i18n('aichat.table.formatColumn')}
																					>
																						<ChevronDown size={8} />
																					</button>
																				</div>
																				{#if formatDropdownOpen === fmtKey}
																					<!-- svelte-ignore a11y_no_static_element_interactions -->
																					<div
																						class="absolute left-0 top-full mt-0.5 bg-popover border border-border rounded-md shadow-lg z-50 min-w-[160px] py-1"
																						onclick={(e) => e.stopPropagation()}
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
																								class="w-full px-3 py-1 text-left text-xs hover:bg-accent flex items-center justify-between"
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
																									<Check size={12} class="text-primary" />
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
																	{#each sortedDataList.slice(0, 100) as row, ri}
																		<tr class="border-b border-border/10 hover:bg-accent/30 transition-colors">
																			<td class="px-3 py-1.5 text-muted-foreground/50 text-[11px]">{ri + 1}</td>
																			{#each row as cell, colIdx}
																				{@const colName = String(filteredHeaders[colIdx]?.name || filteredHeaders[colIdx] || '')}
																				{@const colFmt = columnFormats[msg.id]?.[colName] || 'original'}
																				<td class="px-3 py-1.5 whitespace-nowrap max-w-[200px] truncate text-foreground/85" title={String(cell ?? 'NULL')}>
																					{#if cell === null || cell === undefined}
																						<span class="text-muted-foreground/50 italic">{i18n('aichat.table.null')}</span>
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
														</div>
														{#if filteredDataList.length > 100}
															<div class="px-4 py-1.5 text-[11px] text-muted-foreground/60 border-t border-border/20">
																Showing 100 of {filteredDataList.length} rows
															</div>
														{/if}
													</div>
												{/if}
											</div>
											{/if}

											<!-- Chart Visualization (separate card) -->
											{#if res.headerList && res.dataList}
												{@const chartKey = `${msg.id}-q${qi}`}
												{@const backendChart = query.recommendedChart}
												{@const autoType = backendChart || guessChartType(res)}
												{@const activeChartType = chartTypes[chartKey] || autoType}
												{@const savedConfig = chartConfigs[chartKey]}
												{@const effectiveConfig = savedConfig || buildInitialChartConfig(res, activeChartType as ChartType, query)}
												{@const chartOpt = activeChartType !== 'TABLE' && activeChartType !== 'CARD' ? generateChartOptionWithConfig(res, activeChartType, effectiveConfig) : null}
												{@const chartStats = calculateChartStats(res)}
												{#if activeChartType !== 'TABLE'}
												<div class="mt-10 rounded-lg border border-border bg-card overflow-hidden">
														<div class="flex flex-col gap-0">
															<div class="flex items-center justify-between px-3 py-1.5 border-b border-border/30 bg-muted/20">
																<span class="text-[11px] font-medium text-foreground/70 truncate max-w-[50%]">
																	{stripMarkdown(query.title || generateTitleFromSql(query.sql, qi))}
																</span>
															</div>
														</div>
														<div class="flex items-center justify-between px-3 py-1 bg-muted/10">
															<div class="flex items-center gap-1">
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'BAR' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}"
																	onclick={() => { chartTypes[chartKey] = 'BAR'; showChart[chartKey] = true; }}
																	title={i18n('aichat.chart.bar')}
																><BarChart3 size={10} />{i18n('aichat.chart.bar.short')}</button>
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'LINE' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}"
																	onclick={() => { chartTypes[chartKey] = 'LINE'; showChart[chartKey] = true; }}
																	title={i18n('aichat.chart.line')}
																><TrendingUp size={10} />{i18n('aichat.chart.line.short')}</button>
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'PIE' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}"
																	onclick={() => { chartTypes[chartKey] = 'PIE'; showChart[chartKey] = true; }}
																	title={i18n('aichat.chart.pie')}
																><PieChartIcon size={10} />{i18n('aichat.chart.pie.short')}</button>
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'SCATTER' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}"
																	onclick={() => { chartTypes[chartKey] = 'SCATTER'; showChart[chartKey] = true; }}
																	title={i18n('aichat.chart.scatter')}
																><CircleDot size={10} />{i18n('aichat.chart.scatter.short')}</button>
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] flex items-center gap-0.5 transition-colors {activeChartType === 'CARD' ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground hover:bg-accent border border-border'}"
																	onclick={() => { chartTypes[chartKey] = 'CARD'; showChart[chartKey] = true; }}
																	title={i18n('aichat.chart.card')}
																><BarChart3 size={10} />{i18n('aichat.chart.card.short')}</button>
															</div>
															<div class="flex items-center gap-1">
																{#if chartOpt || activeChartType === 'CARD'}
																	<button
																		class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent disabled:opacity-50"
																		disabled={pngExporting === chartKey}
																		onclick={async () => {
																			const chartEl = document.querySelector(`[data-chart-key="${chartKey}"]`);
																			if (!chartEl) return;
																			pngExporting = chartKey;
																			try {
																				await downloadChartAsPNG(chartEl as HTMLElement, `chart-${chartKey}`);
																			} finally {
																				pngExporting = null;
																			}
																		}}
																		title={i18n('aichat.chart.exportPng')}
																	>{#if pngExporting === chartKey}<Loader2 size={10} class="inline mr-0.5 animate-spin" />{:else}<ImageDown size={10} class="inline mr-0.5" />{/if}PNG</button>
																	<button
																		class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																		onclick={() => {
																			if (activeChartType === 'CARD') {
																				maximizedContent = { type: 'card', chartConfig: effectiveConfig, resultData: res, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) };
																			} else {
																				maximizedContent = { type: 'chart', chartOption: chartOpt, chartType: activeChartType, chartConfig: effectiveConfig, resultData: res, title: stripMarkdown(query.title || generateTitleFromSql(query.sql, qi)) };
																				maximizedChartType = activeChartType as ChartType;
																			}
																		}}
																		title={i18n('aichat.chart.maximize.short')}
																	><Expand size={10} /></button>
																{/if}
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																	onclick={() => {
																	const existingConfig = chartConfigs[chartKey];
																	const inferred = buildInitialChartConfig(res, activeChartType as ChartType, query);
																	const finalConfig = existingConfig || inferred;
																	chartSettingsData = { msgId: msg.id, queryIndex: qi, resultData: res, chartType: activeChartType, chartConfig: finalConfig };
																	chartSettingsOpen = true;
																}}
																	title={i18n('aichat.chart.settings')}
																><Settings size={10} /></button>
																<button
																	class="px-1.5 py-0.5 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent"
																	onclick={() => handlePinToDashboard(msg, qi)}
																	title={i18n('aichat.chart.pinDashboard')}
																><Pin size={10} /></button>
															</div>
														</div>
														{#if activeChartType === 'CARD'}
															<!-- Metric Card View -->
															{@const cardMetrics = buildCardMetrics(res, effectiveConfig.metrics)}
															{@const displayMetrics = cardMetrics}
															<div class="grid grid-cols-[repeat(auto-fit,minmax(180px,1fr))] gap-3 p-3" data-chart-key={chartKey}>
																{#each displayMetrics as metric}
																	<div class="border border-border bg-background rounded-lg p-3 min-h-[74px] flex flex-col justify-between">
																		<div class="text-[11px] font-semibold text-muted-foreground uppercase tracking-wide truncate">{metric.name}</div>
																		<div class="mt-1.5 text-xl font-bold text-foreground truncate">
																			{metric.isNumeric ? formatMetricValue(metric.raw) : String(metric.raw ?? '-')}
																		</div>
																	</div>
																{/each}
															</div>
														{:else if (showChart[chartKey] ?? (autoType !== 'TABLE' && autoType !== 'CARD')) && chartOpt}
															{#await import('$lib/components/ECharts/ECharts.svelte')}
																<div class="flex items-center justify-center gap-2.5 h-[300px] p-2 text-xs text-muted-foreground" data-chart-key={chartKey}>
																	<Loader2 size={14} class="animate-spin" />
																	<span>{currentThinkingTitle(msg, 'Preparing chart...')}</span>
																</div>
															{:then { default: ECharts }}
																<div class="p-2" data-chart-key={chartKey}>
																	<ECharts option={chartOpt} height="300px" theme={isDarkMode ? 'dark' : undefined} />
																</div>
															{/await}
														{/if}
														<!-- Chart Stats Footer -->
														{#if chartStats && res.dataList.length > 0}
															<div class="flex items-center gap-4 px-3 py-1.5 border-t border-border/50 text-[11px] text-muted-foreground">
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
												{/if}
										{/if}

										<!-- Per-query interpretation -->
										{#if query.isAnalyzingPython}
											<div class="mt-10 flex items-center gap-2 text-xs text-muted-foreground">
												<Loader2 size={12} class="animate-spin" />
												<span>{i18n('aichat.processing.stats')}</span>
											</div>
										{:else if query.isInterpreting}
											<div class="mt-10 flex items-center gap-2 text-xs text-muted-foreground">
												<Loader2 size={12} class="animate-spin" />
												<span>{i18n('aichat.processing.analyzing')}</span>
											</div>
										{:else if query.interpretation}
											<div class="mt-10 text-sm text-foreground">
												<MarkdownRenderer content={query.interpretation} dataSourceId={connDataSourceId} databaseName={connDatabaseName} schemaName={connSchemaName} />
											</div>
										{/if}
									{/each}
								{/if}
								{/if}

									{#if msg.suggestedFollowUps?.length && !msg.isStreaming && !msg.isThinking && !msg.queries?.some(q => q.isInterpreting || q.isAnalyzingPython)}
										<div class="mt-10 rounded-xl border border-border/60 bg-muted/20 p-3">
											<div class="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
												<TrendingUp size={13} />
												<span>{i18n('aichat.followUp.title')}</span>
											</div>
											<div class="flex flex-wrap gap-2">
												{#each msg.suggestedFollowUps as followUp}
													<button
														class="group rounded-full border border-border bg-background px-3 py-1.5 text-left text-xs text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5 disabled:opacity-50"
														disabled={chat.isStreaming}
														onclick={() => handleSuggestedFollowUp(msg, followUp)}
														title={followUp.reason || followUp.question}
													>
														<span class="font-medium">{followUp.title}</span>
														<span class="ml-1 text-muted-foreground group-hover:text-foreground">{followUp.question}</span>
													</button>
												{/each}
											</div>
										</div>
									{/if}

									<!-- Feedback & Copy buttons (hover-reveal, outside message content) -->
									{#if !msg.isStreaming && !msg.isThinking && msg.content && !msg.isResearchPlan}
										<div class="flex items-center gap-1 mt-2 pt-2 opacity-0 group-hover:opacity-100 transition-opacity">
											<button
												class="p-1 px-2 h-6 rounded text-xs transition-colors text-muted-foreground/40 hover:text-muted-foreground hover:bg-accent"
												onclick={async () => {
													const text = msg.overview
														? `${msg.overview}\n\n${(msg.queries || []).map(q => `${q.title || ''}\n${q.sql || ''}\n${q.explanation || ''}`).join('\n\n')}`
														: msg.content;
													await navigator.clipboard.writeText(text);
													copiedMsgId = msg.id;
													setTimeout(() => copiedMsgId = null, 2000);
												}}
												title={i18n('aichat.response.copy')}
											>
												{#if copiedMsgId === msg.id}
													<Check size={14} class="text-green-500" />
												{:else}
													<Copy size={14} />
												{/if}
											</button>
											<button
												class="p-1 px-2 h-6 rounded text-xs transition-colors {msg.feedback === 'POSITIVE' ? 'text-primary bg-primary/10' : 'text-muted-foreground/40 hover:text-muted-foreground hover:bg-accent'}"
												onclick={() => storeFeedback(msg.id, 'up', { chatRoomId: msg.chatRoomId, dataSourceId: selectedDatabase ? Number(selectedDatabase) : undefined })}
												title={i18n('aichat.response.good')}
											>
												<ThumbsUp size={14} />
											</button>
											<button
												class="p-1 px-2 h-6 rounded text-xs transition-colors {msg.feedback === 'NEGATIVE' ? 'text-primary bg-primary/10' : 'text-muted-foreground/40 hover:text-muted-foreground hover:bg-accent'}"
												onclick={() => storeFeedback(msg.id, 'down', { chatRoomId: msg.chatRoomId, dataSourceId: selectedDatabase ? Number(selectedDatabase) : undefined })}
												title={i18n('aichat.response.bad')}
											>
												<ThumbsDown size={14} />
											</button>
										</div>
									{/if}
								</div>
							</div>
						{/if}
					{/each}
				</div>
			{/if}
		</div>

		<!-- Input Area -->
		<div class="px-5 pb-5 pt-4">
			<div class="w-[65%] mx-auto">
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div
					class="relative rounded-xl bg-card flex flex-col gap-1.5 px-4 py-3"
					{...attachmentComposerRef?.dragHandlers ?? {}}
				>
					<!-- Pending / uploaded attachment chips + drop overlay -->
					<AttachmentComposer
						bind:this={attachmentComposerRef}
						bind:attachments={pendingAttachments}
						chatRoomId={chat.currentRoomId}
						currentModel={selectedModel}
						capabilities={modelCapabilities}
						disabled={chat.isStreaming}
					/>

					<!-- Row 1: Textarea -->
					<div class="relative flex items-start">
						<textarea
							bind:this={textareaEl}
							bind:value={inputValue}
							onkeydown={handleKeydown}
							onpaste={(e) => {
								// Let the composer consume image / file pastes
								// first. It calls preventDefault when it
								// actually attaches something, so the text-paste
								// branch below only runs for plain-text payloads.
								attachmentComposerRef?.onPaste(e);
								if (e.defaultPrevented) return;
								e.preventDefault();
								const text = e.clipboardData?.getData('text/plain')?.trimEnd() ?? '';
								const ta = e.currentTarget;
								const start = ta.selectionStart;
								const end = ta.selectionEnd;
								inputValue = inputValue.slice(0, start) + text + inputValue.slice(end);
								tick().then(() => { ta.selectionStart = ta.selectionEnd = start + text.length; });
							}}
							oncompositionstart={() => isComposing = true}
							oncompositionend={() => isComposing = false}
							class="flex-1 resize-none bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none min-h-[40px] max-h-[120px]"
							placeholder={i18n('aichat.input.placeholder.simple')}
							rows="1"
							disabled={chat.isStreaming}
						></textarea>
					</div>

					<!-- Row 2: DB selector (left) + buttons (right) -->
					<div class="flex items-center gap-2">
						<!-- Database Selector -->
						<Popover bind:open={showDbDropdown} onOpenChange={(open) => { if (!open) dbSearchQuery = ''; }}>
							<PopoverTrigger class="flex items-center gap-1.5 h-7 px-2 rounded-md text-xs hover:bg-accent transition-colors text-muted-foreground">
								{#if selectedDbInfo?.dbInfo}
									<img src={selectedDbInfo.dbInfo.img} alt={selectedDbInfo.dbInfo.name} class="w-4 h-4 object-contain" />
									<span class="truncate max-w-[120px] text-foreground">{selectedDbInfo.conn.alias}</span>
								{:else}
									<Database size={14} />
									<span>{i18n('aichat.db.label')}</span>
								{/if}
								<ChevronDown size={10} />
							</PopoverTrigger>
							<PopoverContent align="start" class="min-w-[220px] max-h-72 overflow-hidden p-0">
								{#if connections.length > 5}
									<div class="p-1.5 border-b border-border">
										<div class="flex items-center gap-1.5 h-7 px-2 border border-input rounded-md bg-background">
											<Search size={12} class="text-muted-foreground shrink-0" />
											<input
												class="flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground/50"
												placeholder={i18n('aichat.db.search')}
												bind:value={dbSearchQuery}
												onclick={(e) => e.stopPropagation()}
											/>
										</div>
									</div>
								{/if}
								<div class="max-h-56 overflow-auto p-1">
									{#each dbSearchQuery.trim()
										? connections.filter(c => c.alias.toLowerCase().includes(dbSearchQuery.toLowerCase()) || (c.type || '').toLowerCase().includes(dbSearchQuery.toLowerCase()))
										: connections as conn (conn.id)}
										{@const dbInfo = databaseMap[conn.type]}
										<button
											class="flex items-center gap-2 w-full px-3 py-1.5 text-xs hover:bg-accent transition-colors text-left rounded-sm
												{String(conn.id) === selectedDatabase ? 'bg-accent' : ''}"
											onclick={() => { selectedDatabase = String(conn.id); showDbDropdown = false; dbSearchQuery = ''; }}
										>
											{#if dbInfo}
												<img src={dbInfo.img} alt={dbInfo.name} class="w-4 h-4 object-contain shrink-0" />
											{:else}
												<Database size={14} class="text-muted-foreground shrink-0" />
											{/if}
											<span class="truncate">{conn.alias}</span>
											{#if String(conn.id) === selectedDatabase}<Check size={12} class="ml-auto text-primary" />{/if}
										</button>
									{:else}
										<div class="px-3 py-2 text-xs text-muted-foreground text-center">{i18n('aichat.db.noConnections')}</div>
									{/each}
								</div>
							</PopoverContent>
						</Popover>

						<div class="flex-1"></div>

						<!-- Attachment paperclip — opens the native file picker.
						     Disabled mid-stream to mirror the textarea state. -->
						<button
							type="button"
							class="h-8 w-8 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
							onclick={() => attachmentComposerRef?.triggerPicker()}
							disabled={chat.isStreaming}
							title={i18n('aichat.attach.tooltip')}
						>
							<Paperclip size={16} />
						</button>

						<!-- "Room library" toggle — only available once a room
						     exists, otherwise there's nothing to list yet. -->
						{#if chat.currentRoomId}
							<button
								type="button"
								class="h-8 w-8 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
								onclick={() => (isLibraryOpen = true)}
								title={i18n('aichat.attachment.library.tooltip')}
							>
								<FileText size={16} />
							</button>
						{/if}

						{#if chat.isStreaming}
							<Button size="sm" variant="destructive" class="h-8 px-3" onclick={() => stopStreaming()}>
								Stop
							</Button>
						{:else}
							<button
								class="h-8 w-8 rounded-md flex items-center justify-center bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
								onclick={handleSend}
								disabled={!inputValue.trim() && pendingAttachments.length === 0}
								title={executionMode === 'deep' ? 'Send (Deep Research)' : 'Send'}
							>
								{#if executionMode === 'deep'}
									<Microscope size={16} />
								{:else}
									<SendHorizontal size={16} />
								{/if}
							</button>
						{/if}
					</div>
				</div>
			</div>
		</div>
	</div>

	<!-- Deep Research / Infographic Panel resize handle + panel -->
	{#if research.state.isRunning || research.state.isResearchViewOpen || isInfographicViewOpen}
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
		<div
			class="w-1 hover:bg-primary/20 cursor-col-resize transition-colors shrink-0 {isResizingResearchPanel ? 'bg-primary/30' : ''}"
			role="separator"
			aria-orientation="vertical"
			onmousedown={handleResearchPanelResize}
		></div>
		<div class="border-l border-border flex flex-col bg-background min-w-0 shrink-0" style="width: {researchPanelWidth}px;">
			{#if isInfographicViewOpen}
				{#await import('$lib/components/InfographicView/InfographicView.svelte') then { default: InfographicView }}
					<InfographicView
						html={infographicViewHtml}
						isGenerating={isInfographicGenerating}
						onBack={() => isInfographicViewOpen = false}
					/>
				{/await}
			{:else if research.state.isRunning}
				<ResearchProgressPanel
					progress={research.state.progress}
					currentStep={research.state.currentStep}
					isRunning={research.state.isRunning}
				/>
			{:else if research.state.report}
				<ResearchReportView onClose={() => setResearchViewOpen(false)} onInfographicComplete={handleInfographicComplete} />
			{/if}
		</div>
	{/if}
</div>

<!-- Room attachment library panel -->
<AttachmentLibraryPanel
	roomId={chat.currentRoomId}
	open={isLibraryOpen}
	onClose={() => (isLibraryOpen = false)}
	onReattach={(att) => {
		if (pendingAttachments.some((a) => a.id === att.id)) return;
		pendingAttachments = [...pendingAttachments, att];
		isLibraryOpen = false;
	}}
/>

<!-- Toast notification -->
{#if pinSuccessMessage}
	<div class="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 px-5 py-3 rounded-xl bg-primary text-primary-foreground text-sm font-medium shadow-xl z-50 animate-in fade-in zoom-in-95">
		{pinSuccessMessage}
	</div>
{/if}

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
				<h3 class="text-base font-semibold text-foreground truncate">{maximizedContent.title}</h3>
				<button
					class="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
					onclick={() => maximizedContent = null}
					title={i18n('aichat.close')}
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
						<span class="text-sm text-muted-foreground">{tData.dataList.length} rows</span>
						<div class="flex items-center gap-2">
							<button
								class="px-2 py-1 rounded text-xs text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadTableAsCSV(tData.headerList, maxSorted)}
								title={i18n('aichat.table.exportCsv')}
							><Download size={12} />CSV</button>
							<button
								class="px-2 py-1 rounded text-xs text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadTableAsJSON(tData.headerList, maxSorted)}
								title={i18n('aichat.table.exportJson')}
							><Download size={12} />JSON</button>
							<button
								class="px-2 py-1 rounded text-xs text-muted-foreground hover:text-foreground hover:bg-accent border border-border flex items-center gap-1"
								onclick={() => downloadInsertSQL('query_result', tData.headerList, maxSorted)}
								title={i18n('aichat.table.exportSql')}
							><Download size={12} />SQL</button>
						</div>
					</div>
					<!-- Table -->
					<div class="flex-1 overflow-auto rounded-lg border border-border">
						<table class="w-full text-sm border-collapse">
							<thead class="sticky top-0 z-10">
								<tr class="bg-muted dark:bg-muted">
									<th class="px-3 py-2 text-left text-xs font-semibold text-muted-foreground border-b-2 border-border whitespace-nowrap w-10 bg-muted dark:bg-muted">#</th>
									{#each tData.headerList as header, ci}
										{@const colName = String(header.name || header)}
										<th class="px-3 py-2 text-left text-xs font-semibold text-muted-foreground border-b-2 border-border whitespace-nowrap bg-muted dark:bg-muted">
											<button
												class="hover:text-foreground cursor-pointer select-none inline-flex items-center gap-1"
												onclick={() => toggleSort(maxSortKey, ci)}
												title={i18n('aichat.table.sortColumn')}
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
										<td class="px-3 py-1.5 text-xs text-muted-foreground">{ri + 1}</td>
										{#each row as cell, ci}
											<td class="px-3 py-1.5 text-foreground whitespace-nowrap max-w-[400px] truncate" title={String(cell ?? 'NULL')}>
												{#if cell === null || cell === undefined}
													<span class="text-muted-foreground/50 italic">{i18n('aichat.table.null')}</span>
												{:else}
													{formatCellDisplay(cell)}
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
									class="px-2 py-1 rounded text-xs transition-colors {maximizedChartType === ct ? 'bg-primary text-white' : 'text-muted-foreground hover:bg-accent border border-border'}"
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
				<h3 class="text-base font-semibold text-foreground">{i18n('aichat.pinDashboard.title')}</h3>
				<button class="p-1 rounded hover:bg-accent text-muted-foreground" onclick={() => pinToDashboardOpen = false}>
					<X size={16} />
				</button>
			</div>
			<div class="p-5">
				{#if pinDashboardList.length === 0}
					<p class="text-sm text-muted-foreground text-center py-4">{i18n('aichat.pinDashboard.empty')}</p>
				{:else}
					<DropdownMenu>
						<DropdownMenuTrigger class="w-full px-3 py-2 rounded-lg border border-input bg-background text-foreground text-sm text-left inline-flex items-center justify-between">
							{pinDashboardList.find(d => d.id === pinSelectedDashboardId)?.name || i18n('aichat.pinDashboard.select')}
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
					class="px-4 py-2 rounded-lg text-sm text-muted-foreground hover:bg-accent"
					onclick={() => pinToDashboardOpen = false}
				>{i18n('aichat.pinDashboard.cancel')}</button>
				<button
					class="px-4 py-2 rounded-lg text-sm bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
					disabled={!pinSelectedDashboardId || pinSaving}
					onclick={handleSavePinToDashboard}
				>
					{pinSaving ? i18n('aichat.pinDashboard.saving') : i18n('aichat.pinDashboard.ok')}
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
				// Apply chart settings to the message query and update local state
				if (chartSettingsData) {
					const key = `${chartSettingsData.msgId}-q${chartSettingsData.queryIndex}`;
					try {
						const schema = JSON.parse(data.schema);
						// Update local chart type and config state for immediate re-render
						if (schema.chartType) {
							chartTypes[key] = schema.chartType as ChartType;
							showChart[key] = true;
						}
						if (schema.chartConfig) {
							chartConfigs[key] = schema.chartConfig as ChartConfig;
						}
						// Also persist on the query object
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
			initialDataSourceId={selectedDatabase ? Number(selectedDatabase) : undefined}
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
	/* Hide scrollbar but keep scrolling */
	:global(.hide-scrollbar) {
		scrollbar-width: none; /* Firefox */
	}
	:global(.hide-scrollbar::-webkit-scrollbar) {
		display: none; /* Chrome/Safari */
	}

	/* Enhanced thinking animation - bouncing dots */
	:global(.loading-dots) {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	:global(.loading-dots span) {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background-color: hsl(var(--primary));
		animation: loading-dot 1.4s infinite ease-in-out both;
	}
	:global(.loading-dots span:nth-child(1)) {
		animation-delay: -0.32s;
	}
	:global(.loading-dots span:nth-child(2)) {
		animation-delay: -0.16s;
	}

	@keyframes loading-dot {
		0%, 80%, 100% {
			opacity: 0.3;
			transform: scale(0.6);
		}
		40% {
			opacity: 1;
			transform: scale(1);
		}
	}

	/* Thinking step fade-in */
	:global(.thinking-step-row) {
		animation: thinkingStepIn 0.3s ease-out both;
	}

	@keyframes thinkingStepIn {
		from { opacity: 0; transform: translateY(-4px); }
		to { opacity: 1; transform: translateY(0); }
	}

	/* Thinking spinner - purple ring */
	:global(.thinking-spinner) {
		width: 12px;
		height: 12px;
		border: 2px solid rgba(139, 92, 246, 0.3);
		border-top-color: rgba(139, 92, 246, 0.8);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
		flex-shrink: 0;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	/* Query separator - gradient line with star */
	:global(.query-separator) {
		height: 1px;
		background: linear-gradient(to right, transparent, hsl(var(--border)) 20%, hsl(var(--border)) 80%, transparent);
		position: relative;
	}
	:global(.query-separator)::before {
		content: "";
		position: absolute;
		left: 50%;
		top: 50%;
		transform: translate(-50%, -50%);
		width: 32px;
		height: 32px;
		background: hsl(var(--background));
		border-radius: 50%;
	}
	:global(.query-separator)::after {
		content: "\2726";
		position: absolute;
		left: 50%;
		top: 50%;
		transform: translate(-50%, -50%);
		color: hsl(var(--muted-foreground) / 0.4);
		font-size: 12px;
		background: hsl(var(--background));
		padding: 8px;
		border-radius: 50%;
	}

	/* Query explanation styling */
	:global(.query-explanation) :global(ul) {
		margin: 0;
		padding-left: 20px;
		list-style-type: disc;
	}
	:global(.query-explanation) :global(li) {
		font-size: 13px;
		line-height: 1.6;
		color: hsl(var(--muted-foreground));
		margin-bottom: 4px;
	}
	:global(.query-explanation) :global(li:last-child) {
		margin-bottom: 0;
	}
	:global(.query-explanation) :global(p) {
		font-size: 13px;
		line-height: 1.6;
		color: hsl(var(--muted-foreground));
		margin: 0 0 4px 0;
	}
	:global(.query-explanation) :global(p:last-child) {
		margin-bottom: 0;
	}
	:global(.query-explanation) :global(code) {
		background: hsl(var(--muted));
		padding: 2px 6px;
		border-radius: 4px;
		font-size: 12px;
		color: hsl(var(--primary));
	}
</style>
