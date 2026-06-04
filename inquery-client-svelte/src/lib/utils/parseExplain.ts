/**
 * Parse EXPLAIN results into QueryEstimatorData
 * Ported from React: inquery-client-next/src/components/QueryEstimator/parseExplain.ts
 */

export type ResourceLevel = 'low' | 'medium' | 'high' | 'critical';

export interface QueryMetrics {
	estimatedRows?: number;
	estimatedCost?: number;
	estimatedMemoryGB?: number;
	estimatedTimeSeconds?: number;
}

export interface QueryWarning {
	type: 'info' | 'warning' | 'error';
	message: string;
	detail?: string;
}

export interface PlanNode {
	operation: string;
	details?: string;
	cost?: number;
	rows?: number;
	children?: PlanNode[];
}

export interface QueryEstimatorData {
	metrics: QueryMetrics;
	warnings: QueryWarning[];
	plan: PlanNode | null;
	rawPlan?: string;
}

type ThresholdSet = {
	rows: { medium: number; high: number; critical: number };
	cost: { medium: number; high: number; critical: number };
	memory: { medium: number; high: number; critical: number };
	time: { medium: number; high: number; critical: number };
};

// OLTP (PostgreSQL, MySQL): stricter thresholds for transactional workloads
const OLTP_THRESHOLDS: ThresholdSet = {
	rows: { medium: 100_000, high: 1_000_000, critical: 50_000_000 },
	cost: { medium: 1_000, high: 10_000, critical: 100_000 },
	memory: { medium: 0.5, high: 2, critical: 10 }, // GB
	time: { medium: 5, high: 30, critical: 120 } // seconds
};

// OLAP (Snowflake, BigQuery): relaxed thresholds for analytical workloads
const OLAP_THRESHOLDS: ThresholdSet = {
	rows: { medium: 1_000_000, high: 50_000_000, critical: 500_000_000 },
	cost: { medium: 1, high: 10, critical: 50 }, // USD
	memory: { medium: 5, high: 50, critical: 200 }, // GB
	time: { medium: 15, high: 60, critical: 300 } // seconds
};

function getThresholds(databaseType?: string): ThresholdSet {
	const dbType = (databaseType || '').toLowerCase();
	if (dbType.includes('snowflake') || dbType.includes('bigquery')) {
		return OLAP_THRESHOLDS;
	}
	return OLTP_THRESHOLDS;
}

export function calculateResourceLevel(metrics: QueryMetrics, databaseType?: string): ResourceLevel {
	const { estimatedRows, estimatedCost, estimatedMemoryGB, estimatedTimeSeconds } = metrics;
	const t = getThresholds(databaseType);

	if (
		(estimatedRows && estimatedRows >= t.rows.critical) ||
		(estimatedCost && estimatedCost >= t.cost.critical) ||
		(estimatedMemoryGB && estimatedMemoryGB >= t.memory.critical) ||
		(estimatedTimeSeconds && estimatedTimeSeconds >= t.time.critical)
	) {
		return 'critical';
	}
	if (
		(estimatedRows && estimatedRows >= t.rows.high) ||
		(estimatedCost && estimatedCost >= t.cost.high) ||
		(estimatedMemoryGB && estimatedMemoryGB >= t.memory.high) ||
		(estimatedTimeSeconds && estimatedTimeSeconds >= t.time.high)
	) {
		return 'high';
	}
	if (
		(estimatedRows && estimatedRows >= t.rows.medium) ||
		(estimatedCost && estimatedCost >= t.cost.medium) ||
		(estimatedMemoryGB && estimatedMemoryGB >= t.memory.medium) ||
		(estimatedTimeSeconds && estimatedTimeSeconds >= t.time.medium)
	) {
		return 'medium';
	}
	return 'low';
}

export const levelConfig: Record<
	ResourceLevel,
	{
		color: string;
		bgColor: string;
		bgLight: string;
		borderColor: string;
		label: string;
		description: string;
	}
