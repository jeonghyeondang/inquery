<script lang="ts">
	import { X, Search, Check, Loader2 } from 'lucide-svelte';
	import { Button, Badge } from '$lib/components/ui';

	interface Props {
		title: string;
		searchApi: (params: { searchKey: string }) => Promise<any>;
		searchLabel: (item: any) => string;
		searchValue: (item: any) => any;
		searchIcon?: (item: any) => string | null;
		onclose: () => void;
		onconfirm: (selectedValues: any[]) => void;
	}

	let { title, searchApi, searchLabel, searchValue, searchIcon, onclose, onconfirm }: Props = $props();

	let searchKey = $state('');
	let options = $state<any[]>([]);
	let loading = $state(false);
	let selectedValues = $state<Set<any>>(new Set());
	let searchTimeout: ReturnType<typeof setTimeout>;

	$effect(() => {
		loadOptions('');
	});

	function handleSearch(value: string) {
		clearTimeout(searchTimeout);
		searchTimeout = setTimeout(() => loadOptions(value), 300);
	}

	async function loadOptions(key: string) {
		loading = true;
		try {
			const res = await searchApi({ searchKey: key });
			options = Array.isArray(res) ? res : (res as any)?.data || [];
		} catch { options = []; }
		finally { loading = false; }
	}

	function toggleSelect(val: any) {
		const newSet = new Set(selectedValues);
		if (newSet.has(val)) {
			newSet.delete(val);
		} else {
			newSet.add(val);
		}
		selectedValues = newSet;
	}

	function handleConfirm() {
		onconfirm(Array.from(selectedValues));
	}
</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" role="dialog" tabindex="-1" onclick={(e) => { if (e.target === e.currentTarget) onclose(); }} onkeydown={(e) => { if (e.key === 'Escape') onclose(); }}>
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="w-full max-w-md bg-background border border-border rounded-xl shadow-2xl flex flex-col max-h-[70vh]" role="document" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()}>
		<!-- Header -->
		<div class="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
			<h3 class="text-base font-semibold">{title}</h3>
			<button onclick={onclose} class="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground">
				<X size={16} />
			</button>
		</div>

		<!-- Selected Badges -->
		{#if selectedValues.size > 0}
			<div class="flex flex-wrap gap-1.5 px-5 py-2 border-b border-border/50 max-h-[80px] overflow-auto shrink-0">
				{#each options.filter(o => selectedValues.has(searchValue(o))) as item}
					<Badge variant="secondary" class="gap-1 pr-1">
						{#if searchIcon}
							{@const iconUrl = searchIcon(item)}
							{#if iconUrl}
								<img src={iconUrl} alt="" class="w-3.5 h-3.5 shrink-0 object-contain" />
							{/if}
						{/if}
						{searchLabel(item)}
						<button onclick={() => toggleSelect(searchValue(item))} class="ml-0.5 hover:text-destructive">
							<X size={12} />
						</button>
					</Badge>
				{/each}
			</div>
		{/if}

		<!-- Search -->
		<div class="px-5 py-3 shrink-0">
			<div class="relative">
				<Search class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted-foreground" />
				<input
					type="text"
					placeholder="Search..."
					class="flex h-8 w-full rounded-md border border-input bg-background pl-8 pr-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
					bind:value={searchKey}
					oninput={(e) => handleSearch((e.target as HTMLInputElement).value)}
				/>
			</div>
		</div>

		<!-- Options List -->
		<div class="flex-1 min-h-0 overflow-auto px-2 pb-2">
			{#if loading}
				<div class="flex items-center justify-center py-8">
					<Loader2 class="w-5 h-5 animate-spin text-primary" />
				</div>
			{:else if options.length === 0}
				<div class="flex items-center justify-center py-8 text-sm text-muted-foreground">
					No items found
				</div>
			{:else}
				{#each options as item}
					{@const val = searchValue(item)}
					{@const isSelected = selectedValues.has(val)}
					<button
						onclick={() => toggleSelect(val)}
						class="flex items-center gap-3 w-full px-3 py-2 rounded-md text-sm text-left transition-colors
							{isSelected ? 'bg-primary/10 text-primary' : 'hover:bg-accent text-foreground'}"
					>
					<div class="flex items-center justify-center w-4 h-4 rounded border shrink-0
						{isSelected ? 'bg-primary border-primary' : 'border-input'}">
						{#if isSelected}
							<Check size={12} class="text-primary-foreground" />
						{/if}
					</div>
					{#if searchIcon}
						{@const iconUrl = searchIcon(item)}
						{#if iconUrl}
							<img src={iconUrl} alt="" class="w-5 h-5 shrink-0 object-contain" />
						{/if}
					{/if}
					<span class="truncate">{searchLabel(item)}</span>
					</button>
				{/each}
			{/if}
		</div>

		<!-- Footer -->
		<div class="flex items-center justify-between px-5 py-3 border-t border-border shrink-0">
			<span class="text-xs text-muted-foreground">{selectedValues.size} selected</span>
			<div class="flex gap-2">
				<Button size="sm" variant="ghost" onclick={onclose}>Cancel</Button>
				<Button size="sm" disabled={selectedValues.size === 0} onclick={handleConfirm}>
					Confirm
				</Button>
			</div>
		</div>
	</div>
</div>
