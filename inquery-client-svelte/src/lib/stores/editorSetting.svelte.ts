/**
 * Editor Settings store - Svelte 5 Runes (replaces Zustand editorSetting store)
 * Persisted to localStorage
 */

export type EditorThemeType =
	| 'dracula' | 'github-dark' | 'github-light' | 'monokai-bright' | 'monokai'
	| 'one-dark' | 'solarized-dark' | 'solarized-light' | 'xcode'
	| 'vs' | 'vs-dark' | 'hc-light' | 'hc-black';

export interface IEditorSettings {
	editorTheme: EditorThemeType;
	darkModeEditorTheme: EditorThemeType;
	lightModeEditorTheme: EditorThemeType;
	fontFamily: string;
	customFont: string;
	fontSize: number;
	lineHeight: number;
	showLineNumbers: boolean;
	showMinimap: boolean;
	wordWrap: boolean;
	codeFolding: boolean;
	lineHighlight: 'none' | 'gutter' | 'line' | 'all';
	stickyScroll: boolean;
	keywordCase: 'upper' | 'lower';
	errorContinue: boolean;
}

export const defaultEditorSettings: IEditorSettings = {
	editorTheme: 'vs-dark',
	darkModeEditorTheme: 'vs-dark',
	lightModeEditorTheme: 'vs',
	fontFamily: 'Consolas',
	customFont: '',
	fontSize: 14,
	lineHeight: 1.6,
	showLineNumbers: true,
	showMinimap: false,
	wordWrap: true,
	codeFolding: true,
	lineHighlight: 'line',
	stickyScroll: true,
	keywordCase: 'upper',
	errorContinue: true
};

let settings = $state<IEditorSettings>({ ...defaultEditorSettings });

// Load from localStorage on init
if (typeof localStorage !== 'undefined') {
	try {
		const saved = localStorage.getItem('editor-settings');
		if (saved) {
			const parsed = JSON.parse(saved);
			if (parsed?.settings) {
				settings = { ...defaultEditorSettings, ...parsed.settings };
			}
		}
	} catch { /* ignore */ }
}

function persist() {
	if (typeof localStorage !== 'undefined') {
		localStorage.setItem('editor-settings', JSON.stringify({ settings }));
	}
}

export function getEditorSettingStore() {
	return {
		get settings() { return settings; }
	};
}

export function setEditorSettings(partial: Partial<IEditorSettings>) {
	settings = { ...settings, ...partial };
	persist();
}

export function syncEditorThemeWithAppTheme(isDarkMode: boolean) {
	const newTheme = isDarkMode ? settings.darkModeEditorTheme : settings.lightModeEditorTheme;
	if (settings.editorTheme !== newTheme) {
		settings = { ...settings, editorTheme: newTheme };
		persist();
	}
}

export function setEditorThemeForCurrentMode(theme: EditorThemeType, isDarkMode: boolean) {
	if (isDarkMode) {
		settings = { ...settings, editorTheme: theme, darkModeEditorTheme: theme };
	} else {
		settings = { ...settings, editorTheme: theme, lightModeEditorTheme: theme };
	}
	persist();
}

export function resetEditorSettings() {
	settings = { ...defaultEditorSettings };
	persist();
}

export function getMonacoOptions(s: IEditorSettings) {
	return {
		theme: s.editorTheme,
		fontFamily: s.customFont || `'${s.fontFamily}', monospace`,
		fontSize: s.fontSize,
		lineHeight: s.fontSize * s.lineHeight,
		lineNumbers: s.showLineNumbers ? 'on' as const : 'off' as const,
		minimap: { enabled: s.showMinimap },
		wordWrap: s.wordWrap ? 'on' as const : 'off' as const,
		folding: s.codeFolding,
		renderLineHighlight: s.lineHighlight,
		stickyScroll: { enabled: s.stickyScroll }
	};
}
