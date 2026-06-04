<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { queryCurUser, getUserStore } from '$lib/stores/user.svelte';
	import miscService from '$lib/service/misc';
	import i18n from '$lib/i18n';
	import { Spinner } from '$lib/components/ui';
	import { Button } from '$lib/components/ui';
	import { CommandPalette } from '$lib/components/CommandPalette';
	import { AICollectionProgress } from '$lib/components/AICollectionProgress';
	import { AISparkleIcon } from '$lib/components/AISparkleIcon';
	import { resumeActiveJobs } from '$lib/stores/dataCatalog.svelte';
	import { matchesShortcut } from '$lib/stores/shortcuts.svelte';
	import { getBaseURL } from '$lib/service/base';
	import { cn } from '$lib/utils/cn';

	// Lucide icons
	import {
		SquareTerminal, Database, Users, LibraryBig,
		LayoutDashboard, Settings, ChevronLeft, ChevronRight,
	} from 'lucide-svelte';

	let { children } = $props();
	const userStore = getUserStore();

	let serviceReady = $state(false);
	let serviceFailed = $state(false);
	let loading = $state(true);

	// Sidebar state
	let collapsed = $state(true);

	// Navigation config - same order as React
	const navItems = [
		{ key: 'workspace', label: () => i18n('workspace.title'), icon: SquareTerminal },
		{ key: 'connections', label: () => i18n('connection.title'), icon: Database },
		{ key: 'team', label: () => i18n('team.title'), icon: Users, adminOnly: true },
		{ key: 'data-catalog', label: () => i18n('catalog.title'), icon: LibraryBig },
		{ key: 'dashboard', label: () => i18n('dashboard.title'), icon: LayoutDashboard },
		{ key: 'ai-chat', label: () => i18n('aichat.title'), icon: null, isAI: true }
	];

	let isAdmin = $derived(userStore.curUser?.roleCode === 'ADMIN');
	let visibleNavItems = $derived(navItems.filter(item => !item.adminOnly || isAdmin));

	let currentPath = $derived(page.url.pathname);
	let activeNavKey = $derived(currentPath.split('/')[1] || 'workspace');

	onMount(() => {
		// Restore collapsed state from localStorage
		const stored = localStorage.getItem('main-sidebar-collapsed');
		collapsed = stored === null ? true : stored === 'true';

		initApp();
	});

	const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

	async function waitForBackend(maxRetries = 30, intervalMs = 2000) {
		for (let i = 0; i < maxRetries; i++) {
			try {
				await miscService.testService();
				return;
			} catch {
				if (i === maxRetries - 1) throw new Error('Backend unavailable');
				await new Promise((r) => setTimeout(r, intervalMs));
			}
		}
	}

	async function initApp() {
		try {
			if (isTauri) {
				await waitForBackend();
			} else {
				await miscService.testService();
			}
			serviceReady = true;
			const user = await queryCurUser();
			if (!user || user.roleCode === 'DESKTOP') {
				localStorage.removeItem('Inquery');
				goto('/login');
				return;
			}
			// Warmup vector DB
			fetch(`${getBaseURL()}/api/ai/warmup`, { method: 'POST' }).catch(() => {});
			// Resume any active AI collection jobs from before refresh
			resumeActiveJobs().catch(() => {});
			loading = false;
		} catch {
			serviceFailed = true;
			loading = false;
		}
	}

	function navigateTo(key: string) {
		goto('/' + key);
	}

	function toggleCollapsed() {
		collapsed = !collapsed;
		if (typeof window !== 'undefined') {
			localStorage.setItem('main-sidebar-collapsed', String(collapsed));
		}
	}

	const navShortcuts: { id: string; path: string }[] = [
		{ id: 'nav-workspace', path: '/workspace' },
		{ id: 'nav-connections', path: '/connections' },
		{ id: 'nav-team', path: '/team' },
		{ id: 'nav-catalog', path: '/data-catalog' },
		{ id: 'nav-dashboard', path: '/dashboard' },
		{ id: 'nav-ai-chat', path: '/ai-chat' },
		{ id: 'nav-setting', path: '/setting' },
	];

	function handleGlobalKeydown(e: KeyboardEvent) {
		for (const { id, path } of navShortcuts) {
			if (matchesShortcut(e, id)) {
				e.preventDefault();
				goto(path);
				return;
			}
		}
	}
</script>

<svelte:window onkeydown={handleGlobalKeydown} />

