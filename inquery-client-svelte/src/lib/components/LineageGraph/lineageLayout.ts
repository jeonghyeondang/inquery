/**
 * DAG layout algorithm for lineage graphs.
 * Uses topological sorting to assign layers (left-to-right),
 * then vertically spaces nodes within each layer.
 */
import type { Node, Edge } from '@xyflow/svelte';
import { MarkerType } from '@xyflow/svelte';
import type { ILineageNode, ILineageEdge, ILineageGraph } from '$lib/service/catalog';

const NODE_WIDTH = 320;
const NODE_HEIGHT = 90;
const HORIZONTAL_GAP = 400;
const VERTICAL_GAP = 30;

const TYPE_COLORS: Record<string, string> = {
	source: '#ef4444',
	seed: '#f59e0b',
	snapshot: '#8b5cf6',
	model: '#3b82f6'
};

export type ExpandDirection = 'upstream' | 'downstream';
export interface DirectionalExpand {
	upstream: boolean;
	downstream: boolean;
}

export interface VisibleGraph {
	graph: ILineageGraph;
	hiddenCounts: Map<string, { upstream: number; downstream: number }>;
}

/**
 * Builds the visible subgraph with directional expand support.
 * - Focus table is always expanded in both directions.
 * - Each expanded node reveals neighbors only in the expanded direction(s).
 */
export function buildVisibleGraph(
	fullGraph: ILineageGraph,
	focusTable: string,
	expandedNodes: Map<string, DirectionalExpand>,
	focusDatabase?: string,
	focusSchema?: string
): VisibleGraph {
	if (!focusTable || !fullGraph.nodes.length) {
		return { graph: fullGraph, hiddenCounts: new Map() };
	}

	const nameLower = focusTable.toLowerCase();
	const dbLower = focusDatabase?.toLowerCase();
	const schemaLower = focusSchema?.toLowerCase();

	let focusIds = fullGraph.nodes
		.filter((n) => {
			if (n.name.toLowerCase() !== nameLower) return false;
			if (dbLower && n.database?.toLowerCase() !== dbLower) return false;
			if (schemaLower && n.schema?.toLowerCase() !== schemaLower) return false;
			return true;
		})
		.map((n) => n.uniqueId);

	if (focusIds.length === 0) {
		focusIds = fullGraph.nodes
			.filter((n) => n.name.toLowerCase() === nameLower)
			.map((n) => n.uniqueId);
	}

	if (focusIds.length === 0) {
		return { graph: { nodes: [], edges: [] }, hiddenCounts: new Map() };
	}

	const childrenMap = new Map<string, Set<string>>();
	const parentsMap = new Map<string, Set<string>>();
	for (const n of fullGraph.nodes) {
		childrenMap.set(n.uniqueId, new Set());
		parentsMap.set(n.uniqueId, new Set());
	}
	for (const e of fullGraph.edges) {
		childrenMap.get(e.sourceId)?.add(e.targetId);
		parentsMap.get(e.targetId)?.add(e.sourceId);
	}

	const visible = new Set<string>();
	const upstreamNodes = new Set<string>();
	const downstreamNodes = new Set<string>();
	for (const fid of focusIds) {
		visible.add(fid);
	}

	const toProcess: Array<{ id: string; direction: 'focus' | 'upstream' | 'downstream' }> = [];
	for (const fid of focusIds) {
		toProcess.push({ id: fid, direction: 'focus' });
	}
	const processed = new Set<string>();

	while (toProcess.length > 0) {
		const { id: cur, direction } = toProcess.pop()!;
		if (processed.has(cur)) continue;
		processed.add(cur);

		const isFocus = focusIds.includes(cur);
		const expandState = expandedNodes.get(cur);
		const expandUp = isFocus || (expandState?.upstream ?? false);
		const expandDown = expandState?.downstream ?? false;

		if (!expandUp && !expandDown) continue;

		if (expandUp) {
			for (const pid of parentsMap.get(cur) || []) {
				visible.add(pid);
				upstreamNodes.add(pid);
				const parentExpand = expandedNodes.get(pid);
				if (parentExpand && (parentExpand.upstream || parentExpand.downstream) && !processed.has(pid)) {
					toProcess.push({ id: pid, direction: 'upstream' });
				}
			}
		}

		if (expandDown) {
			for (const cid of childrenMap.get(cur) || []) {
				visible.add(cid);
				downstreamNodes.add(cid);
				const childExpand = expandedNodes.get(cid);
				if (childExpand && (childExpand.upstream || childExpand.downstream) && !processed.has(cid)) {
					toProcess.push({ id: cid, direction: 'downstream' });
				}
			}
		}
	}

	const hiddenCounts = new Map<string, { upstream: number; downstream: number }>();
	for (const id of visible) {
		const allParents = parentsMap.get(id) || new Set();
		const allChildren = childrenMap.get(id) || new Set();
		const hiddenUp = [...allParents].filter((p) => !visible.has(p)).length;
		const hiddenDown = [...allChildren].filter((c) => !visible.has(c)).length;

		const isFocus = focusIds.includes(id);
		const isUpstream = upstreamNodes.has(id);
		const isDownstream = downstreamNodes.has(id);

		const relevantUp = (isFocus || isUpstream) ? hiddenUp : 0;
		const relevantDown = (isFocus || isDownstream) ? hiddenDown : 0;

		if (relevantUp > 0 || relevantDown > 0) {
			hiddenCounts.set(id, { upstream: relevantUp, downstream: relevantDown });
		}
	}

	const filteredNodes = fullGraph.nodes.filter((n) => visible.has(n.uniqueId));
	const filteredEdges = fullGraph.edges.filter(
		(e) => visible.has(e.sourceId) && visible.has(e.targetId)
	);

	return { graph: { nodes: filteredNodes, edges: filteredEdges }, hiddenCounts };
}

