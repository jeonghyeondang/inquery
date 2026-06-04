<script lang="ts">
	import type { ResearchProgress } from '$lib/stores/deepResearch.svelte';
	import { ClipboardList, HelpCircle, FileCode, Zap, FlaskConical, BarChart3, Check } from 'lucide-svelte';
	import i18n from '$lib/i18n';

	interface Props {
		progress: ResearchProgress[];
		currentStep: string;
		isRunning: boolean;
	}

	let { progress, currentStep, isRunning }: Props = $props();

	let logContainer = $state<HTMLDivElement | null>(null);

	// Auto-scroll progress log to bottom
	$effect(() => {
		if (progress.length && logContainer) {
			logContainer.scrollTop = logContainer.scrollHeight;
		}
	});

	const steps = [
		{ key: 'planning', label: i18n('research.step.planning'), icon: ClipboardList },
		{ key: 'question', label: i18n('research.step.question'), icon: HelpCircle },
		{ key: 'query', label: i18n('research.step.query'), icon: FileCode },
		{ key: 'executing', label: i18n('research.step.executing'), icon: Zap },
		{ key: 'synthesizing', label: i18n('research.step.synthesizing'), icon: FlaskConical },
		{ key: 'report', label: i18n('research.step.report'), icon: BarChart3 }
	];

	// Map non-step SSE event types to the closest matching step
	const stepMapping: Record<string, string> = {
		planning: 'planning',
		web_search: 'planning',
		question: 'question',
		query: 'query',
		executing: 'executing',
		query_execution: 'executing',
		reflection: 'executing',
		iteration: 'question',     // new iteration starts question loop again
		synthesizing: 'synthesizing',
		finalizing: 'synthesizing',
		report: 'report',
		complete: 'report',
	};

	function getMappedStep(): string {
		return stepMapping[currentStep] || currentStep;
	}

	function getStepStatus(stepKey: string): 'completed' | 'active' | 'pending' {
		const stepOrder = steps.map(s => s.key);
		const mapped = getMappedStep();
		const currentIdx = stepOrder.indexOf(mapped);
		const thisIdx = stepOrder.indexOf(stepKey);

		// If mapped step not found, keep last known state
		if (currentIdx === -1) return 'pending';

		if (thisIdx < currentIdx) return 'completed';
		if (thisIdx === currentIdx) return isRunning ? 'active' : 'completed';
		return 'pending';
	}

	// Get a short label for the progress log step badge
	function getStepBadge(step: string): { label: string; color: string } {
		const badges: Record<string, { labelKey: string; color: string }> = {
			planning: { labelKey: 'research.badge.plan', color: 'text-blue-600 dark:text-blue-400 bg-blue-100 dark:bg-blue-900/30' },
			web_search: { labelKey: 'research.badge.web', color: 'text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/30' },
			question: { labelKey: 'research.badge.qa', color: 'text-violet-600 dark:text-violet-400 bg-violet-100 dark:bg-violet-900/30' },
			query: { labelKey: 'research.badge.sql', color: 'text-amber-600 dark:text-amber-400 bg-amber-100 dark:bg-amber-900/30' },
			executing: { labelKey: 'research.badge.exec', color: 'text-orange-600 dark:text-orange-400 bg-orange-100 dark:bg-orange-900/30' },
			query_execution: { labelKey: 'research.badge.exec', color: 'text-orange-600 dark:text-orange-400 bg-orange-100 dark:bg-orange-900/30' },
			reflection: { labelKey: 'research.badge.think', color: 'text-pink-600 dark:text-pink-400 bg-pink-100 dark:bg-pink-900/30' },
			iteration: { labelKey: 'research.badge.loop', color: 'text-indigo-600 dark:text-indigo-400 bg-indigo-100 dark:bg-indigo-900/30' },
			synthesizing: { labelKey: 'research.badge.synth', color: 'text-cyan-600 dark:text-cyan-400 bg-cyan-100 dark:bg-cyan-900/30' },
			finalizing: { labelKey: 'research.badge.final', color: 'text-cyan-600 dark:text-cyan-400 bg-cyan-100 dark:bg-cyan-900/30' },
			report: { labelKey: 'research.badge.report', color: 'text-green-600 dark:text-green-400 bg-green-100 dark:bg-green-900/30' },
			complete: { labelKey: 'research.badge.done', color: 'text-green-600 dark:text-green-400 bg-green-100 dark:bg-green-900/30' },
		};
		const badge = badges[step];
		if (!badge) return { label: step, color: 'text-muted-foreground bg-muted' };
		return { label: i18n(badge.labelKey), color: badge.color };
	}
