<script lang="ts">
	import type { IGridItem } from '$lib/stores/dashboard.svelte';

	interface Props {
		items: IGridItem[];
		editMode?: boolean;
		onchange?: (items: IGridItem[]) => void;
		onremove?: (id: string) => void;
		ondropnew?: (type: string, insertY: number, insertX?: number, insertWidth?: number) => void;
		ondropchart?: (chartData: string, insertY: number, insertX?: number, insertWidth?: number) => void;
		children?: import('svelte').Snippet<[IGridItem, IGridItem[], number]>;
	}

	let { items, editMode = false, onchange, onremove, ondropnew, ondropchart, children }: Props = $props();

	let containerEl = $state<HTMLDivElement | null>(null);
	let containerWidth = $state(1200);
	let resizingItemId = $state<string | null>(null);

	// Drag & drop state
	let isDraggingOver = $state(false);
	let draggingItemId = $state<string | null>(null);
	let dropIndicator = $state<{ insertY: number; insertX?: number; insertWidth?: number; reorderIndex?: number } | null>(null);
	let dropHandled = $state(false);

	const COLUMNS = 12;
	const GUTTER = 16;
	const MIN_CHART_WIDTH_COLS = 2;

	const columnWidth = $derived((containerWidth - GUTTER * (COLUMNS - 1)) / COLUMNS);

	// Group items into rows by y value, sorted
	const groupedRows = $derived.by(() => {
		const sorted = [...items].sort((a, b) => a.y !== b.y ? a.y - b.y : a.x - b.x);
		const rows: { y: number; items: IGridItem[] }[] = [];

		for (const item of sorted) {
			const existing = rows.find(r => r.y === item.y);
			if (existing) {
				existing.items.push(item);
			} else {
				rows.push({ y: item.y, items: [item] });
			}
		}

		rows.forEach(row => row.items.sort((a, b) => a.x - b.x));
		return rows;
	});

	// ─── Drop indicator logic ───
	// Prefer placing into a row when there is free horizontal space; otherwise fall back to row insertion.
	function handleRowDragOver(e: DragEvent, row: { y: number; items: IGridItem[] }) {
		e.preventDefault();
		e.stopPropagation();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		isDraggingOver = true;

		const el = e.currentTarget as HTMLElement;
		const rect = el.getBoundingClientRect();
		const mouseY = e.clientY - rect.top;
		const mouseX = e.clientX - rect.left;
		const topZone = rect.height * 0.25;
		const bottomZone = rect.height * 0.75;
		const movingItemId = draggingItemId ?? getMovingItemId(e);
		const incomingWidth = getIncomingWidth(e, movingItemId);
		const preferredX = Math.round(mouseX / (columnWidth + GUTTER));

		if (mouseY < topZone) {
			dropIndicator = { insertY: row.y };
			return;
		}

		if (mouseY > bottomZone) {
			dropIndicator = { insertY: row.y + 1 };
			return;
		}

		const reorderPlacement = movingItemId
			? findRowReorderPlacement(row, preferredX, movingItemId)
			: null;
		if (reorderPlacement) {
			dropIndicator = {
				insertY: row.y,
				insertX: reorderPlacement.x,
				insertWidth: reorderPlacement.width,
				reorderIndex: reorderPlacement.index
			};
			return;
		}

		const placement = findRowPlacement(row, preferredX, incomingWidth, movingItemId);
		if (placement) {
			dropIndicator = { insertY: row.y, insertX: placement.x, insertWidth: placement.width };
			return;
		}

		dropIndicator = { insertY: row.y + 1 };
	}

	function handleRowDragLeave(e: DragEvent) {
		const relatedTarget = e.relatedTarget as HTMLElement | null;
		const currentTarget = e.currentTarget as HTMLElement;
		if (relatedTarget && currentTarget.contains(relatedTarget)) return;
		// Don't clear dropIndicator here - let container handle it
	}

	function handleRowDrop(e: DragEvent) {
		e.preventDefault();
		e.stopPropagation();

		if (dropHandled) return;
		if (!dropIndicator) return;

		dropHandled = true;
		setTimeout(() => { dropHandled = false; }, 200);

		const insertY = dropIndicator.insertY;
		const insertX = dropIndicator.insertX;
		const insertWidth = dropIndicator.insertWidth;
		const reorderIndex = dropIndicator.reorderIndex;
		dropIndicator = null;
		isDraggingOver = false;
		draggingItemId = null;

		processDropData(e, insertY, insertX, insertWidth, reorderIndex);
	}

	function processDropData(e: DragEvent, insertY: number, insertX?: number, insertWidth?: number, reorderIndex?: number) {
		// Check for layout element or existing item
		const data = e.dataTransfer?.getData('application/dashboard-item');
		if (data) {
			try {
				const parsed = JSON.parse(data);
				if (parsed.isNew) {
					ondropnew?.(parsed.type, insertY, insertX, insertWidth);
				} else if (parsed.id) {
					handleMoveToRow(parsed.id, insertY, insertX, insertWidth, reorderIndex);
				}
			} catch { /* ignore */ }
			return;
		}

		// Check for saved chart drop
		const chartData = e.dataTransfer?.getData('application/dashboard-chart');
		if (chartData) {
			ondropchart?.(chartData, insertY, insertX, insertWidth);
			return;
		}
	}

	// ─── Container-level drag handlers ───
	function handleContainerDragEnter(e: DragEvent) {
		isDraggingOver = true;
	}

	function handleContainerDragLeave(e: DragEvent) {
		const relatedTarget = e.relatedTarget as HTMLElement | null;
		if (relatedTarget && containerEl?.contains(relatedTarget)) return;
		isDraggingOver = false;
		dropIndicator = null;
		draggingItemId = null;
	}

	function handleContainerDragOver(e: DragEvent) {
		e.preventDefault();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		isDraggingOver = true;
	}

	// Fallback drop on the container itself (empty area below all rows)
	function handleContainerDrop(e: DragEvent) {
		e.preventDefault();
		e.stopPropagation();
		isDraggingOver = false;
		draggingItemId = null;

		if (dropHandled) { dropIndicator = null; return; }
		dropHandled = true;
		setTimeout(() => { dropHandled = false; }, 200);

		// If dropIndicator is set (from a row dragover), use that position
		if (dropIndicator) {
			const insertY = dropIndicator.insertY;
			const insertX = dropIndicator.insertX;
			const insertWidth = dropIndicator.insertWidth;
			const reorderIndex = dropIndicator.reorderIndex;
			dropIndicator = null;
			processDropData(e, insertY, insertX, insertWidth, reorderIndex);
			return;
		}

		dropIndicator = null;

		// Otherwise drop at the end
		const maxY = items.length > 0 ? Math.max(...items.map(i => i.y)) + 1 : 0;
		processDropData(e, maxY);
	}

	function handleMoveToRow(itemId: string, insertY: number, insertX?: number, insertWidth?: number, reorderIndex?: number) {
		const sourceItem = items.find(i => i.id === itemId);
		if (!sourceItem) return;

		const sourceY = sourceItem.y;

		if (reorderIndex !== undefined && sourceY === insertY) {
			onchange?.(reorderItemWithinRow(itemId, insertY, reorderIndex));
			return;
		}

		if (insertX !== undefined) {
			const movedItem = { ...sourceItem, y: insertY, x: insertX, width: insertWidth ?? sourceItem.width };
			const updated = items.map(item => item.id === itemId ? movedItem : item);
			onchange?.(normalizeYPositions(updated));
			return;
		}

		const withoutItem = items.filter(i => i.id !== itemId);
		const normalizedWithout = normalizeYPositions(withoutItem);

		const maxNormY = normalizedWithout.length > 0 ? Math.max(...normalizedWithout.map(i => i.y)) + 1 : 0;
		let actualInsertY = insertY;
		if (sourceY < insertY && insertY !== sourceY + 1) {
			actualInsertY = insertY - 1;
		}
		const clampedInsertY = Math.min(actualInsertY, maxNormY);

		const shifted = normalizedWithout.map(item => {
			if (item.y >= clampedInsertY) {
				return { ...item, y: item.y + 1 };
			}
			return item;
		});

		const movedItem = { ...sourceItem, y: clampedInsertY, x: 0 };
		shifted.push(movedItem);

		const normalized = normalizeYPositions(shifted);
		onchange?.(normalized);
	}

	function reorderItemWithinRow(itemId: string, rowY: number, reorderIndex: number): IGridItem[] {
		const rowItems = items
			.filter(item => item.y === rowY)
			.sort((a, b) => a.x - b.x);
		const movingItem = rowItems.find(item => item.id === itemId);
		if (!movingItem) return items;

		const withoutMoving = rowItems.filter(item => item.id !== itemId);
		const clampedIndex = Math.max(0, Math.min(withoutMoving.length, reorderIndex));
		const reordered = [
			...withoutMoving.slice(0, clampedIndex),
			movingItem,
			...withoutMoving.slice(clampedIndex)
		];

		let cursor = 0;
		const packed = new Map<string, IGridItem>();
		for (const item of reordered) {
			packed.set(item.id, { ...item, x: cursor });
			cursor += item.width;
		}

		return items.map(item => packed.get(item.id) ?? item);
	}

	function getMovingItemId(e: DragEvent): string | undefined {
		const data = e.dataTransfer?.getData('application/dashboard-item');
		if (!data) return undefined;
		try {
			const parsed = JSON.parse(data);
			return parsed.id;
		} catch {
			return undefined;
		}
	}

	function getIncomingWidth(e: DragEvent, movingItemId?: string): number {
		if (movingItemId) {
			return items.find(item => item.id === movingItemId)?.width ?? 6;
		}

		const itemData = e.dataTransfer?.getData('application/dashboard-item');
		if (itemData) {
			try {
				const parsed = JSON.parse(itemData);
				if (parsed.type === 'text') return 6;
				return 12;
			} catch { /* ignore */ }
		}

		if (e.dataTransfer?.getData('application/dashboard-chart')) return 6;
		return 6;
	}

	function findRowPlacement(
		row: { y: number; items: IGridItem[] },
		preferredX: number,
		incomingWidth: number,
		movingItemId?: string
	): { x: number; width: number } | null {
		const gaps: Array<{ start: number; end: number }> = [];
		let cursor = 0;
		const rowItems = row.items
			.filter(item => item.id !== movingItemId)
			.sort((a, b) => a.x - b.x);

		for (const item of rowItems) {
			if (item.x > cursor) {
				gaps.push({ start: cursor, end: item.x });
			}
			cursor = Math.max(cursor, item.x + item.width);
		}
		if (cursor < COLUMNS) {
			gaps.push({ start: cursor, end: COLUMNS });
		}

		const availableGaps = gaps
			.map(gap => ({ ...gap, width: gap.end - gap.start }))
			.filter(gap => gap.width >= MIN_CHART_WIDTH_COLS);
		if (availableGaps.length === 0) return null;

		const selectedGap = availableGaps
			.map(gap => ({
				gap,
				distance:
					preferredX < gap.start
						? gap.start - preferredX
						: preferredX > gap.end
							? preferredX - gap.end
							: 0
			}))
			.sort((a, b) => a.distance - b.distance || a.gap.start - b.gap.start)[0].gap;

		return {
			x: selectedGap.start,
			width: Math.max(MIN_CHART_WIDTH_COLS, Math.min(incomingWidth, selectedGap.width))
		};
	}

	function findRowReorderPlacement(
		row: { y: number; items: IGridItem[] },
		preferredX: number,
		movingItemId: string
	): { index: number; x: number; width: number } | null {
		const movingItem = row.items.find(item => item.id === movingItemId);
		if (!movingItem) return null;

		const otherItems = row.items
			.filter(item => item.id !== movingItemId)
			.sort((a, b) => a.x - b.x);
		const totalWidth = otherItems.reduce((sum, item) => sum + item.width, movingItem.width);
		if (totalWidth > COLUMNS) return null;

		let index = 0;
		for (const item of otherItems) {
			if (preferredX >= item.x + item.width / 2) {
				index += 1;
			}
		}

		const x = otherItems
			.slice(0, index)
			.reduce((sum, item) => sum + item.width, 0);

		return { index, x, width: movingItem.width };
	}

	// ─── Resize handling ───
	function handleResizeUpdate(itemId: string, newWidthCols: number, newHeight: number) {
		onchange?.(resolveResizeUpdate(itemId, newWidthCols, newHeight));
	}

	function resolveResizeUpdate(itemId: string, requestedWidth: number, requestedHeight: number): IGridItem[] {
		const target = items.find(item => item.id === itemId);
		if (!target) return items;

		const rightItems = items
			.filter(item => item.id !== itemId && item.y === target.y && item.x > target.x)
			.sort((a, b) => a.x - b.x);
		let targetWidth = Math.max(MIN_CHART_WIDTH_COLS, Math.min(COLUMNS - target.x, requestedWidth));

		while (targetWidth > MIN_CHART_WIDTH_COLS && !canResizePreservingRightItems(target, targetWidth, rightItems)) {
			targetWidth -= 1;
		}

		const adjustedRightItems = shiftRightItemsPreservingWidth(target, targetWidth, rightItems);

		return items.map(item => {
			if (item.id === itemId) {
				return { ...item, width: targetWidth, height: requestedHeight };
			}
			return adjustedRightItems.get(item.id) ?? item;
		});
	}

	function canResizePreservingRightItems(target: IGridItem, targetWidth: number, rightItems: IGridItem[]): boolean {
		let cursor = target.x + targetWidth;
		for (const item of rightItems) {
			const nextX = Math.max(item.x, cursor);
			cursor = nextX + item.width;
			if (cursor > COLUMNS) return false;
		}
		return true;
	}

	function shiftRightItemsPreservingWidth(target: IGridItem, targetWidth: number, rightItems: IGridItem[]): Map<string, IGridItem> {
		const adjusted = new Map<string, IGridItem>();
		let cursor = target.x + targetWidth;
		for (const item of rightItems) {
			const nextX = Math.max(item.x, cursor);
			cursor = nextX + item.width;
			adjusted.set(item.id, { ...item, x: nextX });
		}
		return adjusted;
	}

	// ─── Item drag within grid (reorder) ───
	function handleItemDragStart(e: DragEvent, item: IGridItem) {
		if (!editMode) return;
		if (!e.dataTransfer) return;
		e.dataTransfer.setData('application/dashboard-item', JSON.stringify({ id: item.id, type: item.type }));
		// Also set grid-chart type so tabs can accept chart drops from the grid
		if (item.type === 'chart') {
			e.dataTransfer.setData('application/grid-chart', JSON.stringify({ id: item.id, chartId: item.chartId }));
		}
		e.dataTransfer.setData('text/plain', item.id);
		e.dataTransfer.effectAllowed = 'all';
		draggingItemId = item.id;
	}

	function handleItemDragEnd(e: DragEvent) {
		draggingItemId = null;
		dropIndicator = null;
		isDraggingOver = false;
	}

	// ─── Normalize y positions (remove gaps) ───
	function normalizeYPositions(itemList: IGridItem[]): IGridItem[] {
		const yValues = [...new Set(itemList.map(i => i.y))].sort((a, b) => a - b);
		const yMap = new Map<number, number>();
		yValues.forEach((y, idx) => yMap.set(y, idx));

		return itemList.map(item => ({
			...item,
			y: yMap.get(item.y) ?? item.y
		}));
	}

	// Check if the drop indicator should show before a specific row
	function showIndicatorBefore(rowY: number): boolean {
		if (!dropIndicator || !isDraggingOver) return false;
		if (dropIndicator.insertX !== undefined) return false;
		return dropIndicator.insertY === rowY;
	}

	// Check if the drop indicator should show after the last row
	function showIndicatorAfterLast(): boolean {
		if (!dropIndicator || !isDraggingOver || groupedRows.length === 0) return false;
		if (dropIndicator.insertX !== undefined) return false;
		const lastRowY = groupedRows[groupedRows.length - 1].y;
		return dropIndicator.insertY > lastRowY;
	}

	function showHorizontalIndicator(rowY: number): boolean {
		return !!dropIndicator
			&& isDraggingOver
			&& dropIndicator.insertY === rowY
			&& dropIndicator.insertX !== undefined
			&& dropIndicator.insertWidth !== undefined;
	}

	function horizontalIndicatorStyle(): string {
		if (!dropIndicator || dropIndicator.insertX === undefined || dropIndicator.insertWidth === undefined) {
			return '';
		}
		const left = dropIndicator.insertX * (columnWidth + GUTTER);
		const width = dropIndicator.insertWidth * (columnWidth + GUTTER) - GUTTER;
		return `left: ${left}px; width: ${width}px;`;
	}

	// Observe container width
	$effect(() => {
		if (!containerEl) return;
		const observer = new ResizeObserver(entries => {
			for (const entry of entries) {
				containerWidth = entry.contentRect.width;
			}
		});
		observer.observe(containerEl);
		return () => observer.disconnect();
	});

	// Reset drag state when any drag operation ends (handles stopPropagation in children)
	$effect(() => {
		function resetDragState() {
			isDraggingOver = false;
			dropIndicator = null;
			draggingItemId = null;
		}
		document.addEventListener('dragend', resetDragState);
		return () => document.removeEventListener('dragend', resetDragState);
	});
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
	bind:this={containerEl}
	class="w-full flex flex-col gap-4 min-h-[200px] relative"
	ondragenter={handleContainerDragEnter}
	ondragleave={handleContainerDragLeave}
	ondragover={handleContainerDragOver}
	ondrop={handleContainerDrop}
