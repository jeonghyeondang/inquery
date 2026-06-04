<script lang="ts">
	import {
		getDeepResearchStore,
		exportResearchAsPDF,
		exportResearchAsMarkdown,
		exportResearchAsDOCX,
		generateInfographic,
		deleteCurrentResearch
	} from '$lib/stores/deepResearch.svelte';
	import type { ISectionCitation } from '$lib/stores/aiChat.svelte';
	import { getBaseURL } from '$lib/service/base';
	import { MarkdownRenderer } from '$lib/components/MarkdownRenderer';
	import MonacoEditor from '$lib/components/MonacoEditor/MonacoEditor.svelte';
	import { getThemeStore } from '$lib/stores/theme.svelte';
	import { Popover, PopoverTrigger, PopoverContent } from '$lib/components/ui';
	import {
		Loader2, BarChart3, ChevronDown, ChevronRight, ChevronLeft,
		Database, Code, X, Globe, List, Trash2, ArrowLeft, Eye, Download
	} from 'lucide-svelte';
	import i18n from '$lib/i18n';

	let {
		onClose,
		onInfographicComplete
	}: {
		onClose?: () => void;
		onInfographicComplete?: (html: string) => void;
	} = $props();

	const store = getDeepResearchStore();
	const themeStore = getThemeStore();

	// State
	let showExportMenu = $state(false);
	let isExporting = $state<'pdf' | 'markdown' | 'docx' | null>(null);
	let expandedSections = $state<Set<number>>(new Set());
	let showCitation = $state<{ table: string; query: string } | null>(null);
	let contentsOpen = $state(false);
	let showDeleteConfirm = $state(false);
	let scrollContainer = $state<HTMLDivElement | null>(null);
	let sectionRefs = $state<HTMLElement[]>([]);

	// Infographic state
	let showInfographic = $state(false);
	let infographicTab = $state<'code' | 'preview'>('code');
	let isDownloadingPDF = $state(false);
	let editedInfographicHtml = $state('');
	let infographicEditorInstance = $state<any>(null);
	let infographicIframeEl = $state<HTMLIFrameElement | null>(null);

	// Streaming write state removed

	// Initialize all sections expanded when report loads
	$effect(() => {
		if (store.state.report?.sections) {
			const indices = store.state.report.sections.map((_: any, i: number) => i);
			indices.push(-1); // Sources section
			expandedSections = new Set(indices);
			sectionRefs = new Array(store.state.report.sections.length);
		}
	});

	// Show infographic panel when generation starts, reset tab to code
	let hasAutoSwitchedInfographic = $state(false);
	$effect(() => {
		if (store.state.isInfographicGenerating) {
			showInfographic = true;
			infographicTab = 'code';
			hasAutoSwitchedInfographic = false;
		}
	});

	// Auto-switch to preview once enough lines are streamed
	$effect(() => {
		if (!hasAutoSwitchedInfographic && editedInfographicHtml.split('\n').length > 50) {
			infographicTab = 'preview';
			hasAutoSwitchedInfographic = true;
		}
	});

	// Sync store HTML during generation
	$effect(() => {
		if (store.state.isInfographicGenerating || store.state.infographicHtml) {
			editedInfographicHtml = store.state.infographicHtml;
		}
	});

	// Real-time preview update with Blob and postMessage
	let infographicBlobUrl: string | null = null;

	$effect(() => {
		if (infographicBlobUrl) URL.revokeObjectURL(infographicBlobUrl);
		const previewBg = themeStore.isDark ? 'hsl(0 0% 12%)' : 'hsl(0 0% 100%)';
		const previewColor = themeStore.isDark ? 'hsl(210 40% 98%)' : 'hsl(222.2 84% 4.9%)';

		const baseHtml = [
			'<!DOCTYPE html>',
			'<html>',
			'<head>',
			'	<meta charset="utf-8">',
			'	<style>',
			`		html, body { margin: 0; min-height: 100%; background: ${previewBg}; color: ${previewColor}; }`,
			'		body { padding: 1rem; font-family: system-ui, sans-serif; }',
			'		/* Hide broken images during generation */',
			'		img:-moz-loading { visibility: hidden; }',
			'	</style>',
			'</head>',
			'<body>',
			'	<div id="content"></div>',
			'	<scr' + 'ipt>',
			'		let isAutoScrolling = true;',
			'',
			'		// Detect manual scroll up to pause auto-scroll',
			'		window.addEventListener("scroll", () => {',
			'			const isAtBottom = window.innerHeight + window.scrollY >= document.body.offsetHeight - 50;',
			'			if (!isAtBottom) {',
			'				isAutoScrolling = false;',
			'			} else {',
			'				isAutoScrolling = true;',
			'			}',
			'		});',
			'',
			'		window.addEventListener("message", (event) => {',
			'			if (event.data.type === "request-html") {',
			'				event.source.postMessage({ type: "response-html", html: document.body.innerHTML }, "*");',
			'				return;',
			'			}',
			'			const contentDiv = document.getElementById("content");',
			'			if (contentDiv && event.data.html !== undefined) {',
			'				contentDiv.innerHTML = event.data.html;',
			'				if (event.data.isGenerating && isAutoScrolling) {',
			'					window.scrollTo(0, document.body.scrollHeight);',
			'				}',
			'			}',
			'		});',
			'	</scr' + 'ipt>',
			'</body>',
			'</html>'
		].join('\n');

		const blob = new Blob([baseHtml], { type: 'text/html' });
		infographicBlobUrl = URL.createObjectURL(blob);

		if (infographicIframeEl) {
			infographicIframeEl.src = infographicBlobUrl;
			infographicIframeEl.onload = () => {
				if (infographicIframeEl?.contentWindow) {
					infographicIframeEl.contentWindow.postMessage({ 
						html: editedInfographicHtml, 
						isGenerating: store.state.isInfographicGenerating 
					}, '*');
				}
			};
		}

		return () => {
			if (infographicBlobUrl) URL.revokeObjectURL(infographicBlobUrl);
		};
	});

	// Send HTML updates continuously without reloading the iframe
	$effect(() => {
		if (infographicIframeEl && infographicIframeEl.contentWindow && infographicTab === 'preview') {
			infographicIframeEl.contentWindow.postMessage({ 
				html: editedInfographicHtml, 
				isGenerating: store.state.isInfographicGenerating 
			}, '*');
		}
	});

	// Auto-scroll code editor during generation
	$effect(() => {
		if (store.state.isInfographicGenerating && infographicTab === 'code' && infographicEditorInstance) {
			requestAnimationFrame(() => {
				const model = infographicEditorInstance?.getModel();
				if (model) infographicEditorInstance.revealLine(model.getLineCount());
			});
		}
	});



	// Download infographic as PDF
	async function handleInfographicDownloadPDF() {
		console.log('[PDF Debug] editedInfographicHtml length:', editedInfographicHtml?.length, 'store infographicHtml length:', store.state.infographicHtml?.length, 'isGenerating:', store.state.isInfographicGenerating);
		const htmlToExport = editedInfographicHtml || store.state.infographicHtml;
		if (!htmlToExport || store.state.isInfographicGenerating) return;

		isDownloadingPDF = true;

		try {
			// Send the complete HTML document directly (editedInfographicHtml preserves head/style/script tags)
			// Using iframe's document.body.innerHTML would lose the <head> section and break styling.
			const response = await fetch(`${getBaseURL()}/api/v1/export/pdf`, {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({ html: htmlToExport })
			});

			if (!response.ok) {
				throw new Error(`Backend PDF generation failed with status ${response.status}`);
			}

			const blob = new Blob([await response.arrayBuffer()], { type: 'application/pdf' });
			const downloadUrl = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = downloadUrl;
			a.download = `infographic-${Date.now()}.pdf`;
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
			setTimeout(() => URL.revokeObjectURL(downloadUrl), 30000);
		} catch (error) {
			console.error('PDF download failed:', error);
		} finally {
			isDownloadingPDF = false;
		}
	}

	// Section toggle
	function toggleSection(index: number) {
		const newSet = new Set(expandedSections);
		if (newSet.has(index)) newSet.delete(index);
		else newSet.add(index);
		expandedSections = newSet;
	}

	// TOC scroll-to-section
	function scrollToSection(index: number) {
		const newSet = new Set(expandedSections);
		newSet.add(index);
		expandedSections = newSet;
		contentsOpen = false;
		setTimeout(() => {
			sectionRefs[index]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
		}, 100);
	}

	function scrollToSources() {
		const newSet = new Set(expandedSections);
		newSet.add(-1);
		expandedSections = newSet;
		contentsOpen = false;
		setTimeout(() => {
			scrollContainer?.scrollTo({ top: scrollContainer.scrollHeight, behavior: 'smooth' });
		}, 100);
	}

	// Export handler
	async function handleExport(format: 'pdf' | 'markdown' | 'docx') {
		showExportMenu = false;
		isExporting = format;
		try {
			if (format === 'pdf') {
				await exportResearchAsPDF();
			} else if (format === 'markdown') {
				exportResearchAsMarkdown();
			} else if (format === 'docx') {
				await exportResearchAsDOCX();
			}
		} finally {
			isExporting = null;
		}
	}

	// Citation helpers
	function isValidCitation(c: any): boolean {
		if (!c || !c.title?.trim()) return false;
		if (c.type === 'database') return !!c.table?.trim();
		if (c.type === 'web') return !!c.url?.trim();
		return !!(c.table?.trim() || c.url?.trim());
	}

	function getValidCitations(citations?: ISectionCitation[]): ISectionCitation[] {
		if (!citations || !Array.isArray(citations)) return [];
		return citations.filter(isValidCitation);
	}

	// Markdown table parsing
	function parseMarkdownTable(markdown: string): { headers: string[]; rows: string[][] } | null {
		if (!markdown) return null;
		const lines = markdown.trim().split('\n');
		if (lines.length < 2) return null;

		const headers = lines[0].split('|').map(h => h.trim()).filter(h => h);
		if (!lines[1]?.includes('---')) return null;

		const rows = lines.slice(2)
			.filter(line => line.trim())
			.map(line => line.split('|').map(c => c.trim()).filter(c => c));
		return { headers, rows };
	}

	function parseInlineBold(text: string): string {
		if (!text) return '';
		return text
			.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
			.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
	}

	function getDomainFromUrl(url: string): string {
		try { return new URL(url).hostname.replace('www.', ''); }
		catch { return url; }
	}
