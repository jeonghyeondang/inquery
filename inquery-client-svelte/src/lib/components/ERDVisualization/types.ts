/**
 * ERD Visualization Types
 */

export interface ERDForeignKey {
	constraintName: string;
	referencedSchema: string;
	referencedTable: string;
	referencedColumn: string;
	inferred?: boolean;
}

export interface ERDColumn {
	name: string;
	dataType: string;
	isPrimaryKey: boolean;
	isNullable: boolean;
	foreignKey?: ERDForeignKey;
	comment?: string;
	ordinalPosition?: number;
}

export interface ERDTable {
	name: string;
	schemaName: string;
	comment?: string;
	type?: string;
	columns: ERDColumn[];
}

export interface ERDSchema {
	name: string;
	databaseName: string;
	tables: ERDTable[];
}

export interface TableGraph {
	nodes: Set<string>;
	edges: Map<string, Set<string>>;
	edgeCounts: Map<string, number>;
}

export interface ClusterLayout {
	tables: Map<string, { x: number; y: number }>;
	bounds: { x: number; y: number; width: number; height: number };
	hub: string;
}

export const CLUSTER_COLORS = [
	'#3b82f6', '#ef4444', '#22c55e', '#f59e0b', '#8b5cf6',
	'#06b6d4', '#ec4899', '#14b8a6', '#f97316', '#6366f1'
];

export const TABLE_WIDTH = 280;
export const HORIZONTAL_SPACING = 350;
export const RADIAL_SPACING = 380;
