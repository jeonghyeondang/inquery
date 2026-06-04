<script lang="ts">
	import type { IGridItem } from '$lib/stores/dashboard.svelte';

	interface Props {
		item: IGridItem;
		editMode: boolean;
		columnWidth: number;
		onmove?: (id: string, deltaX: number, deltaY: number) => void;
		onresize?: (id: string, width: number, height: number) => void;
		onremove?: (id: string) => void;
		onresizestart?: () => void;
		onresizeend?: () => void;
		children?: import('svelte').Snippet;
	}

	let { item, editMode, columnWidth, onmove, onresize, onremove, onresizestart, onresizeend, children }: Props = $props();

	let isDragging = $state(false);
	let isResizing = $state(false);
	let dragStartX = 0;
	let dragStartY = 0;
	let itemStartX = 0;
	let itemStartY = 0;
	let itemStartW = 0;
	let itemStartH = 0;

	const GUTTER = 16;

	function handleDragStart(e: MouseEvent) {
		if (!editMode) return;
		e.preventDefault();
		isDragging = true;
		dragStartX = e.clientX;
		dragStartY = e.clientY;
		itemStartX = item.x;
		itemStartY = item.y;

		const handleMove = (ev: MouseEvent) => {
			const dx = ev.clientX - dragStartX;
			const dy = ev.clientY - dragStartY;
			const colDelta = Math.round(dx / (columnWidth + GUTTER));
			const newY = Math.max(0, itemStartY + dy);
			onmove?.(item.id, itemStartX + colDelta, newY);
		};

		const handleUp = () => {
			isDragging = false;
			window.removeEventListener('mousemove', handleMove);
			window.removeEventListener('mouseup', handleUp);
		};

		window.addEventListener('mousemove', handleMove);
		window.addEventListener('mouseup', handleUp);
	}

	function handleResizeStart(e: MouseEvent, direction: 'corner' | 'right' | 'bottom' = 'corner') {
		if (!editMode) return;
		e.preventDefault();
		e.stopPropagation();
		isResizing = true;
		onresizestart?.();
		dragStartX = e.clientX;
		dragStartY = e.clientY;
		itemStartW = item.width;
		itemStartH = item.height;

		const handleMove = (ev: MouseEvent) => {
			const dx = ev.clientX - dragStartX;
			const dy = ev.clientY - dragStartY;

			let newWidth = itemStartW;
			let newHeight = itemStartH;

			if (direction === 'corner' || direction === 'right') {
				const colDelta = Math.round(dx / (columnWidth + GUTTER));
				newWidth = Math.max(2, Math.min(12 - item.x, itemStartW + colDelta));
			}
			if (direction === 'corner' || direction === 'bottom') {
				newHeight = Math.max(100, Math.round((itemStartH + dy) / 8) * 8);
			}

			onresize?.(item.id, newWidth, newHeight);
		};

		const handleUp = () => {
			isResizing = false;
			onresizeend?.();
			window.removeEventListener('mousemove', handleMove);
			window.removeEventListener('mouseup', handleUp);
		};

		window.addEventListener('mousemove', handleMove);
		window.addEventListener('mouseup', handleUp);
	}

	const left = $derived(item.x * (columnWidth + GUTTER));
	const width = $derived(item.width * (columnWidth + GUTTER) - GUTTER);
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
	class="absolute transition-shadow rounded-lg border bg-card overflow-hidden
		{editMode ? 'cursor-move hover:ring-2 hover:ring-primary/30' : ''}
		{isDragging ? 'ring-2 ring-primary shadow-lg z-20 opacity-40' : ''}
		{isResizing ? 'ring-2 ring-primary/30 z-20' : ''}"
	style="left: {left}px; top: {item.y}px; width: {width}px; height: {item.height}px;"
	onmousedown={handleDragStart}
>
	{#if editMode}
		<!-- Drag handle -->
		<div class="absolute top-0 left-0 right-0 h-6 flex items-center justify-center cursor-grab active:cursor-grabbing z-10 bg-muted/50 opacity-0 hover:opacity-100 transition-opacity">
			<svg class="w-4 h-4 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
				<circle cx="8" cy="6" r="1.5"/><circle cx="16" cy="6" r="1.5"/>
				<circle cx="8" cy="12" r="1.5"/><circle cx="16" cy="12" r="1.5"/>
				<circle cx="8" cy="18" r="1.5"/><circle cx="16" cy="18" r="1.5"/>
			</svg>
		</div>

		<!-- Delete button -->
		<button
			class="absolute top-1 right-1 z-10 w-5 h-5 rounded flex items-center justify-center text-xs bg-destructive/80 text-destructive-foreground hover:bg-destructive opacity-0 hover:opacity-100 transition-opacity"
			onclick={(e) => { e.stopPropagation(); onremove?.(item.id); }}
		>✕</button>

		<!-- Right edge resize handle -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="absolute top-0 right-0 w-1.5 h-full cursor-col-resize z-10 hover:bg-primary/20 transition-colors"
			onmousedown={(e) => handleResizeStart(e, 'right')}
		></div>

		<!-- Bottom edge resize handle -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="absolute bottom-0 left-0 h-1.5 w-full cursor-row-resize z-10 hover:bg-primary/20 transition-colors"
			onmousedown={(e) => handleResizeStart(e, 'bottom')}
		></div>

		<!-- Corner resize handle -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="absolute bottom-0 right-0 w-4 h-4 cursor-se-resize z-10"
			onmousedown={(e) => handleResizeStart(e, 'corner')}
		>
			<svg class="w-4 h-4 text-muted-foreground" viewBox="0 0 24 24" fill="currentColor">
				<path d="M22 22H20V20H22V22ZM22 18H18V22H16V16H22V18ZM18 14H14V18H12V12H18V14Z"/>
			</svg>
		</div>
	{/if}

	<!-- Content -->
	<div class="h-full {editMode && item.type === 'chart' ? 'pointer-events-none' : ''}">
		{#if children}
			{@render children()}
		{:else}
			<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
				{item.title || 'Chart'}
			</div>
		{/if}
	</div>
</div>
