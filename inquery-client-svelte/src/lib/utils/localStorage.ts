import { LangType, ThemeType } from '$lib/types/constants';

export function getLang(): LangType | undefined {
	if (typeof localStorage === 'undefined') return undefined;
	return (localStorage.getItem('inquery-lang') as LangType) || undefined;
}

export function setLang(lang: LangType) {
	if (typeof localStorage === 'undefined') return;
	localStorage.setItem('inquery-lang', lang);
}

export function getTheme(): string | undefined {
	if (typeof localStorage === 'undefined') return undefined;
	return localStorage.getItem('inquery-theme') || undefined;
}

export function setTheme(theme: string) {
	if (typeof localStorage === 'undefined') return;
	localStorage.setItem('inquery-theme', theme);
}

export function getPrimaryColor(): string {
	if (typeof localStorage === 'undefined') return 'indigo';
	return localStorage.getItem('primary-color') || 'indigo';
}

export function setPrimaryColor(color: string) {
	if (typeof localStorage === 'undefined') return;
	localStorage.setItem('primary-color', color);
	document.documentElement.setAttribute('primary-color', color);
}