> = {
	low: {
		color: 'text-green-500',
		bgColor: 'bg-green-500',
		bgLight: 'bg-green-500/10',
		borderColor: 'border-green-500/30',
		label: 'Low Impact',
		description: 'Query should execute quickly with minimal resources'
	},
	medium: {
		color: 'text-yellow-500',
		bgColor: 'bg-yellow-500',
		bgLight: 'bg-yellow-500/10',
		borderColor: 'border-yellow-500/30',
		label: 'Medium Impact',
		description: 'Query may require moderate resources'
	},
	high: {
		color: 'text-orange-500',
		bgColor: 'bg-orange-500',
		bgLight: 'bg-orange-500/10',
		borderColor: 'border-orange-500/30',
		label: 'High Impact',
		description: 'Query will likely consume significant resources'
	},
	critical: {
		color: 'text-red-500',
		bgColor: 'bg-red-500',
		bgLight: 'bg-red-500/10',
		borderColor: 'border-red-500/30',
		label: 'Critical',
		description: 'Query may cause performance issues or timeouts'
	}
};

// Format large numbers
export function formatNumber(num: number | undefined): string {
	if (num === undefined || num === null) return '-';
	if (num >= 1_000_000_000) return `${(num / 1_000_000_000).toFixed(1)}B`;
	if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`;
	if (num >= 1_000) return `${(num / 1_000).toFixed(1)}K`;
	return num.toLocaleString();
}

// Format time
export function formatTime(seconds: number | undefined): string {
	if (seconds === undefined || seconds === null) return '-';
	if (seconds < 1) return `${(seconds * 1000).toFixed(0)}ms`;
	if (seconds < 60) return `${seconds.toFixed(1)}s`;
	if (seconds < 3600) return `${(seconds / 60).toFixed(1)}m`;
	return `${(seconds / 3600).toFixed(1)}h`;
}

// Snowflake warehouse size configuration
const SNOWFLAKE_WAREHOUSE_CONFIG: Record<string, { creditsPerHour: number; throughputMBps: number }> = {
	'x-small': { creditsPerHour: 1, throughputMBps: 50 },
	xsmall: { creditsPerHour: 1, throughputMBps: 50 },
	xs: { creditsPerHour: 1, throughputMBps: 50 },
	small: { creditsPerHour: 2, throughputMBps: 100 },
	s: { creditsPerHour: 2, throughputMBps: 100 },
	medium: { creditsPerHour: 4, throughputMBps: 200 },
	m: { creditsPerHour: 4, throughputMBps: 200 },
	large: { creditsPerHour: 8, throughputMBps: 400 },
	l: { creditsPerHour: 8, throughputMBps: 400 },
	'x-large': { creditsPerHour: 16, throughputMBps: 800 },
	xlarge: { creditsPerHour: 16, throughputMBps: 800 },
	xl: { creditsPerHour: 16, throughputMBps: 800 },
	'2x-large': { creditsPerHour: 32, throughputMBps: 1600 },
	'2xlarge': { creditsPerHour: 32, throughputMBps: 1600 },
	'2xl': { creditsPerHour: 32, throughputMBps: 1600 },
	'3x-large': { creditsPerHour: 64, throughputMBps: 3200 },
	'3xlarge': { creditsPerHour: 64, throughputMBps: 3200 },
	'4x-large': { creditsPerHour: 128, throughputMBps: 6400 },
	'4xlarge': { creditsPerHour: 128, throughputMBps: 6400 }
};

const SNOWFLAKE_CREDIT_PRICE_USD = 3.0;

// Parse Snowflake EXPLAIN JSON
function parseSnowflakeExplain(json: any, warehouseSize?: string): QueryEstimatorData {
	const metrics: QueryMetrics = {};
	const warnings: QueryWarning[] = [];
	let plan: PlanNode | null = null;

	const whSize = (warehouseSize || 'medium').toLowerCase().replace(/_/g, '-');
	const whConfig = SNOWFLAKE_WAREHOUSE_CONFIG[whSize] || SNOWFLAKE_WAREHOUSE_CONFIG['medium'];

	try {
		if (json.GlobalStats) {
			const stats = json.GlobalStats;
			if (stats.partitionsTotal) {
				metrics.estimatedRows = stats.partitionsTotal * 1000;
			}
			if (stats.bytesAssigned) {
				const bytesGB = stats.bytesAssigned / (1024 * 1024 * 1024);
				metrics.estimatedMemoryGB = bytesGB;
				const timeSeconds = stats.bytesAssigned / (whConfig.throughputMBps * 1024 * 1024);
				metrics.estimatedTimeSeconds = timeSeconds;
				const billedSeconds = Math.max(timeSeconds, 60);
				const creditsUsed = (billedSeconds / 3600) * whConfig.creditsPerHour;
				metrics.estimatedCost = creditsUsed * SNOWFLAKE_CREDIT_PRICE_USD;
			}
		}

		if (json.Operations && Array.isArray(json.Operations)) {
			const ops = Array.isArray(json.Operations[0]) ? json.Operations[0] : json.Operations;
			const nodeMap = new Map<number, PlanNode>();

			ops.forEach((op: any) => {
				const opName = op.operation || 'Unknown';
				const node: PlanNode = {
					operation: opName,
					details: op.objects
						? op.objects.join(', ')
						: op.expressions
							? op.expressions[0]?.substring(0, 80)
							: undefined,
					rows: op.partitionsAssigned ? op.partitionsAssigned * 1000 : undefined,
					cost: op.bytesAssigned ? Math.round(op.bytesAssigned / (1024 * 1024)) : undefined,
					children: []
				};
				nodeMap.set(op.id, node);

				if (opName.includes('Scan') && !opName.includes('Index')) {
					const tableName = op.objects?.join(', ') || 'table';
					warnings.push({
						type: 'warning',
						message: `Full scan on ${tableName}`,
						detail: `Partitions: ${op.partitionsAssigned || 'N/A'} / ${op.partitionsTotal || 'N/A'}`
					});
				}
				if (opName.includes('Cartesian') || opName.includes('CrossJoin')) {
					warnings.push({
						type: 'error',
						message: 'Cartesian product detected',
						detail: 'Cross join may produce very large result set'
					});
				}
			});

			ops.forEach((op: any) => {
				const currentNode = nodeMap.get(op.id);
				if (currentNode && op.parentOperators) {
					op.parentOperators.forEach((parentId: number) => {
						const parentNode = nodeMap.get(parentId);
						if (parentNode) {
							parentNode.children = parentNode.children || [];
							parentNode.children.push(currentNode);
						}
					});
				}
			});

			const rootNode = nodeMap.get(0);
			if (rootNode) plan = rootNode;
		}
	} catch (e) {
		console.error('Failed to parse Snowflake EXPLAIN:', e);
	}

	return { metrics, warnings, plan, rawPlan: JSON.stringify(json, null, 2) };
}

// Parse PostgreSQL EXPLAIN JSON
function parsePostgresExplain(json: any): QueryEstimatorData {
	const metrics: QueryMetrics = {};
	const warnings: QueryWarning[] = [];
	let plan: PlanNode | null = null;

	try {
		const planData = Array.isArray(json) ? json[0] : json;
		const planRoot = planData.Plan || planData;

		if (planRoot) {
			metrics.estimatedRows = planRoot['Plan Rows'] || planRoot.rows;
			metrics.estimatedCost = planRoot['Total Cost'] || planRoot.cost;
			if (metrics.estimatedCost) {
				metrics.estimatedTimeSeconds = metrics.estimatedCost / 1000;
			}
			if (planRoot['Shared Hit Blocks'] || planRoot['Shared Read Blocks']) {
				const blocks = (planRoot['Shared Hit Blocks'] || 0) + (planRoot['Shared Read Blocks'] || 0);
				metrics.estimatedMemoryGB = (blocks * 8192) / (1024 * 1024 * 1024);
			}
		}

		const buildNode = (node: any): PlanNode => {
			const planNode: PlanNode = {
				operation: node['Node Type'] || node.type || 'Unknown',
				details: node['Relation Name'] || node['Index Name'] || node['Filter'],
				cost: node['Total Cost'] || node.cost,
				rows: node['Plan Rows'] || node.rows,
				children: []
			};
			const nodeType = (node['Node Type'] || '').toLowerCase();
			if (nodeType.includes('seq scan')) {
				warnings.push({
					type: 'warning',
					message: 'Sequential scan detected',
					detail: `Seq Scan on ${node['Relation Name'] || 'table'} - consider adding an index`
				});
			}
			if (nodeType.includes('nested loop') && node['Join Type'] === 'Full') {
				warnings.push({
					type: 'error',
					message: 'Nested loop with full join',
					detail: 'May be inefficient for large tables'
				});
			}
			if (node['Plans'] && Array.isArray(node['Plans'])) {
				planNode.children = node['Plans'].map(buildNode);
			}
			return planNode;
		};

		if (planRoot) plan = buildNode(planRoot);
	} catch (e) {
		console.error('Failed to parse PostgreSQL EXPLAIN:', e);
	}

	return { metrics, warnings, plan, rawPlan: JSON.stringify(json, null, 2) };
}

// Parse MySQL EXPLAIN JSON
function parseMySQLExplain(json: any): QueryEstimatorData {
	const metrics: QueryMetrics = {};
	const warnings: QueryWarning[] = [];
	let plan: PlanNode | null = null;

	try {
		const queryBlock = json.query_block;
		if (queryBlock) {
			if (queryBlock.cost_info) {
				metrics.estimatedCost = parseFloat(queryBlock.cost_info.query_cost) || 0;
			}
			const buildNode = (item: any): PlanNode => {
				const table = item.table || item;
				const node: PlanNode = {
					operation: table.access_type || 'scan',
					details: table.table_name,
					cost: parseFloat(table.cost_info?.read_cost) || 0,
					rows: table.rows_examined_per_scan || table.rows,
					children: []
				};
				if (table.access_type === 'ALL') {
					warnings.push({
						type: 'warning',
						message: 'Full table scan',
						detail: `Full scan on ${table.table_name}`
					});
				}
				if (table.possible_keys === null && table.key === null) {
					warnings.push({
						type: 'info',
						message: 'No index used',
						detail: `Query on ${table.table_name} doesn't use any index`
					});
				}
				return node;
			};

			if (queryBlock.nested_loop) {
				plan = {
					operation: 'Nested Loop Join',
					children: queryBlock.nested_loop.map(buildNode)
				};
				metrics.estimatedRows = queryBlock.nested_loop.reduce(
					(acc: number, item: any) => acc * (item.table?.rows || 1),
					1
				);
			} else if (queryBlock.table) {
				plan = buildNode(queryBlock);
				metrics.estimatedRows = queryBlock.table.rows;
			}
		}
	} catch (e) {
		console.error('Failed to parse MySQL EXPLAIN:', e);
	}

	return { metrics, warnings, plan, rawPlan: JSON.stringify(json, null, 2) };
}

