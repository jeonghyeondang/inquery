<script lang="ts">
	/**
	 * ECharts Svelte 5 component
	 * SSR-safe dynamic import with auto dark/light theme detection
	 */
	import { onMount, onDestroy, untrack } from 'svelte';
	import { cn } from '$lib/utils/cn';

	interface Props {
		option: Record<string, unknown>;
		class?: string;
		theme?: 'light' | 'dark' | 'auto';
		height?: string;
	}

	let { option, class: className = '', theme = 'auto', height = '300px' }: Props = $props();

	let containerEl = $state<HTMLDivElement | null>(null);
	let chartInstance: any = null;
	let echartsModule: any = null;
	let resizeObserver: ResizeObserver | null = null;
	let themeObserver: MutationObserver | null = null;
	let currentTheme = $state<'light' | 'dark'>('dark');
	let disposed = false;

	function detectTheme(): 'light' | 'dark' {
		if (typeof document === 'undefined') return 'dark';
		const el = document.documentElement;
		if (el.classList.contains('dark')) return 'dark';
		const t = el.getAttribute('data-theme') || el.getAttribute('theme');
		return t === 'dark' ? 'dark' : 'light';
	}

	function resolvedTheme(): 'light' | 'dark' {
		if (theme === 'auto') return currentTheme;
		return theme;
	}

	function initChart() {
		if (!echartsModule || !containerEl || disposed) return;
		if (chartInstance && !chartInstance.isDisposed()) {
			chartInstance.dispose();
		}
		chartInstance = echartsModule.init(containerEl, resolvedTheme());
		chartInstance.setOption(option);
	}

	onMount(() => {
		currentTheme = detectTheme();

		import('echarts').then((echarts) => {
			echartsModule = echarts;
			if (!containerEl) return;
			initChart();

			resizeObserver = new ResizeObserver(() => {
				chartInstance?.resize();
			});
			resizeObserver.observe(containerEl);
		});

		// Watch for theme changes on documentElement
		themeObserver = new MutationObserver(() => {
			const newTheme = detectTheme();
			if (newTheme !== currentTheme) {
				currentTheme = newTheme;
				if (theme === 'auto') {
					initChart();
				}
			}
		});
		themeObserver.observe(document.documentElement, {
			attributes: true,
			attributeFilter: ['class', 'data-theme', 'theme']
		});

		return () => {
			cleanup();
		};
	});

	onDestroy(() => {
		cleanup();
	});

	function cleanup() {
		if (disposed) return;
		disposed = true;
		resizeObserver?.disconnect();
		themeObserver?.disconnect();
		if (chartInstance && !chartInstance.isDisposed()) {
			chartInstance.dispose();
		}
		chartInstance = null;
	}

	// React to option changes
	$effect(() => {
		// Read option to track it
		const opt = option;
		untrack(() => {
			if (chartInstance && opt && !disposed && !chartInstance.isDisposed()) {
				chartInstance.setOption(opt, true);
			}
		});
	});

	// React to explicit theme prop changes (non-auto)
	$effect(() => {
		// Read theme to track it
		const t = theme;
		untrack(() => {
			if (t !== 'auto' && echartsModule && containerEl && !disposed) {
				initChart();
			}
		});
	});

	export function getChart() {
		return chartInstance;
	}
</script>

<div bind:this={containerEl} class={cn('w-full', className)} style="height: {height}"></div>