</script>

<div class="flex flex-col h-full">
	<!-- Header -->
	<div class="px-4 pt-4 pb-3">
		<h3 class="text-sm font-semibold text-foreground">{i18n('research.progress.title')}</h3>
	</div>

	<!-- Step indicators (horizontal pipeline) -->
	<div class="px-4 pb-3">
		<div class="flex items-center gap-0.5 flex-wrap">
			{#each steps as step, i}
				{@const status = getStepStatus(step.key)}
				<div class="flex items-center gap-0.5">
					<div class="flex items-center gap-1 px-2 py-1 rounded-full text-[11px] font-medium transition-all duration-300
						{status === 'completed' ? 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400' :
						 status === 'active' ? 'bg-primary/15 text-primary ring-1 ring-primary/30' :
						 'bg-muted text-muted-foreground/60'}">
						{#if status === 'completed'}
							<Check class="h-3 w-3" />
						{:else if status === 'active' && isRunning}
							<div class="h-3 w-3 rounded-full border-[1.5px] border-current border-t-transparent animate-spin"></div>
						{:else}
							{@const Icon = step.icon}
							<Icon class="h-3 w-3" />
						{/if}
						<span>{step.label}</span>
					</div>
					{#if i < steps.length - 1}
						<div class="w-3 h-px transition-colors duration-300 {status === 'completed' ? 'bg-green-400 dark:bg-green-600' : 'bg-border'}"></div>
					{/if}
				</div>
			{/each}
		</div>
	</div>

	<!-- Current activity indicator -->
	{#if isRunning}
		<div class="mx-4 mb-3 px-3 py-2 rounded-lg bg-primary/5 border border-primary/10 flex items-center gap-2">
			<div class="relative flex h-2 w-2">
				<span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary/60"></span>
				<span class="relative inline-flex rounded-full h-2 w-2 bg-primary"></span>
			</div>
			<span class="text-xs text-primary font-medium truncate">
				{progress.length > 0 ? progress[progress.length - 1].message : i18n('research.starting')}
			</span>
		</div>
	{/if}

	<!-- Progress log -->
	<div
		bind:this={logContainer}
		class="flex-1 overflow-auto px-4 pb-4 space-y-1"
	>
		{#each progress as item, i}
			{@const badge = getStepBadge(item.step)}
			<div class="flex items-start gap-2 py-1 animate-in fade-in slide-in-from-bottom-1 duration-200" style="animation-delay: {Math.min(i * 20, 200)}ms;">
				<span class="shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium {badge.color}">
					{badge.label}
				</span>
				<span class="text-xs text-foreground/80 leading-relaxed">{item.message}</span>
			</div>
		{/each}

		{#if isRunning && progress.length > 0}
			<div class="flex items-center gap-1.5 py-1 text-xs text-muted-foreground/50">
				<div class="flex gap-0.5">
					<span class="w-1 h-1 rounded-full bg-current animate-bounce" style="animation-delay: 0ms;"></span>
					<span class="w-1 h-1 rounded-full bg-current animate-bounce" style="animation-delay: 150ms;"></span>
					<span class="w-1 h-1 rounded-full bg-current animate-bounce" style="animation-delay: 300ms;"></span>
				</div>
			</div>
		{/if}
	</div>
</div>
