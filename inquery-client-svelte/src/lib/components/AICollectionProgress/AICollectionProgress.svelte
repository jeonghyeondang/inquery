<script lang="ts">
	import { X, AlertTriangle, Minus, Maximize2 } from 'lucide-svelte';
	import { Button, Dialog } from '$lib/components/ui';
	import { AISparkleIcon } from '$lib/components/AISparkleIcon';
	import {
		getCollectionStore,
		setShowCollectionProgress,
		cancelCollection,
		resetCollectionProgress
	} from '$lib/stores/dataCatalog.svelte';

	const store = getCollectionStore();

	let showCancelConfirm = $state(false);
	let minimized = $state(false);

	const totalCurrent = $derived(store.collectionProgressList.reduce((sum, p) => sum + p.current, 0));
	const totalAll = $derived(store.collectionProgressList.reduce((sum, p) => sum + p.total, 0));
	const overallPercent = $derived(totalAll > 0 ? Math.round((totalCurrent / totalAll) * 100) : 0);
</script>

<!-- Floating AI Collection Progress Panel -->
{#if store.showCollectionProgress && store.collectionProgressList.length > 0}
	{#if minimized}
		<!-- Minimized View -->
		<button
			class="fixed bottom-6 right-6 z-50 flex items-center gap-2.5 px-3.5 py-2.5 bg-background rounded-xl shadow-lg border hover:bg-accent/50 transition-colors cursor-pointer"
			onclick={() => { minimized = false; }}
		>
			<div class={store.bulkCollecting ? 'animate-pulse' : ''}>
				<AISparkleIcon size={16} filled />
			</div>
			<div class="flex items-center gap-2">
				<div class="w-[80px] h-1.5 bg-muted rounded-full overflow-hidden">
					<div
						class="h-full rounded-full transition-all duration-300 {!store.bulkCollecting ? (store.totalFail > 0 ? 'bg-red-500' : 'bg-green-500') : 'bg-primary'}"
						style="width: {overallPercent}%"
					></div>
				</div>
				<span class="text-xs font-medium text-muted-foreground">{overallPercent}%</span>
			</div>
			<Maximize2 size={14} class="text-muted-foreground" />
		</button>
	{:else}
		<!-- Expanded View -->
		<div class="fixed bottom-6 right-6 w-[360px] bg-background rounded-xl shadow-lg border z-50 overflow-hidden">
			<!-- Header -->
			<div class="px-4 py-3 border-b bg-muted/50 flex items-center justify-between">
				<div class="flex items-center gap-2">
					<div class={store.bulkCollecting ? 'animate-pulse' : ''}>
						<AISparkleIcon size={18} filled />
					</div>
					<span class="font-semibold text-sm">
						{store.bulkCollecting ? 'Collecting...' : 'AI Collection Progress'}
					</span>
				</div>
				<div class="flex items-center gap-0.5">
					<button
						class="p-1 rounded hover:bg-accent"
						title="Minimize"
						onclick={() => { minimized = true; }}
					>
						<Minus size={16} />
					</button>
					<button
						class="p-1 rounded hover:bg-accent"
						title={store.bulkCollecting ? 'Cancel' : 'Close'}
						onclick={() => {
							if (store.bulkCollecting) {
								showCancelConfirm = true;
							} else {
								setShowCollectionProgress(false);
							}
						}}
					>
						<X size={16} />
					</button>
				</div>
			</div>

			<!-- Progress Content -->
			<div class="px-4 py-3 max-h-[300px] overflow-y-auto space-y-4">
				{#each store.collectionProgressList as progress}
					{@const isCompleted = progress.total > 0 && progress.current >= progress.total}
					{@const itemPercent = progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 0}
					<div class="p-3 bg-muted/50 rounded-lg">
						<div class="flex justify-between mb-2">
							<span class="font-mono text-xs font-medium truncate">
								{progress.databaseName}.{progress.schemaName}
							</span>
							<span class="text-xs text-muted-foreground">
								{progress.current} / {progress.total}
							</span>
						</div>

						<!-- Progress Bar -->
						<div class="w-full h-2 bg-muted rounded-full overflow-hidden">
							<div
								class="h-full rounded-full transition-all duration-300 {isCompleted ? (progress.failCount > 0 ? 'bg-red-500' : 'bg-green-500') : 'bg-primary'}"
								style="width: {itemPercent}%"
							></div>
						</div>

						<div class="mt-1.5 flex justify-between items-center">
							<span class="text-[11px] text-muted-foreground max-w-[180px] truncate" title={progress.currentTable}>
								{#if isCompleted}
									<span class="text-green-500">Completed</span>
								{:else}
									Processing: {progress.currentTable}
								{/if}
							</span>
							<span class="text-[11px]">
								<span class="text-green-500">{progress.successCount}</span>
								{' / '}
								<span class={progress.failCount > 0 ? 'text-red-500' : 'text-muted-foreground'}>
									{progress.failCount}
								</span>
							</span>
						</div>
					</div>
				{/each}
			</div>

			<!-- Footer -->
			<div class="px-4 py-2.5 border-t bg-muted/50 flex justify-between items-center">
				<span class="text-xs text-muted-foreground">
					{store.completedSchemas.length} / {store.collectionProgressList.length} schemas
				</span>
				<span class="text-xs">
					<span class="text-green-500">{store.totalSuccess}</span>
					{' success, '}
					<span class={store.totalFail > 0 ? 'text-red-500' : 'text-muted-foreground'}>
						{store.totalFail}
					</span>
					{' failed'}
				</span>
			</div>
		</div>
	{/if}
{/if}

<!-- Cancel Collection Confirmation -->
<Dialog bind:open={showCancelConfirm}>
	<div class="p-6">
		<div class="flex items-center gap-2 mb-2">
			<AlertTriangle size={20} class="text-destructive" />
			<h3 class="text-lg font-semibold">Cancel Collection?</h3>
		</div>
		<p class="text-sm text-muted-foreground mb-6">
			The collection is still in progress. Are you sure you want to cancel?
		</p>
		<div class="flex justify-end gap-2">
			<Button variant="outline" onclick={() => { showCancelConfirm = false; }}>Continue</Button>
			<Button variant="destructive" onclick={() => {
				cancelCollection();
				showCancelConfirm = false;
				resetCollectionProgress();
			}}>Yes, Cancel</Button>
		</div>
	</div>
</Dialog>