// Parse BigQuery EXPLAIN (limited info)
function parseBigQueryExplain(json: any): QueryEstimatorData {
	const metrics: QueryMetrics = {};
	const warnings: QueryWarning[] = [];

	try {
		if (json.stages) {
			const totalRows = json.stages.reduce((acc: number, s: any) => acc + (s.recordsWritten || 0), 0);
			metrics.estimatedRows = totalRows;
		}
		if (json.totalBytesBilled) {
			metrics.estimatedMemoryGB = json.totalBytesBilled / (1024 * 1024 * 1024);
		}
	} catch (e) {
		console.error('Failed to parse BigQuery EXPLAIN:', e);
	}

	return { metrics, warnings, plan: null, rawPlan: JSON.stringify(json, null, 2) };
}

export interface ParseExplainOptions {
	warehouseSize?: string;
}

// Main parser function
export function parseExplainResult(
	json: any,
	databaseType: string,
	options?: ParseExplainOptions
): QueryEstimatorData {
	const dbType = (databaseType || '').toLowerCase();

	if (dbType.includes('snowflake')) return parseSnowflakeExplain(json, options?.warehouseSize);
	if (dbType.includes('postgres')) return parsePostgresExplain(json);
	if (dbType.includes('mysql')) return parseMySQLExplain(json);
	if (dbType.includes('bigquery')) return parseBigQueryExplain(json);

	return {
		metrics: {},
		warnings: [],
		plan: null,
		rawPlan: typeof json === 'string' ? json : JSON.stringify(json, null, 2)
	};
}

// Parse raw text EXPLAIN (non-JSON format)
export function parseRawExplain(text: string): QueryEstimatorData {
	const warnings: QueryWarning[] = [];
	const metrics: QueryMetrics = {};

	const lines = text.split('\n');
	lines.forEach((line) => {
		const lowerLine = line.toLowerCase();
		if (lowerLine.includes('seq scan') || lowerLine.includes('table scan') || lowerLine.includes('full scan')) {
			warnings.push({ type: 'warning', message: 'Table scan detected', detail: line.trim() });
		}
		if (lowerLine.includes('cartesian') || lowerLine.includes('cross join')) {
			warnings.push({ type: 'error', message: 'Cartesian product detected', detail: line.trim() });
		}
		const rowMatch = line.match(/rows[=:]\s*(\d+)/i);
		if (rowMatch && !metrics.estimatedRows) {
			metrics.estimatedRows = parseInt(rowMatch[1], 10);
		}
		const costMatch = line.match(/cost[=:]\s*([\d.]+)/i);
		if (costMatch && !metrics.estimatedCost) {
			metrics.estimatedCost = parseFloat(costMatch[1]);
		}
	});

	return { metrics, warnings, plan: null, rawPlan: text };
}