{#if loading}
	<div class="flex items-center justify-center min-h-screen bg-background">
		<div class="flex flex-col items-center gap-3">
			<Spinner size="lg" class="text-primary" />
			<p class="text-sm text-muted-foreground">{i18n('common.text.serviceStarting')}</p>
		</div>
	</div>
{:else if serviceFailed}
	<div class="flex flex-col items-center justify-center min-h-screen gap-4 bg-background">
		<p class="text-muted-foreground">{i18n('common.text.serviceFail')}</p>
		<Button onclick={() => window.location.reload()}>
			{i18n('common.text.tryToRestart')}
		</Button>
	</div>
{:else}
	<div class="flex w-full bg-background" style:height={isTauri ? 'calc(100vh - 38px)' : '100vh'} style:margin-top={isTauri ? '38px' : '0'}>
		{#if isTauri}
			<div data-tauri-drag-region class="fixed top-0 left-0 right-0 h-[38px] z-50"></div>
		{/if}
		<!-- Sidebar -->
		<aside
			class={cn(
				'group flex flex-col border-r border-sidebar-border bg-sidebar transition-all duration-300 ease-in-out',
				collapsed ? 'w-20' : 'w-56'
			)}
		>
			<!-- Logo + Toggle -->
			<div class="relative flex h-16 items-center justify-center px-4 pt-2">
				{#if collapsed}
					<button
						onclick={toggleCollapsed}
						class="flex h-7 w-7 items-center justify-center rounded-md text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-foreground transition-colors"
					>
						<ChevronRight class="h-4 w-4" />
					</button>
				{:else}
					<img
						src="/document/image/logo_long.png"
						alt="Inquery"
						class="h-9 object-contain"
					/>
					<button
						onclick={toggleCollapsed}
						class="absolute right-2 flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-sidebar-foreground/50 hover:bg-sidebar-accent hover:text-sidebar-foreground opacity-0 group-hover:opacity-100 transition-opacity"
					>
						<ChevronLeft class="h-4 w-4" />
					</button>
				{/if}
			</div>

			<!-- Separator -->
			<div class="bg-sidebar-border my-2 mx-4 h-px opacity-50"></div>

			<!-- Navigation -->
			<nav class="flex-1 space-y-1.5 px-3 py-2">
				{#each visibleNavItems as item}
					{@const isActive = activeNavKey === item.key}
					<div class="relative group/tooltip">
						<button
							onclick={() => navigateTo(item.key)}
							class={cn(
								'flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm transition-all duration-200 outline-none',
								isActive
									? 'bg-primary/10 text-primary shadow-sm'
									: 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-foreground',
								collapsed && 'justify-center px-0 w-10 h-10 mx-auto'
							)}
						>
							{#if item.isAI}
								<AISparkleIcon
									size={18}
									class={cn(
										'shrink-0 transition-colors',
										isActive ? 'text-primary' : 'text-cyan-500/80'
									)}
								/>
							{:else if item.icon}
								{@const IconComp = item.icon}
								<IconComp
									class={cn(
										'h-[18px] w-[18px] shrink-0 transition-colors',
										isActive ? 'text-primary' : 'text-sidebar-foreground/60'
									)}
									strokeWidth={isActive ? 2 : 1.7}
								/>
							{/if}
							{#if !collapsed}
								<span class={cn('truncate', isActive && 'font-semibold')}>
									{item.label()}
								</span>
							{/if}
						</button>

						<!-- Tooltip (collapsed mode) -->
						{#if collapsed}
							<span class="absolute left-full top-1/2 -translate-y-1/2 ml-2 hidden group-hover/tooltip:flex items-center gap-2 bg-popover text-popover-foreground text-xs font-medium px-2.5 py-1.5 rounded-md shadow-md whitespace-nowrap z-50 border border-border">
								{item.label()}
							</span>
						{/if}
					</div>
				{/each}
			</nav>

			<!-- Footer - Settings -->
			<div class="p-3 mt-auto">
				{#if !collapsed}
					<div class="bg-sidebar-border mb-3 h-px opacity-50"></div>
				{/if}
				<div class="relative group/tooltip">
					<button
						onclick={() => navigateTo('setting')}
						class={cn(
							'flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm transition-all duration-200',
							'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-foreground',
							collapsed && 'justify-center px-0 w-10 h-10 mx-auto'
						)}
					>
						<Settings
							class="h-[18px] w-[18px] shrink-0 text-sidebar-foreground/60"
							strokeWidth={1.7}
						/>
						{#if !collapsed}
							<span>{i18n('setting.title.setting')}</span>
						{/if}
					</button>
					{#if collapsed}
						<span class="absolute left-full top-1/2 -translate-y-1/2 ml-2 hidden group-hover/tooltip:flex items-center gap-2 bg-popover text-popover-foreground text-xs font-medium px-2.5 py-1.5 rounded-md shadow-md whitespace-nowrap z-50 border border-border">
							{i18n('setting.title.setting')}
						</span>
					{/if}
				</div>
			</div>
		</aside>

		<!-- Main Content -->
		<main class="flex-1 overflow-hidden bg-background relative flex flex-col min-w-0">
			{@render children()}
		</main>
	</div>

	<!-- Command Palette -->
	<CommandPalette />

	<!-- AI Collection Progress (global floating panel) -->
	<AICollectionProgress />

{/if}
