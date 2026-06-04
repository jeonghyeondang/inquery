/**
 * Custom Monaco Editor theme definitions
 * Ported from React project
 */
import type * as Monaco from 'monaco-editor';

type IStandaloneThemeData = Monaco.editor.IStandaloneThemeData;

export const draculaTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '6272a4' },
		{ token: 'keyword', foreground: 'ff79c6' },
		{ token: 'string', foreground: 'f1fa8c' },
		{ token: 'number', foreground: 'bd93f9' },
		{ token: 'type', foreground: '8be9fd', fontStyle: 'italic' },
		{ token: 'predefined.sql', foreground: '50fa7b' },
		{ token: 'operator.sql', foreground: 'ff79c6' }
	],
	colors: {
		'editor.background': '#282a36',
		'editor.foreground': '#f8f8f2',
		'editor.lineHighlightBackground': '#44475a',
		'editor.selectionBackground': '#44475a',
		'editorCursor.foreground': '#f8f8f2',
		'editorGutter.background': '#282a36',
		'editorSuggestWidget.background': '#21222c',
		'editorSuggestWidget.border': '#44475a',
		'editorSuggestWidget.foreground': '#f8f8f2',
		'editorSuggestWidget.highlightForeground': '#8be9fd',
		'editorSuggestWidget.selectedBackground': '#44475a',
		'editorSuggestWidget.focusHighlightForeground': '#bee4fd'
	}
};

export const githubDarkTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '8b949e' },
		{ token: 'keyword', foreground: 'ff7b72' },
		{ token: 'string', foreground: 'a5d6ff' },
		{ token: 'number', foreground: '79c0ff' },
		{ token: 'type', foreground: 'ffa657' }
	],
	colors: {
		'editor.background': '#0d1117',
		'editor.foreground': '#c9d1d9',
		'editor.lineHighlightBackground': '#161b22',
		'editor.selectionBackground': '#264f78',
		'editorSuggestWidget.background': '#161b22',
		'editorSuggestWidget.border': '#30363d',
		'editorSuggestWidget.foreground': '#c9d1d9',
		'editorSuggestWidget.highlightForeground': '#58a6ff',
		'editorSuggestWidget.selectedBackground': '#30363d',
		'editorSuggestWidget.focusHighlightForeground': '#a5d6ff'
	}
};

export const githubLightTheme: IStandaloneThemeData = {
	base: 'vs',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '6a737d' },
		{ token: 'keyword', foreground: 'd73a49' },
		{ token: 'string', foreground: '032f62' },
		{ token: 'number', foreground: '005cc5' },
		{ token: 'type', foreground: 'e36209' }
	],
	colors: {
		'editor.background': '#ffffff',
		'editor.foreground': '#24292e',
		'editor.lineHighlightBackground': '#f6f8fa',
		'editor.selectionBackground': '#c8e1ff',
		'editorSuggestWidget.background': '#ffffff',
		'editorSuggestWidget.border': '#e1e4e8',
		'editorSuggestWidget.foreground': '#24292e',
		'editorSuggestWidget.highlightForeground': '#0366d6',
		'editorSuggestWidget.selectedBackground': '#c7dbf5',
		'editorSuggestWidget.selectedForeground': '#1a1a1a',
		'editorSuggestWidget.focusHighlightForeground': '#003380'
	}
};

export const monokaiTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '75715e' },
		{ token: 'keyword', foreground: 'f92672' },
		{ token: 'string', foreground: 'e6db74' },
		{ token: 'number', foreground: 'ae81ff' },
		{ token: 'type', foreground: '66d9ef', fontStyle: 'italic' }
	],
	colors: {
		'editor.background': '#272822',
		'editor.foreground': '#f8f8f2',
		'editor.lineHighlightBackground': '#3e3d32',
		'editor.selectionBackground': '#49483e',
		'editorSuggestWidget.background': '#1e1f1c',
		'editorSuggestWidget.border': '#3e3d32',
		'editorSuggestWidget.foreground': '#f8f8f2',
		'editorSuggestWidget.highlightForeground': '#66d9ef',
		'editorSuggestWidget.selectedBackground': '#3e3d32',
		'editorSuggestWidget.focusHighlightForeground': '#a6ecf7'
	}
};

