<script lang="ts">
	/**
	 * Markdown Renderer using marked library
	 * Synchronous rendering with $derived for immediate inline code parsing
	 * Includes DDL tooltip on hover for table name patterns (DATABASE.SCHEMA.TABLE)
	 */
	import { Marked } from 'marked';
	import { cn } from '$lib/utils/cn';
	import { onDestroy } from 'svelte';
	import sqlService from '$lib/service/sql';

	// === Table name detection (matching Next.js TableCodeTooltip logic) ===
	// Only match fully qualified table names: DATABASE.SCHEMA.TABLE (3-part)
	const FULL_TABLE_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/;

	function isTableNamePattern(code: string): boolean {
		return FULL_TABLE_NAME_PATTERN.test(code);
	}

	function parseTableName(code: string) {
		const parts = code.split('.');
		// Always 3-part: DATABASE.SCHEMA.TABLE
		return { databaseName: parts[0], schemaName: parts[1], tableName: parts[2] };
	}

	// === SQL syntax highlighting (returns HTML string) ===
	const SQL_KEYWORDS = new Set([
		'CREATE', 'TABLE', 'ALTER', 'DROP', 'INDEX', 'VIEW', 'PRIMARY', 'KEY', 'FOREIGN',
		'REFERENCES', 'UNIQUE', 'CONSTRAINT', 'ENGINE', 'AUTO_INCREMENT', 'COMMENT', 'USING',
		'ON', 'UPDATE', 'DELETE', 'CASCADE', 'SET', 'COLLATE', 'CHARACTER',
	]);
	const SQL_DATATYPES = new Set([
		'INT', 'INTEGER', 'BIGINT', 'SMALLINT', 'TINYINT', 'FLOAT', 'DOUBLE', 'DECIMAL',
		'NUMERIC', 'CHAR', 'VARCHAR', 'TEXT', 'LONGTEXT', 'MEDIUMTEXT', 'TINYTEXT', 'BLOB',
		'DATE', 'DATETIME', 'TIMESTAMP', 'TIME', 'YEAR', 'BOOLEAN', 'BOOL', 'ENUM', 'JSON',
		'BINARY', 'VARBINARY', 'NUMBER', 'STRING', 'VARIANT', 'OBJECT', 'ARRAY',
		'TIMESTAMP_NTZ', 'TIMESTAMP_LTZ', 'TIMESTAMP_TZ',
	]);
	const SQL_CONSTRAINTS = new Set(['NOT', 'NULL', 'DEFAULT', 'CURRENT_TIMESTAMP', 'TRUE', 'FALSE']);

	function escHtml(t: string): string {
		return t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

	function highlightSQL(sql: string): string {
		return sql.split('\n').map(line => {
			let result = '';
			let remaining = line;
			while (remaining.length > 0) {
				const strMatch = remaining.match(/^'[^']*'/);
				if (strMatch) { result += `<span class="ddl-s-str">${escHtml(strMatch[0])}</span>`; remaining = remaining.slice(strMatch[0].length); continue; }
				const btMatch = remaining.match(/^`[^`]+`/);
				if (btMatch) { result += `<span class="ddl-s-id">${escHtml(btMatch[0])}</span>`; remaining = remaining.slice(btMatch[0].length); continue; }
				const numMatch = remaining.match(/^\d+(\.\d+)?/);
				if (numMatch) { result += `<span class="ddl-s-num">${numMatch[0]}</span>`; remaining = remaining.slice(numMatch[0].length); continue; }
				const wordMatch = remaining.match(/^[A-Za-z_][A-Za-z0-9_]*/);
				if (wordMatch) {
					const w = wordMatch[0], u = w.toUpperCase();
					if (SQL_KEYWORDS.has(u)) result += `<span class="ddl-s-kw">${escHtml(w)}</span>`;
					else if (SQL_DATATYPES.has(u)) result += `<span class="ddl-s-dt">${escHtml(w)}</span>`;
					else if (SQL_CONSTRAINTS.has(u)) result += `<span class="ddl-s-cn">${escHtml(w)}</span>`;
					else result += escHtml(w);
					remaining = remaining.slice(w.length);
					continue;
				}
				result += escHtml(remaining[0]);
				remaining = remaining.slice(1);
			}
			return `<div class="ddl-sql-line">${result}</div>`;
		}).join('');
	}

	// === Marked instance with custom codespan renderer ===
	const markedInstance = new Marked({
		breaks: true,
		gfm: true,
		renderer: {
			codespan({ text }) {
				// Decode HTML entities for pattern matching (table names are alphanumeric + _ + .)
				const decoded = text.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"');
				if (isTableNamePattern(decoded)) {
					return `<code class="ddl-table-code" data-table-name="${decoded}">${text}</code>`;
				}
				return `<code>${text}</code>`;
			}
		}
	});

	// === Props ===
	interface Props {
		content: string;
		class?: string;
		dataSourceId?: number;
		databaseName?: string;
		schemaName?: string;
		/** Use compact DDL tooltip for narrow panels (embedded chat, sidebar) */
		compact?: boolean;
	}

	let { content, class: className = '', dataSourceId, databaseName, schemaName, compact = false }: Props = $props();

	/** Normalize LLM markdown quirks for both English and Korean before marked parses. */
	function normalizeLlmMarkdown(content: string): string {
		if (!content) return '';

		// `** spaced bold **` → valid CommonMark (marked rejects spaces inside delimiters).
		let fixed = content.replace(/\*\*[ \t]+([^*\n]*?)[ \t]+\*\*/g, '**$1**');
		fixed = fixed.replace(/\*\*([^*\n]+?)\*\*/g, (_, inner) => `**${inner.trim()}**`);

		// Glued neighbors: between**Sports**and**Clothing** → pad each complete bold span.
		fixed = fixed.replace(/\*\*([^*\n]+?)\*\*/g, (match, _inner, offset, str) => {
			const before = offset > 0 ? str[offset - 1] : ' ';
			const after = offset + match.length < str.length ? str[offset + match.length] : ' ';
			let out = match;
			if (/[A-Za-z0-9_\uAC00-\uD7A3)]/.test(before)) {
				out = ' ' + out;
			}
			if (/[A-Za-z0-9_\uAC00-\uD7A3]/.test(after)) {
				out = out + ' ';
			}
			return out;
		});

		// Colon glued to the next word (Competition:There → Competition: There).
		fixed = fixed.replace(/:([A-Za-z\uAC00-\uD7A3])/g, ': $1');

		// CommonMark: closing ** directly before CJK suffix particles needs a word boundary.
		fixed = fixed.replace(
			/\*\*([^*\n]+?)\*\*((?:\uC740|\uB294|\uC774|\uAC00|\uC744|\uB97C|\uC640|\uACFC|\uC758|\uC5D0|\uB3C4|\uB85C|\uC73C\uB85C|\uB9CC|\uBD80\uD130|\uAE4C\uC9C0|\uBCF4\uB2E4|\uCC98\uB7FC))(?=$|[\s.,!?;:)\]}])/gu,
			'**$1**<!-- -->$2'
		);
		fixed = fixed.replace(
			/\*\*([^*\n]+?)\*\*([\uAC00-\uD7A3]{1,8})(?=$|[\s.,!?;:)\]}])/gu,
			'**$1**<!-- -->$2'
		);

		return fixed;
	}

	// Synchronous derived HTML
	let html = $derived.by(() => {
		if (!content) return '';
		const fixed = normalizeLlmMarkdown(content);
		return markedInstance.parse(fixed) as string;
	});

	// === DDL Floating Tooltip ===
	let containerEl = $state<HTMLDivElement | null>(null);
	let tooltipEl: HTMLDivElement | null = null;
	const ddlCache = new Map<string, string>();
	let cleanupFns: (() => void)[] = [];
	let hideTimer: ReturnType<typeof setTimeout> | null = null;
	let showTimer: ReturnType<typeof setTimeout> | null = null;
	let currentTarget: HTMLElement | null = null;

	function getOrCreateTooltip(): HTMLDivElement {
		if (!tooltipEl) {
			tooltipEl = document.createElement('div');
			tooltipEl.className = compact ? 'ddl-floating-tooltip ddl-compact' : 'ddl-floating-tooltip';
			tooltipEl.style.cssText = 'display:none;position:fixed;z-index:99999;';
			document.body.appendChild(tooltipEl);

			// Keep tooltip open while hovering over it
			tooltipEl.addEventListener('mouseenter', () => {
				if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
			});
			tooltipEl.addEventListener('mouseleave', () => {
				scheduleHide();
			});
		}
		return tooltipEl;
	}

	function positionTooltip(tooltip: HTMLDivElement, targetRect: DOMRect) {
		// Reset for measurement
		tooltip.style.left = '0px';
		tooltip.style.top = '0px';

		requestAnimationFrame(() => {
			if (tooltip.style.display === 'none') return;
			const tRect = tooltip.getBoundingClientRect();

			// Try above the target
			let top = targetRect.top - tRect.height - 8;
			if (top < 8) {
				// Fallback: below the target
				top = targetRect.bottom + 8;
			}

			let left = targetRect.left;
			// Clamp horizontally
			if (left + tRect.width > window.innerWidth - 8) {
				left = window.innerWidth - tRect.width - 8;
			}
			if (left < 8) left = 8;

			tooltip.style.left = `${left}px`;
			tooltip.style.top = `${top}px`;
		});
	}

	async function showTooltipFor(target: HTMLElement, tableName: string) {
		if (!dataSourceId) return;
		currentTarget = target;

		const tooltip = getOrCreateTooltip();
		const rect = target.getBoundingClientRect();
		tooltip.style.display = 'block';

		// Check cache
		const cacheKey = `${dataSourceId}:${tableName}`;
		const header = `<div class="ddl-tooltip-header"><span class="ddl-tooltip-icon">&#x1F4CB;</span>${escHtml(tableName)}</div>`;
		if (ddlCache.has(cacheKey)) {
			tooltip.innerHTML = `${header}<div class="ddl-tooltip-body">${highlightSQL(ddlCache.get(cacheKey)!)}</div>`;
			positionTooltip(tooltip, rect);
			return;
		}

		tooltip.innerHTML = `${header}<div class="ddl-tooltip-loading"><span class="ddl-spinner"></span>Loading DDL...</div>`;
		positionTooltip(tooltip, rect);

		const parsed = parseTableName(tableName);

		try {
			const result = await sqlService.exportCreateTableSql({
				dataSourceId,
				databaseName: parsed.databaseName,
				schemaName: parsed.schemaName,
				name: parsed.tableName,
			});
			if (result && tooltip.style.display !== 'none' && currentTarget === target) {
				ddlCache.set(cacheKey, result as string);
				tooltip.innerHTML = `${header}<div class="ddl-tooltip-body">${highlightSQL(result as string)}</div>`;
				positionTooltip(tooltip, target.getBoundingClientRect());
			}
		} catch {
			if (tooltip.style.display !== 'none' && currentTarget === target) {
				tooltip.innerHTML = `${header}<div class="ddl-tooltip-err">Failed to load DDL</div>`;
			}
		}
	}

	function scheduleHide() {
		if (hideTimer) clearTimeout(hideTimer);
		hideTimer = setTimeout(() => {
			if (tooltipEl) tooltipEl.style.display = 'none';
			currentTarget = null;
		}, 150);
	}

	function attachHandlers() {
		cleanupFns.forEach(fn => fn());
		cleanupFns = [];
		if (!containerEl || !dataSourceId) return;

		const codeEls = containerEl.querySelectorAll<HTMLElement>('code[data-table-name]');
		codeEls.forEach(el => {
			const tableName = el.getAttribute('data-table-name')!;
			const onEnter = () => {
				if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
				if (showTimer) clearTimeout(showTimer);
				showTimer = setTimeout(() => showTooltipFor(el, tableName), 300);
			};
			const onLeave = () => {
				if (showTimer) { clearTimeout(showTimer); showTimer = null; }
				scheduleHide();
			};
			el.addEventListener('mouseenter', onEnter);
			el.addEventListener('mouseleave', onLeave);
			cleanupFns.push(() => {
				el.removeEventListener('mouseenter', onEnter);
				el.removeEventListener('mouseleave', onLeave);
			});
		});
	}

	// Re-attach handlers when html or dataSourceId changes
	$effect(() => {
		html; // dependency
		dataSourceId; // dependency
		requestAnimationFrame(() => attachHandlers());
		return () => {
			cleanupFns.forEach(fn => fn());
			cleanupFns = [];
		};
	});

	onDestroy(() => {
		if (tooltipEl) { tooltipEl.remove(); tooltipEl = null; }
		if (hideTimer) clearTimeout(hideTimer);
		if (showTimer) clearTimeout(showTimer);
	});
</script>

<div bind:this={containerEl} class={cn('prose dark:prose-invert max-w-none', compact ? 'prose-compact' : 'prose-sm', dataSourceId ? 'ddl-enabled' : '', className)}>
	{@html html}
</div>

<style>
	/* === Compact mode: inherit parent font-size instead of prose-sm's 14px === */
	.prose-compact {
		font-size: inherit !important;
		line-height: inherit !important;
	}
	.prose-compact :global(p),
	.prose-compact :global(ul),
	.prose-compact :global(ol),
	.prose-compact :global(li),
	.prose-compact :global(blockquote) {
		font-size: inherit;
		line-height: 1.5;
		margin-top: 0.25em;
		margin-bottom: 0.25em;
	}
	.prose-compact :global(h1) { font-size: 1.25em; margin-top: 0.5em; margin-bottom: 0.25em; }
	.prose-compact :global(h2) { font-size: 1.15em; margin-top: 0.5em; margin-bottom: 0.25em; }
	.prose-compact :global(h3) { font-size: 1.1em; margin-top: 0.4em; margin-bottom: 0.2em; }
	.prose-compact :global(h4),
	.prose-compact :global(h5),
	.prose-compact :global(h6) { font-size: 1em; margin-top: 0.3em; margin-bottom: 0.15em; }

	/* === Base prose overrides === */
	.prose :global(pre) {
		background-color: var(--color-muted);
		border: 1px solid var(--color-border);
		border-radius: 0.5rem;
		padding: 0.75rem 1rem;
		overflow-x: auto;
	}
	.prose :global(code) {
		font-size: 0.8em;
		color: hsl(var(--primary)) !important;
		background-color: var(--color-muted);
		padding: 0.15rem 0.4rem;
		border-radius: 0.25rem;
	}
	.prose :global(code::before),
	.prose :global(code::after) {
		content: none !important;
	}
	.prose :global(pre code) {
		background: none;
		padding: 0;
		border: none;
		font-weight: normal;
	}
	.prose :global(table) {
		width: 100%;
		border-collapse: collapse;
	}
	.prose :global(th),
	.prose :global(td) {
		border: 1px solid var(--color-border);
		padding: 0.375rem 0.75rem;
		text-align: left;
	}
	.prose :global(th) {
		background-color: var(--color-muted);
		font-weight: 600;
	}
	.prose :global(blockquote) {
		border-left: 3px solid var(--color-primary);
		padding-left: 1rem;
		color: var(--color-muted-foreground);
	}
	.prose :global(blockquote p:first-of-type::before),
	.prose :global(blockquote p:last-of-type::after) {
		content: none !important;
	}

	/* === Table code hover indicator (only when DDL is available) === */
	.ddl-enabled :global(code.ddl-table-code) {
		cursor: pointer;
		transition: background-color 0.2s, outline-color 0.2s;
	}
	.ddl-enabled :global(code.ddl-table-code:hover) {
		background-color: hsl(var(--primary) / 0.12) !important;
		outline: 1.5px solid hsl(var(--primary) / 0.4);
		outline-offset: 1px;
	}

	/* === Floating DDL Tooltip (appended to body, needs global styles) === */
	:global(.ddl-floating-tooltip) {
		min-width: 480px;
		max-width: min(800px, 90vw);
		background: hsl(var(--popover));
		border: 1px solid hsl(var(--border) / 0.6);
		border-radius: 12px;
		box-shadow:
			0 4px 24px rgba(0, 0, 0, 0.10),
			0 1.5px 6px rgba(0, 0, 0, 0.06);
		backdrop-filter: blur(8px);
		overflow: hidden;
	}
	:global(.dark .ddl-floating-tooltip) {
		box-shadow:
			0 4px 24px rgba(0, 0, 0, 0.35),
			0 1.5px 6px rgba(0, 0, 0, 0.2);
	}

	/* Compact mode for narrow panels (embedded chat, workspace sidebar) */
	:global(.ddl-floating-tooltip.ddl-compact) {
		min-width: 260px;
		max-width: min(400px, 80vw);
	}
	:global(.ddl-floating-tooltip.ddl-compact .ddl-tooltip-body) {
		max-height: min(320px, calc(50vh - 44px));
		font-size: 11.5px;
		padding: 10px 14px;
	}
	:global(.ddl-floating-tooltip.ddl-compact .ddl-tooltip-header) {
		padding: 8px 12px;
		font-size: 11px;
	}

	/* Header with table name */
	:global(.ddl-tooltip-header) {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 10px 16px;
		font-size: 12px;
		font-weight: 600;
		color: hsl(var(--foreground));
		border-bottom: 1px solid hsl(var(--border) / 0.4);
		background: hsl(var(--muted) / 0.3);
		flex-shrink: 0;
		letter-spacing: 0.01em;
	}
	:global(.ddl-tooltip-icon) {
		font-size: 13px;
		line-height: 1;
	}

	/* Loading state */
	:global(.ddl-tooltip-loading) {
		display: flex;
		align-items: center;
		gap: 8px;
		color: hsl(var(--muted-foreground));
		padding: 20px 16px;
		font-size: 13px;
	}

	/* Error state */
	:global(.ddl-tooltip-err) {
		color: hsl(var(--destructive));
		padding: 14px 16px;
		font-size: 12px;
	}

	/* DDL code body — scrollable */
	:global(.ddl-tooltip-body) {
		padding: 14px 18px;
		font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', 'Consolas', 'source-code-pro', monospace;
		font-size: 12.5px;
		line-height: 1.65;
		max-height: min(500px, calc(70vh - 44px));
		overflow: auto;
		color: hsl(var(--foreground) / 0.85);
	}
	:global(.ddl-tooltip-body::-webkit-scrollbar) {
		width: 6px;
		height: 6px;
	}
	:global(.ddl-tooltip-body::-webkit-scrollbar-thumb) {
		background: hsl(var(--muted-foreground) / 0.25);
		border-radius: 3px;
	}
	:global(.ddl-tooltip-body::-webkit-scrollbar-thumb:hover) {
		background: hsl(var(--muted-foreground) / 0.4);
	}
	:global(.ddl-tooltip-body::-webkit-scrollbar-track) {
		background: transparent;
	}
	:global(.ddl-sql-line) {
		white-space: pre;
		min-width: fit-content;
	}

	/* SQL syntax highlighting – Light */
	:global(.ddl-tooltip-body .ddl-s-kw) { color: #2563eb; font-weight: 600; }
	:global(.ddl-tooltip-body .ddl-s-dt) { color: #059669; font-weight: 500; }
	:global(.ddl-tooltip-body .ddl-s-cn) { color: #dc2626; font-weight: 500; }
	:global(.ddl-tooltip-body .ddl-s-str) { color: #9333ea; }
	:global(.ddl-tooltip-body .ddl-s-id) { color: #374151; }
	:global(.ddl-tooltip-body .ddl-s-num) { color: #d97706; }

	/* SQL syntax highlighting – Dark */
	:global(.dark .ddl-tooltip-body .ddl-s-kw) { color: #60a5fa; font-weight: 600; }
	:global(.dark .ddl-tooltip-body .ddl-s-dt) { color: #34d399; font-weight: 500; }
	:global(.dark .ddl-tooltip-body .ddl-s-cn) { color: #f472b6; font-weight: 500; }
	:global(.dark .ddl-tooltip-body .ddl-s-str) { color: #c084fc; }
	:global(.dark .ddl-tooltip-body .ddl-s-id) { color: #d1d5db; }
	:global(.dark .ddl-tooltip-body .ddl-s-num) { color: #fbbf24; }

	/* Spinner */
	:global(.ddl-spinner) {
		width: 14px;
		height: 14px;
		border: 2px solid hsl(var(--muted-foreground) / 0.2);
		border-top-color: hsl(var(--primary));
		border-radius: 50%;
		animation: ddl-spin 0.6s linear infinite;
		display: inline-block;
		flex-shrink: 0;
	}

	@keyframes -global-ddl-spin {
		to { transform: rotate(360deg); }
	}
</style>
