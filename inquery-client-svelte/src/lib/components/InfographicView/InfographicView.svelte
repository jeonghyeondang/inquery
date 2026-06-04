<script lang="ts">
	import { ArrowLeft, Code, Eye, Download, Loader2 } from 'lucide-svelte';
	import MonacoEditor from '$lib/components/MonacoEditor/MonacoEditor.svelte';
	import { getBaseURL } from '$lib/service/base';
	import { getThemeStore } from '$lib/stores/theme.svelte';

	let {
		html = '',
		isGenerating = false,
		onBack
	}: {
		html: string;
		isGenerating: boolean;
		onBack: () => void;
	} = $props();

	let activeTab = $state<'code' | 'preview'>('code');
	let isDownloading = $state(false);
	let editedHtml = $state('');
	let editorInstance = $state<any>(null);
	let iframeEl = $state<HTMLIFrameElement | null>(null);
	const themeStore = getThemeStore();

	// Streaming write state removed

	// Sync from prop when html changes (during generation)
	$effect(() => {
		editedHtml = html;
	});

	// Real-time preview update with Blob and postMessage
	let blobUrl: string | null = null;
	
	$effect(() => {
		if (blobUrl) URL.revokeObjectURL(blobUrl);
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
			'	<script>',
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
		blobUrl = URL.createObjectURL(blob);

		if (iframeEl) {
			iframeEl.src = blobUrl;
			// Wait for iframe to load its skeleton before sending the first message
			iframeEl.onload = () => {
				if (iframeEl?.contentWindow) {
					iframeEl.contentWindow.postMessage({ html: editedHtml, isGenerating }, '*');
				}
			};
		}

		return () => {
			if (blobUrl) URL.revokeObjectURL(blobUrl);
		};
	});

	// Send HTML updates continuously without reloading the iframe
	$effect(() => {
		if (iframeEl && iframeEl.contentWindow && activeTab === 'preview') {
			iframeEl.contentWindow.postMessage({ html: editedHtml, isGenerating }, '*');
		}
	});



	// Auto-scroll editor to bottom during generation
	$effect(() => {
		if (isGenerating && activeTab === 'code' && editorInstance) {
			requestAnimationFrame(() => {
				const model = editorInstance?.getModel();
				if (model) editorInstance.revealLine(model.getLineCount());
			});
		}
	});

	// Auto-switch to preview once enough lines are streamed
	let hasAutoSwitched = $state(false);
	$effect(() => {
		if (!hasAutoSwitched && html.split('\n').length > 100) {
			activeTab = 'preview';
			hasAutoSwitched = true;
		}
	});

	async function handleDownloadPDF() {
		console.log('[PDF Debug] editedHtml length:', editedHtml?.length, 'html prop length:', html?.length, 'isGenerating:', isGenerating);
		const htmlToExport = editedHtml || html;
		if (!htmlToExport || isGenerating) return;
		isDownloading = true;

		try {
			// Send the complete HTML document directly (editedHtml preserves <head>, <style>, <script>, etc.)
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
			isDownloading = false;
		}
	}
</script>

<div class="flex flex-col h-full bg-background rounded-lg overflow-hidden">
	<!-- Header -->
	<div class="flex items-center justify-between px-4 py-2 border-b border-border min-h-[48px] gap-3">
		<div class="flex-1 flex items-center">
			<button
				class="flex items-center gap-1.5 text-sm text-foreground hover:text-primary transition-colors"
				onclick={onBack}
			>
				<ArrowLeft size={16} />
				Back to Report
			</button>
		</div>
		<div class="flex items-center justify-center">
			<div class="flex items-center bg-muted rounded-lg p-0.5 gap-0.5">
				<button
					class="flex items-center gap-1.5 px-3.5 py-1.5 rounded-md text-xs transition-all {activeTab === 'code' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}"
					onclick={() => activeTab = 'code'}
				>
					<Code size={14} />
					Code
				</button>
				<button
					class="flex items-center gap-1.5 px-3.5 py-1.5 rounded-md text-xs transition-all {activeTab === 'preview' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}"
					onclick={() => activeTab = 'preview'}
				>
					<Eye size={14} />
					Preview
				</button>
			</div>
		</div>
		<div class="flex-1 flex items-center justify-end">
			{#if isGenerating}
				<div class="flex items-center gap-2 text-primary text-xs">
					<Loader2 class="h-3.5 w-3.5 animate-spin" />
					<span>Generating...</span>
				</div>
			{:else if html}
				<button
					class="flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs hover:bg-primary/90 transition-colors disabled:opacity-50"
					onclick={handleDownloadPDF}
					disabled={isDownloading}
				>
					{#if isDownloading}
						<Loader2 size={14} class="animate-spin" />
					{:else}
						<Download size={14} />
					{/if}
					Download PDF
				</button>
			{/if}
		</div>
	</div>

	<!-- Content: both tabs always mounted, toggled via display to avoid Monaco destroy/recreate -->
	<div class="flex-1 overflow-hidden relative">
		<div class="w-full h-full" style:display={activeTab === 'code' ? 'block' : 'none'}>
			<MonacoEditor
				bind:value={editedHtml}
				language="html"
				readOnly={isGenerating}
				onmount={(editor) => { editorInstance = editor; }}
				options={{
					scrollBeyondLastLine: false,
					formatOnPaste: true
				}}
			/>
		</div>
		<div class="w-full h-full overflow-hidden" style:display={activeTab === 'preview' ? 'block' : 'none'}>
			{#if editedHtml}
				<iframe
					bind:this={iframeEl}
					class="w-full h-full border-0 bg-background"
					sandbox="allow-scripts"
					title="Infographic Preview"
				></iframe>
			{:else}
				<div class="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
					<Loader2 class="h-8 w-8 animate-spin" />
					<p class="text-sm">Generating infographic...</p>
				</div>
			{/if}
		</div>
	</div>
</div>