>
	{#if editMode && resizingItemId}
		<!-- Grid column guides (only visible during resize, scoped to container) -->
		<div class="absolute inset-0 pointer-events-none z-40 animate-in fade-in-0 duration-150">
			{#each Array(COLUMNS) as _, col}
				<div
					class="absolute top-0 bottom-0 rounded-sm bg-primary/5 border-x border-primary/10"
					style="left: {col * (columnWidth + GUTTER)}px; width: {columnWidth}px;"
				></div>
			{/each}
		</div>
	{/if}

	<!-- Rows -->
	{#each groupedRows as row, rowIndex (row.y)}
		{@const hasOnlyFullWidthElements = row.items.every(i => i.type === 'header' || i.type === 'divider' || i.type === 'tabs')}
		{@const isBeingDragged = row.items.length === 1 && row.items[0].id === draggingItemId}

		<!-- Drop indicator BEFORE this row -->
		{#if showIndicatorBefore(row.y)}
			<div class="h-10 border-2 border-dashed border-primary bg-primary/10 rounded-lg flex items-center justify-center transition-all">
				<span class="text-xs text-primary font-medium">Drop here</span>
			</div>
		{/if}

		{#if hasOnlyFullWidthElements}
			<!-- Full-width elements: render vertically -->
			{#each row.items as item (item.id)}
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div
					class="group relative {editMode ? 'hover:ring-2 hover:ring-primary/30 rounded-lg' : ''} {draggingItemId === item.id ? 'opacity-30' : ''}"
					draggable={editMode}
					ondragstart={(e) => handleItemDragStart(e, item)}
					ondragend={handleItemDragEnd}
					ondragover={(e) => handleRowDragOver(e, row)}
					ondragleave={handleRowDragLeave}
					ondrop={handleRowDrop}
				>
					{#if editMode}
						<div class="absolute top-1 left-1/2 -translate-x-1/2 flex items-center justify-center cursor-grab active:cursor-grabbing z-10 opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity">
							<svg class="w-3 h-3 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
								<circle cx="8" cy="6" r="1.5"/><circle cx="16" cy="6" r="1.5"/>
								<circle cx="8" cy="12" r="1.5"/><circle cx="16" cy="12" r="1.5"/>
								<circle cx="8" cy="18" r="1.5"/><circle cx="16" cy="18" r="1.5"/>
							</svg>
						</div>
					{/if}
					{#if children}
						{@render children(item, row.items, containerWidth)}
					{/if}
				</div>
			{/each}
		{:else}
			<!-- Row with charts/text: render on a 12-column grid so x/width are respected -->
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div
				class="relative grid gap-4 {showHorizontalIndicator(row.y) ? 'ring-2 ring-primary/30 rounded-lg' : ''}"
				style="grid-template-columns: repeat({COLUMNS}, minmax(0, 1fr));"
				ondragover={(e) => handleRowDragOver(e, row)}
				ondragleave={handleRowDragLeave}
				ondrop={handleRowDrop}
			>
				{#if showHorizontalIndicator(row.y)}
					<div
						class="pointer-events-none absolute inset-y-0 z-30 rounded-lg border-2 border-dashed border-primary bg-primary/10 flex items-center justify-center"
						style={horizontalIndicatorStyle()}
					>
						<span class="text-xs text-primary font-medium bg-background/90 px-2 py-1 rounded shadow-sm">Drop here</span>
					</div>
				{/if}

				{#each row.items as item (item.id)}
					<div
						class="group relative shrink-0 rounded-lg border border-border bg-card overflow-hidden transition-shadow {editMode ? 'hover:ring-2 hover:ring-primary/30' : ''} {draggingItemId === item.id ? 'opacity-30' : ''}"
						style="grid-column: {item.x + 1} / span {item.width}; height: {item.height}px;"
						draggable={editMode}
						ondragstart={(e) => handleItemDragStart(e, item)}
						ondragend={handleItemDragEnd}
					>
						{#if editMode}
							<!-- Drag handle -->
							<div class="absolute top-1 left-1/2 -translate-x-1/2 flex items-center justify-center cursor-grab active:cursor-grabbing z-10 opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity">
								<svg class="w-3.5 h-3.5 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
									<circle cx="8" cy="6" r="1.5"/><circle cx="16" cy="6" r="1.5"/>
									<circle cx="8" cy="12" r="1.5"/><circle cx="16" cy="12" r="1.5"/>
									<circle cx="8" cy="18" r="1.5"/><circle cx="16" cy="18" r="1.5"/>
								</svg>
							</div>
						{/if}

						<!-- Resize handles (edit mode only) -->
						{#if editMode}
							<!-- Right edge resize -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="absolute top-0 right-0 w-1.5 h-full cursor-col-resize z-20 hover:bg-primary/20 transition-colors"
								onmousedown={(e) => {
									e.preventDefault();
									e.stopPropagation();
									resizingItemId = item.id;
									const startX = e.clientX;
									const startW = item.width;
									const move = (ev: MouseEvent) => {
										const dx = ev.clientX - startX;
										const colDelta = Math.round(dx / (columnWidth + GUTTER));
										const newW = Math.max(MIN_CHART_WIDTH_COLS, Math.min(COLUMNS, startW + colDelta));
										handleResizeUpdate(item.id, newW, item.height);
									};
									const up = () => {
										window.removeEventListener('mousemove', move);
										window.removeEventListener('mouseup', up);
										resizingItemId = null;
									};
									window.addEventListener('mousemove', move);
									window.addEventListener('mouseup', up);
								}}
							></div>

							<!-- Bottom edge resize -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="absolute bottom-0 left-0 h-1.5 w-full cursor-row-resize z-20 hover:bg-primary/20 transition-colors"
								onmousedown={(e) => {
									e.preventDefault();
									e.stopPropagation();
									resizingItemId = item.id;
									const startY = e.clientY;
									const startH = item.height;
									const move = (ev: MouseEvent) => {
										const dy = ev.clientY - startY;
										const newH = Math.max(100, Math.round((startH + dy) / 8) * 8);
										handleResizeUpdate(item.id, item.width, newH);
									};
									const up = () => {
										window.removeEventListener('mousemove', move);
										window.removeEventListener('mouseup', up);
										resizingItemId = null;
									};
									window.addEventListener('mousemove', move);
									window.addEventListener('mouseup', up);
								}}
							></div>

							<!-- Corner resize -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="absolute bottom-0 right-0 w-4 h-4 cursor-se-resize z-20"
								onmousedown={(e) => {
									e.preventDefault();
									e.stopPropagation();
									resizingItemId = item.id;
									const startX = e.clientX;
									const startY = e.clientY;
									const startW = item.width;
									const startH = item.height;
									const move = (ev: MouseEvent) => {
										const dx = ev.clientX - startX;
										const dy = ev.clientY - startY;
										const colDelta = Math.round(dx / (columnWidth + GUTTER));
										const newW = Math.max(MIN_CHART_WIDTH_COLS, Math.min(COLUMNS, startW + colDelta));
										const newH = Math.max(100, Math.round((startH + dy) / 8) * 8);
										handleResizeUpdate(item.id, newW, newH);
									};
									const up = () => {
										window.removeEventListener('mousemove', move);
										window.removeEventListener('mouseup', up);
										resizingItemId = null;
									};
									window.addEventListener('mousemove', move);
									window.addEventListener('mouseup', up);
								}}
							>
								<svg class="w-4 h-4 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
									<path d="M22 22H20V20H22V22ZM22 18H18V22H16V16H22V18ZM18 14H14V18H12V12H18V14Z"/>
								</svg>
							</div>
						{/if}

						{#if children}
							{@render children(item, row.items, containerWidth)}
						{/if}
					</div>
				{/each}
			</div>
		{/if}
	{/each}

	<!-- Drop indicator AFTER the last row -->
	{#if showIndicatorAfterLast()}
		<div class="h-10 border-2 border-dashed border-primary bg-primary/10 rounded-lg flex items-center justify-center transition-all">
			<span class="text-xs text-primary font-medium">Drop here</span>
		</div>
	{/if}

	{#if items.length === 0}
		<!-- Empty state with full drop zone -->
		<div
			class="flex flex-col items-center justify-center h-64 rounded-xl transition-all
				{isDraggingOver ? 'border-2 border-dashed border-primary bg-primary/5' : 'border-2 border-dashed border-border'}"
			ondragover={(e) => { e.preventDefault(); e.stopPropagation(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'; isDraggingOver = true; }}
			ondragleave={() => isDraggingOver = false}
			ondrop={(e) => { e.preventDefault(); e.stopPropagation(); if (dropHandled) return; dropHandled = true; setTimeout(() => { dropHandled = false; }, 200); isDraggingOver = false; draggingItemId = null; processDropData(e, 0); }}
			role="region"
		>
			{#if isDraggingOver}
				<div class="text-sm text-primary font-medium">Drop here to add</div>
			{:else}
				<div class="text-sm text-muted-foreground">{editMode ? 'Drag items here or click to add from sidebar' : 'No charts added yet'}</div>
			{/if}
		</div>
	{/if}
</div>
