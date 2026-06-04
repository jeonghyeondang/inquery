/**
 * Graph algorithms and Svelte Flow data builder for ERD layout.
 * Ported from data-peek/apps/desktop/src/renderer/src/components/erd-visualization.tsx
 */
import type { Node, Edge } from '@xyflow/svelte';
import { MarkerType } from '@xyflow/svelte';
import type { ERDTable, TableGraph, ClusterLayout } from './types';
import { TABLE_WIDTH, CLUSTER_COLORS } from './types';

export function getTableKey(schemaName: string | undefined, tableName: string): string {
	return `${schemaName || ''}.${tableName}`;
}

export function calculateTableHeight(table: ERDTable): number {
	return 70 + Math.min(table.columns.length, 12) * 28;
}

// ---------------------------------------------------------------------------
// Graph construction
// ---------------------------------------------------------------------------

export function buildRelationshipGraph(tables: ERDTable[]): TableGraph {
	const nodes = new Set<string>();
	const edges = new Map<string, Set<string>>();
	const edgeCounts = new Map<string, number>();

	for (const table of tables) {
		const key = getTableKey(table.schemaName, table.name);
		nodes.add(key);
		edges.set(key, new Set());
		edgeCounts.set(key, 0);
	}

	const tableByKey = new Map(
		tables.map((t) => [getTableKey(t.schemaName, t.name).toLowerCase(), t])
	);
	const tableKeysByName = new Map<string, string[]>();
	for (const table of tables) {
		const key = getTableKey(table.schemaName, table.name);
		const nameKey = table.name.toLowerCase();
		if (!tableKeysByName.has(nameKey)) tableKeysByName.set(nameKey, []);
		tableKeysByName.get(nameKey)!.push(key);
	}

	for (const table of tables) {
		const sourceKey = getTableKey(table.schemaName, table.name);

		for (const col of table.columns) {
			if (!col.foreignKey) continue;

			let targetKey = '';
			const fkSchema = (col.foreignKey.referencedSchema || table.schemaName || '').toLowerCase();
			const fkTable = col.foreignKey.referencedTable.toLowerCase();
			const fullyQualified = `${fkSchema}.${fkTable}`;

			if (tableByKey.has(fullyQualified)) {
				targetKey = getTableKey(
					col.foreignKey.referencedSchema || table.schemaName,
					col.foreignKey.referencedTable
				);
			} else {
				const candidates = tableKeysByName.get(fkTable) || [];
				if (candidates.length === 1) {
					targetKey = candidates[0];
				} else if (candidates.length > 1) {
					const sameSchema = candidates.find((k) =>
						k.toLowerCase().startsWith(`${(table.schemaName || '').toLowerCase()}.`)
					);
					if (sameSchema) targetKey = sameSchema;
				}
			}

			if (!targetKey || !nodes.has(targetKey)) continue;

			edges.get(sourceKey)?.add(targetKey);
			edges.get(targetKey)?.add(sourceKey);
			edgeCounts.set(sourceKey, (edgeCounts.get(sourceKey) || 0) + 1);
			edgeCounts.set(targetKey, (edgeCounts.get(targetKey) || 0) + 1);
		}
	}

	return { nodes, edges, edgeCounts };
}

// ---------------------------------------------------------------------------
// Connected components
// ---------------------------------------------------------------------------

export function findConnectedComponents(graph: TableGraph): string[][] {
	const visited = new Set<string>();
	const components: string[][] = [];

	function dfs(node: string, component: string[]) {
		if (visited.has(node)) return;
		visited.add(node);
		component.push(node);
		graph.edges.get(node)?.forEach((neighbor) => dfs(neighbor, component));
	}

	for (const node of graph.nodes) {
		if (!visited.has(node)) {
			const component: string[] = [];
			dfs(node, component);
			components.push(component);
		}
	}

	return components.sort((a, b) => {
		const aEdges = a.reduce((sum, n) => sum + (graph.edgeCounts.get(n) || 0), 0);
		const bEdges = b.reduce((sum, n) => sum + (graph.edgeCounts.get(n) || 0), 0);
		if (a.length !== b.length) return b.length - a.length;
		return bEdges - aEdges;
	});
}

// ---------------------------------------------------------------------------
// Hub detection
// ---------------------------------------------------------------------------

function findHubTable(component: string[], edgeCounts: Map<string, number>): string {
	return component.reduce((hub, table) => {
		return (edgeCounts.get(table) || 0) > (edgeCounts.get(hub) || 0) ? table : hub;
	}, component[0]);
}

// ---------------------------------------------------------------------------
// Cluster layout (matches data-peek reference)
// ---------------------------------------------------------------------------

const TABLE_MIN_HEIGHT = 200;
const HORIZONTAL_SPACING = 350;
const VERTICAL_SPACING = 120;
const RADIAL_SPACING = 380;