</script>

<div class="flex flex-col h-full">
	<!-- Header -->
	<div class="flex items-center justify-between px-4 py-3 border-b border-border gap-2">
		<h2 class="text-sm font-semibold text-foreground truncate flex-1 min-w-0">
			{store.state.report?.title || i18n('research.report.defaultTitle')}
		</h2>
		<div class="flex items-center gap-2 flex-shrink-0">
			<!-- Table of Contents -->
			{#if store.state.report?.sections?.length}
				<Popover bind:open={contentsOpen}>
					<PopoverTrigger>
						<button class="px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-accent text-foreground transition-colors flex items-center gap-1.5">
							<List class="h-3 w-3" />
							{i18n('research.contents')}
							<ChevronDown class="h-3 w-3 opacity-50" />
						</button>
					</PopoverTrigger>
					<PopoverContent align="end" class="w-72 max-h-[60vh] overflow-y-auto p-0">
						{#if store.state.report?.title}
							<div class="px-4 py-3 border-b border-border">
								<span class="text-xs font-semibold text-foreground line-clamp-2">{store.state.report.title}</span>
							</div>
						{/if}
						<div class="py-1">
							{#if store.state.report?.sections}
								{#each store.state.report.sections as section, index}
									<button
										class="w-full px-4 py-2 text-left text-xs hover:bg-accent transition-colors text-foreground font-medium truncate"
										onclick={() => scrollToSection(index)}
									>
										{section.title}
									</button>
								{/each}
							{/if}
							{#if (store.state.report?.citations?.length ?? 0) > 0 || (store.state.report?.webSources?.length ?? 0) > 0}
								<div class="border-t border-border mt-1 pt-1">
									<button
										class="w-full px-4 py-2 text-left text-xs hover:bg-accent transition-colors text-muted-foreground"
										onclick={scrollToSources}
									>
										{i18n('research.sources')}
									</button>
								</div>
							{/if}
						</div>
					</PopoverContent>
				</Popover>
			{/if}

			<!-- Infographic button -->
			{#if showInfographic}
				<button
					class="px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-accent text-foreground transition-colors flex items-center gap-1.5"
					onclick={() => { showInfographic = false; }}
				>
					<ArrowLeft class="h-3 w-3" /> {i18n('research.report.back')}
				</button>
			{:else}
				<button
					class="px-3 py-1.5 text-xs rounded-md bg-primary/10 text-primary hover:bg-primary/20 transition-colors disabled:opacity-50 flex items-center gap-1.5"
					onclick={() => {
						if (store.state.infographicHtml) {
							showInfographic = true;
						} else {
							generateInfographic(onInfographicComplete);
						}
					}}
					disabled={store.state.isInfographicGenerating || !store.state.sessionId}
				>
					{#if store.state.isInfographicGenerating}
						<Loader2 class="h-3 w-3 animate-spin" /> {i18n('research.infographic.generating')}
					{:else}
						<BarChart3 class="h-3 w-3" /> {i18n('research.infographic')}
					{/if}
				</button>
			{/if}

			<!-- Export dropdown -->
			<div class="relative">
				<button
					class="px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-accent text-foreground transition-colors flex items-center gap-1.5 disabled:opacity-50"
					onclick={() => { if (!isExporting) showExportMenu = !showExportMenu; }}
					disabled={!!isExporting}
				>
					{#if isExporting}
						<Loader2 class="h-3 w-3 animate-spin" />
						{i18n('research.exporting')}
					{:else}
						{i18n('research.export')} ▾
					{/if}
				</button>
				{#if showExportMenu && !isExporting}
					<div class="absolute right-0 top-full mt-1 bg-popover border border-border rounded-md shadow-lg z-10 py-1 min-w-[140px]">
						<button class="w-full px-3 py-1.5 text-xs text-left hover:bg-accent transition-colors" onclick={() => handleExport('pdf')}>
							{i18n('research.export.pdf')}
						</button>
						<button class="w-full px-3 py-1.5 text-xs text-left hover:bg-accent transition-colors" onclick={() => handleExport('markdown')}>
							{i18n('research.export.markdown')}
						</button>
						<button class="w-full px-3 py-1.5 text-xs text-left hover:bg-accent transition-colors" onclick={() => handleExport('docx')}>
							{i18n('research.export.docx')}
						</button>
					</div>
				{/if}
			</div>

			<!-- Delete button -->
			<button
				class="p-1.5 rounded-md hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"
				onclick={() => { showDeleteConfirm = true; }}
				title={i18n('research.delete.title')}
			>
				<Trash2 class="h-4 w-4" />
			</button>

			<!-- Close button -->
			{#if onClose}
				<button
					class="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
					onclick={onClose}
					title={i18n('research.close')}
				>
					<X class="h-4 w-4" />
				</button>
			{/if}
		</div>
	</div>

	<!-- Content: Report or Infographic -->
	{#if showInfographic || store.state.isInfographicGenerating}
		<!-- Infographic Panel (inline) -->
		<div class="flex-1 flex flex-col overflow-hidden">
			<!-- Infographic toolbar -->
			<div class="flex items-center justify-between px-4 py-2 border-b border-border bg-muted/30">
				<div class="flex items-center bg-muted rounded-lg p-0.5">
					<button
						class="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md transition-colors {infographicTab === 'preview' ? 'bg-background text-foreground shadow-sm font-medium' : 'text-muted-foreground hover:text-foreground'}"
						onclick={() => { infographicTab = 'preview'; }}
					>
						<Eye class="h-3.5 w-3.5" />
						{i18n('research.preview')}
					</button>
					<button
						class="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md transition-colors {infographicTab === 'code' ? 'bg-background text-foreground shadow-sm font-medium' : 'text-muted-foreground hover:text-foreground'}"
						onclick={() => { infographicTab = 'code'; }}
					>
						<Code class="h-3.5 w-3.5" />
						{i18n('research.code')}
					</button>
				</div>

				<div class="flex items-center gap-2">
					{#if store.state.isInfographicGenerating}
						<div class="flex items-center gap-2 text-xs text-muted-foreground">
							<Loader2 class="h-3.5 w-3.5 animate-spin" />
							{i18n('research.infographic.generating')}
						</div>
					{:else if store.state.infographicHtml}
						<button
							class="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
							onclick={handleInfographicDownloadPDF}
							disabled={isDownloadingPDF}
						>
							{#if isDownloadingPDF}
								<Loader2 class="h-3.5 w-3.5 animate-spin" /> {i18n('research.downloading')}
							{:else}
								<Download class="h-3.5 w-3.5" /> {i18n('research.downloadPdf')}
							{/if}
						</button>
						<button
							class="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-accent text-foreground transition-colors"
							onclick={() => generateInfographic(onInfographicComplete)}
						>
							{i18n('research.regenerate')}
						</button>
					{/if}
				</div>
			</div>

			<!-- Infographic content: both tabs always mounted to avoid Monaco destroy -->
			<div class="flex-1 overflow-hidden">
				<div class="h-full overflow-hidden" style:display={infographicTab === 'preview' ? 'block' : 'none'}>
					{#if editedInfographicHtml}
						<iframe
							bind:this={infographicIframeEl}
							class="w-full h-full border-none bg-background"
							sandbox="allow-scripts"
							title={i18n('research.infographic.previewTitle')}
						></iframe>
					{:else}
						<div class="flex flex-col items-center justify-center h-full text-muted-foreground gap-3">
							<Loader2 class="h-8 w-8 animate-spin" />
							<p class="text-sm">{i18n('research.infographic.generatingMessage')}</p>
						</div>
					{/if}
				</div>
				<div class="h-full" style:display={infographicTab === 'code' ? 'block' : 'none'}>
					<MonacoEditor
						bind:value={editedInfographicHtml}
						language="html"
						readOnly={store.state.isInfographicGenerating}
						onmount={(editor) => { infographicEditorInstance = editor; }}
						options={{
							scrollBeyondLastLine: false,
							formatOnPaste: true
						}}
					/>
				</div>
			</div>
		</div>
	{:else}
	<!-- Report Content -->
	<div class="flex-1 overflow-auto p-6" data-research-report bind:this={scrollContainer}>
		{#if store.state.report}
			{@const report = store.state.report}
			<article class="prose prose-sm dark:prose-invert max-w-none">
				{#if report.title}
					<h1 class="text-xl font-bold mb-6">{report.title}</h1>
				{/if}

				{#if report.sections}
					{#each report.sections as section, index}
						<section class="mb-6" bind:this={sectionRefs[index]}>
							<!-- Collapsible section header -->
							<button
								class="flex items-center gap-2 w-full text-left py-3 border-b border-border mb-4 cursor-pointer select-none group/sec"
								onclick={() => toggleSection(index)}
							>
								<span class="text-muted-foreground group-hover/sec:text-primary transition-colors">
									{#if expandedSections.has(index)}
										<ChevronDown class="h-4 w-4" />
									{:else}
										<ChevronRight class="h-4 w-4" />
									{/if}
								</span>
								<h2 class="text-lg font-semibold text-foreground flex-1 m-0 p-0 border-none">{section.title}</h2>
							</button>

							{#if expandedSections.has(index)}
								<!-- Section content -->
								{#if section.content}
									<div class="mb-4">
										<MarkdownRenderer content={section.content} />
									</div>
								{/if}

								<!-- Tables -->
								{#if section.tables}
									{#each section.tables as table}
										<div class="overflow-auto my-3 bg-card border border-border rounded-lg">
											{#if table.caption}
												<div class="px-4 py-2 text-xs font-medium bg-muted border-b border-border">{table.caption}</div>
											{/if}
											{#if table.markdown}
												{@const parsed = parseMarkdownTable(table.markdown)}
												{#if parsed}
													<table class="min-w-full text-xs">
														<thead class="bg-muted">
															<tr>
																{#each parsed.headers as header}
																	<th class="px-3 py-1.5 text-left font-medium border-b border-border">{@html parseInlineBold(header)}</th>
																{/each}
															</tr>
														</thead>
														<tbody>
															{#each parsed.rows as row}
																<tr class="border-b border-border/50">
																	{#each row as cell}
																		<td class="px-3 py-1 whitespace-nowrap">{@html parseInlineBold(cell)}</td>
																	{/each}
																</tr>
															{/each}
														</tbody>
													</table>
												{:else}
													<div class="p-3">
														<MarkdownRenderer content={table.markdown} />
													</div>
												{/if}
											{:else if table.headers && table.rows}
												<table class="min-w-full text-xs">
													<thead class="bg-muted">
														<tr>
															{#each table.headers as header}
																<th class="px-3 py-1.5 text-left font-medium border-b border-border">{header}</th>
															{/each}
														</tr>
													</thead>
													<tbody>
														{#each table.rows as row}
															<tr class="border-b border-border/50">
																{#each row as cell}
																	<td class="px-3 py-1 whitespace-nowrap">{cell}</td>
																{/each}
															</tr>
														{/each}
													</tbody>
												</table>
											{/if}
										</div>
									{/each}
								{/if}

								<!-- Citation Carousel (per section) -->
								{#if getValidCitations(section.citations).length > 0}
									{@const validCitations = getValidCitations(section.citations)}
									<div class="citation-carousel relative mt-4 mb-2 group/carousel">
										<!-- Left arrow -->
										<button
											class="carousel-arrow carousel-arrow-left absolute left-0 top-1/2 -translate-y-1/2 z-10 w-7 h-7 rounded-full bg-background border border-border shadow-md flex items-center justify-center text-muted-foreground hover:text-foreground opacity-0 group-hover/carousel:opacity-100 transition-opacity disabled:hidden"
											onclick={(e) => {
												const container = (e.currentTarget as HTMLElement).parentElement?.querySelector('.carousel-scroll');
												container?.scrollBy({ left: -280, behavior: 'smooth' });
											}}
										>
											<ChevronLeft class="h-4 w-4" />
										</button>

										<div class="carousel-scroll flex gap-3 overflow-x-auto pb-2 px-1" style="scrollbar-width: none;">
											{#each validCitations as citation}
												<button
													class="flex-shrink-0 w-[260px] p-3 bg-card border border-border rounded-xl cursor-pointer hover:border-primary hover:shadow-sm transition-all text-left"
													onclick={() => {
														if (citation.type === 'database' && citation.table) {
															showCitation = { table: citation.table, query: citation.query || '' };
														} else if (citation.type === 'web' && citation.url) {
															window.open(citation.url, '_blank');
														}
													}}
												>
													<div class="text-xs font-medium text-foreground line-clamp-2 mb-2">{citation.title}</div>
													<div class="flex items-center gap-1.5">
														{#if citation.type === 'web' && citation.url}
															<Globe class="h-3.5 w-3.5 text-muted-foreground flex-shrink-0" />
															<span class="text-[10px] text-muted-foreground truncate">
																{getDomainFromUrl(citation.url)}
															</span>
														{:else}
															<Database class="h-3.5 w-3.5 text-primary flex-shrink-0" />
															<span class="text-[10px] text-muted-foreground truncate">{citation.table}</span>
														{/if}
														<span class="ml-auto flex-shrink-0 min-w-[18px] h-[18px] flex items-center justify-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground">
															{citation.number}
														</span>
													</div>
												</button>
											{/each}
										</div>

										<!-- Right arrow -->
										<button
											class="carousel-arrow carousel-arrow-right absolute right-0 top-1/2 -translate-y-1/2 z-10 w-7 h-7 rounded-full bg-background border border-border shadow-md flex items-center justify-center text-muted-foreground hover:text-foreground opacity-0 group-hover/carousel:opacity-100 transition-opacity disabled:hidden"
											onclick={(e) => {
												const container = (e.currentTarget as HTMLElement).parentElement?.querySelector('.carousel-scroll');
												container?.scrollBy({ left: 280, behavior: 'smooth' });
											}}
										>
											<ChevronRight class="h-4 w-4" />
										</button>
									</div>
								{/if}
							{/if}
						</section>
					{/each}
				{/if}

				{#if report.mdContent && !report.sections?.length}
					<div class="text-sm">
						<MarkdownRenderer content={report.mdContent} />
					</div>
				{/if}

				<!-- Sources Section -->
				{#if (report.webSources?.length ?? 0) > 0 || (report.citations?.length ?? 0) > 0}
					<section class="mt-8 border-t border-border">
						<button
							class="flex items-center gap-2 w-full text-left py-4 cursor-pointer select-none group/src"
							onclick={() => toggleSection(-1)}
						>
							<span class="text-muted-foreground group-hover/src:text-primary transition-colors">
								{#if expandedSections.has(-1)}
									<ChevronDown class="h-4 w-4" />
								{:else}
									<ChevronRight class="h-4 w-4" />
								{/if}
							</span>
							<span class="text-sm font-medium text-foreground">{i18n('research.sourcesUsed')}</span>
						</button>
						{#if expandedSections.has(-1)}
							<div class="flex flex-col gap-0.5 pb-4">
								{#if report.webSources}
									{#each report.webSources as source}
										<a
											href={source.url}
											target="_blank"
											rel="noopener noreferrer"
											class="flex items-center gap-2 px-2.5 py-1.5 rounded text-xs hover:bg-accent transition-colors no-underline"
										>
											<Globe class="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" />
											<span class="font-medium text-foreground flex-shrink-0">{source.domain || getDomainFromUrl(source.url)}</span>
											<span class="text-muted-foreground truncate">{source.title}</span>
										</a>
									{/each}
								{/if}
								{#if report.citations}
									{#each report.citations as citation}
										<button
											class="flex items-center gap-2 px-2.5 py-1.5 rounded text-xs hover:bg-accent transition-colors text-left w-full"
											onclick={() => { showCitation = { table: citation.table, query: citation.query }; }}
										>
											<Database class="h-3.5 w-3.5 flex-shrink-0 text-primary" />
											<span class="font-medium text-foreground flex-shrink-0">{citation.table}</span>
											<span class="text-muted-foreground truncate">{citation.description}</span>
										</button>
									{/each}
								{/if}
							</div>
						{/if}
					</section>
				{/if}
			</article>
		{:else}
			<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
				{i18n('research.noReport')}
			</div>
		{/if}
	</div>
	{/if}
</div>

<!-- Delete Confirmation Dialog -->
{#if showDeleteConfirm}
	<div
		class="fixed inset-0 z-[60] bg-black/50 flex items-center justify-center"
		onclick={() => { showDeleteConfirm = false; }}
		onkeydown={(e) => { if (e.key === 'Escape') showDeleteConfirm = false; }}
		role="dialog"
		tabindex="-1"
	>
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
		<div
			class="bg-card border border-border rounded-xl w-[90%] max-w-[400px] overflow-hidden flex flex-col shadow-2xl"
			role="document"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<div class="px-5 py-4 border-b border-border">
				<h4 class="text-base font-semibold text-foreground m-0">{i18n('research.delete.title')}</h4>
			</div>
			<div class="px-5 py-4">
				<p class="text-sm text-muted-foreground m-0">
					{i18n('research.delete.message')}
				</p>
			</div>
			<div class="flex items-center justify-end gap-2 px-5 py-3 border-t border-border">
				<button
					class="px-4 py-2 text-xs rounded-md bg-muted hover:bg-accent text-foreground transition-colors"
					onclick={() => { showDeleteConfirm = false; }}
				>
					{i18n('common.button.cancel')}
				</button>
				<button
					class="px-4 py-2 text-xs rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
					onclick={async () => {
						showDeleteConfirm = false;
						const success = await deleteCurrentResearch();
						if (success && onClose) onClose();
					}}
				>
					{i18n('research.delete.confirm')}
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- Citation Modal -->
{#if showCitation}
	<div
		class="fixed inset-0 z-[60] bg-black/50 flex items-center justify-center"
		onclick={() => { showCitation = null; }}
		onkeydown={(e) => { if (e.key === 'Escape') showCitation = null; }}
		role="dialog"
		tabindex="-1"
	>
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
		<div
			class="bg-card border border-border rounded-xl w-[90%] max-w-[700px] max-h-[80vh] overflow-hidden flex flex-col shadow-2xl"
			role="document"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<div class="flex items-center justify-between px-5 py-4 border-b border-border">
				<h4 class="flex items-center gap-2 text-base font-semibold text-foreground m-0">
					<Database class="h-4 w-4" />
					{i18n('research.citation.source', showCitation.table)}
				</h4>
				<button
					class="p-1 rounded-md hover:bg-accent transition-colors text-muted-foreground"
					onclick={() => { showCitation = null; }}
				>
					<X class="h-4 w-4" />
				</button>
			</div>
			<div class="p-5 overflow-y-auto">
				<div class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground mb-3">
					<Code class="h-3.5 w-3.5" />
					{i18n('research.citation.sqlQuery')}
				</div>
				<pre class="bg-muted border border-border rounded-lg p-4 font-mono text-xs text-foreground whitespace-pre-wrap break-words overflow-x-auto m-0">{showCitation.query}</pre>
			</div>
		</div>
	</div>
{/if}

<style>
	/* Hide scrollbar for citation carousel */
	:global(.carousel-scroll) {
		scrollbar-width: none;
		-ms-overflow-style: none;
	}
	:global(.carousel-scroll::-webkit-scrollbar) {
		display: none;
	}

	/* Carousel arrow positioning */
	:global(.carousel-arrow-left) {
		left: -4px;
	}
	:global(.carousel-arrow-right) {
		right: -4px;
	}

	/* Reset prose heading styles for collapsible headers */
	article :global(h2) {
		margin: 0;
		padding: 0;
		border: none;
	}
</style>