/**
 * Extracts full subgraph reachable from a table (both directions, all depths).
 */
export function filterGraphByTable(
	graph: ILineageGraph,
	tableName: string,
	database?: string,
	schema?: string
): ILineageGraph {
	if (!tableName || !graph.nodes.length) return graph;
	const allExpanded = new Map<string, DirectionalExpand>();
	for (const n of graph.nodes) {
		allExpanded.set(n.uniqueId, { upstream: true, downstream: true });
	}
	return buildVisibleGraph(graph, tableName, allExpanded, database, schema).graph;
}

export function buildLineageFlowData(
	graphNodes: ILineageNode[],
	graphEdges: ILineageEdge[],
	focusTable?: string,
	hiddenCounts?: Map<string, { upstream: number; downstream: number }>,
	expandedNodes?: Map<string, DirectionalExpand>,
	onToggle?: (nodeId: string, direction: ExpandDirection) => void,
	previousPositions?: Map<string, { x: number; y: number }>
): { nodes: Node[]; edges: Edge[] } {
	if (!graphNodes.length) return { nodes: [], edges: [] };

	const nodeById = new Map(graphNodes.map((n) => [n.uniqueId, n]));

	const children = new Map<string, Set<string>>();
	const parents = new Map<string, Set<string>>();
	for (const n of graphNodes) {
		children.set(n.uniqueId, new Set());
		parents.set(n.uniqueId, new Set());
	}
	for (const e of graphEdges) {
		children.get(e.sourceId)?.add(e.targetId);
		parents.get(e.targetId)?.add(e.sourceId);
	}

	const layers = new Map<string, number>();
	const focusNodeId = graphNodes.find(
		(n) => focusTable && n.name.toLowerCase() === focusTable.toLowerCase()
	)?.uniqueId;

	if (focusNodeId) {
		layers.set(focusNodeId, 0);

		const upQueue: string[] = [focusNodeId];
		const upVisited = new Set<string>([focusNodeId]);
		while (upQueue.length > 0) {
			const cur = upQueue.shift()!;
			const curLayer = layers.get(cur)!;
			for (const pid of parents.get(cur) || []) {
				if (!upVisited.has(pid)) {
					upVisited.add(pid);
					layers.set(pid, curLayer - 1);
					upQueue.push(pid);
				}
			}
		}

		const downQueue: string[] = [focusNodeId];
		const downVisited = new Set<string>([focusNodeId]);
		while (downQueue.length > 0) {
			const cur = downQueue.shift()!;
			const curLayer = layers.get(cur)!;
			for (const cid of children.get(cur) || []) {
				if (!downVisited.has(cid)) {
					downVisited.add(cid);
					layers.set(cid, curLayer + 1);
					downQueue.push(cid);
				}
			}
		}
	} else {
		const queue: string[] = [];
		for (const n of graphNodes) {
			if ((parents.get(n.uniqueId)?.size || 0) === 0) {
				queue.push(n.uniqueId);
				layers.set(n.uniqueId, 0);
			}
		}
		if (queue.length === 0 && graphNodes.length > 0) {
			queue.push(graphNodes[0].uniqueId);
			layers.set(graphNodes[0].uniqueId, 0);
		}
		let head = 0;
		while (head < queue.length) {
			const current = queue[head++];
			const currentLayer = layers.get(current)!;
			for (const childId of children.get(current) || []) {
				const newLayer = currentLayer + 1;
				const existing = layers.get(childId);
				if (existing === undefined || newLayer > existing) {
					layers.set(childId, newLayer);
				}
				if (!queue.includes(childId)) {
					queue.push(childId);
				}
			}
		}
	}

	for (const n of graphNodes) {
		if (!layers.has(n.uniqueId)) layers.set(n.uniqueId, 0);
	}

	const positions = new Map<string, { x: number; y: number }>();
	const hasPrev = previousPositions && previousPositions.size > 0;

	let focusX = 0;
	if (hasPrev && focusNodeId && previousPositions!.has(focusNodeId)) {
		focusX = previousPositions!.get(focusNodeId)!.x;
	}

	if (hasPrev) {
		for (const [id, pos] of previousPositions!) {
			if (layers.has(id)) {
				positions.set(id, pos);
			}
		}
	}

	const layerGroups = new Map<number, string[]>();
	for (const [id, layer] of layers) {
		if (!layerGroups.has(layer)) layerGroups.set(layer, []);
		layerGroups.get(layer)!.push(id);
	}

	if (!hasPrev) {
		const sortedLayers = [...layerGroups.keys()].sort((a, b) => a - b);
		for (const relLayer of sortedLayers) {
			const ids = layerGroups.get(relLayer)!;
			const x = focusX + relLayer * HORIZONTAL_GAP;
			const totalHeight = ids.length * NODE_HEIGHT + (ids.length - 1) * VERTICAL_GAP;
			const startY = -totalHeight / 2;
			ids.forEach((id, i) => {
				positions.set(id, { x, y: startY + i * (NODE_HEIGHT + VERTICAL_GAP) });
			});
		}
	} else {
		for (const [relLayer, ids] of layerGroups) {
			const newIds = ids.filter((id) => !positions.has(id));
			if (newIds.length === 0) continue;

			const x = focusX + relLayer * HORIZONTAL_GAP;
			const takenYs: number[] = ids
				.filter((id) => positions.has(id))
				.map((id) => positions.get(id)!.y);

			for (const id of newIds) {
				const connectedYs: number[] = [];
				for (const pid of parents.get(id) || []) {
					if (positions.has(pid)) connectedYs.push(positions.get(pid)!.y);
				}
				for (const cid of children.get(id) || []) {
					if (positions.has(cid)) connectedYs.push(positions.get(cid)!.y);
				}

				let targetY =
					connectedYs.length > 0
						? connectedYs.reduce((a, b) => a + b, 0) / connectedYs.length
						: 0;

				while (takenYs.some((ey) => Math.abs(ey - targetY) < NODE_HEIGHT + VERTICAL_GAP)) {
					targetY += NODE_HEIGHT + VERTICAL_GAP;
				}
				takenYs.push(targetY);
				positions.set(id, { x, y: targetY });
			}
		}
	}

	const flowNodes: Node[] = graphNodes
		.filter((n) => positions.has(n.uniqueId))
		.map((n) => {
			const pos = positions.get(n.uniqueId)!;
			const parentCount = parents.get(n.uniqueId)?.size || 0;
			const childCount = children.get(n.uniqueId)?.size || 0;
			const focused = focusTable ? n.name.toLowerCase() === focusTable.toLowerCase() : false;
			const hidden = hiddenCounts?.get(n.uniqueId);
			const expandState = expandedNodes?.get(n.uniqueId);

			return {
				id: n.uniqueId,
				type: 'lineageNode',
				position: pos,
				data: {
					label: n.name,
					uniqueId: n.uniqueId,
					resourceType: n.resourceType,
					description: n.description || '',
					materialization: n.materialization,
					schema: n.schema || '',
					database: n.database || '',
					parentCount,
					childCount,
					focused,
					compiledSql: n.compiledSql || '',
					hiddenUpstream: hidden?.upstream || 0,
					hiddenDownstream: hidden?.downstream || 0,
					expandedUpstream: focused || (expandState?.upstream ?? false),
					expandedDownstream: expandState?.downstream ?? false,
					onToggle
				}
			};
		});

	const flowEdges: Edge[] = graphEdges
		.filter((e) => positions.has(e.sourceId) && positions.has(e.targetId))
		.map((e, i) => {
			const sourceNode = nodeById.get(e.sourceId);
			const color = TYPE_COLORS[sourceNode?.resourceType || 'model'] || '#6366f1';

			return {
				id: `lineage-edge-${i}`,
				source: e.sourceId,
				target: e.targetId,
				type: 'smoothstep',
				animated: false,
				style: `stroke: ${color}; stroke-width: 2; opacity: 0.6;`,
				markerEnd: {
					type: MarkerType.ArrowClosed,
					color,
					width: 16,
					height: 16
				}
			};
		});

	return { nodes: flowNodes, edges: flowEdges };
}

