<script lang="ts">
	import { X, Plus, Trash2, ChevronLeft, ChevronRight, Search, Loader2 } from 'lucide-svelte';
	import confirmDialog from '$lib/utils/confirmDialog';
	import { Button, Badge, Input } from '$lib/components/ui';
	import UniversalAddModal from './UniversalAddModal.svelte';
	import {
		getTeamListFromUser, updateTeamListFromUser, deleteTeamFromUser,
		getDataSourceListFromUser, updateDataSourceListFromUser, deleteDataSourceFromUser,
		getUserListFromTeam, updateUserListFromTeam, deleteUserFromTeam,
		getDataSourceListFromTeam, updateDataSourceListFromTeam, deleteDataSourceFromTeam,
		getUserAndTeamListFromDataSource, updateUserAndTeamListFromDataSource, deleteUserOrTeamFromDataSource,
		getCommonUserList, getCommonTeamList, getCommonDataSourceList, getCommonUserAndTeamList
	} from '$lib/service/team';
	import { databaseMap } from '$lib/types/database';

	export type AffiliationType =
		| 'USER_TEAM' | 'USER_DATASOURCE'
		| 'TEAM_USER' | 'TEAM_DATASOURCE'
		| 'DATASOURCE_ACCESS';

	interface ColumnDef {
		title: string;
		dataPath: string[];
	}

	interface AffiliationConfig {
		title: string;
		byIdKey: string;
		columns: ColumnDef[];
		queryListApi: (params: any) => Promise<any>;
		updateListApi: (params: any) => Promise<any>;
		deleteApi: (params: any) => Promise<any>;
		searchApi: (params: any) => Promise<any>;
		searchLabel: (item: any) => string;
		searchValue: (item: any) => any;
		searchIcon?: (item: any) => string | null;
		searchListKey: string;
		hasStatusColumn?: boolean;
	}

	function getDbIcon(item: any): string | null {
		const info = databaseMap[item?.type?.toUpperCase()];
		return info?.img || null;
	}

	const configMap: Record<AffiliationType, AffiliationConfig> = {
		USER_TEAM: {
			title: 'Teams',
			byIdKey: 'userId',
			columns: [
				{ title: 'Code', dataPath: ['team', 'code'] },
				{ title: 'Name', dataPath: ['team', 'name'] }
			],
			queryListApi: getTeamListFromUser,
			updateListApi: updateTeamListFromUser,
			deleteApi: deleteTeamFromUser,
			searchApi: getCommonTeamList,
			searchLabel: (item: any) => `${item.code} - ${item.name}`,
			searchValue: (item: any) => item.id,
			searchListKey: 'teamIdList'
		},
		USER_DATASOURCE: {
			title: 'Data Sources',
			byIdKey: 'userId',
			columns: [
				{ title: 'Alias', dataPath: ['dataSource', 'alias'] },
				{ title: 'Type', dataPath: ['dataSource', 'type'] }
			],
			queryListApi: getDataSourceListFromUser,
			updateListApi: updateDataSourceListFromUser,
			deleteApi: deleteDataSourceFromUser,
			searchApi: getCommonDataSourceList,
			searchLabel: (item: any) => item.alias || item.name || String(item.id),
			searchValue: (item: any) => item.id,
			searchIcon: getDbIcon,
			searchListKey: 'dataSourceIdList'
		},
		TEAM_USER: {
			title: 'Users',
			byIdKey: 'teamId',
			columns: [
				{ title: 'Username', dataPath: ['user', 'userName'] },
				{ title: 'Nickname', dataPath: ['user', 'nickName'] },
				{ title: 'Email', dataPath: ['user', 'email'] }
			],
			queryListApi: getUserListFromTeam,
			updateListApi: updateUserListFromTeam,
			deleteApi: deleteUserFromTeam,
			searchApi: getCommonUserList,
			searchLabel: (item: any) => `${item.userName} (${item.nickName || item.email || ''})`,
			searchValue: (item: any) => item.id,
			searchListKey: 'userIdList'
		},
		TEAM_DATASOURCE: {
			title: 'Data Sources',
			byIdKey: 'teamId',
			columns: [
				{ title: 'Alias', dataPath: ['dataSource', 'alias'] },
				{ title: 'Type', dataPath: ['dataSource', 'type'] }
			],
			queryListApi: getDataSourceListFromTeam,
			updateListApi: updateDataSourceListFromTeam,
			deleteApi: deleteDataSourceFromTeam,
			searchApi: getCommonDataSourceList,
			searchLabel: (item: any) => item.alias || item.name || String(item.id),
			searchValue: (item: any) => item.id,
			searchIcon: getDbIcon,
			searchListKey: 'dataSourceIdList'
		},
		DATASOURCE_ACCESS: {
			title: 'Access Rights',
			byIdKey: 'dataSourceId',
			columns: [
				{ title: 'Name', dataPath: ['accessObject', 'name'] },
				{ title: 'Code', dataPath: ['accessObject', 'code'] }
			],
			queryListApi: getUserAndTeamListFromDataSource,
			updateListApi: updateUserAndTeamListFromDataSource,
			deleteApi: deleteUserOrTeamFromDataSource,
			searchApi: getCommonUserAndTeamList,
			searchLabel: (item: any) => `[${item.type}] ${item.name || item.code}`,
			searchValue: (item: any) => JSON.stringify({ id: item.id, type: item.type }),
			searchListKey: 'accessObjectList',
			hasStatusColumn: true
		}
	};

	interface Props {
		open: boolean;
		type: AffiliationType;
		byId: number;
		onclose: () => void;
	}

	let { open, type, byId, onclose }: Props = $props();

	let dataList = $state<any[]>([]);
	let loading = $state(false);
	let searchKey = $state('');
	let page = $state(1);
	let total = $state(0);
	let showAddModal = $state(false);
	const PAGE_SIZE = 10;

	let config = $derived(configMap[type]);

	$effect(() => {
		if (open && byId) {
			page = 1;
			searchKey = '';
			fetchList();
		}
	});

	function getNestedValue(obj: any, path: string[]): any {
		return path.reduce((acc, key) => acc?.[key], obj);
	}

	async function fetchList() {
		loading = true;
		try {
			const params: any = {
				[config.byIdKey]: byId,
				pageNo: page,
				pageSize: PAGE_SIZE,
				searchKey
			};
			const res = await config.queryListApi(params);
			const data = res as any;
			dataList = data?.data || [];
			total = data?.total || dataList.length;
		} catch { dataList = []; total = 0; }
		finally { loading = false; }
	}

	async function handleDelete(id: number) {
		const confirmed = await confirmDialog({ title: 'Remove Item', message: 'Are you sure you want to remove this item?', confirmText: 'Remove', variant: 'destructive' });
		if (!confirmed) return;
		try {
			await config.deleteApi({ id });
			await fetchList();
		} catch (e) { console.error(e); }
	}

	async function handleAddItems(selectedValues: any[]) {
		if (selectedValues.length === 0) return;
		try {
			const params: any = { [config.byIdKey]: byId };
			if (type === 'DATASOURCE_ACCESS') {
				params[config.searchListKey] = selectedValues.map(v => {
					try { return JSON.parse(v); } catch { return v; }
				});
			} else {
				params[config.searchListKey] = selectedValues;
			}
			await config.updateListApi(params);
			showAddModal = false;
			await fetchList();
		} catch (e) { console.error(e); }
	}
