<script lang="ts">
	import { goto } from '$app/navigation';
	import { matchesShortcut, getShortcutById, formatKeys } from '$lib/stores/shortcuts.svelte';

	interface Command {
		id: string;
		label: string;
		description?: string;
		category: string;
		shortcutId?: string;
		action: () => void;
	}

	let open = $state(false);
	let search = $state('');
	let selectedIndex = $state(0);
	let inputEl = $state<HTMLInputElement | null>(null);
	let listEl = $state<HTMLDivElement | null>(null);

	const commands: Command[] = [
		{ id: 'nav-workspace', label: 'Go to Workspace', description: 'Open SQL workspace', category: 'Navigation', shortcutId: 'nav-workspace', action: () => goto('/workspace') },
		{ id: 'nav-ai-chat', label: 'Go to AI Chat', description: 'Open AI assistant', category: 'Navigation', shortcutId: 'nav-ai-chat', action: () => goto('/ai-chat') },
		{ id: 'nav-connections', label: 'Go to Connections', description: 'Manage connections', category: 'Navigation', shortcutId: 'nav-connections', action: () => goto('/connections') },
		{ id: 'nav-dashboard', label: 'Go to Dashboard', description: 'View dashboards', category: 'Navigation', shortcutId: 'nav-dashboard', action: () => goto('/dashboard') },
		{ id: 'nav-catalog', label: 'Go to Data Catalog', description: 'Browse data catalog', category: 'Navigation', shortcutId: 'nav-catalog', action: () => goto('/data-catalog') },
		{ id: 'nav-settings', label: 'Go to Settings', description: 'App settings', category: 'Navigation', shortcutId: 'nav-setting', action: () => goto('/setting') },
		{ id: 'nav-team', label: 'Go to Team', description: 'Team management', category: 'Navigation', shortcutId: 'nav-team', action: () => goto('/team') },
		{ id: 'theme-toggle', label: 'Toggle Theme', description: 'Switch dark/light mode', category: 'Settings', action: () => {
			const html = document.documentElement;
			html.classList.toggle('dark');
		}},
	];

	function getCommandShortcutLabel(cmd: Command): string {
		if (!cmd.shortcutId) return '';
		const def = getShortcutById(cmd.shortcutId);
		return def ? formatKeys(def.keys) : '';
	}

	const filteredCommands = $derived.by(() => {
		if (!search.trim()) return commands;
		const q = search.toLowerCase();
		return commands.filter(cmd =>
			cmd.label.toLowerCase().includes(q) ||
			cmd.description?.toLowerCase().includes(q) ||
			cmd.category.toLowerCase().includes(q)
		);
	});

	const groupedCommands = $derived.by(() => {
		const groups: Record<string, Command[]> = {};
		for (const cmd of filteredCommands) {
			if (!groups[cmd.category]) groups[cmd.category] = [];
			groups[cmd.category].push(cmd);
		}
		return groups;
	});

	function openPalette() {
		open = true;
		search = '';
		selectedIndex = 0;
		setTimeout(() => inputEl?.focus(), 50);
	}

	function closePalette() {
		open = false;
		search = '';
		selectedIndex = 0;
	}

	function executeCommand(cmd: Command) {
		closePalette();
		cmd.action();
	}

	function handleKeydown(e: KeyboardEvent) {
		if (matchesShortcut(e, 'command-palette')) {
			e.preventDefault();
			if (open) {
				closePalette();
			} else {
				openPalette();
			}
			return;
		}

		if (!open) return;

		if (e.key === 'Escape') {
			e.preventDefault();
			closePalette();
		} else if (e.key === 'ArrowDown') {
			e.preventDefault();
			selectedIndex = Math.min(selectedIndex + 1, filteredCommands.length - 1);
			scrollToSelected();
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			selectedIndex = Math.max(selectedIndex - 1, 0);
			scrollToSelected();
		} else if (e.key === 'Enter') {
			e.preventDefault();
			if (filteredCommands[selectedIndex]) {
				executeCommand(filteredCommands[selectedIndex]);
			}
		}
	}

	function scrollToSelected() {
		requestAnimationFrame(() => {
			const el = listEl?.querySelector(`[data-index="${selectedIndex}"]`);
			el?.scrollIntoView({ block: 'nearest' });
		});
	}

	// Reset selection when search changes
	$effect(() => {
		search; // track
		selectedIndex = 0;
	});

	// Expose trigger function
	export function trigger() {
		openPalette();
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#if open}
	<!-- Backdrop -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
		onclick={closePalette}
		onkeydown={(e) => { if (e.key === 'Escape') closePalette(); }}
	>
		<!-- Palette -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="fixed left-1/2 top-[20%] -translate-x-1/2 w-[560px] max-h-[400px] bg-popover border border-border rounded-xl shadow-2xl overflow-hidden"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<!-- Search Input -->
			<div class="flex items-center border-b border-border px-4">
				<svg class="w-4 h-4 text-muted-foreground shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
				</svg>
				<input
					bind:this={inputEl}
					bind:value={search}
					type="text"
					placeholder="Type a command..."
					class="flex-1 bg-transparent border-0 px-3 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
				/>
				<kbd class="text-[10px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded font-mono">ESC</kbd>
			</div>

			<!-- Commands List -->
			<div bind:this={listEl} class="overflow-auto max-h-[320px] py-1.5">
				{#if filteredCommands.length === 0}
					<div class="px-4 py-8 text-center text-sm text-muted-foreground">
						No commands found
					</div>
				{:else}
					{@const flatIndex = { value: 0 }}
					{#each Object.entries(groupedCommands) as [category, cmds]}
						<div class="px-3 py-1.5 text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">
							{category}
						</div>
						{#each cmds as cmd}
							{@const idx = flatIndex.value}
							{@const _ = flatIndex.value++}
							<button
								data-index={idx}
								class="flex items-center justify-between w-full px-3 py-2 mx-1 rounded-md text-sm transition-colors text-left
									{selectedIndex === idx ? 'bg-accent text-accent-foreground' : 'text-foreground hover:bg-accent/50'}"
								onclick={() => executeCommand(cmd)}
								onmouseenter={() => { selectedIndex = idx; }}
							>
							<div class="flex flex-col">
								<span class="font-medium">{cmd.label}</span>
								{#if cmd.description}
									<span class="text-xs text-muted-foreground">{cmd.description}</span>
								{/if}
							</div>
							{#if getCommandShortcutLabel(cmd)}
								<kbd class="text-[10px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded font-mono">{getCommandShortcutLabel(cmd)}</kbd>
							{/if}
							</button>
						{/each}
					{/each}
				{/if}
			</div>
		</div>
	</div>
{/if}
