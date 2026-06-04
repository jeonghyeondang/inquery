<script lang="ts">
	/**
	 * Monaco Editor Svelte 5 component
	 * Direct integration with monaco-editor (no React wrapper needed)
	 */
	import { onMount, onDestroy } from 'svelte';
	import { cn } from '$lib/utils/cn';
	import { registerCustomThemes } from './editorThemes';
	import { getEditorSettingStore, getMonacoOptions } from '$lib/stores/editorSetting.svelte';
	import { initUnifiedIntelliSense } from '$lib/utils/intellisense/unified-provider';
	import { setCopyContext } from '$lib/utils/clipboardContext';

	interface Props {
		value?: string;
		language?: string;
		theme?: string;
		readOnly?: boolean;
		class?: string;
		options?: Record<string, unknown>;
		onchange?: (value: string) => void;
		onmount?: (editor: any, monaco: any) => void;
		onselectionchange?: (selection: { startLine: number; endLine: number; text: string; top: number; left: number } | null) => void;
		disableContextMenu?: boolean;
	}

	let {
		value = $bindable(''),
		language = 'sql',
		theme: themeOverride,
		readOnly = false,
		class: className = '',
		options = {},
		onchange,
		onmount,
		onselectionchange,
		disableContextMenu = false
	}: Props = $props();

	let containerEl = $state<HTMLDivElement | null>(null);
	let editor = $state<any>(null);
	let monacoInstance = $state<any>(null);
	let isUpdatingFromExternal = false;

	const editorSettingStore = getEditorSettingStore();

	onMount(async () => {
		// Configure Monaco Web Workers for Vite
		self.MonacoEnvironment = {
			getWorker(_: string, label: string) {
				if (label === 'json') {
					return new Worker(new URL('monaco-editor/esm/vs/language/json/json.worker.js', import.meta.url), { type: 'module' });
				}
				if (label === 'css' || label === 'scss' || label === 'less') {
					return new Worker(new URL('monaco-editor/esm/vs/language/css/css.worker.js', import.meta.url), { type: 'module' });
				}
				if (label === 'html' || label === 'handlebars' || label === 'razor') {
					return new Worker(new URL('monaco-editor/esm/vs/language/html/html.worker.js', import.meta.url), { type: 'module' });
				}
				if (label === 'typescript' || label === 'javascript') {
					return new Worker(new URL('monaco-editor/esm/vs/language/typescript/ts.worker.js', import.meta.url), { type: 'module' });
				}
				return new Worker(new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url), { type: 'module' });
			}
		};

		// Dynamic import for SSR safety
		const monaco = await import('monaco-editor');
		monacoInstance = monaco;

		// Register custom themes
		registerCustomThemes(monaco);

		// Get editor settings
		const settings = editorSettingStore.settings;
		const monacoOpts = getMonacoOptions(settings);

		// Create editor - options from props first, then store values override
		if (!containerEl) return;
		editor = monaco.editor.create(containerEl, {
			value,
			language,
			readOnly,
			automaticLayout: true,
			scrollBeyondLastLine: false,
			tabSize: 2,
			padding: { top: 8, bottom: 8 },
			scrollbar: {
				verticalScrollbarSize: 8,
				horizontalScrollbarSize: 8,
				alwaysConsumeMouseWheel: false
			},
			suggestOnTriggerCharacters: true,
			quickSuggestions: true,
			contextmenu: !disableContextMenu,
			// Store values as defaults, then prop options override
			theme: themeOverride || monacoOpts.theme || 'vs-dark',
			fontSize: monacoOpts.fontSize,
			fontFamily: monacoOpts.fontFamily,
			lineHeight: monacoOpts.lineHeight,
			lineNumbers: monacoOpts.lineNumbers,
			minimap: monacoOpts.minimap,
			wordWrap: monacoOpts.wordWrap,
			folding: monacoOpts.folding,
			renderLineHighlight: monacoOpts.renderLineHighlight,
			stickyScroll: monacoOpts.stickyScroll,
			...options,
		});

		// Listen for content changes
		editor.onDidChangeModelContent(() => {
			if (isUpdatingFromExternal) return;
			const newValue = editor.getValue();
			value = newValue;
			onchange?.(newValue);
		});

		// Listen for selection changes (always register for floating UI support)
		editor.onDidChangeCursorSelection(() => {
			const selection = editor.getSelection();
			if (!selection || selection.isEmpty()) {
				onselectionchange?.(null);
				return;
			}
			const text = editor.getModel()?.getValueInRange(selection) || '';
			if (!text.trim()) {
				onselectionchange?.(null);
				return;
			}
			const pos = editor.getScrolledVisiblePosition({
				lineNumber: selection.startLineNumber,
				column: selection.startColumn,
			});
			onselectionchange?.({
				startLine: selection.startLineNumber,
				endLine: selection.endLineNumber,
				text,
				top: pos?.top || 0,
				left: pos?.left || 0
			});
		});

		// Capture line range on copy for clipboard context chips
		containerEl?.addEventListener('copy', () => {
			const sel = editor?.getSelection();
			if (sel && !sel.isEmpty()) {
				setCopyContext({
					startLine: sel.startLineNumber,
					endLine: sel.endLineNumber,
					language: language || 'sql',
				});
			}
		});

		// Initialize IntelliSense for SQL language
		if (language === 'sql') {
			initUnifiedIntelliSense(monaco);
		}

		// Callback
		onmount?.(editor, monaco);
	});

	onDestroy(() => {
		editor?.dispose();
	});

	// Sync external value changes
	$effect(() => {
		if (editor && value !== editor.getValue()) {
			isUpdatingFromExternal = true;
			editor.setValue(value);
			isUpdatingFromExternal = false;
		}
	});

	// Sync theme changes
	$effect(() => {
		const t = themeOverride || editorSettingStore.settings.editorTheme;
		if (monacoInstance && t) {
			monacoInstance.editor.setTheme(t);
		}
	});

	// Sync editor options reactively (for settings preview etc.)
	// Prop options override store values when explicitly provided
	$effect(() => {
		if (!editor) return;
		const s = editorSettingStore.settings;
		const fontFamily = s.fontFamily;
		const customFont = s.customFont;
		const fontSize = s.fontSize;
		const lineHeight = s.lineHeight;
		const showLineNumbers = s.showLineNumbers;
		const showMinimap = s.showMinimap;
		const wordWrap = s.wordWrap;
		const codeFolding = s.codeFolding;
		const lineHighlight = s.lineHighlight;
		const stickyScroll = s.stickyScroll;

		const storeOpts: Record<string, unknown> = {
			fontFamily: customFont || `'${fontFamily}', monospace`,
			fontSize,
			lineHeight: fontSize * lineHeight,
			lineNumbers: showLineNumbers ? 'on' : 'off',
			minimap: { enabled: showMinimap },
			wordWrap: wordWrap ? 'on' : 'off',
			folding: codeFolding,
			renderLineHighlight: lineHighlight,
			stickyScroll: { enabled: stickyScroll }
		};

		// Prop options take priority over store settings
		if (options.fontSize != null) {
			storeOpts.fontSize = options.fontSize;
			storeOpts.lineHeight = (options.fontSize as number) * lineHeight;
		}
		if (options.lineNumbers != null) storeOpts.lineNumbers = options.lineNumbers;
		if (options.wordWrap != null) storeOpts.wordWrap = options.wordWrap;
		if (options.minimap != null) storeOpts.minimap = options.minimap;
		if (options.fontFamily != null) storeOpts.fontFamily = options.fontFamily;

		editor.updateOptions(storeOpts);
	});

	// Public API
	export function getEditor() {
		return editor;
	}

	export function getMonaco() {
		return monacoInstance;
	}

	export function focus() {
		editor?.focus();
	}

	export function getSelectedText(): string {
		if (!editor) return '';
		const selection = editor.getSelection();
		return editor.getModel()?.getValueInRange(selection) || '';
	}

	export function getCurrentQueryAtCursor(): string {
		if (!editor) return '';
		const model = editor.getModel();
		if (!model) return '';
		const position = editor.getPosition();
		if (!position) return '';
		const content = model.getValue();
		if (!content.trim()) return '';

		const cursorOffset = model.getOffsetAt(position);
		const queries: { start: number; end: number; text: string }[] = [];
		let currentStart = 0;
		let inString = false;
		let stringChar = '';

		for (let i = 0; i < content.length; i++) {
			const char = content[i];
			if ((char === "'" || char === '"') && (i === 0 || content[i - 1] !== '\\')) {
				if (!inString) {
					inString = true;
					stringChar = char;
				} else if (char === stringChar) {
					inString = false;
				}
			}
			if (char === ';' && !inString) {
				const queryText = content.substring(currentStart, i + 1).trim();
				if (queryText) {
					queries.push({ start: currentStart, end: i + 1, text: queryText });
				}
				currentStart = i + 1;
			}
		}

		const lastQuery = content.substring(currentStart).trim();
		if (lastQuery) {
			queries.push({ start: currentStart, end: content.length, text: lastQuery });
		}

		for (const query of queries) {
			if (cursorOffset >= query.start && cursorOffset <= query.end) {
				return query.text;
			}
		}

		if (queries.length > 0 && cursorOffset >= queries[queries.length - 1].end) {
			return queries[queries.length - 1].text;
		}

		return '';
	}

	export function insertTextAtCursor(text: string) {
		if (!editor) return;
		const selection = editor.getSelection();
		if (selection) {
			editor.executeEdits('insert', [{
				range: selection,
				text,
				forceMoveMarkers: true
			}]);
		}
	}

	let currentViewZoneId: string | null = null;

	export function addViewZone(afterLineNumber: number, heightInPx: number): void {
		if (!editor) return;
		editor.changeViewZones((accessor: any) => {
			if (currentViewZoneId) {
				accessor.removeZone(currentViewZoneId);
			}
			const domNode = document.createElement('div');
			currentViewZoneId = accessor.addZone({
				afterLineNumber,
				heightInPx,
				domNode
			});
		});
	}

	export function removeViewZone(): void {
		if (!editor || !currentViewZoneId) return;
		editor.changeViewZones((accessor: any) => {
			if (currentViewZoneId) {
				accessor.removeZone(currentViewZoneId);
				currentViewZoneId = null;
			}
		});
	}

	export function getLineTop(lineNumber: number): number {
		if (!editor) return 0;
		const pos = editor.getScrolledVisiblePosition({ lineNumber, column: 1 });
		return pos?.top ?? 0;
	}

	export function onDidScroll(callback: () => void): { dispose: () => void } | null {
		if (!editor) return null;
		return editor.onDidScrollChange(callback);
	}
</script>

<div bind:this={containerEl} class={cn('h-full w-full', className)}></div>
