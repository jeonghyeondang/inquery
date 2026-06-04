export enum ThemeType {
	Light = 'light',
	Dark = 'dark',
	FollowOs = 'followOs'
}

export enum LangType {
	EN_US = 'en-us',
	KO_KR = 'ko-kr',
	JA_JP = 'ja-jp',
	TR_TR = 'tr-tr'
}

export const primaryColors = [
	'indigo',
	'violet',
	'fuchsia',
	'rose',
	'sky',
	'teal',
	'emerald',
	'amber',
	'slate'
] as const;

export type PrimaryColor = (typeof primaryColors)[number];
