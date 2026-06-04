import { getLang } from '$lib/utils/localStorage';
import { LangType } from '$lib/types/constants';
import enUS from './en-us';
import koKR from './ko-kr';
import jaJP from './ja-jp';
import trTR from './tr-tr';

const locale: Record<string, Record<string, string>> = {
	'en-us': enUS,
	'ko-kr': koKR,
	'ja-jp': jaJP,
	'tr-tr': trTR
};

export const currentLang: LangType = getLang() || LangType.EN_US;
export const isEn = currentLang === LangType.EN_US;
export const isKO = currentLang === LangType.KO_KR;
export const isJA = currentLang === LangType.JA_JP;
export const isTR = currentLang === LangType.TR_TR;

const langSet: Record<string, string> = locale[currentLang] || locale['en-us'];

function i18n(key: string, ...args: unknown[]): string {
	let result = langSet[key];
	if (result === undefined) {
		return `[${key}]`;
	}
	args.forEach((arg, i) => {
		result = result.replace(new RegExp(`\\{${i + 1}\\}`, 'g'), String(arg));
	});
	if (args.length) {
		result = result.replace(/\{(.+?)\|(.+?)\}/g, (_, singular, plural) => {
			const n = args[0];
			return n == 1 ? singular : plural;
		});
	}
	return result;
}

export default i18n;
export { i18n };