export const oneDarkTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '5c6370' },
		{ token: 'keyword', foreground: 'c678dd' },
		{ token: 'string', foreground: '98c379' },
		{ token: 'number', foreground: 'd19a66' },
		{ token: 'type', foreground: 'e5c07b' }
	],
	colors: {
		'editor.background': '#282c34',
		'editor.foreground': '#abb2bf',
		'editor.lineHighlightBackground': '#2c313c',
		'editor.selectionBackground': '#3e4451',
		'editorSuggestWidget.background': '#21252b',
		'editorSuggestWidget.border': '#3e4451',
		'editorSuggestWidget.foreground': '#abb2bf',
		'editorSuggestWidget.highlightForeground': '#61afef',
		'editorSuggestWidget.selectedBackground': '#2c313c',
		'editorSuggestWidget.focusHighlightForeground': '#a0d2f7'
	}
};

export const solarizedDarkTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '586e75' },
		{ token: 'keyword', foreground: '859900' },
		{ token: 'string', foreground: '2aa198' },
		{ token: 'number', foreground: 'd33682' },
		{ token: 'type', foreground: 'b58900' }
	],
	colors: {
		'editor.background': '#002b36',
		'editor.foreground': '#839496',
		'editor.lineHighlightBackground': '#073642',
		'editor.selectionBackground': '#073642',
		'editorSuggestWidget.background': '#00212b',
		'editorSuggestWidget.border': '#073642',
		'editorSuggestWidget.foreground': '#839496',
		'editorSuggestWidget.highlightForeground': '#2aa198',
		'editorSuggestWidget.selectedBackground': '#073642',
		'editorSuggestWidget.focusHighlightForeground': '#6ec8c0'
	}
};

export const solarizedLightTheme: IStandaloneThemeData = {
	base: 'vs',
	inherit: true,
	rules: [
		{ token: 'comment', foreground: '93a1a1' },
		{ token: 'keyword', foreground: '859900' },
		{ token: 'string', foreground: '2aa198' },
		{ token: 'number', foreground: 'd33682' },
		{ token: 'type', foreground: 'b58900' }
	],
	colors: {
		'editor.background': '#fdf6e3',
		'editor.foreground': '#657b83',
		'editor.lineHighlightBackground': '#eee8d5',
		'editor.selectionBackground': '#eee8d5',
		'editorSuggestWidget.background': '#fdf6e3',
		'editorSuggestWidget.border': '#eee8d5',
		'editorSuggestWidget.foreground': '#657b83',
		'editorSuggestWidget.highlightForeground': '#268bd2',
		'editorSuggestWidget.selectedBackground': '#d6cdb5',
		'editorSuggestWidget.selectedForeground': '#3b4d54',
		'editorSuggestWidget.focusHighlightForeground': '#1a6091'
	}
};

export const vsDefaultDarkTheme: IStandaloneThemeData = {
	base: 'vs-dark',
	inherit: true,
	rules: [],
	colors: {
		'editorSuggestWidget.background': '#1e1e1e',
		'editorSuggestWidget.border': '#333333',
		'editorSuggestWidget.foreground': '#d4d4d4',
		'editorSuggestWidget.highlightForeground': '#18a3ff',
		'editorSuggestWidget.selectedBackground': '#04395e',
		'editorSuggestWidget.focusHighlightForeground': '#7fcdff'
	}
};

export const vsDefaultLightTheme: IStandaloneThemeData = {
	base: 'vs',
	inherit: true,
	rules: [],
	colors: {
		'editorSuggestWidget.background': '#ffffff',
		'editorSuggestWidget.border': '#e5e7eb',
		'editorSuggestWidget.foreground': '#1f2937',
		'editorSuggestWidget.highlightForeground': '#0066bf',
		'editorSuggestWidget.selectedBackground': '#c7dbf5',
		'editorSuggestWidget.selectedForeground': '#1a1a1a',
		'editorSuggestWidget.focusHighlightForeground': '#003380'
	}
};

export const themes: Record<string, IStandaloneThemeData> = {
	dracula: draculaTheme,
	'github-dark': githubDarkTheme,
	'github-light': githubLightTheme,
	monokai: monokaiTheme,
	'monokai-bright': monokaiTheme,
	'one-dark': oneDarkTheme,
	'solarized-dark': solarizedDarkTheme,
	'solarized-light': solarizedLightTheme,
	'vs-dark': vsDefaultDarkTheme,
	'vs': vsDefaultLightTheme
};

let themesRegistered = false;

export function registerCustomThemes(monaco: typeof Monaco) {
	if (themesRegistered) return;
	Object.entries(themes).forEach(([name, data]) => {
		monaco.editor.defineTheme(name, data);
	});
	themesRegistered = true;
}
