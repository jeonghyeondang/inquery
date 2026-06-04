<script lang="ts">
	import { BaseEdge, EdgeLabel, getSmoothStepPath, type EdgeProps } from '@xyflow/svelte';

	let {
		id,
		sourceX,
		sourceY,
		targetX,
		targetY,
		sourcePosition,
		targetPosition,
		label,
		markerEnd,
		style,
		data
	}: EdgeProps = $props();

	const CHAR_WIDTH = 6.5;
	const LABEL_H = 22;
	const PAD = 12;

	type Rect = { x: number; y: number; w: number; h: number };

	function estimateLabelWidth(text: string): number {
		return text.length * CHAR_WIDTH + 16;
	}

	function overlapsAny(lx: number, ly: number, lw: number, rects: Rect[]): boolean {
		const halfW = lw / 2 + PAD;
		const halfH = LABEL_H / 2 + PAD;
		for (const r of rects) {
			if (
				lx + halfW > r.x &&
				lx - halfW < r.x + r.w &&
				ly + halfH > r.y &&
				ly - halfH < r.y + r.h
			) {
				return true;
			}
		}
		return false;
	}

	let edgePath = $derived(
		getSmoothStepPath({
			sourceX,
			sourceY,
			sourcePosition,
			targetX,
			targetY,
			targetPosition
		})
	);

	let labelPos = $derived.by(() => {
		const defaultX = edgePath[1];
		const defaultY = edgePath[2];
		if (!label) return { x: defaultX, y: defaultY };

		const rawRects = (data as Record<string, unknown>)?.nodeRects as
			| Record<string, Rect>
			| undefined;
		if (!rawRects) return { x: defaultX, y: defaultY };

		const rects = Object.values(rawRects);
		const labelText = String(label);
		const lw = estimateLabelWidth(labelText);

		if (!overlapsAny(defaultX, defaultY, lw, rects)) {
			return { x: defaultX, y: defaultY };
		}

		const midX = (sourceX + targetX) / 2;
		const midY = (sourceY + targetY) / 2;

		const dx = targetX - sourceX;
		const dy = targetY - sourceY;
		const len = Math.sqrt(dx * dx + dy * dy) || 1;
		const perpX = -dy / len;
		const perpY = dx / len;
		const dirX = dx / len;
		const dirY = dy / len;

		const candidates: [number, number][] = [
			[midX, midY],
			// Perpendicular offsets from midpoint (wide range)
			...([50, 90, 140, 200, -50, -90, -140, -200].map(
				(d) => [midX + perpX * d, midY + perpY * d] as [number, number]
			)),
			// Along the edge direction from midpoint
			...([0.15, 0.3, 0.7, 0.85].map(
				(t) => [sourceX + dx * t, sourceY + dy * t] as [number, number]
			)),
			// Perpendicular offsets from quarter/three-quarter points
			...([0.25, 0.75].flatMap((t) => {
				const px = sourceX + dx * t;
				const py = sourceY + dy * t;
				return [60, -60, 120, -120].map(
					(d) => [px + perpX * d, py + perpY * d] as [number, number]
				);
			})),
		];

		for (const [cx, cy] of candidates) {
			if (!overlapsAny(cx, cy, lw, rects)) {
				return { x: cx, y: cy };
			}
		}

		return { x: midX + perpX * 160, y: midY + perpY * 160 };
	});
</script>

<BaseEdge {id} path={edgePath[0]} {markerEnd} {style} />

{#if label}
	<EdgeLabel x={labelPos.x} y={labelPos.y}>
		<span class="erd-edge-label">
			{label}
		</span>
	</EdgeLabel>
{/if}

<style>
	.erd-edge-label {
		font-size: 9px;
		font-weight: 500;
		color: hsl(var(--muted-foreground));
		background: hsl(var(--background));
		padding: 2px 6px;
		border-radius: 4px;
		border: 1px solid color-mix(in srgb, hsl(var(--border)) 60%, transparent);
		box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
		white-space: nowrap;
		pointer-events: none;
	}

	:global(.dark) .erd-edge-label {
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.28);
	}
</style>
