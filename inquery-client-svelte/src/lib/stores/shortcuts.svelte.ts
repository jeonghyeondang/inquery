/**
 * Keyboard Shortcuts store - Svelte 5 Runes
 * Central management for all customizable keyboard shortcuts.
 * Persisted to localStorage.
 */

export interface ShortcutDef {
	id: string;
	label: string;
	category: 'General' | 'Navigation' | 'Workspace' | 'Editor';
	keys: ShortcutKeys;
	defaultKeys: ShortcutKeys;
}

export interface ShortcutKeys {
	key: string;
	mod?: boolean;   // Cmd (Mac) / Ctrl (Win/Linux)
	shift?: boolean;
	alt?: boolean;
}

const isMac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);

export function getModLabel(): string {
	return isMac ? '⌘' : 'Ctrl';
}

export function formatKeys(keys: ShortcutKeys): string {
	const parts: string[] = [];
	if (keys.mod) parts.push(getModLabel());
	if (keys.alt) parts.push('Alt');
	if (keys.shift) parts.push('Shift');
	parts.push(displayKey(keys.key));
	return parts.join(' + ');
}

function displayKey(key: string): string {
	const map: Record<string, string> = {
		Enter: '↵',
		ArrowLeft: '←',
		ArrowRight: '→',
		ArrowUp: '↑',
		ArrowDown: '↓',
		' ': 'Space',
		Escape: 'Esc',
	};
	return map[key] ?? (key.length === 1 ? key.toUpperCase() : key);
}

function makeDefaults(): ShortcutDef[] {
	const defs: Omit<ShortcutDef, 'defaultKeys'>[] = [
		// General
		{ id: 'command-palette', label: 'Command Palette', category: 'General', keys: { key: 'k', mod: true } },
		{ id: 'settings', label: 'Settings', category: 'General', keys: { key: ',', mod: true } },

		// Navigation (Alt + number)
		{ id: 'nav-workspace', label: 'Go to Workspace', category: 'Navigation', keys: { key: '1', alt: true } },
		{ id: 'nav-connections', label: 'Go to Connections', category: 'Navigation', keys: { key: '2', alt: true } },
		{ id: 'nav-team', label: 'Go to Team', category: 'Navigation', keys: { key: '3', alt: true } },
		{ id: 'nav-catalog', label: 'Go to Data Catalog', category: 'Navigation', keys: { key: '4', alt: true } },
		{ id: 'nav-dashboard', label: 'Go to Dashboard', category: 'Navigation', keys: { key: '5', alt: true } },
		{ id: 'nav-ai-chat', label: 'Go to AI Chat', category: 'Navigation', keys: { key: '6', alt: true } },
		{ id: 'nav-setting', label: 'Go to Settings', category: 'Navigation', keys: { key: '7', alt: true } },

		// Editor
		{ id: 'run-query', label: 'Run Query', category: 'Editor', keys: { key: 'Enter', mod: true } },
		{ id: 'save-console', label: 'Save', category: 'Editor', keys: { key: 's', mod: true } },
		{ id: 'new-console', label: 'New Console', category: 'Editor', keys: { key: 'l', mod: true, shift: true } },
	];

	return defs.map(d => ({ ...d, defaultKeys: { ...d.keys } }));
}

const STORAGE_KEY = 'inquery-shortcuts';

let shortcuts = $state<ShortcutDef[]>(makeDefaults());

// Load from localStorage on init
if (typeof localStorage !== 'undefined') {
	try {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved) {
			const parsed: Record<string, ShortcutKeys> = JSON.parse(saved);
			const defaults = makeDefaults();
			shortcuts = defaults.map(def => {
				const custom = parsed[def.id];
				return custom ? { ...def, keys: { ...custom } } : def;
			});
		}
	} catch { /* ignore */ }
}

function persist() {
	if (typeof localStorage === 'undefined') return;
	const map: Record<string, ShortcutKeys> = {};
	for (const s of shortcuts) {
		if (!keysEqual(s.keys, s.defaultKeys)) {
			map[s.id] = s.keys;
		}
	}
	if (Object.keys(map).length === 0) {
		localStorage.removeItem(STORAGE_KEY);
	} else {
		localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
	}
}

export function keysEqual(a: ShortcutKeys, b: ShortcutKeys): boolean {
	return a.key === b.key
		&& !!a.mod === !!b.mod
		&& !!a.shift === !!b.shift
		&& !!a.alt === !!b.alt;
}

/** Check if a KeyboardEvent matches a specific shortcut */
export function matchesShortcut(e: KeyboardEvent, id: string): boolean {
	const def = shortcuts.find(s => s.id === id);
	if (!def) return false;
	return matchesKeys(e, def.keys);
}

export function matchesKeys(e: KeyboardEvent, keys: ShortcutKeys): boolean {
	const modPressed = e.metaKey || e.ctrlKey;
	if (!!keys.mod !== modPressed) return false;
	if (!!keys.shift !== e.shiftKey) return false;
	if (!!keys.alt !== e.altKey) return false;
	return e.key.toLowerCase() === keys.key.toLowerCase();
}

/** Get grouped shortcuts by category */
export function getShortcutsByCategory(): Record<string, ShortcutDef[]> {
	const groups: Record<string, ShortcutDef[]> = {};
	for (const s of shortcuts) {
		if (!groups[s.category]) groups[s.category] = [];
		groups[s.category].push(s);
	}
	return groups;
}

export function getShortcutStore() {
	return {
		get all() { return shortcuts; },
		get grouped() { return getShortcutsByCategory(); }
	};
}

/** Update a shortcut's keys. Returns conflicting shortcut id if conflict found. */
export function updateShortcut(id: string, newKeys: ShortcutKeys): string | null {
	const conflict = shortcuts.find(s => s.id !== id && keysEqual(s.keys, newKeys));
	if (conflict) return conflict.id;

	shortcuts = shortcuts.map(s =>
		s.id === id ? { ...s, keys: { ...newKeys } } : s
	);
	persist();
	return null;
}

export function resetShortcut(id: string) {
	shortcuts = shortcuts.map(s =>
		s.id === id ? { ...s, keys: { ...s.defaultKeys } } : s
	);
	persist();
}

export function resetAllShortcuts() {
	shortcuts = makeDefaults();
	persist();
}

/** Convert a KeyboardEvent to ShortcutKeys (for recording) */
export function eventToKeys(e: KeyboardEvent): ShortcutKeys | null {
	const ignoredKeys = ['Control', 'Meta', 'Shift', 'Alt', 'CapsLock', 'Tab'];
	if (ignoredKeys.includes(e.key)) return null;

	return {
		key: e.key,
		mod: e.metaKey || e.ctrlKey || undefined,
		shift: e.shiftKey || undefined,
		alt: e.altKey || undefined,
	};
}

export function getShortcutById(id: string): ShortcutDef | undefined {
	return shortcuts.find(s => s.id === id);
}