function layoutCluster(
	component: string[],
	graph: TableGraph,
	tableHeights: Map<string, number>
): ClusterLayout {
	const positions = new Map<string, { x: number; y: number }>();
	const hub = findHubTable(component, graph.edgeCounts);

	if (component.length === 1) {
		positions.set(component[0], { x: 0, y: 0 });
		const height = tableHeights.get(component[0]) || TABLE_MIN_HEIGHT;
		return {
			tables: positions,
			bounds: { x: -20, y: -20, width: TABLE_WIDTH + 40, height: height + 60 },
			hub
		};
	}

	if (component.length === 2) {
		const [t1, t2] = component;
		positions.set(t1, { x: 0, y: 0 });
		positions.set(t2, { x: HORIZONTAL_SPACING, y: 0 });
		const h1 = tableHeights.get(t1) || TABLE_MIN_HEIGHT;
		const h2 = tableHeights.get(t2) || TABLE_MIN_HEIGHT;
		return {
			tables: positions,
			bounds: {
				x: -40,
				y: -40,
				width: HORIZONTAL_SPACING + TABLE_WIDTH + 80,
				height: Math.max(h1, h2) + 80
			},
			hub
		};
	}

	const hubHeight = tableHeights.get(hub) || TABLE_MIN_HEIGHT;
	positions.set(hub, { x: 0, y: 0 });

	const directlyConnected = [...(graph.edges.get(hub) || [])].filter((t) =>
		component.includes(t)
	);
	const otherTables = component.filter((t) => t !== hub && !directlyConnected.includes(t));

	if (directlyConnected.length <= 4) {
		const hubCenterY = hubHeight / 2;
		const cardinalPositions = [
			{ x: 0, y: -RADIAL_SPACING - hubHeight / 2 },
			{ x: RADIAL_SPACING + TABLE_WIDTH / 2, y: -hubCenterY + 50 },
			{ x: 0, y: RADIAL_SPACING },
			{ x: -RADIAL_SPACING - TABLE_WIDTH / 2, y: -hubCenterY + 50 }
		];
		directlyConnected.forEach((table, i) => {
			positions.set(table, cardinalPositions[i]);
		});
	} else {
		const angleStep = (2 * Math.PI) / directlyConnected.length;
		directlyConnected.forEach((table, i) => {
			const angle = i * angleStep - Math.PI / 2;
			const tableHeight = tableHeights.get(table) || TABLE_MIN_HEIGHT;
			const adjustedRadius = RADIAL_SPACING + tableHeight / 4;
			positions.set(table, {
				x: Math.cos(angle) * adjustedRadius,
				y: Math.sin(angle) * adjustedRadius
			});
		});
	}

	if (otherTables.length > 0) {
		const outerRadius = RADIAL_SPACING * 2;
		const angleStep = (2 * Math.PI) / otherTables.length;
		otherTables.forEach((table, i) => {
			const angle = i * angleStep + Math.PI / (otherTables.length * 2);
			positions.set(table, {
				x: Math.cos(angle) * outerRadius,
				y: Math.sin(angle) * outerRadius
			});
		});
	}

	let minX = Infinity,
		maxX = -Infinity,
		minY = Infinity,
		maxY = -Infinity;
	for (const [tKey, pos] of positions) {
		const h = tableHeights.get(tKey) || TABLE_MIN_HEIGHT;
		minX = Math.min(minX, pos.x);
		maxX = Math.max(maxX, pos.x + TABLE_WIDTH);
		minY = Math.min(minY, pos.y);
		maxY = Math.max(maxY, pos.y + h);
	}

	const padding = 80;
	return {
		tables: positions,
		bounds: {
			x: minX - padding,
			y: minY - padding,
			width: maxX - minX + TABLE_WIDTH + padding * 2,
			height: maxY - minY + padding * 2 + VERTICAL_SPACING
		},
		hub
	};
}

// ---------------------------------------------------------------------------
// Main: build Svelte Flow nodes + edges (matches data-peek reference exactly)
// ---------------------------------------------------------------------------

