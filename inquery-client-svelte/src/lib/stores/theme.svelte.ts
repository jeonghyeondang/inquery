import { ThemeType } from '$lib/types/constants';
import { getTheme, setTheme as saveTheme, getPrimaryColor } from '$lib/utils/localStorage';
import { syncEditorThemeWithAppTheme } from '$lib/stores/editorSetting.svelte';

/**
 * Theme store - Svelte 5 Runes (replaces useTheme hook + Zustand)
 */
let currentTheme = $state<ThemeType>(ThemeType.Light);
let initialized = $state(false);

function resolveEffectiveTheme(theme: ThemeType): ThemeType {
	if (theme === ThemeType.FollowOs) {
		if (typeof window !== 'undefined') {
			return window.matchMedia('(prefers-color-scheme: dark)').matches
				? ThemeType.Dark
				: ThemeType.Light;
		}
		return ThemeType.Light;
	}
	return theme;
}

export function getThemeStore() {
	return {
		get theme() {
			return currentTheme;
		},
		get isDark() {
			return currentTheme === ThemeType.Dark;
		},
		get initialized() {
			return initialized;
		}
	};
}

export function initTheme() {
	if (typeof window === 'undefined') return;
	const saved = getTheme() as ThemeType | undefined;
	const theme = saved || ThemeType.Light;
	const effective = resolveEffectiveTheme(theme);
	currentTheme = effective;

	applyThemeToDOM(effective);
	syncEditorThemeWithAppTheme(effective === ThemeType.Dark);
	initialized = true;

	// Monitor OS theme changes
	const matchMedia = window.matchMedia('(prefers-color-scheme: dark)');
	matchMedia.onchange = (e) => {
		const originalTheme = getTheme();
		if (originalTheme === ThemeType.FollowOs) {
			const newTheme = e.matches ? ThemeType.Dark : ThemeType.Light;
			currentTheme = newTheme;
			applyThemeToDOM(newTheme);
			syncEditorThemeWithAppTheme(newTheme === ThemeType.Dark);
		}
	};
}

export function setAppTheme(theme: ThemeType) {
	saveTheme(theme);
	const effective = resolveEffectiveTheme(theme);
	currentTheme = effective;
	applyThemeToDOM(effective);
	syncEditorThemeWithAppTheme(effective === ThemeType.Dark);
}

function applyThemeToDOM(theme: ThemeType) {
	if (typeof document === 'undefined') return;

	const primaryColor = getPrimaryColor();
	document.documentElement.setAttribute('primary-color', primaryColor);
	document.documentElement.setAttribute('theme', theme);

	if (theme === ThemeType.Dark) {
		document.documentElement.classList.add('dark');
	} else {
		document.documentElement.classList.remove('dark');
	}
}
