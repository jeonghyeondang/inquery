<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import i18n from '$lib/i18n';
	import { Button, Card, Separator, Badge, Popover, PopoverTrigger, PopoverContent, Switch } from '$lib/components/ui';
	import {
		X, Sun, Moon, Monitor, Check, Keyboard, LogOut, Database,
		Key, Server, FolderTree, Tag, ExternalLink, Globe, RefreshCw,
		CheckCircle2, AlertTriangle, ChevronDown, ChevronRight, Bot, Plug,
		Loader2, XCircle, HelpCircle, Sparkles, Hash, Eye, EyeOff, Lock,
		FileText, Upload, Trash2, Download
	} from 'lucide-svelte';
	import { SiOpenai, SiGooglegemini } from '@icons-pack/svelte-simple-icons';
	import { getUserStore } from '$lib/stores/user.svelte';
	import { getThemeStore, setAppTheme } from '$lib/stores/theme.svelte';
	import { getSettingStore, getAiSystemConfig, setAiConfig } from '$lib/stores/setting.svelte';
	import { getEditorSettingStore, setEditorSettings, resetEditorSettings, setEditorThemeForCurrentMode, type IEditorSettings, type EditorThemeType } from '$lib/stores/editorSetting.svelte';
	import { ThemeType, LangType } from '$lib/types/constants';
	import { setLang, getLang } from '$lib/utils/localStorage';
	import { userLogout, changePassword } from '$lib/service/user';
	import message from '$lib/utils/message';
	import confirmDialog from '$lib/utils/confirmDialog';
	import configService, { type IUserAIConfig } from '$lib/service/config';
	import referenceDocumentService, { type IReferenceDocumentMeta } from '$lib/service/referenceDocument';
	import { getBaseURL } from '$lib/service/base';
	import { databaseMap } from '$lib/types/database';
	import connectionService from '$lib/service/connection';

	import MonacoEditor from '$lib/components/MonacoEditor/MonacoEditor.svelte';
	import {
		getShortcutStore, updateShortcut, resetShortcut, resetAllShortcuts,
		formatKeys, keysEqual, eventToKeys, getModLabel,
		type ShortcutDef, type ShortcutKeys
	} from '$lib/stores/shortcuts.svelte';

	const userStore = getUserStore();
	const themeStore = getThemeStore();
	const settingStore = getSettingStore();
	const editorStore = getEditorSettingStore();
	const shortcutStore = getShortcutStore();

	let currentTab = $state('basic');
	let loggingOut = $state(false);

	// ─── Basic: Accent Colors ───
	const accentColors = [
		{ code: 'indigo', name: 'Indigo', color: '#6366f1', gradient: 'linear-gradient(135deg, #818cf8 0%, #6366f1 50%, #4f46e5 100%)' },
		{ code: 'violet', name: 'Violet', color: '#8b5cf6', gradient: 'linear-gradient(135deg, #a78bfa 0%, #8b5cf6 50%, #7c3aed 100%)' },
		{ code: 'fuchsia', name: 'Fuchsia', color: '#d946ef', gradient: 'linear-gradient(135deg, #e879f9 0%, #d946ef 50%, #c026d3 100%)' },
		{ code: 'rose', name: 'Rose', color: '#f43f5e', gradient: 'linear-gradient(135deg, #fb7185 0%, #f43f5e 50%, #e11d48 100%)' },
		{ code: 'sky', name: 'Sky', color: '#0ea5e9', gradient: 'linear-gradient(135deg, #38bdf8 0%, #0ea5e9 50%, #0284c7 100%)' },
		{ code: 'teal', name: 'Teal', color: '#14b8a6', gradient: 'linear-gradient(135deg, #2dd4bf 0%, #14b8a6 50%, #0d9488 100%)' },
		{ code: 'emerald', name: 'Emerald', color: '#10b981', gradient: 'linear-gradient(135deg, #34d399 0%, #10b981 50%, #059669 100%)' },
		{ code: 'amber', name: 'Amber', color: '#f59e0b', gradient: 'linear-gradient(135deg, #fbbf24 0%, #f59e0b 50%, #d97706 100%)' },
		{ code: 'slate', name: 'Slate', color: '#64748b', gradient: 'linear-gradient(135deg, #94a3b8 0%, #64748b 50%, #475569 100%)' },
		{ code: 'zinc', name: 'Zinc', color: '#71717a', gradient: 'linear-gradient(135deg, #a1a1aa 0%, #71717a 50%, #52525b 100%)' }
	];
	let selectedAccent = $state<string | null>(null);

	function handleAccentChange(color: typeof accentColors[number]) {
		selectedAccent = color.code;
		if (typeof document !== 'undefined') {
			document.documentElement.setAttribute('primary-color', color.code);
			localStorage.setItem('primary-color', color.code);
		}
	}

	// ─── Editor Settings ───
	const editorThemeOptions = [
		{ value: 'vs', label: 'Light (VS)' },
		{ value: 'vs-dark', label: 'Dark (VS Dark)' },
		{ value: 'dracula', label: 'Dracula' },
		{ value: 'github-dark', label: 'Github Dark' },
		{ value: 'github-light', label: 'Github Light' },
		{ value: 'monokai-bright', label: 'Monokai Bright' },
		{ value: 'monokai', label: 'Monokai' },
		{ value: 'one-dark', label: 'One Dark' },
		{ value: 'solarized-dark', label: 'Solarized Dark' },
		{ value: 'solarized-light', label: 'Solarized Light' },
		{ value: 'xcode', label: 'Xcode' },
		{ value: 'hc-light', label: 'High Contrast Light' },
		{ value: 'hc-black', label: 'High Contrast Black' }
	];
	const fontFamilyOptions = [
		{ value: 'Fira Code', label: 'Fira Code' },
		{ value: 'JetBrains Mono', label: 'JetBrains Mono' },
		{ value: 'Source Code Pro', label: 'Source Code Pro' },
		{ value: 'IBM Plex Mono', label: 'IBM Plex Mono' },
		{ value: 'Hack', label: 'Hack' },
		{ value: 'Inconsolata', label: 'Inconsolata' },
		{ value: 'Roboto Mono', label: 'Roboto Mono' },
		{ value: 'Ubuntu Mono', label: 'Ubuntu Mono' },
		{ value: 'Anonymous Pro', label: 'Anonymous Pro' },
		{ value: 'Consolas', label: 'Consolas (Windows)' },
		{ value: 'Monaco', label: 'Monaco (Mac)' },
		{ value: 'SF Mono', label: 'SF Mono (Mac)' }
	];
	const lineHighlightOptions = [
		{ value: 'none', label: 'None' },
		{ value: 'gutter', label: 'Gutter' },
		{ value: 'line', label: 'Line' },
		{ value: 'all', label: 'All' }
	];

	const sampleSQL = `-- =============================================
-- Inquery SQL Editor Preview
-- Test your editor settings with this sample
-- =============================================

-- Create tables with various data types
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    metadata JSONB
);

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    user_id INT REFERENCES users(id),
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    order_date DATE NOT NULL
);

-- Insert sample data
INSERT INTO users (username, email, metadata)
VALUES 
    ('john_doe', 'john@example.com', '{"role": "admin"}'),
    ('jane_smith', 'jane@example.com', '{"role": "user"}'),
    ('bob_wilson', 'bob@example.com', '{"role": "user"}');

-- Complex query with JOIN, aggregation, and subquery
SELECT 
    u.username,
    u.email,
    COUNT(o.id) AS order_count,
    COALESCE(SUM(o.total_amount), 0) AS total_spent,
    CASE 
        WHEN SUM(o.total_amount) > 1000 THEN 'VIP'
        WHEN SUM(o.total_amount) > 500 THEN 'Regular'
        ELSE 'New'
    END AS customer_tier
FROM users u
LEFT JOIN orders o ON u.id = o.user_id
WHERE u.is_active = true
  AND u.created_at >= '2024-01-01'
GROUP BY u.id, u.username, u.email
HAVING COUNT(o.id) > 0
ORDER BY total_spent DESC
LIMIT 10;

-- CTE (Common Table Expression) example
WITH monthly_sales AS (
    SELECT 
        DATE_TRUNC('month', order_date) AS month,
        SUM(total_amount) AS revenue
    FROM orders
    WHERE status = 'completed'
    GROUP BY DATE_TRUNC('month', order_date)
),
growth_rate AS (
    SELECT 
        month,
        revenue,
        LAG(revenue) OVER (ORDER BY month) AS prev_revenue,
        ROUND((revenue - LAG(revenue) OVER (ORDER BY month)) 
            / NULLIF(LAG(revenue) OVER (ORDER BY month), 0) * 100, 2) AS growth_pct
    FROM monthly_sales
)
SELECT * FROM growth_rate WHERE growth_pct IS NOT NULL;

-- Window function example
SELECT 
    username,
    order_date,
    total_amount,
    SUM(total_amount) OVER (
        PARTITION BY user_id 
        ORDER BY order_date 
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS running_total,
    ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY order_date) AS order_num
FROM orders o
JOIN users u ON o.user_id = u.id;

-- Update with conditional logic
UPDATE users
SET metadata = jsonb_set(
    metadata,
    '{last_login}',
    to_jsonb(CURRENT_TIMESTAMP::TEXT)
)
WHERE is_active = true;

-- Delete with subquery
DELETE FROM orders
WHERE user_id IN (
    SELECT id FROM users WHERE is_active = false
);`;

	let isDarkMode = $derived(themeStore.theme === ThemeType.Dark);
	let currentModeLabel = $derived(isDarkMode ? i18n('setting.text.dark') : i18n('setting.text.light'));

	// Theme list for Basic tab
	const themeList = [
		{ code: ThemeType.Light, name: () => i18n('setting.text.light'), icon: Sun },
		{ code: ThemeType.Dark, name: () => i18n('setting.text.dark'), icon: Moon },
		{ code: ThemeType.FollowOs, name: () => i18n('setting.text.followOS'), icon: Monitor }
	];

	function handleEditorChange<K extends keyof IEditorSettings>(key: K, value: IEditorSettings[K]) {
		if (key === 'editorTheme') {
			setEditorThemeForCurrentMode(value as EditorThemeType, isDarkMode);
		} else {
			setEditorSettings({ [key]: value });
		}
	}

	function handleResetEditorSettings() {
		resetEditorSettings();
		setEditorThemeForCurrentMode(isDarkMode ? 'vs-dark' : 'vs', isDarkMode);
		message.success(i18n('setting.editor.resetSuccess'));
	}

	// ─── Custom Select (using bits-ui Popover) ───
	let edThemeOpen = $state(false);
	let edFontOpen = $state(false);
	let edHighlightOpen = $state(false);
	let aiModelOpen = $state(false);

	// ─── AI Config ───
	interface IAIProviderConfig { apiKey: string; apiHost: string; model: string; enabled: boolean; }

	const providerStyles: Record<string, { color: string; bgColor: string }> = {
		OPENAI: { color: '#10a37f', bgColor: 'rgba(16, 163, 127, 0.1)' },
		CLAUDEAI: { color: '#d97706', bgColor: 'rgba(217, 119, 6, 0.1)' },
		GEMINI: { color: '#4285f4', bgColor: 'rgba(66, 133, 244, 0.1)' }
	};

	let aiProviders = $state<Record<string, IAIProviderConfig>>({
		OPENAI: { apiKey: '', apiHost: '', model: '', enabled: true },
		CLAUDEAI: { apiKey: '', apiHost: '', model: '', enabled: true },
		GEMINI: { apiKey: '', apiHost: '', model: '', enabled: true }
	});
	let expandedProviders = $state<Set<string>>(new Set());
	let aiTestResults = $state<Record<string, { success: boolean; message: string } | null>>({});
	let aiTestingProvider = $state<string | null>(null);
	let aiSaving = $state(false);
	let aiMessage = $state<{ type: 'success' | 'error' | 'warning'; text: string } | null>(null);
	let aiActiveTab = $state<'custom' | 'integration'>('custom');
	let aiLoading = $state(true);

	const aiProviderInfo: Record<string, { name: string; desc: string; placeholder: string; iconPath?: string }> = {
		OPENAI: { name: 'OpenAI', desc: 'GPT-5, o3-mini and more', placeholder: 'sk-...' },
		CLAUDEAI: { name: 'Claude', desc: 'Claude Sonnet 4.6, Opus 4.7 and more', placeholder: 'sk-ant-...', iconPath: '/icons/claude.svg' },
		GEMINI: { name: 'Gemini', desc: 'Gemini 3.5 Flash, Pro and more', placeholder: 'AIza...' }
	};

	async function loadAllAiProviders() {
		aiLoading = true;
		const providers = ['OPENAI', 'CLAUDEAI', 'GEMINI'];
		try {
			await Promise.all(providers.map(async (key) => {
				try {
					const res = await configService.getAiSystemConfig({ aiSqlSource: key }) as any;
					// API Host is not used by the Claude provider in the
					//   backend; only seed the model default so the input
					//   isn't blank for new users.
					if (key === 'CLAUDEAI' && !res.model) {
						res.model = 'claude-sonnet-4-6';
					}
					// `enabled` may be missing on legacy backends; default to true
					//   so an upgrade doesn't accidentally disable a provider.
					const enabled = res.enabled === false ? false : true;
					aiProviders[key] = { apiKey: res.apiKey || '', apiHost: res.apiHost || '', model: res.model || '', enabled };
				} catch { /* keep defaults */ }
			}));
		} catch (e) { console.error(e); }
		finally { aiLoading = false; }
	}

	function toggleProvider(key: string) {
		const next = new Set(expandedProviders);
		if (next.has(key)) next.delete(key); else next.add(key);
		expandedProviders = next;
	}

	async function testAiProvider(provider: string) {
		const config = aiProviders[provider];
		if (!config.apiKey) return;
		aiTestingProvider = provider;
		aiTestResults[provider] = null;
		try {
			const result = await configService.testAiApiKey({ aiSqlSource: provider, apiKey: config.apiKey, apiHost: config.apiHost, model: config.model });
			aiTestResults[provider] = result;
		} catch (e: any) {
			aiTestResults[provider] = { success: false, message: e?.message || i18n('setting.message.testFailed') };
		}
		finally { aiTestingProvider = null; }
	}

	async function saveAllAiProviders() {
		aiSaving = true;
		aiMessage = null;
		try {
			// Partition entries by intent:
			//   - keysToValidate: user typed an apiKey -> validate then save
			//   - keysToClear:    apiKey is blank but DB has an old value
			//                     -> treat as explicit "remove this key"
			//                     (Apply sends an empty string to the backend
			//                     so the persisted config matches the UI.)
			const entries = Object.entries(aiProviders);
			const keysToValidate = entries.filter(([_, c]) => c.apiKey.trim() !== '');
			const keysToClear = await (async () => {
				const blanks = entries.filter(([_, c]) => c.apiKey.trim() === '');
				if (blanks.length === 0) return [] as typeof entries;
				// Only clear providers that actually have something persisted.
				// Untouched + never-configured rows shouldn't trigger writes.
				const existing = await Promise.all(blanks.map(async ([key]) => {
					try {
						const cur = await configService.getAiSystemConfig({ aiSqlSource: key }) as any;
						return cur?.apiKey ? key : null;
					} catch { return null; }
				}));
				const dirty = new Set(existing.filter((k): k is string => !!k));
				return blanks.filter(([k]) => dirty.has(k));
			})();

			if (keysToValidate.length === 0 && keysToClear.length === 0) {
				aiMessage = { type: 'warning', text: i18n('setting.message.configureProvider') };
				aiSaving = false;
				return;
			}

			// Step 1: Validate the keys that the user filled in.
			const validationResults = await Promise.all(
				keysToValidate.map(async ([key, config]) => {
					try {
						const result = await configService.testAiApiKey({
							aiSqlSource: key, apiKey: config.apiKey, apiHost: config.apiHost, model: config.model
						});
						return { key, config, valid: result?.success ?? false, message: result?.message };
					} catch (e: any) {
						return { key, config, valid: false, message: e?.message || i18n('setting.message.validationFailed') };
					}
				})
			);

			const validProviders = validationResults.filter(r => r.valid);
			const invalidProviders = validationResults.filter(r => !r.valid);

			// Step 2a: Save only valid providers (with non-empty keys).
			//   `enabled` is included so a save round-trips the toggle state
			//   that the user might have flipped before clicking Apply.
			let savedCount = 0;
			await Promise.all(validProviders.map(async ({ key, config }) => {
				try {
					await configService.setAiSystemConfig({
						aiSqlSource: key,
						apiKey: config.apiKey,
						apiHost: config.apiHost,
						model: config.model,
						enabled: config.enabled,
					});
					savedCount++;
					aiTestResults[key] = { success: true, message: i18n('setting.ai.testValid') };
				} catch { /* skip */ }
			}));

			// Step 2b: Clear providers whose key the user emptied. We send
			//   blank apiKey/apiHost/model so the backend persists the
			//   removal. Skip test-API call since there's no key to test.
			//   Reset enabled to true so a re-added key starts active.
			let clearedCount = 0;
			await Promise.all(keysToClear.map(async ([key]) => {
				try {
					await configService.setAiSystemConfig({
						aiSqlSource: key, apiKey: '', apiHost: '', model: '', enabled: true,
					});
					clearedCount++;
					aiTestResults[key] = null;
				} catch { /* skip */ }
			}));

			// Mark invalid ones in UI
			for (const inv of invalidProviders) {
				aiTestResults[inv.key] = { success: false, message: inv.message || i18n('setting.ai.testInvalidKey') };
			}

			const successParts: string[] = [];
			if (savedCount > 0) successParts.push(`saved ${savedCount} provider${savedCount > 1 ? 's' : ''}`);
			if (clearedCount > 0) successParts.push(`cleared ${clearedCount}`);
			const successText = successParts.length > 0 ? successParts.join(', ') : 'no changes';

			if (invalidProviders.length === 0) {
				aiMessage = { type: 'success', text: `Successfully ${successText}.` };
			} else if (savedCount > 0 || clearedCount > 0) {
				aiMessage = { type: 'warning', text: `${successText.charAt(0).toUpperCase() + successText.slice(1)}. ${invalidProviders.length} failed validation: ${invalidProviders.map(p => p.key).join(', ')}` };
			} else {
				aiMessage = { type: 'error', text: `All ${invalidProviders.length} provider(s) failed validation` };
			}
		} catch (e: any) { aiMessage = { type: 'error', text: e?.message || i18n('setting.message.saveFailed') }; }
		finally { aiSaving = false; }
	}

	// Explicit "remove key" action shown as a per-provider button. Clears
	//   the persisted apiKey/apiHost/model and the in-memory UI state
	//   atomically so the Configured badge disappears immediately.
	async function removeAiProvider(provider: string) {
		const confirmed = await confirmDialog({
			title: 'Remove API Key',
			message: `Remove the ${provider} API key? You can re-enter it later.`,
			confirmText: 'Remove',
			variant: 'destructive'
		});
		if (!confirmed) return;
		aiSaving = true;
		try {
			await configService.setAiSystemConfig({
				aiSqlSource: provider, apiKey: '', apiHost: '', model: '', enabled: true,
			});
			aiProviders[provider] = { apiKey: '', apiHost: '', model: '', enabled: true };
			aiTestResults[provider] = null;
			aiMessage = { type: 'success', text: `${provider} API key removed.` };
		} catch (e: any) {
			aiMessage = { type: 'error', text: e?.message || i18n('setting.message.removeKeyFailed') };
		} finally {
			aiSaving = false;
		}
	}

	// Toggle the per-provider enabled flag. Persists immediately so the
	//   backend router (ChatController.pickPreferredProvider) sees the
	//   change without waiting for an Apply click. We only allow toggling
	//   when a key is actually configured — disabling an empty slot has
	//   no effect on routing and would just confuse users.
	async function toggleAiProviderEnabled(provider: string) {
		const cfg = aiProviders[provider];
		if (!cfg || !cfg.apiKey.trim()) return;
		const next = !cfg.enabled;
		aiProviders[provider] = { ...cfg, enabled: next };
		try {
			await configService.setAiSystemConfig({
				aiSqlSource: provider,
				apiKey: cfg.apiKey,
				apiHost: cfg.apiHost,
				model: cfg.model,
				enabled: next,
			});
			aiMessage = { type: 'success', text: `${provider} ${next ? 'enabled' : 'disabled'}.` };
		} catch (e: any) {
			aiProviders[provider] = { ...cfg, enabled: !next };
			aiMessage = { type: 'error', text: e?.message || i18n('setting.message.updateFlagFailed') };
		}
	}

	let configuredAiCount = $derived(Object.values(aiProviders).filter(c => c.apiKey.trim()).length);

	// ─── AI Integration ───
	const DEFAULT_AI_CONFIG: IUserAIConfig = {
		confluenceBaseUrl: '', confluenceUsername: '', confluenceApiToken: '',
		jiraBaseUrl: '', jiraUsername: '', jiraApiToken: '',
		slackUserToken: '',
		githubToken: '', githubBaseUrl: '', githubOrganization: '',
		outlookTenantId: '', outlookClientId: '', outlookClientSecret: '', outlookUserEmail: '',
		googleClientId: '', googleClientSecret: '',
		geminiModel: 'gemini-3.5-flash', // Default Gemini model - matches backend ModelMapper.getDefaultPrimaryModel()
		dbtIntegrationType: 'git',
		dbtGitRepoUrl: '', dbtGitBranch: 'main', dbtProjectPath: '', dbtGitToken: '',
		dbtManifestUrl: '', dbtCatalogUrl: '', dbtArtifactToken: '',
		dbtCloudBaseUrl: 'https://cloud.getdbt.com', dbtCloudAccountId: '', dbtCloudProjectId: '',
		dbtCloudEnvironmentId: '', dbtCloudJobId: '', dbtCloudApiToken: ''
	};

	let integrationFormData = $state<IUserAIConfig>({ ...DEFAULT_AI_CONFIG });
	let visibleTokens = $state<Set<string>>(new Set());
	let integrationLoading = $state(false);
	let integrationSaving = $state(false);
	let integrationMessage = $state<{ type: 'success' | 'error'; text: string } | null>(null);
	let configuredServices = $state<Set<string>>(new Set());
	let expandedService = $state<string | null>(null);
	let modelOptions = $state<Array<{ label: string; value: string }>>([]);

	let referenceDocuments = $state<IReferenceDocumentMeta[]>([]);
	let referenceDocsUsedBytes = $state(0);
	let referenceDocsQuotaBytes = $state(5 * 1024 * 1024 * 1024);
	let referenceDocsLoading = $state(false);
	let referenceDocsUploading = $state(false);
	let referenceDocDeletingId = $state<number | null>(null);
	let referenceDocReindexingId = $state<number | null>(null);
	let referenceDocInput = $state<HTMLInputElement | null>(null);

	function formatBytes(bytes: number): string {
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
		if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
		return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
	}

	async function loadReferenceDocuments() {
		referenceDocsLoading = true;
		try {
			const result = await referenceDocumentService.listDocuments();
			referenceDocuments = result.documents ?? [];
			referenceDocsUsedBytes = result.usedBytes ?? 0;
			referenceDocsQuotaBytes = result.quotaBytes ?? referenceDocsQuotaBytes;
			const next = new Set(configuredServices);
			if (referenceDocuments.length > 0) {
				next.add('documents');
			} else {
				next.delete('documents');
			}
			configuredServices = next;
		} catch (e: any) {
			console.error('Failed to load reference documents:', e);
		} finally {
			referenceDocsLoading = false;
		}
	}

	async function handleReferenceDocumentUpload(event: Event) {
		const input = event.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		referenceDocsUploading = true;
		integrationMessage = null;
		try {
			await referenceDocumentService.uploadDocument(file);
			integrationMessage = { type: 'success', text: `Uploaded "${file.name}" and indexed for search.` };
			await loadReferenceDocuments();
		} catch (e: any) {
			integrationMessage = { type: 'error', text: e?.message || i18n('setting.message.uploadFailed') };
		} finally {
			referenceDocsUploading = false;
			input.value = '';
		}
	}

	async function handleReferenceDocumentDownload(doc: IReferenceDocumentMeta) {
		try {
			await referenceDocumentService.downloadDocument(doc.id, doc.filename);
		} catch (e: any) {
			integrationMessage = { type: 'error', text: e?.message || i18n('setting.message.downloadFailed') };
		}
	}

	async function handleReferenceDocumentReindex(doc: IReferenceDocumentMeta) {
		referenceDocReindexingId = doc.id;
		integrationMessage = null;
		try {
			await referenceDocumentService.reindexDocument(doc.id);
			integrationMessage = { type: 'success', text: `Re-indexed "${doc.filename}".` };
			await loadReferenceDocuments();
		} catch (e: any) {
			integrationMessage = { type: 'error', text: e?.message || i18n('setting.message.reindexFailed') };
			await loadReferenceDocuments();
		} finally {
			referenceDocReindexingId = null;
		}
	}

	async function handleReferenceDocumentDelete(doc: IReferenceDocumentMeta) {
		const confirmed = await confirmDialog({
			title: 'Delete Document',
			message: `Delete "${doc.filename}"? This removes the file, database record, and all indexed vectors.`,
			confirmText: 'Delete',
			variant: 'destructive'
		});
		if (!confirmed) return;
		referenceDocDeletingId = doc.id;
		integrationMessage = null;
		try {
			await referenceDocumentService.deleteDocument(doc.id);
			integrationMessage = { type: 'success', text: `Deleted "${doc.filename}".` };
			await loadReferenceDocuments();
		} catch (e: any) {
			integrationMessage = { type: 'error', text: e?.message || i18n('setting.message.deleteFailed') };
		} finally {
			referenceDocDeletingId = null;
		}
	}

	async function loadIntegrationConfig() {
		integrationLoading = true;
		try {
			const config = await configService.getUserAIConfig() as any;
			const filtered = Object.fromEntries(Object.entries(config || {}).filter(([_, v]) => v != null));
			integrationFormData = { ...DEFAULT_AI_CONFIG, ...filtered } as IUserAIConfig;
			const configured = new Set<string>();
			if (integrationFormData.confluenceApiToken) configured.add('confluence');
			if (integrationFormData.jiraApiToken) configured.add('jira');
			if (integrationFormData.slackUserToken) configured.add('slack');
			if (integrationFormData.githubToken) configured.add('github');
			if (integrationFormData.outlookConnected) configured.add('outlook');
			if (integrationFormData.googleConnected) configured.add('google');
			if (
				integrationFormData.dbtGitRepoUrl
				|| integrationFormData.dbtManifestUrl
				|| integrationFormData.dbtCatalogUrl
				|| integrationFormData.dbtCloudAccountId
				|| integrationFormData.dbtCloudApiToken
			) configured.add('dbt');
			configuredServices = configured;
		} catch (e: any) {
			if (e?.response?.status !== 404) console.error('Failed to load config:', e);
			integrationFormData = { ...DEFAULT_AI_CONFIG };
		} finally {
			integrationLoading = false;
			await loadReferenceDocuments();
		}
	}

	async function loadAvailableModels() {
		const models: Array<{ label: string; value: string }> = [];
		const providers = [
			{ key: 'OPENAI', default: 'gpt-5.5', label: 'OpenAI' },
			{ key: 'CLAUDEAI', default: 'claude-sonnet-4-6', label: 'Claude' },
			{ key: 'GEMINI', default: 'gemini-3.5-flash', label: 'Gemini' }
		];
		for (const p of providers) {
			try {
				const cfg = await configService.getAiSystemConfig({ aiSqlSource: p.key }) as any;
				if (cfg?.apiKey?.trim()) {
					models.push({ label: `${p.label}: ${cfg.model || p.default}`, value: cfg.model || p.default });
				}
			} catch { /* skip */ }
		}
		if (models.length === 0) {
			models.push({ label: 'gemini-3.5-flash (default)', value: 'gemini-3.5-flash' });
		}
		modelOptions = models;
	}

	async function saveIntegration() {
		integrationSaving = true;
		integrationMessage = null;
		try {
			const cleaned = Object.fromEntries(
				Object.entries(integrationFormData).filter(([_, v]) => v != null && v !== '')
			);
			await configService.setUserAIConfig(cleaned);
			integrationMessage = { type: 'success', text: i18n('setting.message.saved') };
			loadIntegrationConfig();
		} catch (e: any) {
			integrationMessage = { type: 'error', text: e?.message || i18n('setting.message.saveFailed') };
		} finally { integrationSaving = false; }
	}

	async function handleOutlookConnect() {
		try {
			if (!integrationFormData.outlookTenantId?.trim() || !integrationFormData.outlookClientId?.trim()
				|| !integrationFormData.outlookClientSecret?.trim()) {
				integrationMessage = { type: 'error', text: i18n('setting.message.outlookCredentialsRequired') };
				return;
			}
			await configService.setUserAIConfig({
				outlookTenantId: integrationFormData.outlookTenantId,
				outlookClientId: integrationFormData.outlookClientId,
				outlookClientSecret: integrationFormData.outlookClientSecret,
				outlookUserEmail: integrationFormData.outlookUserEmail
			});
			const token = typeof window !== 'undefined' ? localStorage.getItem('Inquery') : null;
			const startUrl = `${getBaseURL()}/api/config/ai/outlook/oauth/start${token ? `?token=${encodeURIComponent(token)}` : ''}`;
			const popup = window.open(startUrl, 'outlook_oauth', 'width=520,height=720');
			if (!popup) { integrationMessage = { type: 'error', text: i18n('setting.message.allowPopups') }; return; }
			const onMessage = (evt: MessageEvent) => {
				if (evt?.data?.type === 'OUTLOOK_OAUTH') {
					window.removeEventListener('message', onMessage);
					loadIntegrationConfig();
					integrationMessage = { type: evt.data.status === 'success' ? 'success' : 'error', text: evt.data.status === 'success' ? i18n('setting.message.outlookConnected') : i18n('setting.message.connectionFailed') };
				}
			};
			window.addEventListener('message', onMessage);
		} catch (e) { console.error('Outlook connect failed:', e); }
	}

	async function handleOutlookDisconnect() {
		try {
			await configService.disconnectOutlookOAuth();
			await loadIntegrationConfig();
			integrationMessage = { type: 'success', text: i18n('setting.message.outlookDisconnected') };
		} catch (e) { console.error('Outlook disconnect failed:', e); }
	}

	async function handleGoogleConnect() {
		try {
			if (!integrationFormData.googleClientId || !integrationFormData.googleClientSecret) {
				integrationMessage = { type: 'error', text: i18n('setting.message.googleCredentialsRequired') };
				return;
			}
			await configService.setUserAIConfig({
				googleClientId: integrationFormData.googleClientId,
				googleClientSecret: integrationFormData.googleClientSecret
			});
			// Popups can't send the auth header, so pass the JWT as a query param
			// (the backend JwtAuthenticationFilter also reads the `token` parameter).
			const token = typeof window !== 'undefined' ? localStorage.getItem('Inquery') : null;
			const startUrl = `${getBaseURL()}/api/config/ai/google/oauth/start${token ? `?token=${encodeURIComponent(token)}` : ''}`;
			const popup = window.open(startUrl, 'google_oauth', 'width=520,height=720');
			if (!popup) { integrationMessage = { type: 'error', text: i18n('setting.message.allowPopups') }; return; }
			const onMessage = (evt: MessageEvent) => {
				if (evt?.data?.type === 'GOOGLE_OAUTH') {
					window.removeEventListener('message', onMessage);
					loadIntegrationConfig();
					integrationMessage = { type: evt.data.status === 'success' ? 'success' : 'error', text: evt.data.status === 'success' ? i18n('setting.message.googleConnected') : i18n('setting.message.connectionFailed') };
				}
			};
			window.addEventListener('message', onMessage);
		} catch (e) { console.error('Google connect failed:', e); }
	}

	async function handleGoogleDisconnect() {
		try {
			await configService.disconnectGoogleOAuth();
			await loadIntegrationConfig();
			integrationMessage = { type: 'success', text: i18n('setting.message.googleDisconnected') };
		} catch (e) { console.error('Google disconnect failed:', e); }
	}

	// ─── Vector DB (multi-provider) ───
	let vectorDbType = $state('pgvector');
	let pineconeConfig = $state({ apiKey: '', host: '', indexName: 'table-schemas', namespace: 'default' });
	let qdrantConfig = $state({ host: '', port: '6333', apiKey: '', collectionName: 'table-schemas', useTls: 'false' });
	let qdrantMode = $state<'self-hosted' | 'cloud'>('self-hosted');
	let pineconeTestStatus = $state<'idle' | 'testing' | 'success' | 'error'>('idle');
	let pineconeLoading = $state(false);
	let pineconeIsConfigured = $state(false);
	let pineconeMessage = $state<{ type: 'success' | 'error'; text: string } | null>(null);
	let vectorDbMessage = $state<{ type: 'success' | 'error'; text: string } | null>(null);
	let vectorDbSaving = $state(false);

	async function loadVectorDbType() {
		try {
			const result = await configService.getVectorDbType() as any;
			if (result?.type) vectorDbType = result.type;
		} catch { /* keep default */ }
	}

	async function selectVectorDbType(type: string) {
		vectorDbType = type;
		vectorDbMessage = null;
		try {
			await configService.setVectorDbType({ type });
			vectorDbMessage = { type: 'success', text: `Switched to ${type === 'pinecone' ? 'Pinecone' : type === 'pgvector' ? 'pgvector' : 'Qdrant'}` };
		} catch { vectorDbMessage = { type: 'error', text: 'Failed to switch provider' }; }
	}

	async function loadPineconeConfig() {
		try {
			const config = await configService.getPineconeConfig() as any;
			if (config) {
				pineconeConfig = {
					apiKey: config.apiKey ?? '',
					host: config.host ?? '',
					indexName: config.indexName ?? 'table-schemas',
					namespace: config.namespace ?? 'default'
				};
				pineconeIsConfigured = !!config.apiKey;
			}
		} catch { /* keep defaults */ }
	}

	async function loadQdrantConfig() {
		try {
			const config = await configService.getQdrantConfig() as any;
			if (config) {
				qdrantConfig = {
					host: config.host ?? '',
					port: config.port ?? '6333',
					apiKey: config.apiKey ?? '',
					collectionName: config.collectionName ?? 'table-schemas',
					useTls: config.useTls ?? 'false'
				};
				if (config.apiKey || (config.host && config.host !== 'localhost' && config.host !== '127.0.0.1')) {
					qdrantMode = 'cloud';
				}
			}
		} catch { /* keep defaults */ }
	}

	async function testPineconeConnection() {
		if (!pineconeConfig.apiKey) {
			pineconeMessage = { type: 'error', text: i18n('setting.message.enterApiKeyFirst') };
			return;
		}
		if (!pineconeIsConfigured) {
			pineconeMessage = { type: 'error', text: 'Please save configuration before testing' };
			return;
		}
		pineconeTestStatus = 'testing';
		pineconeMessage = null;
		try {
			const result = await configService.testVectorDbConnection({ type: 'pinecone' }) as any;
			if (result && typeof result === 'object') {
				pineconeTestStatus = result.success ? 'success' : 'error';
				pineconeMessage = { type: result.success ? 'success' : 'error', text: result.message || (result.success ? 'Connected' : 'Test failed') };
			} else {
				pineconeTestStatus = 'error';
				pineconeMessage = { type: 'error', text: 'Unexpected response from server' };
			}
		} catch (e: any) {
			pineconeTestStatus = 'error';
			const msg = e?.errorMessage || e?.message || 'Connection test failed';
			pineconeMessage = { type: 'error', text: msg };
		}
	}

	async function savePineconeConfig() {
		if (!pineconeConfig.apiKey) {
			pineconeMessage = { type: 'error', text: i18n('setting.message.pineconeApiKeyRequired') };
			return;
		}
		pineconeLoading = true;
		pineconeMessage = null;
		try {
			await configService.setPineconeConfig(pineconeConfig);
			pineconeMessage = { type: 'success', text: 'Configuration saved successfully!' };
			await loadPineconeConfig();
		} catch (e: any) {
			let msg = 'Failed to save configuration';
			if (e?.response?.status === 404) msg = 'API endpoint not found. Please restart the server.';
			else if (e?.response?.status === 403) msg = 'Permission denied. Admin access required.';
			pineconeMessage = { type: 'error', text: msg };
		} finally { pineconeLoading = false; }
	}

	async function saveQdrantConfig() {
		const configToSave = { ...qdrantConfig };
		if (qdrantMode === 'self-hosted') {
			configToSave.host = configToSave.host || 'localhost';
			configToSave.port = configToSave.port || '6333';
			configToSave.apiKey = '';
			configToSave.useTls = 'false';
		} else if (!configToSave.host) {
			vectorDbMessage = { type: 'error', text: 'Qdrant Cloud host URL is required' };
			return;
		}
		vectorDbSaving = true;
		vectorDbMessage = null;
		try {
			await configService.setQdrantConfig(configToSave);
			vectorDbMessage = { type: 'success', text: 'Qdrant configuration saved!' };
		} catch {
			vectorDbMessage = { type: 'error', text: 'Failed to save Qdrant configuration' };
		} finally { vectorDbSaving = false; }
	}

	async function testQdrantConnection() {
		vectorDbMessage = null;
		vectorDbSaving = true;
		try {
			const result = await configService.testVectorDbConnection({ type: 'qdrant' }) as any;
			vectorDbMessage = { type: result?.success ? 'success' : 'error', text: result?.message || 'Test completed' };
		} catch {
			vectorDbMessage = { type: 'error', text: 'Connection test failed' };
		} finally { vectorDbSaving = false; }
	}

	async function testPgvectorConnection() {
		vectorDbMessage = null;
		vectorDbSaving = true;
		try {
			const result = await configService.testVectorDbConnection({ type: 'pgvector' }) as any;
			vectorDbMessage = { type: result?.success ? 'success' : 'error', text: result?.message || 'Test completed' };
		} catch {
			vectorDbMessage = { type: 'error', text: 'Connection test failed' };
		} finally { vectorDbSaving = false; }
	}

	let vectorDbLoaded = false;
	$effect(() => {
		if (currentTab === 'vectordb' && !vectorDbLoaded) {
			vectorDbLoaded = true;
			loadVectorDbType();
			loadPineconeConfig();
			loadQdrantConfig();
		}
	});

	// ─── Slack Tab ───
	let slackConfig = $state({
		enabled: false, connected: false, botToken: '', appToken: '',
		defaultDataSourceId: '' as string, defaultDatabase: '', defaultSchema: '', defaultModel: ''
	});
	let slackTestStatus = $state<'idle' | 'testing' | 'success' | 'error'>('idle');
	let slackTestMessage = $state('');

	// Token prefix validation
	let botTokenError = $derived(
		slackConfig.botToken && !slackConfig.botToken.includes('***') && !slackConfig.botToken.startsWith('xoxb-')
			? slackConfig.botToken.startsWith('xoxp-')
				? 'This is a User Token (xoxp-). Bot Token must start with xoxb-.'
				: 'Bot Token must start with xoxb-.'
			: ''
	);
	let appTokenError = $derived(
		slackConfig.appToken && !slackConfig.appToken.includes('***') && !slackConfig.appToken.startsWith('xapp-')
			? 'App Token must start with xapp-.'
			: ''
	);
	let slackLoading = $state(false);
	let slackSaving = $state(false);
	let slackMessage = $state<{ type: 'success' | 'error'; text: string } | null>(null);
	let slackAvailableModels = $state<Array<{ label: string; value: string }>>([]);
	let slackConnections = $state<any[]>([]);
	let slackDsPopoverOpen = $state(false);
	let slackModelPopoverOpen = $state(false);
	let showBotToken = $state(false);
	let showAppToken = $state(false);

	async function loadSlackConfig() {
		slackLoading = true;
		try {
			const config = await configService.getSlackConfig() as any;
			if (config) {
				slackConfig = {
					enabled: config.enabled ?? false,
					connected: config.connected ?? false,
					botToken: config.botToken ?? '',
					appToken: config.appToken ?? '',
					defaultDataSourceId: config.defaultDataSourceId ? String(config.defaultDataSourceId) : '',
					defaultDatabase: config.defaultDatabase ?? '',
					defaultSchema: config.defaultSchema ?? '',
					defaultModel: config.defaultModel ?? ''
				};
			}
		} catch { /* keep defaults */ }
		try {
			const models = await configService.getSlackAvailableModels() as any;
			slackAvailableModels = Array.isArray(models) ? models : [];
		} catch { slackAvailableModels = []; }
		// Load connections for data source selector
		try {
			const { default: connectionService } = await import('$lib/service/connection');
			const res = await connectionService.getList({}) as any;
			slackConnections = Array.isArray(res?.data) ? res.data : (Array.isArray(res) ? res : []);
		} catch { slackConnections = []; }
		finally { slackLoading = false; }
	}

	async function handleTestSlack() {
		if (!slackConfig.botToken) return;
		if (botTokenError) {
			slackTestStatus = 'error';
			slackTestMessage = botTokenError;
			return;
		}
		slackTestStatus = 'testing';
		slackTestMessage = '';
		try {
			const result = await configService.testSlackConnection({ botToken: slackConfig.botToken }) as any;
			if (result?.success) {
				slackTestStatus = 'success';
				slackTestMessage = result.message || 'Connected!';
			} else {
				slackTestStatus = 'error';
				slackTestMessage = result?.message || 'Connection test failed';
			}
		} catch {
			slackTestStatus = 'error';
			slackTestMessage = 'Connection test failed';
		}
	}

	async function handleSaveSlack() {
		slackSaving = true;
		slackMessage = null;
		try {
			const result = await configService.setSlackConfig(slackConfig) as any;
			if (result?.success !== false) {
				slackMessage = { type: 'success', text: i18n('setting.message.saved') };
				await loadSlackConfig();
			} else {
				slackMessage = { type: 'error', text: result?.errorMessage || 'Failed to save' };
			}
		} catch {
			slackMessage = { type: 'error', text: 'Failed to save settings' };
		} finally { slackSaving = false; }
	}

	let slackLoaded = false;
	$effect(() => {
		if (currentTab === 'slack' && !slackLoaded) {
			slackLoaded = true;
			loadSlackConfig();
		}
	});

	// ─── Proxy ───
	let proxyUrl = $state(typeof window !== 'undefined' ? (localStorage.getItem('_BaseURL') || (window as any)._BaseURL || '') : '');
	let proxyTesting = $state(false);
	let proxyMessage = $state('');

	async function applyProxy() {
		if (!proxyUrl) { proxyMessage = 'Please enter a URL'; return; }
		proxyTesting = true;
		proxyMessage = '';
		try {
			const res = await fetch(`${proxyUrl}/api/system/get-version-a`);
			if (res.ok) { localStorage.setItem('_BaseURL', proxyUrl); window.location.reload(); }
			else { proxyMessage = i18n('setting.proxy.testFailed'); }
		} catch { proxyMessage = i18n('setting.proxy.testFailed'); }
		finally { proxyTesting = false; }
	}

	// ─── Tabs ───
	const tabs = [
		{ key: 'basic', label: () => i18n('setting.nav.basic') },
		{ key: 'editor', label: () => i18n('setting.nav.editor') },
		{ key: 'ai', label: () => i18n('setting.nav.ai') },
		{ key: 'vectordb', label: () => i18n('setting.nav.vectordb') },
		{ key: 'slack', label: () => i18n('setting.nav.slack') },
		{ key: 'proxy', label: () => i18n('setting.nav.proxy') },
		{ key: 'shortcuts', label: () => i18n('setting.nav.shortcuts') },
		{ key: 'profile', label: () => i18n('setting.nav.profile') }
	];

	// ─── Shortcuts ───
	const modKey = getModLabel();
	let recordingId = $state<string | null>(null);
	let recordedKeys = $state<ShortcutKeys | null>(null);
	let conflictId = $state<string | null>(null);

	function startRecording(id: string) {
		recordingId = id;
		recordedKeys = null;
		conflictId = null;
	}

	function cancelRecording() {
		recordingId = null;
		recordedKeys = null;
		conflictId = null;
	}

	function handleShortcutKeydown(e: KeyboardEvent) {
		if (!recordingId) return;
		e.preventDefault();
		e.stopImmediatePropagation();

		if (e.key === 'Escape') {
			cancelRecording();
			return;
		}

		const keys = eventToKeys(e);
		if (!keys) return;

		recordedKeys = keys;
		const conflict = shortcutStore.all.find(
			s => s.id !== recordingId && keysEqual(s.keys, keys)
		);
		conflictId = conflict?.id ?? null;
	}

	$effect(() => {
		if (!recordingId) return;
		const handler = (e: KeyboardEvent) => handleShortcutKeydown(e);
		window.addEventListener('keydown', handler, true);
		return () => window.removeEventListener('keydown', handler, true);
	});

	function confirmRecording() {
		if (!recordingId || !recordedKeys) return;
		updateShortcut(recordingId, recordedKeys);
		cancelRecording();
	}

	function handleResetShortcut(id: string) {
		resetShortcut(id);
		if (recordingId === id) cancelRecording();
	}

	function handleResetAll() {
		resetAllShortcuts();
		cancelRecording();
	}

	onMount(() => {
		loadAllAiProviders();
		loadIntegrationConfig();
		loadAvailableModels();
		// Load saved accent color
		const savedColor = localStorage.getItem('primary-color');
		if (savedColor) selectedAccent = savedColor;
		// Load saved theme preference (including FollowOs)
		const savedThemeVal = localStorage.getItem('inquery-theme');
		if (savedThemeVal) {
			savedTheme = savedThemeVal as ThemeType;
		}
	});

	let savedTheme = $state<ThemeType>(ThemeType.Light);

	function handleClose() { goto('/workspace'); }
	function handleThemeChange(theme: ThemeType) {
		savedTheme = theme;
		setAppTheme(theme);
	}
	function handleLangChange(lang: LangType) { setLang(lang); window.location.reload(); }
	async function handleLogout() {
		loggingOut = true;
		try {
			await userLogout();
			localStorage.removeItem('Inquery');
			goto('/login');
		} catch { console.error('Failed to logout'); }
		finally { loggingOut = false; }
	}

	// ─── Change password (Profile tab) ───
	let pwCurrent = $state('');
	let pwNew = $state('');
	let pwConfirm = $state('');
	let pwShowCurrent = $state(false);
	let pwShowNew = $state(false);
	let pwShowConfirm = $state(false);
	let pwSubmitting = $state(false);
	let pwError = $state('');
	let pwSuccess = $state('');

	const PW_MIN_LEN = 6;
	const PW_MAX_LEN = 64;

	// We intentionally don't disable the submit button on every validation
	// failure — silent disabled buttons make users wonder "why won't it click?".
	// Instead we let the click through and surface the exact reason via inline
	// error messages from `handleChangePassword`.

	function resetPasswordForm() {
		pwCurrent = '';
		pwNew = '';
		pwConfirm = '';
		pwShowCurrent = false;
		pwShowNew = false;
		pwShowConfirm = false;
	}

	async function handleChangePassword() {
		pwError = '';
		pwSuccess = '';
		// Defensive client-side validation (the derived `pwCanSubmit` already
		// keeps the button disabled, but users can still submit via Enter).
		if (!pwCurrent) { pwError = 'Please enter your current password.'; return; }
		if (pwNew.length < PW_MIN_LEN || pwNew.length > PW_MAX_LEN) {
			pwError = `New password must be ${PW_MIN_LEN}–${PW_MAX_LEN} characters.`;
			return;
		}
		if (pwNew === pwCurrent) {
			pwError = 'New password must differ from the current one.';
			return;
		}
		if (pwConfirm !== pwNew) { pwError = 'New passwords do not match.'; return; }

		pwSubmitting = true;
		try {
			await changePassword({ currentPassword: pwCurrent, newPassword: pwNew });
			pwSuccess = 'Password updated.';
			resetPasswordForm();
			message.success(i18n('setting.message.passwordUpdated'));
		} catch (e: any) {
			pwError = e?.errorMessage || e?.message || 'Failed to change password.';
		} finally {
			pwSubmitting = false;
		}
	}

	let currentLang = $state<LangType>(getLang() || LangType.EN_US);

	const languageOptions: { key: LangType; label: string; flag: string }[] = [
		{ key: LangType.EN_US, label: 'English', flag: '🇺🇸' },
		{ key: LangType.KO_KR, label: '한국어', flag: '🇰🇷' },
		{ key: LangType.JA_JP, label: '日本語', flag: '🇯🇵' },
		{ key: LangType.TR_TR, label: 'Türkçe', flag: '🇹🇷' }
	];

	// (bits-ui Popover handles outside click automatically)
