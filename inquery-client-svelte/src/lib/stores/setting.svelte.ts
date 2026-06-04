/**
 * Setting store - Svelte 5 Runes (replaces Zustand setting store)
 */

import configService from '$lib/service/config';

export interface IAiConfig {
	aiSqlSource?: string;
	apiKey?: string;
	apiHost?: string;
	model?: string;
	[key: string]: unknown;
}

let aiConfig = $state<IAiConfig>({ aiSqlSource: 'OPENAI' });
let holdingService = $state(false);

export function getSettingStore() {
	return {
		get aiConfig() { return aiConfig; },
		get holdingService() { return holdingService; }
	};
}

export function setAiConfig(config: IAiConfig) {
	aiConfig = config;
}

export function setHoldingService(value: boolean) {
	holdingService = value;
}

export async function getAiSystemConfig() {
	try {
		const res = await configService.getAiSystemConfig({});
		if (res) setAiConfig(res as IAiConfig);
	} catch (error) {
		console.error('Failed to get AI config:', error);
	}
}