export function buildFlowData(tables: ERDTable[]): { nodes: Node[]; edges: Edge[] } {
	if (!tables.length) return { nodes: [], edges: [] };

	const graph = buildRelationshipGraph(tables);

	const tableHeights = new Map<string, number>();
	const tableDataMap = new Map<string, ERDTable>();
	for (const table of tables) {
		const key = getTableKey(table.schemaName, table.name);
		tableHeights.set(key, calculateTableHeight(table));
		tableDataMap.set(key, table);
	}

	const components = findConnectedComponents(graph);

	const clusterLayouts: { layout: ClusterLayout; component: string[]; colorIndex: number }[] = [];
	components.forEach((component, index) => {
		const layout = layoutCluster(component, graph, tableHeights);
		clusterLayouts.push({ layout, component, colorIndex: index % CLUSTER_COLORS.length });
	});

	// Position clusters in a grid
	const CLUSTER_GAP = 200;
	const MAX_CLUSTERS_PER_ROW = 3;
	let currentX = 0;
	let currentY = 0;
	let rowMaxHeight = 0;
	let clustersInRow = 0;
	const clusterOffsets = new Map<number, { x: number; y: number }>();

	clusterLayouts.forEach((cluster, index) => {
		const { bounds } = cluster.layout;
		if (clustersInRow >= MAX_CLUSTERS_PER_ROW) {
			currentX = 0;
			currentY += rowMaxHeight + CLUSTER_GAP;
			rowMaxHeight = 0;
			clustersInRow = 0;
		}
		clusterOffsets.set(index, { x: currentX - bounds.x, y: currentY - bounds.y });
		currentX += bounds.width + CLUSTER_GAP;
		rowMaxHeight = Math.max(rowMaxHeight, bounds.height);
		clustersInRow++;
	});

	const nodes: Node[] = [];
	const edges: Edge[] = [];

	// Build nodes (cluster backgrounds + table nodes)
	clusterLayouts.forEach((cluster, clusterIndex) => {
		const { layout, component } = cluster;
		const color = CLUSTER_COLORS[cluster.colorIndex];
		const offset = clusterOffsets.get(clusterIndex)!;

		// Cluster background node for multi-table clusters
		if (component.length > 1) {
			const b = layout.bounds;
			nodes.push({
				id: `cluster-${clusterIndex}`,
				type: 'clusterNode',
				position: { x: b.x + offset.x, y: b.y + offset.y },
				data: {
					label: `Cluster ${clusterIndex + 1}`,
					tableCount: component.length,
					color
				},
				style: `width: ${b.width}px; height: ${b.height}px; z-index: -1;`,
				selectable: false,
				draggable: false
			});
		}

		// Table nodes
		for (const tableKey of component) {
			const pos = layout.tables.get(tableKey)!;
			const table = tableDataMap.get(tableKey)!;
			const relationshipCount = graph.edgeCounts.get(tableKey) || 0;
			const isHub = tableKey === layout.hub && component.length > 1;

			nodes.push({
				id: tableKey,
				type: 'tableNode',
				position: { x: pos.x + offset.x, y: pos.y + offset.y },
				data: {
					label: table.name,
					schemaName: table.schemaName,
					columns: table.columns,
					isHub,
					clusterColor: color,
					relationshipCount
				},
				zIndex: 1
			});
		}
	});

	// Build a position+size map for all table nodes (for edge label collision avoidance)
	const nodeRects = new Map<string, { x: number; y: number; w: number; h: number }>();
	for (const n of nodes) {
		if (n.type === 'tableNode') {
			const h = tableHeights.get(n.id) || TABLE_MIN_HEIGHT;
			nodeRects.set(n.id, { x: n.position.x, y: n.position.y, w: TABLE_WIDTH, h });
		}
	}

	// Build edges for foreign keys
	const tableKeysByName = new Map<string, string[]>();
	for (const table of tables) {
		const key = getTableKey(table.schemaName, table.name);
		const nameKey = table.name.toLowerCase();
		if (!tableKeysByName.has(nameKey)) tableKeysByName.set(nameKey, []);
		tableKeysByName.get(nameKey)!.push(key);
	}

	for (const table of tables) {
		const sourceKey = getTableKey(table.schemaName, table.name);
		for (const col of table.columns) {
			if (!col.foreignKey) continue;

			let targetKey = '';
			const fkSchema = col.foreignKey.referencedSchema || table.schemaName || '';
			const exactKey = getTableKey(fkSchema, col.foreignKey.referencedTable);

			if (tableDataMap.has(exactKey)) {
				targetKey = exactKey;
			} else {
				const candidates =
					tableKeysByName.get(col.foreignKey.referencedTable.toLowerCase()) || [];
				if (candidates.length === 1) {
					targetKey = candidates[0];
				} else if (candidates.length > 1) {
					const sameSchema = candidates.find((k) =>
						k.startsWith(`${table.schemaName || ''}.`)
					);
					if (sameSchema) targetKey = sameSchema;
				}
			}

			if (!targetKey || !tableDataMap.has(targetKey)) continue;

			const clusterIndex = clusterLayouts.findIndex((c) => c.component.includes(sourceKey));
			const edgeColor =
				clusterIndex >= 0 ? CLUSTER_COLORS[clusterIndex % CLUSTER_COLORS.length] : '#6366f1';

			const isInferred = col.foreignKey.inferred || false;
			const constraintName = col.foreignKey.constraintName;

			edges.push({
				id: `${sourceKey}.${col.name}->${targetKey}.${col.foreignKey.referencedColumn}`,
				source: sourceKey,
				sourceHandle: `${col.name}-source`,
				target: targetKey,
				targetHandle: `${col.foreignKey.referencedColumn}-target`,
				type: 'erdEdge',
				animated: isInferred,
				label: constraintName || undefined,
				data: { nodeRects: Object.fromEntries(nodeRects) },
				style: `stroke: ${edgeColor}; stroke-width: 2; opacity: 0.7;${isInferred ? ' stroke-dasharray: 6 3;' : ''}`,
				markerEnd: {
					type: MarkerType.ArrowClosed,
					color: edgeColor,
					width: 20,
					height: 20
				}
			});
		}
	}

	return { nodes, edges };
}