</script>

<div class="relative h-full w-full overflow-hidden bg-background">
	<button onclick={handleClose} class="absolute right-4 top-4 z-10 flex h-10 w-10 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors">
		<X class="h-5 w-5" />
	</button>

	<div class="mx-auto h-full max-w-5xl overflow-y-auto px-6 py-8 [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]">
		<div class="mb-8">
			<h1 class="text-3xl font-bold tracking-tight">{i18n('setting.title.setting')}</h1>
			<p class="mt-2 text-muted-foreground">{i18n('setting.page.description')}</p>
		</div>

		<!-- Tab Navigation -->
		<div class="flex flex-wrap gap-1 mb-6">
			{#each tabs as tab}
				<button onclick={() => { currentTab = tab.key; }}
					class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors
						{currentTab === tab.key ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}">
					{tab.label()}
				</button>
			{/each}
		</div>
		<Separator class="mb-6" />

		<div class="pb-8">
			<!-- ═══════ BASIC ═══════ -->
			{#if currentTab === 'basic'}
				<div class="space-y-4">
					<!-- Theme Selection -->
					<Card>
						<div class="px-3 pb-3 pt-3">
							<h4 class="text-sm font-medium">{i18n('setting.title.backgroundColor')}</h4>
						</div>
						<div class="px-3 pb-3">
							<div class="grid grid-cols-3 gap-2">
								{#each themeList as t}
									{@const Icon = t.icon}
									{@const isSelected = savedTheme === t.code}
									<button onclick={() => handleThemeChange(t.code)}
										class="flex flex-col items-center gap-1.5 rounded-md border p-3 transition-all {isSelected ? 'border-primary bg-primary/5' : 'border-transparent bg-muted/50 hover:bg-muted'}">
										<div class="flex h-8 w-8 items-center justify-center rounded-full {isSelected ? 'bg-primary text-primary-foreground' : 'bg-background'}">
											<Icon class="h-4 w-4" />
										</div>
										<span class="text-xs font-medium">{t.name()}</span>
									</button>
								{/each}
							</div>
						</div>
					</Card>

					<!-- Language Selection -->
					<Card>
						<div class="px-3 pb-3 pt-3">
							<h4 class="text-sm font-medium">{i18n('setting.title.language')}</h4>
						</div>
						<div class="px-3 pb-3">
							<div class="flex flex-wrap gap-3">
								{#each languageOptions as lang}
									<button onclick={() => handleLangChange(lang.key)}
										class="flex items-center gap-2 rounded-md border-2 px-4 py-2.5 transition-all {currentLang === lang.key ? 'border-primary bg-primary/5' : 'border-transparent bg-muted/50 hover:bg-muted'}">
										<span class="text-xl">{lang.flag}</span>
										<span class="text-sm font-medium">{lang.label}</span>
									</button>
								{/each}
							</div>
						</div>
					</Card>

					<!-- Accent Color -->
					<Card>
						<div class="px-3 pb-3 pt-3">
							<h4 class="text-sm font-medium">{i18n('setting.title.themeColor')}</h4>
						</div>
						<div class="px-3 pb-3">
							<div class="flex flex-wrap gap-2">
								{#each accentColors as color}
									<button onclick={() => handleAccentChange(color)}
										class="relative flex h-8 w-8 items-center justify-center rounded-full transition-transform hover:scale-110 {selectedAccent === color.code ? 'ring-2 ring-offset-2 ring-offset-background' : ''}"
										style="background: {color.gradient}"
										title={color.name}>
										{#if selectedAccent === color.code}
											<Check class="h-4 w-4 text-white drop-shadow-md" />
										{/if}
									</button>
								{/each}
							</div>
						</div>
					</Card>
				</div>

			<!-- ═══════ EDITOR (2-Panel) ═══════ -->
			{:else if currentTab === 'editor'}
				<div class="flex gap-4">
					<!-- Left Panel - Settings -->
					<div class="w-[320px] shrink-0 space-y-3">
						<div class="flex items-start justify-between gap-3">
							<div>
								<h3 class="text-sm font-medium">{i18n('setting.editor.title')}</h3>
								<p class="text-xs text-muted-foreground">{i18n('setting.editor.subtitle')}</p>
							</div>
							<Button variant="outline" size="sm" class="h-7 gap-1.5 px-2 text-xs" onclick={handleResetEditorSettings}>
								<RefreshCw class="h-3 w-3" />
								{i18n('setting.button.reset')}
							</Button>
						</div>

						<!-- Appearance -->
						<Card>
							<div class="px-3 pb-2 pt-3">
								<h4 class="text-xs font-medium">{i18n('setting.editor.appearance')}</h4>
							</div>
							<div class="px-3 pb-3 space-y-0.5">
								<!-- Editor Theme -->
								<div class="flex items-center justify-between gap-2 py-2">
									<div class="flex items-center gap-1.5 shrink-0">
										<span class="text-xs whitespace-nowrap">{i18n('setting.editor.editorTheme')}</span>
										<span class="text-[10px] text-muted-foreground whitespace-nowrap">({currentModeLabel})</span>
										<span title={i18n('setting.editor.editorThemeHelp')}><HelpCircle class="h-3 w-3 text-muted-foreground shrink-0" /></span>
									</div>
									<Popover bind:open={edThemeOpen}>
										<PopoverTrigger class="flex h-7 w-32 items-center justify-between rounded-md border border-input bg-background px-2 text-xs hover:bg-accent/50 transition-colors">
											<span class="truncate">{editorThemeOptions.find(o => o.value === editorStore.settings.editorTheme)?.label || editorStore.settings.editorTheme}</span>
											<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="end" class="w-44 p-1 max-h-60 overflow-y-auto">
											{#each editorThemeOptions as opt}
												<button onclick={() => { handleEditorChange('editorTheme', opt.value as EditorThemeType); edThemeOpen = false; }}
													class="flex w-full items-center rounded-sm px-2 py-1.5 text-xs hover:bg-accent transition-colors {editorStore.settings.editorTheme === opt.value ? 'bg-accent text-accent-foreground' : ''}">
													{opt.label}
												</button>
											{/each}
										</PopoverContent>
									</Popover>
								</div>

								<!-- Font Family -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.fontFamily')}</span>
									<Popover bind:open={edFontOpen}>
										<PopoverTrigger class="flex h-7 w-36 items-center justify-between rounded-md border border-input bg-background px-2 text-xs hover:bg-accent/50 transition-colors">
											<span class="truncate">{fontFamilyOptions.find(o => o.value === editorStore.settings.fontFamily)?.label || editorStore.settings.fontFamily}</span>
											<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="end" class="w-44 p-1 max-h-60 overflow-y-auto">
											{#each fontFamilyOptions as opt}
												<button onclick={() => { handleEditorChange('fontFamily', opt.value); edFontOpen = false; }}
													class="flex w-full items-center rounded-sm px-2 py-1.5 text-xs hover:bg-accent transition-colors {editorStore.settings.fontFamily === opt.value ? 'bg-accent text-accent-foreground' : ''}">
													{opt.label}
												</button>
											{/each}
										</PopoverContent>
									</Popover>
								</div>

								<!-- Custom Font -->
								<div class="flex items-center justify-between py-2">
									<div class="flex items-center gap-1.5">
										<span class="text-xs">{i18n('setting.editor.customFont')}</span>
										<span title={i18n('setting.editor.customFontHelp')}><HelpCircle class="h-3 w-3 text-muted-foreground" /></span>
									</div>
									<input type="text" value={editorStore.settings.customFont}
										oninput={(e) => handleEditorChange('customFont', (e.target as HTMLInputElement).value)}
										placeholder={i18n('setting.editor.customFontPlaceholder')}
										class="h-7 w-36 rounded-md border border-input bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
								</div>

								<!-- Font Size -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.fontSize')}</span>
									<div class="flex items-center gap-1.5">
										<input type="number" value={editorStore.settings.fontSize}
											oninput={(e) => handleEditorChange('fontSize', Number((e.target as HTMLInputElement).value) || 14)}
											min="10" max="32"
											class="h-7 w-16 rounded-md border border-input bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
										<span class="text-xs text-muted-foreground">px</span>
									</div>
								</div>

								<!-- Line Height -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.lineHeight')}</span>
									<div class="flex items-center gap-1.5">
										<input type="number" value={editorStore.settings.lineHeight}
											oninput={(e) => handleEditorChange('lineHeight', Number((e.target as HTMLInputElement).value) || 1.6)}
											min="1" max="3" step="0.1"
											class="h-7 w-16 rounded-md border border-input bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" />
										<span class="text-xs text-muted-foreground">px</span>
									</div>
								</div>
							</div>
						</Card>

						<!-- Display -->
						<Card>
							<div class="px-3 pb-2 pt-3">
								<h4 class="text-xs font-medium">{i18n('setting.editor.display')}</h4>
							</div>
							<div class="px-3 pb-3 space-y-0.5">
								<!-- Line Numbers -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.lineNumbers')}</span>
									<div class="flex gap-1">
										<button onclick={() => handleEditorChange('showLineNumbers', true)}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.showLineNumbers ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.show')}</button>
										<button onclick={() => handleEditorChange('showLineNumbers', false)}
											class="rounded-md px-2 py-1 text-xs transition-colors {!editorStore.settings.showLineNumbers ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.hide')}</button>
									</div>
								</div>

								<!-- Minimap -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.minimap')}</span>
									<div class="flex gap-1">
										<button onclick={() => handleEditorChange('showMinimap', true)}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.showMinimap ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.show')}</button>
										<button onclick={() => handleEditorChange('showMinimap', false)}
											class="rounded-md px-2 py-1 text-xs transition-colors {!editorStore.settings.showMinimap ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.hide')}</button>
									</div>
								</div>

								<!-- Word Wrap -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.wordWrap')}</span>
									<div class="flex gap-1">
										<button onclick={() => handleEditorChange('wordWrap', true)}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.wordWrap ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.on')}</button>
										<button onclick={() => handleEditorChange('wordWrap', false)}
											class="rounded-md px-2 py-1 text-xs transition-colors {!editorStore.settings.wordWrap ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.off')}</button>
									</div>
								</div>

								<!-- Code Folding -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.codeFolding')}</span>
									<div class="flex gap-1">
										<button onclick={() => handleEditorChange('codeFolding', true)}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.codeFolding ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.show')}</button>
										<button onclick={() => handleEditorChange('codeFolding', false)}
											class="rounded-md px-2 py-1 text-xs transition-colors {!editorStore.settings.codeFolding ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">{i18n('setting.text.hide')}</button>
									</div>
								</div>

								<!-- Line Highlight -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.lineHighlight')}</span>
									<Popover bind:open={edHighlightOpen}>
										<PopoverTrigger class="flex h-7 w-24 items-center justify-between rounded-md border border-input bg-background px-2 text-xs hover:bg-accent/50 transition-colors">
											<span>{lineHighlightOptions.find(o => o.value === editorStore.settings.lineHighlight)?.label || editorStore.settings.lineHighlight}</span>
											<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="end" class="w-28 p-1">
											{#each lineHighlightOptions as opt}
												<button onclick={() => { handleEditorChange('lineHighlight', opt.value as any); edHighlightOpen = false; }}
													class="flex w-full items-center rounded-sm px-2 py-1.5 text-xs hover:bg-accent transition-colors {editorStore.settings.lineHighlight === opt.value ? 'bg-accent text-accent-foreground' : ''}">
													{opt.label}
												</button>
											{/each}
										</PopoverContent>
									</Popover>
								</div>
							</div>
						</Card>

						<!-- SQL -->
						<Card>
							<div class="px-3 pb-2 pt-3">
								<h4 class="text-xs font-medium">{i18n('setting.editor.sql')}</h4>
							</div>
							<div class="px-3 pb-3 space-y-0.5">
								<!-- Keyword Case -->
								<div class="flex items-center justify-between py-2">
									<div class="flex items-center gap-1.5">
										<span class="text-xs">{i18n('setting.editor.keywordCase')}</span>
										<span title={i18n('setting.editor.keywordCaseHelp')}><HelpCircle class="h-3 w-3 text-muted-foreground" /></span>
									</div>
									<div class="flex gap-1">
										<button onclick={() => handleEditorChange('keywordCase', 'upper')}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.keywordCase === 'upper' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">UPPER</button>
										<button onclick={() => handleEditorChange('keywordCase', 'lower')}
											class="rounded-md px-2 py-1 text-xs transition-colors {editorStore.settings.keywordCase === 'lower' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}">lower</button>
									</div>
								</div>

								<!-- Sticky Scroll (Switch) -->
								<div class="flex items-center justify-between py-2">
									<span class="text-xs">{i18n('setting.editor.stickyScroll')}</span>
									<label class="relative inline-flex cursor-pointer items-center">
										<input type="checkbox" checked={editorStore.settings.stickyScroll}
											onchange={(e) => handleEditorChange('stickyScroll', (e.target as HTMLInputElement).checked)}
											class="peer sr-only" />
										<div class="h-5 w-9 rounded-full bg-muted peer-checked:bg-primary transition-colors after:absolute after:left-[2px] after:top-[2px] after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-all peer-checked:after:translate-x-full"></div>
									</label>
								</div>
							</div>
						</Card>
					</div>

					<!-- Right Panel - Preview -->
					<div class="flex-1 min-w-[350px]">
						<Card class="overflow-hidden">
							<div class="px-3 pb-2 pt-3">
								<h4 class="text-xs font-medium">{i18n('setting.editor.preview')}</h4>
							</div>
							<div class="h-[700px]">
								<MonacoEditor
									value={sampleSQL}
									language="sql"
									theme={editorStore.settings.editorTheme}
									options={{
										readOnly: true,
										scrollBeyondLastLine: false,
										automaticLayout: true,
										scrollbar: { vertical: 'auto', horizontal: 'auto', verticalScrollbarSize: 8, horizontalScrollbarSize: 8, alwaysConsumeMouseWheel: false }
									}}
								/>
							</div>
						</Card>
					</div>
				</div>

			<!-- ═══════ AI (Vertical Tab Layout) ═══════ -->
			{:else if currentTab === 'ai'}
				<div class="flex gap-6">
					<!-- Vertical Tab Navigation -->
					<div class="w-56 space-y-1">
						<button onclick={() => { aiActiveTab = 'custom'; }}
							class="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors {aiActiveTab === 'custom' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}">
							<Bot class="h-5 w-5 shrink-0" strokeWidth={1.5} />
							<div class="min-w-0">
								<div class="font-medium text-sm">{i18n('setting.ai.tabCustomTitle')}</div>
								<div class="truncate text-xs {aiActiveTab === 'custom' ? 'text-primary-foreground/70' : 'text-muted-foreground'}">{i18n('setting.ai.tabCustomDesc')}</div>
							</div>
						</button>
						<button onclick={() => { aiActiveTab = 'integration'; }}
							class="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors {aiActiveTab === 'integration' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}">
							<Plug class="h-5 w-5 shrink-0" strokeWidth={1.5} />
							<div class="min-w-0">
								<div class="font-medium text-sm">{i18n('setting.ai.tabIntegrationTitle')}</div>
								<div class="truncate text-xs {aiActiveTab === 'integration' ? 'text-primary-foreground/70' : 'text-muted-foreground'}">{i18n('setting.ai.tabIntegrationDesc')}</div>
							</div>
						</button>
					</div>

					<!-- Tab Content -->
					<div class="flex-1">
						{#if aiActiveTab === 'custom'}
							<!-- Custom AI Providers -->
							{#if userStore.curUser?.roleCode === 'USER'}
								<div class="flex items-center gap-2 rounded-lg border border-border p-4 text-sm">
									<AlertTriangle class="h-4 w-4 text-muted-foreground" />
									<span class="text-muted-foreground">Contact your administrator to configure AI settings.</span>
								</div>
							{:else if aiLoading}
								<div class="flex flex-col items-center justify-center gap-4 py-12">
									<Loader2 class="h-8 w-8 animate-spin text-primary" />
									<span class="text-muted-foreground">Loading configurations...</span>
								</div>
							{:else}
								<div class="space-y-4">
									<!-- Header -->
									<div class="flex items-center justify-between">
										<div>
											<h3 class="text-sm font-medium">{i18n('setting.ai.providers')}</h3>
											<p class="text-xs text-muted-foreground">{i18n('setting.ai.configureProvidersDesc')}</p>
										</div>
										{#if configuredAiCount > 0}
											<div class="flex items-center gap-1 rounded-full bg-green-500/10 dark:bg-green-500/20 px-2 py-0.5">
												<CheckCircle2 class="h-3 w-3 text-green-600 dark:text-green-400" />
												<span class="text-xs font-medium text-green-600 dark:text-green-400">{i18n('setting.ai.active', configuredAiCount)}</span>
											</div>
										{/if}
									</div>

									{#if aiMessage}
										<div class="flex items-center gap-2 rounded-lg border p-3 text-sm {aiMessage.type === 'error' ? 'border-destructive/50 bg-destructive/10 text-destructive' : aiMessage.type === 'success' ? 'border-green-500/50 bg-green-500/10 text-green-600' : 'border-amber-500/50 bg-amber-500/10 text-amber-600'}">
											{aiMessage.text}
										</div>
									{/if}

									<!-- Provider Cards -->
									<div class="space-y-2">
										{#each Object.entries(aiProviderInfo) as [key, info]}
											{@const config = aiProviders[key]}
											{@const style = providerStyles[key]}
											{@const isConfigured = config?.apiKey?.trim() !== ''}
											{@const isExpanded = expandedProviders.has(key)}
											{@const testResult = aiTestResults[key]}
											<Card class="transition-all {isExpanded ? 'ring-1 ring-primary' : ''}">
												<button onclick={() => toggleProvider(key)}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md" style="background-color: {style?.bgColor}; color: {style?.color}">
															{#if key === 'OPENAI'}
																<span class="h-5 w-5 flex items-center justify-center"><SiOpenai size={18} color={style?.color} /></span>
															{:else if key === 'CLAUDEAI'}
																<img src="/icons/claude.svg" alt="Claude" class="h-5 w-5" />
															{:else if key === 'GEMINI'}
																<span class="h-5 w-5 flex items-center justify-center"><SiGooglegemini size={18} color={style?.color} /></span>
															{:else}
																<Sparkles class="h-5 w-5" />
															{/if}
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">{info.name}</span>
																{#if isConfigured}
																	{#if config?.enabled === false}
																		<Badge variant="secondary" class="h-5 gap-0.5 px-1.5 text-xs bg-muted text-muted-foreground">
																			<Lock class="h-3 w-3" /> Disabled
																		</Badge>
																	{:else}
																		<Badge variant="secondary" class="h-5 gap-0.5 px-1.5 text-xs bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300">
																			<CheckCircle2 class="h-3 w-3" /> Configured
																		</Badge>
																	{/if}
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{info.desc}</p>
														</div>
													</div>
													{#if isExpanded}
														<ChevronDown class="h-4 w-4 text-muted-foreground" />
													{:else}
														<ChevronRight class="h-4 w-4 text-muted-foreground" />
													{/if}
												</button>
												{#if isExpanded}
													<div class="border-t border-border px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="ai-{key}-apikey">{i18n('setting.label.apiKey')} <span class="text-destructive">*</span></label>
															<input id="ai-{key}-apikey" type="password" bind:value={config.apiKey} placeholder={info.placeholder} autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<!--
															API Host: only OpenAI honours a custom baseUrl
															(OpenAI-compatible gateways like LiteLLM / Azure
															proxies). Claude and Gemini providers in
															LangChainModelProvider don't read a host value,
															so we don't show that field for them — it would
															be a dead input that misleads users.
														-->
														{#if key !== 'OPENAI'}
															<div class="space-y-1">
																<label class="text-xs font-medium" for="ai-{key}-model">{i18n('setting.label.model')}</label>
																<input id="ai-{key}-model" bind:value={config.model} placeholder={key === 'CLAUDEAI' ? 'claude-sonnet-4-6' : 'gemini-3.5-flash'} autocomplete="off"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
														{/if}
														<div class="space-y-2 pt-1">
															<!--
																Enable/Disable: when off, the backend router
																(ChatController.pickPreferredProvider) skips
																this provider even though the key is still
																stored. Hidden until a key is configured —
																there's nothing to enable on an empty slot.
															-->
															{#if isConfigured}
																<div class="flex items-center justify-between rounded-md border border-border px-3 py-2">
																	<div class="space-y-0.5">
																		<label class="text-xs font-medium" for="ai-{key}-enabled">{i18n('setting.label.enabled')}</label>
																		<p class="text-[11px] text-muted-foreground">
																			{config.enabled ? 'This provider can be selected by the chat router.' : 'Key is stored but the router will skip this provider.'}
																		</p>
																	</div>
																	<Switch id="ai-{key}-enabled" checked={config.enabled} onchange={() => toggleAiProviderEnabled(key)} disabled={aiSaving} />
																</div>
															{/if}
															<div class="flex items-center gap-2">
																<Button size="sm" variant="outline" onclick={() => testAiProvider(key)} disabled={!config.apiKey || aiTestingProvider === key}>
																	{#if aiTestingProvider === key}
																		<Loader2 class="mr-1.5 h-3.5 w-3.5 animate-spin" />
																	{:else}
																		<RefreshCw class="mr-1.5 h-3.5 w-3.5" />
																	{/if}
																	{i18n('setting.button.test')}
																</Button>
																{#if isConfigured}
																	<Button size="sm" variant="ghost" onclick={() => removeAiProvider(key)} disabled={aiSaving}
																		class="text-destructive hover:text-destructive hover:bg-destructive/10">
																		<XCircle class="mr-1.5 h-3.5 w-3.5" />
																		Remove
																	</Button>
																{/if}
															</div>
															{#if testResult}
																<div class="flex items-start gap-1.5 text-xs rounded-md p-2 {testResult.success ? 'text-green-600 bg-green-50 dark:bg-green-950/30' : 'text-destructive bg-destructive/10'}">
																	{#if testResult.success}
																		<CheckCircle2 class="h-3.5 w-3.5 mt-0.5 shrink-0" />
																	{:else}
																		<XCircle class="h-3.5 w-3.5 mt-0.5 shrink-0" />
																	{/if}
																	<span class="break-all">{testResult.message}</span>
																</div>
															{/if}
														</div>
													</div>
												{/if}
											</Card>
										{/each}
									</div>

									<!-- Save Button -->
									<div class="flex items-center justify-end gap-2 pt-2">
										<Button size="sm" onclick={saveAllAiProviders} disabled={aiSaving}>
											{#if aiSaving}
												<Loader2 class="mr-1.5 h-3.5 w-3.5 animate-spin" /> Saving...
											{:else}
												<Check class="mr-1.5 h-3.5 w-3.5" /> Apply
											{/if}
										</Button>
									</div>
								</div>
							{/if}

						{:else}
							<!-- AI Integration -->
							{#if integrationLoading}
								<div class="flex items-center justify-center py-12">
									<Loader2 class="h-8 w-8 animate-spin text-primary" />
								</div>
							{:else}
								<div class="space-y-4">
								<!-- AI Model Selection -->
								<Card>
									<div class="p-3">
										<h4 class="text-sm font-medium mb-1">{i18n('setting.ai.integrationModel')}</h4>
										<p class="text-xs text-muted-foreground mb-3">Select the LLM used for external service integration. This model is used for AI chat with connected services (Confluence, JIRA, Slack, GitHub) and AI metadata collection in Data Catalog.</p>
										<Popover bind:open={aiModelOpen}>
											<PopoverTrigger class="flex h-8 w-[280px] items-center justify-between rounded-md border border-input bg-background px-3 text-sm hover:bg-accent/50 transition-colors">
												<span class="truncate">{modelOptions.find(o => o.value === integrationFormData.geminiModel)?.label || integrationFormData.geminiModel}</span>
												<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" />
											</PopoverTrigger>
											<PopoverContent align="start" class="w-[300px] p-1">
												{#each modelOptions as opt}
													<button onclick={() => { integrationFormData.geminiModel = opt.value; aiModelOpen = false; }}
														class="flex w-full items-center rounded-sm px-2 py-1.5 text-sm hover:bg-accent transition-colors {integrationFormData.geminiModel === opt.value ? 'bg-accent text-accent-foreground' : ''}">
														{opt.label}
													</button>
												{/each}
											</PopoverContent>
										</Popover>
										<p class="text-[11px] text-muted-foreground mt-2">Only models with a configured API key in Custom AI will appear here.</p>
									</div>
								</Card>

									{#if integrationMessage}
										<div class="flex items-center gap-2 rounded-lg border p-3 text-sm {integrationMessage.type === 'error' ? 'border-destructive/50 bg-destructive/10 text-destructive' : 'border-green-500/50 bg-green-500/10 text-green-600'}">
											{integrationMessage.text}
										</div>
									{/if}

									<!-- Connected Services -->
									<div>
										<h3 class="mb-3 text-sm font-medium">{i18n('setting.ai.connectedServices')}</h3>
										<div class="space-y-2">
											<!-- Reference Documents -->
											<Card class={configuredServices.has('documents') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'documents' ? null : 'documents'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<FileText class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">{i18n('setting.ai.referenceDocuments')}</span>
																{#if configuredServices.has('documents')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> {referenceDocuments.length} file{referenceDocuments.length === 1 ? '' : 's'}</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">Upload PDF, Word, or text files for AI catalog and chat context</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														{#if expandedService === 'documents'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'documents'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="rounded-lg border border-dashed p-3">
															<div class="flex flex-wrap items-center justify-between gap-2">
																<div class="text-xs text-muted-foreground">
																	PDF, DOCX, MD, CSV, TXT — max 50MB per file · {formatBytes(referenceDocsUsedBytes)} / {formatBytes(referenceDocsQuotaBytes)} used
																</div>
																<div class="flex items-center gap-2">
																	<input
																		bind:this={referenceDocInput}
																		type="file"
																		accept=".pdf,.docx,.md,.markdown,.txt,.csv,.json,.xml,.yaml,.yml"
																		class="hidden"
																		onchange={handleReferenceDocumentUpload}
																	/>
																	<Button size="sm" variant="outline" disabled={referenceDocsUploading}
																		onclick={() => referenceDocInput?.click()}>
																		{#if referenceDocsUploading}
																			<Loader2 class="mr-1.5 h-3.5 w-3.5 animate-spin" />
																		{:else}
																			<Upload class="mr-1.5 h-3.5 w-3.5" />
																		{/if}
																		Upload
																	</Button>
																</div>
															</div>
															<div class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
																<div class="h-full rounded-full bg-primary transition-all"
																	style="width: {Math.min(100, (referenceDocsUsedBytes / Math.max(referenceDocsQuotaBytes, 1)) * 100)}%"></div>
															</div>
														</div>

														{#if referenceDocsLoading}
															<div class="flex items-center gap-2 py-2 text-xs text-muted-foreground">
																<Loader2 class="h-3.5 w-3.5 animate-spin" /> Loading documents…
															</div>
														{:else if referenceDocuments.length === 0}
															<p class="text-xs text-muted-foreground py-1">No documents uploaded yet.</p>
														{:else}
															<ul class="divide-y rounded-lg border">
																{#each referenceDocuments as doc (doc.id)}
																	<li class="flex items-center gap-2 px-3 py-2">
																		<FileText class="h-4 w-4 shrink-0 text-muted-foreground" />
																		<div class="min-w-0 flex-1">
																			<div class="truncate text-sm font-medium">{doc.filename}</div>
																			<div class="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
																				<span>{formatBytes(doc.sizeBytes)}</span>
																				<span>·</span>
																				<span class={doc.indexStatus === 'indexed' ? 'text-green-600 dark:text-green-400' : doc.indexStatus === 'error' ? 'text-destructive' : ''}>
																					{doc.indexStatus}{doc.chunkCount ? ` (${doc.chunkCount} chunks)` : ''}
																				</span>
																				{#if doc.indexError}
																					<span class="text-destructive truncate" title={doc.indexError}>— {doc.indexError}</span>
																				{/if}
																			</div>
																		</div>
																		{#if doc.indexStatus === 'skipped' || doc.indexStatus === 'error'}
																			<Button size="icon" variant="ghost" class="h-8 w-8 shrink-0" title="Re-index"
																				disabled={referenceDocReindexingId === doc.id}
																				onclick={() => handleReferenceDocumentReindex(doc)}>
																				{#if referenceDocReindexingId === doc.id}
																					<Loader2 class="h-4 w-4 animate-spin" />
																				{:else}
																					<RefreshCw class="h-4 w-4" />
																				{/if}
																			</Button>
																		{/if}
																		<Button size="icon" variant="ghost" class="h-8 w-8 shrink-0" title="Download"
																			onclick={() => handleReferenceDocumentDownload(doc)}>
																			<Download class="h-4 w-4" />
																		</Button>
																		<Button size="icon" variant="ghost" class="h-8 w-8 shrink-0 text-destructive hover:text-destructive" title="Delete"
																			disabled={referenceDocDeletingId === doc.id}
																			onclick={() => handleReferenceDocumentDelete(doc)}>
																			{#if referenceDocDeletingId === doc.id}
																				<Loader2 class="h-4 w-4 animate-spin" />
																			{:else}
																				<Trash2 class="h-4 w-4" />
																			{/if}
																		</Button>
																	</li>
																{/each}
															</ul>
														{/if}
														<p class="text-xs text-muted-foreground">Deleting a document removes the original file, database record, and all search vectors.</p>
													</div>
												{/if}
											</Card>

											<!-- Confluence -->
											<Card class={configuredServices.has('confluence') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'confluence' ? null : 'confluence'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/confluence.svg" alt="Confluence" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">Confluence</span>
																{#if configuredServices.has('confluence')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{i18n('setting.integration.confluenceDesc')}</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'confluence'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'confluence'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-conf-url">{i18n('setting.label.baseUrl')}</label>
															<input id="int-conf-url" bind:value={integrationFormData.confluenceBaseUrl} placeholder="https://your-domain.atlassian.net" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-conf-user">{i18n('setting.label.username')}</label>
															<input id="int-conf-user" bind:value={integrationFormData.confluenceUsername} placeholder="your@email.com" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-conf-token">{i18n('setting.label.apiToken')}</label>
															<input id="int-conf-token" type="password" bind:value={integrationFormData.confluenceApiToken} placeholder="Your API token" autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
													</div>
												{/if}
											</Card>

											<!-- JIRA -->
											<Card class={configuredServices.has('jira') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'jira' ? null : 'jira'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/jira.svg" alt="JIRA" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">JIRA</span>
																{#if configuredServices.has('jira')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{i18n('setting.integration.jiraDesc')}</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'jira'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'jira'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-jira-url">{i18n('setting.label.baseUrl')}</label>
															<input id="int-jira-url" bind:value={integrationFormData.jiraBaseUrl} placeholder="https://your-domain.atlassian.net" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-jira-user">{i18n('setting.label.username')}</label>
															<input id="int-jira-user" bind:value={integrationFormData.jiraUsername} placeholder="your@email.com" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-jira-token">{i18n('setting.label.apiToken')}</label>
															<input id="int-jira-token" type="password" bind:value={integrationFormData.jiraApiToken} placeholder="Your API token" autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
													</div>
												{/if}
											</Card>

											<!-- Slack -->
											<Card class={configuredServices.has('slack') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'slack' ? null : 'slack'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/slack.svg" alt="Slack" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">Slack</span>
																{#if configuredServices.has('slack')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{i18n('setting.integration.slackDesc')}</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://api.slack.com/authentication/token-types" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'slack'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'slack'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-slack-token">{i18n('setting.label.userToken')}</label>
															<div class="relative">
																<input id="int-slack-token" type={visibleTokens.has('slackUser') ? 'text' : 'password'} bind:value={integrationFormData.slackUserToken} placeholder="xoxp-..." autocomplete="new-password"
																	class="flex h-8 w-full rounded-md border {integrationFormData.slackUserToken && !integrationFormData.slackUserToken.includes('***') && !integrationFormData.slackUserToken.startsWith('xoxp-') ? 'border-destructive' : 'border-input'} bg-background px-3 pr-9 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																<button type="button" onclick={() => { const s = new Set(visibleTokens); if (s.has('slackUser')) s.delete('slackUser'); else s.add('slackUser'); visibleTokens = s; }}
																	class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors">
																	{#if visibleTokens.has('slackUser')}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
																</button>
															</div>
															{#if integrationFormData.slackUserToken && !integrationFormData.slackUserToken.includes('***') && !integrationFormData.slackUserToken.startsWith('xoxp-')}
																<p class="text-xs text-destructive">
																	{integrationFormData.slackUserToken.startsWith('xoxb-') ? 'This is a Bot Token (xoxb-). User Token must start with xoxp-.' : 'User Token must start with xoxp-.'}
																</p>
															{/if}
														</div>
													</div>
												{/if}
											</Card>

											<!-- GitHub -->
											<Card class={configuredServices.has('github') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'github' ? null : 'github'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/github.svg" alt="GitHub" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">GitHub</span>
																{#if configuredServices.has('github')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{i18n('setting.integration.githubDesc')}</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'github'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'github'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-gh-token">{i18n('setting.label.personalAccessToken')}</label>
															<input id="int-gh-token" type="password" bind:value={integrationFormData.githubToken} placeholder="ghp_..." autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-gh-url">{i18n('setting.label.baseUrl')}</label>
															<input id="int-gh-url" bind:value={integrationFormData.githubBaseUrl} placeholder="https://api.github.com" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															<p class="text-xs text-muted-foreground">Leave empty for github.com</p>
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-gh-org">{i18n('setting.label.organization')}</label>
															<input id="int-gh-org" bind:value={integrationFormData.githubOrganization} placeholder="your-org (optional)" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
													</div>
												{/if}
											</Card>

											<!-- Outlook -->
											<Card class={configuredServices.has('outlook') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'outlook' ? null : 'outlook'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/outlook.svg" alt="Outlook" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">Outlook</span>
																{#if configuredServices.has('outlook')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">{i18n('setting.integration.outlookDesc')}</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'outlook'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'outlook'}
													<div class="border-t px-3 py-3 space-y-3">
														<p class="rounded-md bg-muted/50 px-2.5 py-2 text-xs text-muted-foreground">
															In Microsoft Entra (Azure Portal), register an app (platform: Web), add API permission
															<span class="font-medium text-foreground">Microsoft Graph → Mail.Read</span>, create a client secret, and add this redirect URI:
															<code class="mt-1 block break-all rounded bg-background px-1.5 py-1 text-[11px] text-foreground">http://localhost:10821/api/config/ai/outlook/oauth/callback</code>
														</p>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-ol-tenant">{i18n('setting.label.tenantId')} <span class="text-destructive">*</span></label>
															<input id="int-ol-tenant" bind:value={integrationFormData.outlookTenantId} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															<p class="text-xs text-muted-foreground">Entra ID → Overview → Tenant ID (Directory ID). Work/school: your org tenant. Personal Microsoft accounts: try <code class="rounded bg-muted px-1 text-[11px]">common</code>.</p>
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-ol-client">{i18n('setting.label.clientId')} <span class="text-destructive">*</span></label>
															<input id="int-ol-client" bind:value={integrationFormData.outlookClientId} placeholder="Application (client) ID" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-ol-secret">{i18n('setting.label.clientSecret')} <span class="text-destructive">*</span></label>
															<input id="int-ol-secret" type="password" bind:value={integrationFormData.outlookClientSecret} placeholder="Client secret value" autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-ol-email">{i18n('setting.label.mailboxEmail')} <span class="text-muted-foreground font-normal">({i18n('setting.label.optional')})</span></label>
															<input id="int-ol-email" bind:value={integrationFormData.outlookUserEmail} placeholder="Leave empty to use the account you sign in with" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="flex gap-2 pt-1">
															<Button size="sm" onclick={handleOutlookConnect}>{i18n('setting.button.connect')}</Button>
															<Button size="sm" variant="outline" onclick={handleOutlookDisconnect}>{i18n('setting.button.disconnect')}</Button>
														</div>
													</div>
												{/if}
											</Card>

											<!-- Google Drive -->
											<Card class={configuredServices.has('google') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'google' ? null : 'google'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<img src="/icons/google.svg" alt="Google Drive" class="h-4 w-4" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">Google Drive</span>
																{#if configuredServices.has('google')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">Search Google Docs &amp; Sheets in your Drive</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://developers.google.com/identity/protocols/oauth2/web-server#creatingcred" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'google'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'google'}
													<div class="border-t px-3 py-3 space-y-3">
														<p class="rounded-md bg-muted/50 px-2.5 py-2 text-xs text-muted-foreground">
															In Google Cloud Console, create an OAuth client (type: Web application), enable the
															<span class="font-medium text-foreground">Google Drive API</span>, and add this authorized redirect URI:
															<code class="mt-1 block break-all rounded bg-background px-1.5 py-1 text-[11px] text-foreground">http://localhost:10821/api/config/ai/google/oauth/callback</code>
														</p>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-gg-client">{i18n('setting.label.clientId')} <span class="text-destructive">*</span></label>
															<input id="int-gg-client" bind:value={integrationFormData.googleClientId} placeholder="xxxxx.apps.googleusercontent.com" autocomplete="off"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-gg-secret">{i18n('setting.label.clientSecret')} <span class="text-destructive">*</span></label>
															<input id="int-gg-secret" type="password" bind:value={integrationFormData.googleClientSecret} placeholder="GOCSPX-..." autocomplete="new-password"
																class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
														</div>
														<div class="flex gap-2 pt-1">
															<Button size="sm" onclick={handleGoogleConnect}>{i18n('setting.button.connect')}</Button>
															<Button size="sm" variant="outline" onclick={handleGoogleDisconnect}>{i18n('setting.button.disconnect')}</Button>
														</div>
													</div>
												{/if}
											</Card>

											<!-- DBT -->
											<Card class={configuredServices.has('dbt') ? 'ring-1 ring-green-500/30' : ''}>
												<button onclick={() => { expandedService = expandedService === 'dbt' ? null : 'dbt'; }}
													class="flex w-full items-center justify-between p-3 text-left">
													<div class="flex items-center gap-2.5">
														<div class="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
															<Database class="h-4 w-4 text-muted-foreground" />
														</div>
														<div>
															<div class="flex items-center gap-2">
																<span class="text-sm font-medium">DBT</span>
																{#if configuredServices.has('dbt')}
																	<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600"><CheckCircle2 class="h-3 w-3" /> Connected</Badge>
																{/if}
															</div>
															<p class="text-xs text-muted-foreground">Connect dbt project files, artifacts, or dbt Cloud metadata</p>
														</div>
													</div>
													<div class="flex items-center gap-1.5">
														<a href="https://docs.getdbt.com/reference/artifacts/dbt-artifacts" target="_blank" rel="noopener noreferrer"
															class="flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
															onclick={(e) => e.stopPropagation()}>
															<ExternalLink class="h-3 w-3" /> Docs
														</a>
														{#if expandedService === 'dbt'}<ChevronDown class="h-4 w-4 text-muted-foreground" />{:else}<ChevronRight class="h-4 w-4 text-muted-foreground" />{/if}
													</div>
												</button>
												{#if expandedService === 'dbt'}
													<div class="border-t px-3 py-3 space-y-3">
														<div class="space-y-1">
															<label class="text-xs font-medium" for="int-dbt-type">{i18n('setting.label.integrationType')}</label>
															<div id="int-dbt-type" class="grid grid-cols-3 gap-2">
																<button type="button" onclick={() => integrationFormData.dbtIntegrationType = 'git'}
																	class="rounded-md border px-3 py-2 text-xs text-left transition-colors {integrationFormData.dbtIntegrationType === 'git' ? 'border-primary bg-primary/5 text-primary' : 'border-input hover:bg-muted'}">
																	Git Repository
																</button>
																<button type="button" onclick={() => integrationFormData.dbtIntegrationType = 'artifacts'}
																	class="rounded-md border px-3 py-2 text-xs text-left transition-colors {integrationFormData.dbtIntegrationType === 'artifacts' ? 'border-primary bg-primary/5 text-primary' : 'border-input hover:bg-muted'}">
																	Artifacts
																</button>
																<button type="button" onclick={() => integrationFormData.dbtIntegrationType = 'cloud'}
																	class="rounded-md border px-3 py-2 text-xs text-left transition-colors {integrationFormData.dbtIntegrationType === 'cloud' ? 'border-primary bg-primary/5 text-primary' : 'border-input hover:bg-muted'}">
																	dbt Cloud
																</button>
															</div>
														</div>

														{#if integrationFormData.dbtIntegrationType === 'git'}
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-git-url">{i18n('setting.label.repositoryUrl')}</label>
																<input id="int-dbt-git-url" bind:value={integrationFormData.dbtGitRepoUrl} placeholder="https://github.com/org/dbt-project.git" autocomplete="off"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
															<div class="grid grid-cols-2 gap-3">
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-branch">{i18n('setting.label.branch')}</label>
																	<input id="int-dbt-branch" bind:value={integrationFormData.dbtGitBranch} placeholder="main" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-path">{i18n('setting.label.projectPath')}</label>
																	<input id="int-dbt-path" bind:value={integrationFormData.dbtProjectPath} placeholder="Optional, e.g. analytics/dbt" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
															</div>
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-git-token">{i18n('setting.label.accessToken')}</label>
																<input id="int-dbt-git-token" type="password" bind:value={integrationFormData.dbtGitToken} placeholder="Optional for private repositories" autocomplete="new-password"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
														{:else if integrationFormData.dbtIntegrationType === 'artifacts'}
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-manifest">manifest.json URL</label>
																<input id="int-dbt-manifest" bind:value={integrationFormData.dbtManifestUrl} placeholder="https://.../manifest.json" autocomplete="off"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-catalog">catalog.json URL</label>
																<input id="int-dbt-catalog" bind:value={integrationFormData.dbtCatalogUrl} placeholder="https://.../catalog.json" autocomplete="off"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-artifact-token">{i18n('setting.label.artifactToken')}</label>
																<input id="int-dbt-artifact-token" type="password" bind:value={integrationFormData.dbtArtifactToken} placeholder="Optional bearer token" autocomplete="new-password"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
														{:else}
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-cloud-url">{i18n('setting.label.dbtCloudBaseUrl')}</label>
																<input id="int-dbt-cloud-url" bind:value={integrationFormData.dbtCloudBaseUrl} placeholder="https://cloud.getdbt.com" autocomplete="off"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
															<div class="grid grid-cols-2 gap-3">
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-account">{i18n('setting.label.accountId')}</label>
																	<input id="int-dbt-account" bind:value={integrationFormData.dbtCloudAccountId} placeholder="12345" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-project">{i18n('setting.label.projectId')}</label>
																	<input id="int-dbt-project" bind:value={integrationFormData.dbtCloudProjectId} placeholder="12345" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-env">{i18n('setting.label.environmentId')}</label>
																	<input id="int-dbt-env" bind:value={integrationFormData.dbtCloudEnvironmentId} placeholder="Optional" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
																<div class="space-y-1">
																	<label class="text-xs font-medium" for="int-dbt-job">{i18n('setting.label.jobId')}</label>
																	<input id="int-dbt-job" bind:value={integrationFormData.dbtCloudJobId} placeholder="Optional" autocomplete="off"
																		class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
																</div>
															</div>
															<div class="space-y-1">
																<label class="text-xs font-medium" for="int-dbt-cloud-token">{i18n('setting.label.apiToken')}</label>
																<input id="int-dbt-cloud-token" type="password" bind:value={integrationFormData.dbtCloudApiToken} placeholder="dbtc_..." autocomplete="new-password"
																	class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
															</div>
														{/if}
													</div>
												{/if}
											</Card>
										</div>
									</div>

									<!-- Save Button -->
									<div class="flex justify-end">
										<Button size="sm" onclick={saveIntegration} disabled={integrationSaving}>
											{#if integrationSaving}
												<Loader2 class="mr-1.5 h-3.5 w-3.5 animate-spin" />
											{/if}
											{i18n('setting.button.save')}
										</Button>
									</div>
								</div>
							{/if}
						{/if}
					</div>
				</div>

			<!-- ═══════ VECTOR DB ═══════ -->
		{:else if currentTab === 'vectordb'}
			{#if userStore.curUser?.roleCode === 'USER'}
				<div class="flex items-center gap-2 rounded-lg border border-border p-4 text-sm">
					<AlertTriangle class="h-4 w-4 text-muted-foreground" />
					<span class="text-muted-foreground">Contact your administrator to configure Vector DB settings.</span>
				</div>
			{:else}
			<div class="space-y-6">
				<!-- Provider Selector -->
				<div class="space-y-2">
					<p class="text-sm text-muted-foreground">Select the vector database to use for schema embedding storage and semantic search.</p>
					<div class="grid grid-cols-3 gap-3">
						<button onclick={() => selectVectorDbType('pgvector')}
							class="relative flex flex-col gap-2 rounded-xl border-2 p-4 text-left transition-all hover:shadow-md {vectorDbType === 'pgvector' ? 'border-primary bg-primary/5 shadow-sm' : 'border-border bg-background hover:border-muted-foreground/30'}">
							<div class="absolute top-2 right-2">
								<Badge variant="secondary" class="h-5 bg-green-500/15 px-1.5 text-[10px] font-semibold text-green-600 dark:text-green-400">Recommended</Badge>
							</div>
							<div class="flex items-center gap-2.5">
								<div class="flex h-9 w-9 items-center justify-center rounded-lg {vectorDbType === 'pgvector' ? 'bg-primary/10' : 'bg-muted'}">
									<Database class="h-4.5 w-4.5 {vectorDbType === 'pgvector' ? 'text-primary' : 'text-muted-foreground'}" />
								</div>
								<div>
									<span class="text-sm font-semibold">pgvector</span>
									<p class="text-xs text-muted-foreground">Built-in PostgreSQL</p>
								</div>
							</div>
							<p class="text-xs text-muted-foreground leading-relaxed">Zero config. Uses the existing app database. No API key needed.</p>
						</button>
						<button onclick={() => selectVectorDbType('qdrant')}
							class="relative flex flex-col gap-2 rounded-xl border-2 p-4 text-left transition-all hover:shadow-md {vectorDbType === 'qdrant' ? 'border-primary bg-primary/5 shadow-sm' : 'border-border bg-background hover:border-muted-foreground/30'}">
							<div class="flex items-center gap-2.5">
								<div class="flex h-9 w-9 items-center justify-center rounded-lg {vectorDbType === 'qdrant' ? 'bg-primary/10' : 'bg-muted'}">
									<Database class="h-4.5 w-4.5 {vectorDbType === 'qdrant' ? 'text-primary' : 'text-muted-foreground'}" />
								</div>
								<div>
									<span class="text-sm font-semibold">Qdrant</span>
									<p class="text-xs text-muted-foreground">Self-hosted / Cloud</p>
								</div>
							</div>
							<p class="text-xs text-muted-foreground leading-relaxed">High-performance vector DB. Deploy via Docker or use Qdrant Cloud.</p>
						</button>
						<button onclick={() => selectVectorDbType('pinecone')}
							class="relative flex flex-col gap-2 rounded-xl border-2 p-4 text-left transition-all hover:shadow-md {vectorDbType === 'pinecone' ? 'border-primary bg-primary/5 shadow-sm' : 'border-border bg-background hover:border-muted-foreground/30'}">
							<div class="flex items-center gap-2.5">
								<div class="flex h-9 w-9 items-center justify-center rounded-lg {vectorDbType === 'pinecone' ? 'bg-primary/10' : 'bg-muted'}">
									<Database class="h-4.5 w-4.5 {vectorDbType === 'pinecone' ? 'text-primary' : 'text-muted-foreground'}" />
								</div>
								<div>
									<span class="text-sm font-semibold">Pinecone</span>
									<p class="text-xs text-muted-foreground">Managed cloud</p>
								</div>
							</div>
							<p class="text-xs text-muted-foreground leading-relaxed">Hybrid search with BM25. Requires Pinecone + AI API key.</p>
						</button>
					</div>
				</div>

				{#if vectorDbMessage}
					<div class="flex items-center gap-2 rounded-lg border p-3 text-sm {vectorDbMessage.type === 'success' ? 'border-green-500/50 bg-green-500/10 text-green-600 dark:text-green-400' : 'border-destructive/50 bg-destructive/10 text-destructive'}">
						{vectorDbMessage.text}
					</div>
				{/if}

				<!-- Pinecone Config -->
				{#if vectorDbType === 'pinecone'}
				<Card class="p-6">
					<div class="flex items-center gap-4 mb-4">
						<div class="flex-1">
							<div class="flex items-center gap-2">
								<h3 class="text-base font-semibold">{i18n('setting.vectordb.pinecone')}</h3>
								{#if pineconeIsConfigured}
									<Badge variant="secondary"><CheckCircle2 class="h-3 w-3 mr-1" /> Configured</Badge>
								{/if}
							</div>
							<p class="text-sm text-muted-foreground mt-0.5">Cloud-hosted vector database with hybrid search support.</p>
						</div>
						<a href="https://docs.pinecone.io/guides/get-started/quickstart" target="_blank" rel="noopener" class="flex items-center gap-1 text-sm text-primary hover:underline">
							Docs <ExternalLink class="h-3 w-3" />
						</a>
					</div>
					{#if configuredAiCount === 0}
					<div class="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 mb-4 text-sm">
						<div class="flex items-start gap-2">
							<AlertTriangle class="h-4 w-4 text-amber-500 mt-0.5 shrink-0" />
							<div>
								<p class="font-medium text-amber-600 dark:text-amber-400">Embedding API key required</p>
								<p class="text-muted-foreground mt-0.5">Pinecone uses Gemini or OpenAI for embedding generation (512 dimensions). Configure your AI API key in the <strong>AI Settings</strong> tab first.</p>
							</div>
						</div>
					</div>
				{/if}
					<div class="space-y-4">
						<div class="space-y-1.5">
							<label class="text-sm font-medium flex items-center gap-2" for="pc-key"><Key class="h-3.5 w-3.5 text-muted-foreground" /> API Key *</label>
							<input id="pc-key" type="password" bind:value={pineconeConfig.apiKey} placeholder="pcsk_..."
								class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
						</div>
						<div class="space-y-1.5">
							<label class="text-sm font-medium flex items-center gap-2" for="pc-host"><Server class="h-3.5 w-3.5 text-muted-foreground" /> Host URL</label>
							<input id="pc-host" bind:value={pineconeConfig.host} placeholder="https://xxx.pinecone.io (optional)"
								class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
						</div>
						<div class="grid grid-cols-2 gap-4">
							<div class="space-y-1.5">
								<label class="text-sm font-medium flex items-center gap-2" for="pc-index"><FolderTree class="h-3.5 w-3.5 text-muted-foreground" /> Index Name</label>
								<input id="pc-index" bind:value={pineconeConfig.indexName} placeholder="table-schemas"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
							<div class="space-y-1.5">
								<label class="text-sm font-medium flex items-center gap-2" for="pc-ns"><Tag class="h-3.5 w-3.5 text-muted-foreground" /> Namespace</label>
								<input id="pc-ns" bind:value={pineconeConfig.namespace} placeholder="default"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
						</div>
					</div>

					{#if pineconeMessage}
						<div class="mt-4 flex items-center gap-2 rounded-lg border p-3 text-sm {pineconeMessage.type === 'success' ? 'border-green-500/50 bg-green-500/10 text-green-600 dark:text-green-400' : 'border-destructive/50 bg-destructive/10 text-destructive'}">
							{pineconeMessage.text}
						</div>
					{/if}

					<div class="mt-4 flex items-center justify-end gap-3">
						{#if pineconeTestStatus === 'success'}
							<span class="flex items-center gap-1 text-sm text-green-600 dark:text-green-400"><CheckCircle2 class="h-4 w-4" /> Connected</span>
						{/if}
						<Button variant="outline" onclick={testPineconeConnection} disabled={!pineconeConfig.apiKey || pineconeTestStatus === 'testing'}>
							{#if pineconeTestStatus === 'testing'}
								<Loader2 class="h-3.5 w-3.5 mr-1.5 animate-spin" />
							{:else}
								<RefreshCw class="h-3.5 w-3.5 mr-1.5" />
							{/if}
							{i18n('setting.button.test')}
						</Button>
						<Button onclick={savePineconeConfig} disabled={pineconeLoading}>
							{#if pineconeLoading}<Loader2 class="h-3.5 w-3.5 mr-1.5 animate-spin" />{/if}
							{i18n('setting.button.save')}
						</Button>
					</div>
				</Card>

				<!-- pgvector Config -->
				{:else if vectorDbType === 'pgvector'}
				<Card class="p-6">
					<div class="flex items-center gap-4 mb-4">
						<div class="flex-1">
							<div class="flex items-center gap-2">
								<h3 class="text-base font-semibold">{i18n('setting.vectordb.pgvector')}</h3>
								<Badge variant="secondary" class="h-5 gap-0.5 bg-green-500/10 px-1.5 text-xs text-green-600 dark:text-green-400"><CheckCircle2 class="h-3 w-3" /> Auto-configured</Badge>
							</div>
							<p class="text-sm text-muted-foreground mt-0.5">Uses the existing application PostgreSQL database. No separate setup required.</p>
						</div>
						<a href="https://github.com/pgvector/pgvector" target="_blank" rel="noopener" class="flex items-center gap-1 text-sm text-primary hover:underline">
							Docs <ExternalLink class="h-3 w-3" />
						</a>
					</div>
					<div class="rounded-lg border border-green-500/30 bg-green-500/5 p-4 text-sm">
						<div class="flex items-start gap-2">
							<CheckCircle2 class="h-4 w-4 text-green-500 mt-0.5 shrink-0" />
							<div class="space-y-1.5">
								<p class="font-medium text-green-600 dark:text-green-400">Ready to use &mdash; fully free, no API key needed</p>
								<p class="text-muted-foreground">Uses the built-in <strong>all-MiniLM-L6-v2</strong> embedding model (384 dims, runs locally). The pgvector extension and vector table are automatically created on first use.</p>
							</div>
						</div>
					</div>
				</Card>

				<!-- Qdrant Config -->
				{:else if vectorDbType === 'qdrant'}
				<Card class="p-6">
					<div class="flex items-center gap-4 mb-4">
						<div class="flex-1">
							<h3 class="text-base font-semibold">{i18n('setting.vectordb.qdrant')}</h3>
							<p class="text-sm text-muted-foreground mt-0.5">Open-source vector database for high-performance similarity search.</p>
						</div>
						<a href="https://qdrant.tech/documentation/" target="_blank" rel="noopener" class="flex items-center gap-1 text-sm text-primary hover:underline">
							Docs <ExternalLink class="h-3 w-3" />
						</a>
					</div>

					<!-- Mode Selector -->
					<div class="mb-4 flex rounded-lg border border-border p-1 bg-muted/30">
						<button onclick={() => { qdrantMode = 'self-hosted'; }}
							class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-all {qdrantMode === 'self-hosted' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}">
							Self-hosted (Docker)
						</button>
						<button onclick={() => { qdrantMode = 'cloud'; }}
							class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-all {qdrantMode === 'cloud' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}">
							Qdrant Cloud
						</button>
					</div>

					{#if qdrantMode === 'self-hosted'}
						<div class="space-y-4">
							<div class="rounded-lg border border-green-500/30 bg-green-500/5 p-4 text-sm">
								<div class="flex items-start gap-2">
									<CheckCircle2 class="h-4 w-4 text-green-500 mt-0.5 shrink-0" />
									<div class="space-y-1.5">
										<p class="font-medium text-green-600 dark:text-green-400">Free &mdash; no API key needed</p>
										<p class="text-muted-foreground">Uses the built-in <strong>all-MiniLM-L6-v2</strong> embedding model (384 dims, runs locally). Connects to <code class="rounded bg-muted px-1.5 py-0.5 text-xs">localhost:6333</code> by default.</p>
									</div>
								</div>
							</div>
							<div class="grid grid-cols-2 gap-4">
								<div class="space-y-1.5">
									<label class="text-sm font-medium flex items-center gap-2" for="qd-host-local"><Server class="h-3.5 w-3.5 text-muted-foreground" /> Host</label>
									<input id="qd-host-local" bind:value={qdrantConfig.host} placeholder="localhost"
										class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
								</div>
								<div class="space-y-1.5">
									<label class="text-sm font-medium flex items-center gap-2" for="qd-port-local">Port (gRPC)</label>
									<input id="qd-port-local" bind:value={qdrantConfig.port} placeholder="6333"
										class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
								</div>
							</div>
						</div>
					{:else}
						<div class="space-y-4">
							<div class="rounded-lg border border-blue-500/30 bg-blue-500/5 p-3 text-sm">
								<div class="flex items-start gap-2">
									<CheckCircle2 class="h-4 w-4 text-blue-500 mt-0.5 shrink-0" />
									<p class="text-muted-foreground">Uses the built-in <strong>all-MiniLM-L6-v2</strong> embedding model (384 dims). No separate embedding API key needed.</p>
								</div>
							</div>
							<div class="space-y-1.5">
								<label class="text-sm font-medium flex items-center gap-2" for="qd-host-cloud"><Server class="h-3.5 w-3.5 text-muted-foreground" /> Cloud Host URL *</label>
								<input id="qd-host-cloud" bind:value={qdrantConfig.host} placeholder="xyz-abc.aws.cloud.qdrant.io"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
							<div class="space-y-1.5">
								<label class="text-sm font-medium flex items-center gap-2" for="qd-key-cloud"><Key class="h-3.5 w-3.5 text-muted-foreground" /> API Key *</label>
								<input id="qd-key-cloud" type="password" bind:value={qdrantConfig.apiKey} placeholder="Enter your Qdrant Cloud API key"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
							<div class="grid grid-cols-2 gap-4">
								<div class="space-y-1.5">
									<label class="text-sm font-medium flex items-center gap-2" for="qd-port-cloud">Port (gRPC)</label>
									<input id="qd-port-cloud" bind:value={qdrantConfig.port} placeholder="6333"
										class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
								</div>
								<div class="space-y-1.5">
									<label class="text-sm font-medium flex items-center gap-2" for="qd-collection-cloud"><FolderTree class="h-3.5 w-3.5 text-muted-foreground" /> Collection Name</label>
									<input id="qd-collection-cloud" bind:value={qdrantConfig.collectionName} placeholder="table-schemas"
										class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
								</div>
							</div>
						</div>
					{/if}

					<div class="mt-4 flex items-center justify-end gap-3">
						<Button variant="outline" onclick={testQdrantConnection} disabled={vectorDbSaving}>
							{#if vectorDbSaving}
								<Loader2 class="h-3.5 w-3.5 mr-1.5 animate-spin" />
							{:else}
								<RefreshCw class="h-3.5 w-3.5 mr-1.5" />
							{/if}
							{i18n('setting.button.test')}
						</Button>
						<Button onclick={saveQdrantConfig} disabled={vectorDbSaving}>
							{#if vectorDbSaving}<Loader2 class="h-3.5 w-3.5 mr-1.5 animate-spin" />{/if}
							{i18n('setting.button.save')}
						</Button>
					</div>
				</Card>
				{/if}
			</div>
			{/if}

			<!-- ═══════ SLACK ═══════ -->
			{:else if currentTab === 'slack'}
				{#if slackLoading}
					<div class="flex items-center justify-center py-12"><Loader2 class="h-8 w-8 animate-spin text-primary" /></div>
				{:else}
				<div class="space-y-4">
					<!-- Header -->
					<Card class="p-4">
						<div class="flex items-center justify-between">
							<div class="flex items-center gap-3">
								<div class="flex h-9 w-9 items-center justify-center rounded-md bg-[#4A154B]/10">
									<img src="/icons/slack.svg" alt="Slack" class="h-5 w-5" />
								</div>
								<div>
									<div class="flex items-center gap-2">
										<h3 class="text-sm font-medium">{i18n('setting.slack.title')}</h3>
										<Badge variant={slackConfig.connected ? 'default' : 'secondary'}
											class={slackConfig.connected ? 'h-5 gap-0.5 px-1.5 text-xs bg-green-500/10 text-green-600' : 'h-5 gap-0.5 px-1.5 text-xs'}>
											{#if slackConfig.connected}
												<CheckCircle2 class="h-3 w-3" /> Running
											{:else}
												<XCircle class="h-3 w-3" /> Not Connected
											{/if}
										</Badge>
									</div>
									<p class="text-xs text-muted-foreground">Query your database using natural language from Slack</p>
								</div>
							</div>
							<a href="https://api.slack.com/apps" target="_blank" rel="noopener noreferrer" class="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
								<ExternalLink class="h-3.5 w-3.5" /> Docs
							</a>
						</div>
					</Card>

					{#if slackMessage}
						<div class="flex items-center gap-2 rounded-lg border p-3 text-sm {slackMessage.type === 'success' ? 'border-green-500/50 bg-green-500/10 text-green-600' : 'border-destructive/50 bg-destructive/10 text-destructive'}">
							{slackMessage.text}
						</div>
					{/if}

					<!-- Enable Toggle -->
					<Card class="p-4">
						<div class="flex items-center justify-between">
							<div class="flex items-center gap-2.5">
								<Bot class="h-4 w-4 text-muted-foreground" />
								<div>
									<p class="text-sm font-medium">{i18n('setting.slack.enableBot')}</p>
									<p class="text-xs text-muted-foreground">Activate the Slack integration</p>
								</div>
							</div>
							<label class="relative inline-flex cursor-pointer items-center">
								<input type="checkbox" bind:checked={slackConfig.enabled} class="peer sr-only" />
								<div class="h-5 w-9 rounded-full bg-muted peer-checked:bg-primary transition-colors after:absolute after:left-[2px] after:top-[2px] after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-all peer-checked:after:translate-x-full"></div>
							</label>
						</div>
					</Card>

					<!-- Authentication -->
					<Card class="p-4">
						<h4 class="text-sm font-medium mb-3">{i18n('setting.slack.auth')}</h4>
						<div class="grid gap-3 md:grid-cols-2">
							<div class="space-y-1">
								<label class="text-xs font-medium" for="slack-bot">{i18n('setting.slack.botToken')}</label>
								<div class="relative">
									<input id="slack-bot" type={showBotToken ? 'text' : 'password'} bind:value={slackConfig.botToken} placeholder="xoxb-..." autocomplete="new-password"
										class="flex h-8 w-full rounded-md border {botTokenError ? 'border-destructive' : 'border-input'} bg-background px-3 pr-9 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 {botTokenError ? 'focus-visible:ring-destructive' : 'focus-visible:ring-ring'}" />
									<button type="button" onclick={() => showBotToken = !showBotToken}
										class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors">
										{#if showBotToken}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
									</button>
								</div>
								{#if botTokenError}
									<p class="text-xs text-destructive">{botTokenError}</p>
								{/if}
							</div>
							<div class="space-y-1">
								<label class="text-xs font-medium" for="slack-app">{i18n('setting.slack.appToken')}</label>
								<div class="relative">
									<input id="slack-app" type={showAppToken ? 'text' : 'password'} bind:value={slackConfig.appToken} placeholder="xapp-..." autocomplete="new-password"
										class="flex h-8 w-full rounded-md border {appTokenError ? 'border-destructive' : 'border-input'} bg-background px-3 pr-9 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 {appTokenError ? 'focus-visible:ring-destructive' : 'focus-visible:ring-ring'}" />
									<button type="button" onclick={() => showAppToken = !showAppToken}
										class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors">
										{#if showAppToken}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
									</button>
								</div>
								{#if appTokenError}
									<p class="text-xs text-destructive">{appTokenError}</p>
								{/if}
							</div>
						</div>
						<div class="flex items-center gap-4 mt-3">
							<Button variant="outline" onclick={handleTestSlack} disabled={!slackConfig.botToken || !!botTokenError || slackTestStatus === 'testing'}>
								{#if slackTestStatus === 'testing'}
									<Loader2 class="mr-2 h-4 w-4 animate-spin" />
								{:else}
									<RefreshCw class="mr-2 h-4 w-4" />
								{/if}
								{i18n('setting.button.testConnection')}
							</Button>
							{#if slackTestStatus === 'success'}
								<span class="flex items-center gap-1 text-sm text-green-600"><CheckCircle2 class="h-4 w-4" /> {slackTestMessage}</span>
							{:else if slackTestStatus === 'error'}
								<span class="flex items-center gap-1 text-sm text-destructive"><XCircle class="h-4 w-4" /> {slackTestMessage}</span>
							{/if}
						</div>
					</Card>

					<!-- Data Source -->
					<Card class="p-4">
						<h4 class="text-base font-medium mb-3">{i18n('setting.slack.defaultDataSource')}</h4>
						<div class="grid gap-4 md:grid-cols-3">
							<div class="space-y-2">
								<span class="text-sm font-medium">{i18n('setting.slack.dataSource')}</span>
								<Popover bind:open={slackDsPopoverOpen}>
									<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
										{@const selectedConn = slackConnections.find((c: any) => String(c.id) === slackConfig.defaultDataSourceId)}
										{#if selectedConn}
											{@const selDbInfo = databaseMap[selectedConn.type]}
											<span class="flex items-center gap-2">
												{#if selDbInfo?.img}
													<img src={selDbInfo.img} alt={selDbInfo.name} class="h-4 w-4 object-contain shrink-0" />
												{:else}
													<Database class="h-4 w-4 text-muted-foreground shrink-0" />
												{/if}
												{selectedConn.alias || selectedConn.host}
											</span>
										{:else}
											<span class="text-muted-foreground">Select data source</span>
										{/if}
										<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
									</PopoverTrigger>
									<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1 max-h-48 overflow-y-auto">
										{#if slackConnections.length === 0}
											<div class="px-3 py-2 text-sm text-muted-foreground">No connections available</div>
										{:else}
											{#each slackConnections as conn}
												{@const dbInfo = databaseMap[conn.type]}
												<button
													class="flex w-full items-center gap-2 rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {slackConfig.defaultDataSourceId === String(conn.id) ? 'bg-accent' : ''}"
													onclick={() => { slackConfig.defaultDataSourceId = String(conn.id); slackDsPopoverOpen = false; }}
												>
													{#if dbInfo?.img}
														<img src={dbInfo.img} alt={dbInfo.name} class="h-4 w-4 object-contain shrink-0" />
													{:else}
														<Database class="h-4 w-4 text-muted-foreground shrink-0" />
													{/if}
													{conn.alias || conn.host}
												</button>
											{/each}
										{/if}
									</PopoverContent>
								</Popover>
							</div>
							<div class="space-y-2">
								<label class="text-sm font-medium" for="slack-db">{i18n('setting.slack.databaseName')} <span class="text-xs font-normal text-muted-foreground">({i18n('setting.label.optional')})</span></label>
								<input id="slack-db" bind:value={slackConfig.defaultDatabase} placeholder="database_name" autocomplete="off"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
							<div class="space-y-2">
								<label class="text-sm font-medium" for="slack-schema">{i18n('setting.slack.schemaName')} <span class="text-xs font-normal text-muted-foreground">({i18n('setting.label.optional')})</span></label>
								<input id="slack-schema" bind:value={slackConfig.defaultSchema} placeholder="schema_name" autocomplete="off"
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
							</div>
						</div>
					</Card>

					<!-- AI Model -->
					<Card class="p-4">
						<h4 class="text-base font-medium mb-3">{i18n('setting.slack.aiModel')}</h4>
						<div class="space-y-2">
							<span class="text-sm font-medium">{i18n('setting.slack.llmModel')}</span>
							<Popover bind:open={slackModelPopoverOpen}>
								<PopoverTrigger class="flex h-10 w-full md:w-[300px] items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors" disabled={slackAvailableModels.length === 0}>
									<span class={slackConfig.defaultModel ? '' : 'text-muted-foreground'}>
										{slackAvailableModels.find(m => m.value === slackConfig.defaultModel)?.label || (slackAvailableModels.length > 0 ? 'Select model' : 'No models available')}
									</span>
									<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
								</PopoverTrigger>
								<PopoverContent align="start" class="w-[300px] p-1 max-h-48 overflow-y-auto">
									{#if slackAvailableModels.length === 0}
										<div class="px-3 py-2 text-sm text-muted-foreground">No models available</div>
									{:else}
										{#each slackAvailableModels as model}
											<button
												class="flex w-full items-center rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {slackConfig.defaultModel === model.value ? 'bg-accent' : ''}"
												onclick={() => { slackConfig.defaultModel = model.value; slackModelPopoverOpen = false; }}
											>
												{model.label}
											</button>
										{/each}
									{/if}
								</PopoverContent>
							</Popover>
							<p class="text-xs text-muted-foreground">
								{slackAvailableModels.length > 0 ? 'Select AI model for Slack queries' : 'Configure API keys in AI Settings first'}
							</p>
						</div>
						{#if slackAvailableModels.length === 0}
							<div class="mt-3 flex items-center gap-2 rounded-lg border p-3 text-sm text-muted-foreground">
								No AI models available. Configure at least one AI provider in the AI Settings.
							</div>
						{/if}
					</Card>

					<!-- Quick Setup Guide -->
					<Card class="p-4">
						<h4 class="text-base font-medium mb-3">{i18n('setting.slack.setupGuide')}</h4>
						<ol class="space-y-2 text-sm">
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">1</span>
								<span class="text-muted-foreground">Create a Slack App at <a href="https://api.slack.com/apps" target="_blank" rel="noopener" class="text-primary hover:underline">api.slack.com/apps</a></span>
							</li>
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">2</span>
								<span class="text-muted-foreground">Enable <code class="rounded bg-muted px-1">Socket Mode</code> in Settings</span>
							</li>
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">3</span>
								<span class="text-muted-foreground">Generate App-Level Token with <code class="rounded bg-muted px-1">connections:write</code> scope</span>
							</li>
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">4</span>
								<span class="text-muted-foreground">Add Bot Token Scopes: <code class="rounded bg-muted px-1">chat:write</code>, <code class="rounded bg-muted px-1">files:write</code>, <code class="rounded bg-muted px-1">reactions:write</code></span>
							</li>
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">5</span>
								<span class="text-muted-foreground">Subscribe to Events: <code class="rounded bg-muted px-1">app_mention</code>, <code class="rounded bg-muted px-1">message.im</code></span>
							</li>
							<li class="flex items-start gap-3">
								<span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-primary-foreground">6</span>
								<span class="text-muted-foreground">Install the app to your workspace</span>
							</li>
						</ol>
					</Card>

					<!-- Save Button -->
					<div class="flex justify-end">
						<Button onclick={handleSaveSlack} disabled={slackSaving}>
							{#if slackSaving}<Loader2 class="mr-2 h-4 w-4 animate-spin" />{/if}
							{i18n('setting.button.save')}
						</Button>
					</div>
				</div>
				{/if}

			<!-- ═══════ PROXY ═══════ -->
			{:else if currentTab === 'proxy'}
				<div class="space-y-6">
					<Card class="p-6">
						<div class="flex items-center gap-4 mb-6">
							<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10">
								<Globe class="h-6 w-6 text-primary" />
							</div>
							<div>
								<h3 class="text-base font-semibold">{i18n('setting.proxy.title')}</h3>
								<p class="text-sm text-muted-foreground">Configure the server address for API requests</p>
							</div>
						</div>

						{#if proxyMessage}
							<div class="mb-4 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive flex items-center gap-2">
								<AlertTriangle class="h-4 w-4" /> {proxyMessage}
							</div>
						{/if}

						<div class="space-y-1.5">
							<label class="text-sm font-medium flex items-center gap-2" for="proxy-url">
								<Server class="h-3.5 w-3.5 text-muted-foreground" /> {i18n('setting.label.serviceAddress')}
							</label>
							<input id="proxy-url" bind:value={proxyUrl} placeholder="https://api.example.com"
								class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
						</div>

						<div class="flex justify-end mt-4">
							<Button onclick={applyProxy} disabled={proxyTesting}>
								{proxyTesting ? i18n('setting.button.testing') : i18n('setting.button.apply')}
							</Button>
						</div>
					</Card>
				</div>

			<!-- ═══════ SHORTCUTS ═══════ -->
			{:else if currentTab === 'shortcuts'}
				<div class="space-y-4">
					<!-- Header with Reset All -->
					<div class="flex items-center justify-between">
						<div>
							<h3 class="text-base font-semibold">{i18n('setting.shortcuts.title')}</h3>
							<p class="text-sm text-muted-foreground">Click on a shortcut to customize it</p>
						</div>
						<Button variant="outline" size="sm" onclick={handleResetAll}>
							<RefreshCw class="h-3.5 w-3.5 mr-1.5" />
							{i18n('setting.button.resetAll')}
						</Button>
					</div>

					<!-- Command Palette quick-launch card -->
					<Card class="cursor-pointer transition-colors hover:bg-accent p-6"
						onclick={() => {
							const mac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);
							window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', metaKey: mac, ctrlKey: !mac, bubbles: true }));
						}}>
						<div class="flex items-center gap-4">
							<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10">
								<Keyboard class="h-6 w-6 text-primary" />
							</div>
							<div class="flex-1">
								<p class="font-medium">Command Palette</p>
								<p class="text-sm text-muted-foreground">Quick access to all commands and actions</p>
							</div>
							<div class="flex items-center gap-1">
								<kbd class="rounded bg-muted px-2 py-1 text-xs font-semibold">{modKey}</kbd>
								<span class="text-muted-foreground">+</span>
								<kbd class="rounded bg-muted px-2 py-1 text-xs font-semibold">K</kbd>
							</div>
						</div>
					</Card>

					<!-- Shortcut sections -->
					{#each Object.entries(shortcutStore.grouped) as [category, items]}
						<Card class="overflow-hidden">
							<div class="p-4 pb-3"><h3 class="text-base font-semibold">{category}</h3></div>
							<div class="px-4 pb-4">
								{#each items as item, idx}
									{@const isRecording = recordingId === item.id}
									{@const isModified = !keysEqual(item.keys, item.defaultKeys)}
									{@const isConflictTarget = conflictId === item.id}
									<div class="flex items-center justify-between py-3 gap-2 {idx !== items.length - 1 ? 'border-b border-border' : ''}">
										<div class="flex items-center gap-2 min-w-0">
											<span class="text-sm">{item.label}</span>
											{#if isModified}
												<Badge variant="secondary" class="text-[10px] px-1.5 py-0">customized</Badge>
											{/if}
											{#if isConflictTarget}
												<Badge variant="destructive" class="text-[10px] px-1.5 py-0">conflict</Badge>
											{/if}
										</div>
										<div class="flex items-center gap-2 shrink-0">
											{#if isRecording}
												<!-- Recording mode -->
												<div class="flex items-center gap-2">
													{#if recordedKeys}
														<div class="flex items-center gap-1">
															{#each formatKeys(recordedKeys).split(' + ') as part, ki}
																{#if ki > 0}<span class="text-xs text-muted-foreground">+</span>{/if}
																<kbd class="rounded bg-primary/20 border border-primary/40 px-2 py-1 text-xs font-semibold text-primary">{part}</kbd>
															{/each}
														</div>
														{#if conflictId}
															<span class="text-xs text-destructive">Conflict!</span>
														{/if}
														<Button variant="ghost" size="sm" class="h-7 px-2 text-xs" onclick={confirmRecording} disabled={!!conflictId}>
															<Check class="h-3 w-3 mr-1" />Apply
														</Button>
													{:else}
														<span class="text-xs text-muted-foreground animate-pulse">Press keys...</span>
													{/if}
													<Button variant="ghost" size="sm" class="h-7 px-2 text-xs" onclick={cancelRecording}>
														<X class="h-3 w-3" />
													</Button>
												</div>
											{:else}
												<!-- Display mode -->
												<button class="flex items-center gap-1 rounded-md px-2 py-1 transition-colors hover:bg-accent cursor-pointer"
													onclick={() => startRecording(item.id)}>
													{#each formatKeys(item.keys).split(' + ') as part, ki}
														{#if ki > 0}<span class="text-xs text-muted-foreground">+</span>{/if}
														<kbd class="rounded bg-muted px-2 py-1 text-xs font-semibold">{part}</kbd>
													{/each}
												</button>
												{#if isModified}
													<button class="text-muted-foreground hover:text-foreground transition-colors p-1 rounded"
														onclick={() => handleResetShortcut(item.id)}
														title="Reset to default">
														<RefreshCw class="h-3.5 w-3.5" />
													</button>
												{/if}
											{/if}
										</div>
									</div>
								{/each}
							</div>
						</Card>
					{/each}
				</div>

			<!-- ═══════ PROFILE ═══════ -->
			{:else if currentTab === 'profile'}
				<div class="space-y-6">
					<Card class="p-6">
						<div class="flex items-center gap-4">
							<div class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-primary text-xl font-semibold">
								{(userStore.curUser?.nickName || 'U')[0].toUpperCase()}
							</div>
							<div>
								<h3 class="text-lg font-semibold">{userStore.curUser?.nickName || 'User'}</h3>
								<p class="text-sm text-muted-foreground">{userStore.curUser?.roleCode || 'Member'}</p>
							</div>
						</div>
						<Separator class="my-4" />
						<div class="space-y-3">
							<div class="flex items-center justify-between py-2">
								<span class="text-sm text-muted-foreground">Role</span>
								<Badge variant="secondary">{userStore.curUser?.roleCode || 'User'}</Badge>
							</div>
							{#if userStore.curUser?.id}
								<div class="flex items-center justify-between py-2">
									<span class="text-sm text-muted-foreground">User ID</span>
									<span class="text-sm font-mono">{userStore.curUser.id}</span>
								</div>
							{/if}
						</div>
					</Card>

					<Card class="p-6">
						<div class="flex items-center gap-3">
							<div class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
								<Lock class="h-5 w-5 text-primary" />
							</div>
							<div>
								<p class="font-medium">{i18n('setting.profile.changePassword')}</p>
								<p class="text-sm text-muted-foreground">
									Update the password used to sign in to your account.
								</p>
							</div>
						</div>
						<Separator class="my-4" />

						<div class="space-y-4 max-w-md">
							<!-- Current password -->
							<div class="space-y-1.5">
								<label for="pw-current" class="text-sm font-medium">{i18n('setting.profile.currentPassword')}</label>
								<div class="relative">
									<input
										id="pw-current"
										type={pwShowCurrent ? 'text' : 'password'}
										autocomplete="current-password"
										bind:value={pwCurrent}
										class="w-full rounded-md border border-input bg-background px-3 py-2 pr-10 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
										disabled={pwSubmitting}
										onkeydown={(e) => { if (e.key === 'Enter' && !pwSubmitting) handleChangePassword(); }}
									/>
									<button
										type="button"
										aria-label={pwShowCurrent ? 'Hide password' : 'Show password'}
										class="absolute inset-y-0 right-2 flex items-center text-muted-foreground hover:text-foreground"
										onclick={() => pwShowCurrent = !pwShowCurrent}
										tabindex={-1}
									>
										{#if pwShowCurrent}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
									</button>
								</div>
							</div>

							<!-- New password -->
							<div class="space-y-1.5">
								<label for="pw-new" class="text-sm font-medium">{i18n('setting.profile.newPassword')}</label>
								<div class="relative">
									<input
										id="pw-new"
										type={pwShowNew ? 'text' : 'password'}
										autocomplete="new-password"
										bind:value={pwNew}
										class="w-full rounded-md border border-input bg-background px-3 py-2 pr-10 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
										disabled={pwSubmitting}
										onkeydown={(e) => { if (e.key === 'Enter' && !pwSubmitting) handleChangePassword(); }}
									/>
									<button
										type="button"
										aria-label={pwShowNew ? 'Hide password' : 'Show password'}
										class="absolute inset-y-0 right-2 flex items-center text-muted-foreground hover:text-foreground"
										onclick={() => pwShowNew = !pwShowNew}
										tabindex={-1}
									>
										{#if pwShowNew}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
									</button>
								</div>
								<p class="text-xs text-muted-foreground">
									{PW_MIN_LEN}–{PW_MAX_LEN} characters.
								</p>
							</div>

							<!-- Confirm new password -->
							<div class="space-y-1.5">
								<label for="pw-confirm" class="text-sm font-medium">{i18n('setting.profile.confirmPassword')}</label>
								<div class="relative">
									<input
										id="pw-confirm"
										type={pwShowConfirm ? 'text' : 'password'}
										autocomplete="new-password"
										bind:value={pwConfirm}
										class="w-full rounded-md border border-input bg-background px-3 py-2 pr-10 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
										disabled={pwSubmitting}
										onkeydown={(e) => { if (e.key === 'Enter' && !pwSubmitting) handleChangePassword(); }}
									/>
									<button
										type="button"
										aria-label={pwShowConfirm ? 'Hide password' : 'Show password'}
										class="absolute inset-y-0 right-2 flex items-center text-muted-foreground hover:text-foreground"
										onclick={() => pwShowConfirm = !pwShowConfirm}
										tabindex={-1}
									>
										{#if pwShowConfirm}<EyeOff class="h-4 w-4" />{:else}<Eye class="h-4 w-4" />{/if}
									</button>
								</div>
								{#if pwConfirm.length > 0 && pwConfirm !== pwNew}
									<p class="text-xs text-destructive">Passwords do not match.</p>
								{/if}
							</div>

							<!-- Inline status -->
							{#if pwError}
								<div class="flex items-start gap-2 rounded-md bg-destructive/10 px-3 py-2 text-xs text-destructive">
									<XCircle class="h-4 w-4 shrink-0 mt-0.5" />
									<span>{pwError}</span>
								</div>
							{:else if pwSuccess}
								<div class="flex items-start gap-2 rounded-md bg-emerald-500/10 px-3 py-2 text-xs text-emerald-700 dark:text-emerald-400">
									<CheckCircle2 class="h-4 w-4 shrink-0 mt-0.5" />
									<span>{pwSuccess}</span>
								</div>
							{/if}

							<div class="flex justify-end pt-1">
								<Button onclick={handleChangePassword} disabled={pwSubmitting} class="gap-2">
									{#if pwSubmitting}
										<Loader2 class="h-4 w-4 animate-spin" />
										{i18n('setting.profile.updating')}
									{:else}
										{i18n('setting.profile.updatePassword')}
									{/if}
								</Button>
							</div>
						</div>
					</Card>

					<Card class="border-destructive/50 p-6">
						<div class="flex items-center justify-between">
							<div class="flex items-center gap-3">
								<div class="flex h-10 w-10 items-center justify-center rounded-lg bg-destructive/10">
									<LogOut class="h-5 w-5 text-destructive" />
								</div>
								<div>
									<p class="font-medium">Sign Out</p>
									<p class="text-sm text-muted-foreground">Sign out of your account on this device</p>
								</div>
							</div>
							<Button variant="destructive" disabled={loggingOut} onclick={handleLogout}>
								{loggingOut ? 'Signing out...' : 'Sign Out'}
							</Button>
						</div>
					</Card>
				</div>
			{/if}
		</div>
	</div>
</div>