</script>

{#if open}
	<button class="fixed inset-0 z-50 bg-black/30 cursor-default border-none p-0" onclick={onclose} tabindex="-1" aria-label="Close drawer"></button>
	<div class="fixed top-0 right-0 bottom-0 z-50 w-[500px] bg-background border-l border-border shadow-2xl flex flex-col animate-in slide-in-from-right">
		<!-- Header -->
		<div class="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
			<h3 class="text-base font-semibold">{config.title}</h3>
			<button onclick={onclose} class="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground">
				<X size={16} />
			</button>
		</div>

		<!-- Toolbar -->
		<div class="flex items-center gap-2 px-5 py-3 border-b border-border/50 shrink-0">
			<div class="relative flex-1">
				<Search class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted-foreground" />
				<input
					type="text"
					placeholder="Search..."
					class="flex h-8 w-full rounded-md border border-input bg-background pl-8 pr-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
					bind:value={searchKey}
					onkeydown={(e) => { if (e.key === 'Enter') fetchList(); }}
				/>
			</div>
			<Button size="sm" class="h-8 gap-1 text-xs" onclick={() => showAddModal = true}>
				<Plus size={14} />
				Add
			</Button>
		</div>

		<!-- Content -->
		<div class="flex-1 min-h-0 overflow-auto">
			{#if loading}
				<div class="flex items-center justify-center py-12">
					<Loader2 class="w-5 h-5 animate-spin text-primary" />
				</div>
			{:else if dataList.length === 0}
				<div class="flex items-center justify-center py-12 text-sm text-muted-foreground">
					No items found
				</div>
			{:else}
				<table class="w-full text-sm">
					<thead class="bg-muted/50 sticky top-0">
						<tr>
							{#each config.columns as col}
								<th class="px-4 py-2 text-left font-medium text-muted-foreground text-xs">{col.title}</th>
							{/each}
							{#if config.hasStatusColumn}
								<th class="px-4 py-2 text-left font-medium text-muted-foreground text-xs">Type</th>
							{/if}
							<th class="px-4 py-2 text-right font-medium text-muted-foreground text-xs w-16"></th>
						</tr>
					</thead>
					<tbody>
						{#each dataList as item (item.id)}
							{@const accessType = config.hasStatusColumn ? getNestedValue(item, ['accessObject', 'type']) : null}
							<tr class="border-t border-border/30 hover:bg-accent/20">
								{#each config.columns as col}
									<td class="px-4 py-2 truncate max-w-[150px]">{getNestedValue(item, col.dataPath) ?? '-'}</td>
								{/each}
								{#if config.hasStatusColumn}
									<td class="px-4 py-2">
										<Badge variant={accessType === 'TEAM' ? 'default' : 'secondary'}>
											{accessType || '-'}
										</Badge>
									</td>
								{/if}
								<td class="px-4 py-2 text-right">
									<button
										onclick={() => handleDelete(item.id)}
										class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive"
									>
										<Trash2 size={14} />
									</button>
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			{/if}
		</div>

		<!-- Pagination -->
		{#if total > PAGE_SIZE}
			<div class="flex items-center justify-between px-5 py-2 border-t border-border shrink-0">
				<span class="text-xs text-muted-foreground">
					Page {page} of {Math.ceil(total / PAGE_SIZE)} ({total} items)
				</span>
				<div class="flex items-center gap-1">
					<Button size="sm" variant="outline" class="h-7 w-7 p-0" disabled={page <= 1} onclick={() => { page--; fetchList(); }}>
						<ChevronLeft size={14} />
					</Button>
					<Button size="sm" variant="outline" class="h-7 w-7 p-0" disabled={page >= Math.ceil(total / PAGE_SIZE)} onclick={() => { page++; fetchList(); }}>
						<ChevronRight size={14} />
					</Button>
				</div>
			</div>
		{/if}
	</div>
{/if}

<!-- Add Modal -->
{#if showAddModal}
	<UniversalAddModal
		searchApi={config.searchApi}
		searchLabel={config.searchLabel}
		searchValue={config.searchValue}
		searchIcon={config.searchIcon}
		title="Add {config.title}"
		onclose={() => showAddModal = false}
		onconfirm={handleAddItems}
	/>
{/if}
