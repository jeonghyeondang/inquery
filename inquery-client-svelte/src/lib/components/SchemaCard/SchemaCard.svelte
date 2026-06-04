<script lang="ts">
	import { ChevronDown, ChevronUp, Key, Link, Table2 } from 'lucide-svelte';

	export interface ISchemaColumn {
		name: string;
		columnType: string;
		nullable?: boolean;
		isPrimaryKey?: boolean;
		isForeignKey?: boolean;
		foreignKeyRef?: string;
		comment?: string;
		description?: string;
		exampleValues?: string;
	}

	interface Props {
		tableName: string;
		schemaName?: string;
		databaseName?: string;
		columns: ISchemaColumn[];
		comment?: string;
		defaultExpanded?: boolean;
	}

	let { tableName, schemaName, databaseName, columns, comment, defaultExpanded = true }: Props = $props();

	// svelte-ignore state_referenced_locally
	let isExpanded = $state(defaultExpanded);

	let fullTableName = $derived([databaseName, schemaName, tableName].filter(Boolean).join('.'));
	let pkColumns = $derived(columns.filter(c => c.isPrimaryKey));
	let fkColumns = $derived(columns.filter(c => c.isForeignKey));
	let displayColumns = $derived(isExpanded ? columns : columns.slice(0, 5));

	function parseExamples(exampleValues?: string): string[] {
		if (!exampleValues) return [];
		try {
			const parsed = JSON.parse(exampleValues);
			return Array.isArray(parsed) ? parsed.slice(0, 3).map(String) : [];
		} catch { return []; }
	}

	function getTypeColorClass(columnType: string): string {
		if (!columnType) return '';
		const lower = columnType.toLowerCase();
		if (lower.includes('uuid') || lower.includes('rowid')) return 'text-purple-400';
		if (lower.includes('varchar') || lower.includes('text') || lower.includes('char') || lower.includes('string')) return 'text-green-400';
		if (lower.includes('int') || lower.includes('numeric') || lower.includes('decimal') || lower.includes('float') || lower.includes('double') || lower.includes('number') || lower.includes('serial')) return 'text-blue-400';
		if (lower.includes('timestamp') || lower.includes('date') || lower.includes('time')) return 'text-orange-400';
		if (lower.includes('bool')) return 'text-yellow-400';
		if (lower.includes('blob') || lower.includes('binary')) return 'text-red-400';
		if (lower.includes('json') || lower.includes('object') || lower.includes('variant')) return 'text-cyan-400';
		if (lower.includes('array')) return 'text-pink-400';
		return 'text-muted-foreground';
	}
</script>

<div class="rounded-lg border border-border bg-muted/20 overflow-hidden mb-2">
	<!-- Header -->
	<button
		class="flex items-center justify-between w-full px-3 py-2 hover:bg-accent/50 transition-colors text-left"
		onclick={() => isExpanded = !isExpanded}
	>
		<div class="flex items-center gap-2 min-w-0">
			<Table2 size={14} class="text-blue-500 shrink-0" />
			<span class="text-xs font-medium text-foreground truncate">{fullTableName}</span>
			<span class="text-[10px] text-muted-foreground">{columns.length} column{columns.length !== 1 ? 's' : ''}</span>
		</div>
		<div class="flex items-center gap-1.5 shrink-0">
			{#if pkColumns.length > 0}
				<span class="flex items-center gap-0.5 text-[10px] px-1.5 py-0.5 rounded bg-yellow-500/10 text-yellow-500 border border-yellow-500/20">
					<Key size={10} />{pkColumns.length} PK
				</span>
			{/if}
			{#if fkColumns.length > 0}
				<span class="flex items-center gap-0.5 text-[10px] px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-500 border border-blue-500/20">
					<Link size={10} />{fkColumns.length} FK
				</span>
			{/if}
			{#if isExpanded}
				<ChevronUp size={14} class="text-muted-foreground" />
			{:else}
				<ChevronDown size={14} class="text-muted-foreground" />
			{/if}
		</div>
	</button>

	<!-- Comment -->
	{#if (comment) && isExpanded}
		<div class="px-3 py-1.5 text-[11px] text-muted-foreground border-t border-border/50 bg-muted/10">
			{comment}
		</div>
	{/if}

	<!-- Columns -->
	<div class="divide-y divide-border/30">
		{#each displayColumns as col, idx (col.name + idx)}
			{@const examples = parseExamples(col.exampleValues)}
			<div class="px-3 py-1.5 hover:bg-accent/20 transition-colors">
				<div class="flex items-center justify-between">
					<div class="flex items-center gap-1.5 min-w-0">
						{#if col.isPrimaryKey}
							<Key size={10} class="text-yellow-500 shrink-0" />
						{:else if col.isForeignKey}
							<Link size={10} class="text-blue-500 shrink-0" />
						{/if}
						<span class="text-[11px] font-mono {col.isPrimaryKey ? 'font-semibold text-foreground' : 'text-foreground/80'}">
							{col.name}
						</span>
					</div>
					<div class="flex items-center gap-1.5 shrink-0">
						<span class="text-[10px] font-mono {getTypeColorClass(col.columnType)}">
							{col.columnType?.toUpperCase() || ''}
						</span>
						{#if col.nullable}
							<span class="text-[9px] px-1 py-0.5 rounded bg-muted text-muted-foreground">NULL</span>
						{/if}
						{#if col.isForeignKey && col.foreignKeyRef}
							<span class="text-[10px] text-blue-400">→ {col.foreignKeyRef}</span>
						{/if}
					</div>
				</div>
				{#if col.description || examples.length > 0}
					<div class="mt-0.5 flex items-center gap-2 text-[10px] text-muted-foreground">
						{#if col.description}
							<span>{col.description}</span>
						{/if}
						{#if examples.length > 0}
							<span class="flex items-center gap-1">
								<span class="opacity-60">e.g.</span>
								{#each examples as ex}
									<span class="px-1 py-0 bg-muted rounded">{ex}</span>
								{/each}
							</span>
						{/if}
					</div>
				{/if}
			</div>
		{/each}

	{#if !isExpanded && columns.length > 5}
		<button class="w-full px-3 py-1.5 text-[11px] text-primary cursor-pointer hover:bg-accent/20 text-left border-none bg-transparent"
			onclick={() => isExpanded = true}
		>
			+{columns.length - 5} more columns
		</button>
	{/if}
	</div>
</div>
