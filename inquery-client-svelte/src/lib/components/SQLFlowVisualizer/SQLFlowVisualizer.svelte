<script lang="ts">
	import { ArrowDown, Table2, Filter, Columns3, GitMerge, ArrowUpDown, Hash, Layers, BarChart3 } from 'lucide-svelte';

	interface Props {
		sql: string;
	}

	let { sql }: Props = $props();

	interface FlowNode {
		id: string;
		type: 'table' | 'cte' | 'join' | 'filter' | 'group' | 'aggregate' | 'select' | 'order' | 'union' | 'result';
		label: string;
		detail?: string;
		color: string;
	}

	const nodeConfig: Record<string, { color: string; icon: any }> = {
		table: { color: '#22c55e', icon: Table2 },
		cte: { color: '#f97316', icon: Layers },
		join: { color: '#3b82f6', icon: GitMerge },
		filter: { color: '#a855f7', icon: Filter },
		group: { color: '#06b6d4', icon: Hash },
		aggregate: { color: '#8b5cf6', icon: BarChart3 },
		select: { color: '#ec4899', icon: Columns3 },
		order: { color: '#eab308', icon: ArrowUpDown },
		union: { color: '#ef4444', icon: Layers },
		result: { color: '#6366f1', icon: ArrowDown }
	};

	let nodes = $derived(parseSQLToFlow(sql));

	function parseSQLToFlow(rawSql: string): FlowNode[] {
		if (!rawSql?.trim()) return [];

		const result: FlowNode[] = [];
		const upper = rawSql.toUpperCase();
		let id = 0;

		// CTEs
		const cteMatch = rawSql.match(/WITH\s+([\s\S]*?)(?=\bSELECT\b(?![\s\S]*\bAS\s*\())/i);
		if (cteMatch) {
			const cteNames = cteMatch[1].match(/(\w+)\s+AS\s*\(/gi);
			if (cteNames) {
				cteNames.forEach(c => {
					const name = c.replace(/\s+AS\s*\(/i, '').trim();
					result.push({ id: `cte-${id++}`, type: 'cte', label: `CTE: ${name}`, color: nodeConfig.cte.color });
				});
			}
		}

		// FROM tables
		const fromMatch = rawSql.match(/FROM\s+([\s\S]*?)(?=WHERE|GROUP|ORDER|HAVING|LIMIT|UNION|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|FULL|$)/i);
		if (fromMatch) {
			const tables = fromMatch[1].split(',').map(t => t.trim().split(/\s+/)[0]).filter(Boolean);
			tables.forEach(t => {
				if (t && !['(', 'SELECT'].includes(t.toUpperCase())) {
					result.push({ id: `table-${id++}`, type: 'table', label: t.replace(/[`"[\]]/g, ''), color: nodeConfig.table.color });
				}
			});
		}

		// JOINs
		const joinRegex = /(LEFT|RIGHT|INNER|OUTER|CROSS|FULL)?\s*JOIN\s+(\w+(?:\.\w+)*)\s+(?:AS\s+)?(\w+)?\s*(?:ON\s+([\s\S]*?))?(?=(?:LEFT|RIGHT|INNER|OUTER|CROSS|FULL)?\s*JOIN|WHERE|GROUP|ORDER|HAVING|LIMIT|UNION|$)/gi;
		let joinMatch;
		while ((joinMatch = joinRegex.exec(rawSql)) !== null) {
			const joinType = (joinMatch[1] || 'INNER').toUpperCase();
			const table = joinMatch[2].replace(/[`"[\]]/g, '');
			const condition = joinMatch[4]?.trim()?.substring(0, 60) || '';
			result.push({
				id: `join-${id++}`,
				type: 'join',
				label: `${joinType} JOIN ${table}`,
				detail: condition ? `ON ${condition}` : undefined,
				color: nodeConfig.join.color
			});
		}

		// WHERE
		const whereMatch = rawSql.match(/WHERE\s+([\s\S]*?)(?=GROUP|ORDER|HAVING|LIMIT|UNION|$)/i);
		if (whereMatch) {
			const cond = whereMatch[1].trim().substring(0, 80);
			result.push({ id: `filter-${id++}`, type: 'filter', label: 'WHERE', detail: cond, color: nodeConfig.filter.color });
		}

		// GROUP BY
		const groupMatch = rawSql.match(/GROUP\s+BY\s+([\s\S]*?)(?=HAVING|ORDER|LIMIT|UNION|$)/i);
		if (groupMatch) {
			result.push({ id: `group-${id++}`, type: 'group', label: 'GROUP BY', detail: groupMatch[1].trim().substring(0, 60), color: nodeConfig.group.color });
		}

		// HAVING
		const havingMatch = rawSql.match(/HAVING\s+([\s\S]*?)(?=ORDER|LIMIT|UNION|$)/i);
		if (havingMatch) {
			result.push({ id: `filter-${id++}`, type: 'filter', label: 'HAVING', detail: havingMatch[1].trim().substring(0, 60), color: nodeConfig.filter.color });
		}

		// Aggregates in SELECT
		const selectMatch = rawSql.match(/SELECT\s+([\s\S]*?)(?=FROM)/i);
		if (selectMatch) {
			const aggRegex = /(COUNT|SUM|AVG|MIN|MAX)\s*\(/gi;
			const aggs: string[] = [];
			let aggMatch;
			while ((aggMatch = aggRegex.exec(selectMatch[1])) !== null) {
				aggs.push(aggMatch[1].toUpperCase());
			}
			if (aggs.length > 0) {
				result.push({ id: `agg-${id++}`, type: 'aggregate', label: 'Aggregates', detail: [...new Set(aggs)].join(', '), color: nodeConfig.aggregate.color });
			}

			// SELECT columns
			const cols = selectMatch[1].trim().substring(0, 80);
			result.push({ id: `select-${id++}`, type: 'select', label: 'SELECT', detail: cols, color: nodeConfig.select.color });
		}

		// ORDER BY
		const orderMatch = rawSql.match(/ORDER\s+BY\s+([\s\S]*?)(?=LIMIT|UNION|$)/i);
		if (orderMatch) {
			result.push({ id: `order-${id++}`, type: 'order', label: 'ORDER BY', detail: orderMatch[1].trim().substring(0, 60), color: nodeConfig.order.color });
		}

		// UNION
		if (upper.includes('UNION')) {
			result.push({ id: `union-${id++}`, type: 'union', label: upper.includes('UNION ALL') ? 'UNION ALL' : 'UNION', color: nodeConfig.union.color });
		}

		// Result
		if (result.length > 0) {
			result.push({ id: `result-${id++}`, type: 'result', label: 'Result', color: nodeConfig.result.color });
		}

		return result;
	}
</script>

<div class="h-full w-full overflow-auto p-4 bg-background">
	{#if nodes.length === 0}
		<div class="flex items-center justify-center h-full text-sm text-muted-foreground">
			Write a SQL query to see the flow visualization
		</div>
	{:else}
		<!-- Legend -->
		<div class="flex flex-wrap gap-3 mb-4 px-2">
			{#each Object.entries(nodeConfig) as [type, config]}
				<div class="flex items-center gap-1.5 text-[10px] text-muted-foreground">
					<div class="w-2.5 h-2.5 rounded-sm" style="background: {config.color}"></div>
					<span class="capitalize">{type}</span>
				</div>
			{/each}
		</div>

		<!-- Flow -->
		<div class="flex flex-col items-center gap-0">
			{#each nodes as node, i}
				{@const config = nodeConfig[node.type]}
				{@const Icon = config.icon}
				<div class="flex flex-col items-center">
					{#if i > 0}
						<div class="w-px h-6 bg-border"></div>
						<ArrowDown size={12} class="text-muted-foreground -my-1" />
						<div class="w-px h-2 bg-border"></div>
					{/if}

					<div
						class="flex items-center gap-2 px-4 py-2 rounded-lg border-2 min-w-[200px] max-w-[400px] bg-card shadow-sm"
						style="border-color: {node.color}"
					>
						<div class="w-7 h-7 rounded-md flex items-center justify-center shrink-0" style="background: {node.color}20">
							<Icon size={14} style="color: {node.color}" />
						</div>
						<div class="min-w-0">
							<div class="text-xs font-semibold" style="color: {node.color}">{node.label}</div>
							{#if node.detail}
								<div class="text-[10px] text-muted-foreground truncate max-w-[300px]">{node.detail}</div>
							{/if}
						</div>
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>
