<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { getUserStore } from '$lib/stores/user.svelte';
	import i18n from '$lib/i18n';
	import { Button, Card, Separator, Spinner, Input, Badge, Dialog, Sheet } from '$lib/components/ui';
	import { Database, User, Users, X, ChevronLeft, ChevronRight, Shield, Search, Plus, Pencil, Trash2 } from 'lucide-svelte';
	import {
		getUserManagementList, createUser, updateUser, deleteUser,
		getDataSourceList, createDataSource, updateDataSource, deleteDataSource,
		getTeamList, createTeam, updateTeam, deleteTeam,
		type ITeamUserVO, type IDataSourceVO, type ITeamVO
	} from '$lib/service/team';
	import connectionService from '$lib/service/connection';
	import CreateConnectionForm from '$lib/components/CreateConnectionForm.svelte';
	import UniversalDrawer from '$lib/components/UniversalDrawer/UniversalDrawer.svelte';
	import type { AffiliationType } from '$lib/components/UniversalDrawer/UniversalDrawer.svelte';
	import { databaseMap } from '$lib/types/database';

	let activeTab = $state('datasource');

	// Drawer state
	let drawerOpen = $state(false);
	let drawerType = $state<AffiliationType>('USER_TEAM');
	let drawerById = $state(0);

	function openDrawer(type: AffiliationType, id: number) {
		drawerType = type;
		drawerById = id;
		drawerOpen = true;
	}

	// ─────────── Delete Confirmation ───────────
	let deleteDialogOpen = $state(false);
	let deleteTarget = $state<{ type: 'ds' | 'user' | 'team'; id: number; name: string }>({ type: 'ds', id: 0, name: '' });

	function openDeleteDialog(type: 'ds' | 'user' | 'team', id: number, name: string) {
		deleteTarget = { type, id, name };
		deleteDialogOpen = true;
	}

	async function confirmDelete() {
		try {
			if (deleteTarget.type === 'ds') {
				await deleteDataSource({ id: deleteTarget.id });
				await loadDataSources();
			} else if (deleteTarget.type === 'user') {
				await deleteUser({ id: deleteTarget.id });
				await loadUsers();
			} else if (deleteTarget.type === 'team') {
				await deleteTeam({ id: deleteTarget.id });
				await loadTeams();
			}
		} catch (e) { console.error(e); }
		deleteDialogOpen = false;
	}

	// ─────────── DataSource state ───────────
	let dataSources = $state<IDataSourceVO[]>([]);
	let dsLoading = $state(false);
	let dsSearch = $state('');
	let dsPage = $state(1);
	let dsTotal = $state(0);

	// DataSource Sheet (Add/Edit)
	let dsSheetOpen = $state(false);
	let dsConnectionDetail = $state<any>(null);

	function openDsSheet(editId?: number) {
		if (editId) {
			// Load connection details for editing
			connectionService.getDetails({ id: editId }).then((detail: any) => {
				dsConnectionDetail = detail;
				dsSheetOpen = true;
			}).catch(() => {
				dsConnectionDetail = null;
				dsSheetOpen = true;
			});
		} else {
			dsConnectionDetail = null;
			dsSheetOpen = true;
		}
	}

	async function handleDsSubmit(data: any) {
		const isUpdate = !!data.id;
		const requestApi = isUpdate ? updateDataSource : createDataSource;
		await requestApi(data);
		dsSheetOpen = false;
		await loadDataSources();
	}

	// ─────────── User state ───────────
	let users = $state<ITeamUserVO[]>([]);
	let userLoading = $state(false);
	let userSearch = $state('');
	let userDialogOpen = $state(false);
	let editingUser = $state<Partial<ITeamUserVO> & { password?: string }>({});
	let userFormErrors = $state<Record<string, string>>({});
	let userPage = $state(1);
	let userTotal = $state(0);

	// ─────────── Team state ───────────
	let teams = $state<ITeamVO[]>([]);
	let teamLoading = $state(false);
	let teamSearch = $state('');
	let teamDialogOpen = $state(false);
	let editingTeam = $state<Partial<ITeamVO>>({});
	let teamFormErrors = $state<Record<string, string>>({});
	let teamPage = $state(1);
	let teamTotal = $state(0);

	const PAGE_SIZE = 10;

	const tabList = [
		{ key: 'datasource', label: () => i18n('team.tab.datasource'), icon: Database },
		{ key: 'user', label: () => i18n('team.tab.user'), icon: User },
		{ key: 'team', label: () => i18n('team.tab.team'), icon: Users }
	];

	function switchTab(key: string) {
		activeTab = key;
		if (key === 'datasource') loadDataSources();
		else if (key === 'user') loadUsers();
		else if (key === 'team') loadTeams();
	}

	const userStore = getUserStore();

	onMount(() => {
		if (userStore.curUser?.roleCode !== 'ADMIN') {
			goto('/workspace');
			return;
		}
		loadDataSources();
	});

	async function loadDataSources() {
		dsLoading = true;
		try {
			const res = await getDataSourceList({ searchKey: dsSearch, pageNo: dsPage, pageSize: PAGE_SIZE });
			const data = res as any;
			dataSources = data?.data || [];
			dsTotal = data?.total || dataSources.length;
		} catch { dataSources = []; }
		finally { dsLoading = false; }
	}

	async function loadUsers() {
		userLoading = true;
		try {
			const res = await getUserManagementList({ searchKey: userSearch, pageNo: userPage, pageSize: PAGE_SIZE });
			const data = res as any;
			users = data?.data || [];
			userTotal = data?.total || users.length;
		} catch { users = []; }
		finally { userLoading = false; }
	}

	async function loadTeams() {
		teamLoading = true;
		try {
			const res = await getTeamList({ searchKey: teamSearch, pageNo: teamPage, pageSize: PAGE_SIZE });
			const data = res as any;
			teams = data?.data || [];
			teamTotal = data?.total || teams.length;
		} catch { teams = []; }
		finally { teamLoading = false; }
	}

	// Password length policy. Mirrors the server-side @Size on UserCreateRequest /
	// UserUpdateRequest (admin-api) and the self change-password endpoint, so the
	// rule the user sees here matches what the backend enforces.
	const PW_MIN_LEN = 6;
	const PW_MAX_LEN = 64;

	function validateUserForm(): boolean {
		const errors: Record<string, string> = {};
		if (!editingUser.userName?.trim()) errors.userName = i18n('team.validation.required', i18n('team.user.addForm.userName'));
		if (!editingUser.nickName?.trim()) errors.nickName = i18n('team.validation.required', i18n('team.user.addForm.nickName'));
		if (!editingUser.email?.trim()) errors.email = i18n('team.validation.required', i18n('team.user.addForm.email'));
		else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(editingUser.email!)) errors.email = i18n('team.validation.invalidEmail');

		const pwRaw = editingUser.password ?? '';
		const pw = pwRaw.trim();
		if (!editingUser.id) {
			// Create: password is required and must satisfy the length policy.
			if (!pw) {
				errors.password = i18n('team.validation.required', i18n('team.user.addForm.password'));
			} else if (pw.length < PW_MIN_LEN || pw.length > PW_MAX_LEN) {
				errors.password = `Password must be ${PW_MIN_LEN}–${PW_MAX_LEN} characters.`;
			}
		} else if (pw) {
			// Update: password is optional; if provided it must satisfy the policy.
			if (pw.length < PW_MIN_LEN || pw.length > PW_MAX_LEN) {
				errors.password = `Password must be ${PW_MIN_LEN}–${PW_MAX_LEN} characters.`;
			}
		}

		userFormErrors = errors;
		return Object.keys(errors).length === 0;
	}

	async function handleSaveUser() {
		if (!validateUserForm()) return;
		try {
			const submitData = { ...editingUser };
			if (submitData.id && !submitData.password) {
				delete submitData.password;
			}
			if (editingUser.id) {
				await updateUser(submitData as Record<string, unknown>);
			} else {
				await createUser(submitData as Record<string, unknown>);
			}
			userDialogOpen = false;
			editingUser = {};
			userFormErrors = {};
			await loadUsers();
		} catch (e) { console.error(e); }
	}

	function validateTeamForm(): boolean {
		const errors: Record<string, string> = {};
		if (!editingTeam.code?.trim()) errors.code = i18n('team.validation.required', i18n('team.team.addForm.code'));
		if (!editingTeam.name?.trim()) errors.name = i18n('team.validation.required', i18n('team.team.addForm.name'));
		teamFormErrors = errors;
		return Object.keys(errors).length === 0;
	}

	async function handleSaveTeam() {
		if (!validateTeamForm()) return;
		try {
			if (editingTeam.id) {
				await updateTeam(editingTeam as Record<string, unknown>);
			} else {
				await createTeam(editingTeam as Record<string, unknown>);
			}
			teamDialogOpen = false;
			editingTeam = {};
			teamFormErrors = {};
			await loadTeams();
		} catch (e) { console.error(e); }
	}

	function handleClose() { goto('/workspace'); }
</script>

<div class="relative h-full w-full overflow-hidden bg-background">
	<button onclick={handleClose} class="absolute right-4 top-4 z-10 flex h-10 w-10 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors">
		<X class="h-5 w-5" />
	</button>

	<div class="mx-auto h-full max-w-6xl overflow-y-auto px-6 py-8">
		<div class="mb-8">
			<h1 class="text-3xl font-bold tracking-tight">{i18n('team.title')}</h1>
			<p class="mt-2 text-muted-foreground">{i18n('team.text.description')}</p>
		</div>

		<div class="flex flex-wrap gap-1 mb-6">
			{#each tabList as tab}
				{@const TabIcon = tab.icon}
			<button onclick={() => switchTab(tab.key)}
				class="flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition-colors
					{activeTab === tab.key ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}">
				<TabIcon class="h-4 w-4" />{tab.label()}
			</button>
			{/each}
		</div>
		<Separator class="mb-6" />

		<div class="pb-8">
			{#if activeTab === 'datasource'}
				<!-- ═══════ DataSource Tab ═══════ -->
				<div class="space-y-4">
					<div class="flex items-center justify-between">
						<div class="relative w-80">
							<Search class="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
							<input type="text" bind:value={dsSearch} placeholder={i18n('team.input.search.placeholder') || 'Search...'}
								class="flex h-10 w-full rounded-md border border-input bg-background pl-8 pr-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
								onkeydown={(e) => { if (e.key === 'Enter') loadDataSources(); }} />
						</div>
						<Button onclick={() => openDsSheet()}>
							<Plus class="mr-1 h-4 w-4" />{i18n('team.action.addDatasource')}
						</Button>
					</div>
					<Card class="overflow-hidden">
						<table class="w-full text-sm">
							<thead class="bg-muted/50"><tr>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.datasource.alias')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.datasource.type')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.datasource.url')}</th>
								<th class="px-4 py-2.5 text-right font-medium text-muted-foreground w-[280px]">{i18n('common.text.action')}</th>
							</tr></thead>
							<tbody>
								{#if dsLoading}
									<tr><td colspan="4" class="px-4 py-8 text-center"><Spinner size="sm" class="mx-auto text-primary" /></td></tr>
								{:else if dataSources.length === 0}
									<tr><td colspan="4" class="px-4 py-8 text-center text-muted-foreground">{i18n('common.text.noData')}</td></tr>
								{:else}
									{#each dataSources as ds (ds.id)}
										<tr class="border-t border-border hover:bg-accent/30">
											<td class="px-4 py-2.5 font-medium">{ds.alias}</td>
											<td class="px-4 py-2.5">
												{#if ds.type && databaseMap[ds.type]}
													<div class="flex items-center gap-1.5">
														<img src={databaseMap[ds.type].img} alt={ds.type} class="w-5 h-5 shrink-0 object-contain" />
														<span class="text-sm">{databaseMap[ds.type].name}</span>
													</div>
												{:else if ds.type}
													<Badge>{ds.type}</Badge>
												{:else}
													<span class="text-muted-foreground">-</span>
												{/if}
											</td>
											<td class="px-4 py-2.5 text-muted-foreground truncate max-w-[300px]">{ds.url}</td>
											<td class="px-4 py-2.5 text-right">
												<div class="flex gap-1 justify-end">
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDsSheet(ds.id)}>
														<Pencil class="h-3.5 w-3.5" />{i18n('common.button.edit')}
													</Button>
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDrawer('DATASOURCE_ACCESS', ds.id)}>
														<Shield class="h-3.5 w-3.5" />{i18n('team.action.rightManagement')}
													</Button>
													<Button size="sm" variant="ghost" class="text-destructive gap-1" onclick={() => openDeleteDialog('ds', ds.id, ds.alias)}>
														<Trash2 class="h-3.5 w-3.5" />{i18n('common.button.delete')}
													</Button>
												</div>
											</td>
										</tr>
									{/each}
								{/if}
							</tbody>
						</table>
					</Card>
					{#if dsTotal > PAGE_SIZE}
						<div class="flex items-center justify-between mt-4">
							<span class="text-sm text-muted-foreground">{i18n('team.pagination.total', dsTotal)}</span>
							<div class="flex items-center gap-2">
								<Button size="sm" variant="outline" disabled={dsPage <= 1} onclick={() => { dsPage--; loadDataSources(); }}>
									<ChevronLeft class="h-4 w-4" />
								</Button>
								<span class="text-sm">{dsPage} / {Math.ceil(dsTotal / PAGE_SIZE)}</span>
								<Button size="sm" variant="outline" disabled={dsPage >= Math.ceil(dsTotal / PAGE_SIZE)} onclick={() => { dsPage++; loadDataSources(); }}>
									<ChevronRight class="h-4 w-4" />
								</Button>
							</div>
						</div>
					{/if}
				</div>

			{:else if activeTab === 'user'}
				<!-- ═══════ User Tab ═══════ -->
				<div class="space-y-4">
					<div class="flex items-center justify-between">
						<div class="relative w-80">
							<Search class="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
							<input type="text" bind:value={userSearch} placeholder={i18n('team.input.search.placeholder') || 'Search...'}
								class="flex h-10 w-full rounded-md border border-input bg-background pl-8 pr-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
								onkeydown={(e) => { if (e.key === 'Enter') loadUsers(); }} />
						</div>
						<Button onclick={() => { editingUser = { userName: '', nickName: '', email: '', password: '', roleCode: 'USER', status: 'VALID' }; userFormErrors = {}; userDialogOpen = true; }}>
							<Plus class="mr-1 h-4 w-4" />{i18n('team.action.addUser')}
						</Button>
					</div>
					<Card class="overflow-hidden">
						<table class="w-full text-sm">
							<thead class="bg-muted/50"><tr>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.user.userName')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.user.nickName')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.text.status')}</th>
								<th class="px-4 py-2.5 text-right font-medium text-muted-foreground w-[320px]">{i18n('common.text.action')}</th>
							</tr></thead>
							<tbody>
								{#if userLoading}
									<tr><td colspan="4" class="px-4 py-8 text-center"><Spinner size="sm" class="mx-auto text-primary" /></td></tr>
								{:else if users.length === 0}
									<tr><td colspan="4" class="px-4 py-8 text-center text-muted-foreground">{i18n('common.text.noData')}</td></tr>
								{:else}
									{#each users as user (user.id)}
										<tr class="border-t border-border hover:bg-accent/30">
											<td class="px-4 py-2.5 font-medium">{user.userName}</td>
											<td class="px-4 py-2.5">{user.nickName}</td>
											<td class="px-4 py-2.5"><Badge variant={user.status === 'VALID' ? 'default' : 'destructive'}>{user.status || '-'}</Badge></td>
											<td class="px-4 py-2.5 text-right">
												<div class="flex gap-1 justify-end">
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => { editingUser = { ...user, email: user.email ?? '', password: '' }; userFormErrors = {}; userDialogOpen = true; }}>
														<Pencil class="h-3.5 w-3.5" />{i18n('common.button.edit')}
													</Button>
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDrawer('USER_TEAM', user.id)}>
														<Users class="h-3.5 w-3.5" />{i18n('team.action.affiliation.team')}
													</Button>
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDrawer('USER_DATASOURCE', user.id)}>
														<Database class="h-3.5 w-3.5" />{i18n('team.action.affiliation.datasource')}
													</Button>
													<Button size="sm" variant="ghost" class="text-destructive gap-1" onclick={() => openDeleteDialog('user', user.id, user.userName)}>
														<Trash2 class="h-3.5 w-3.5" />{i18n('common.button.delete')}
													</Button>
												</div>
											</td>
										</tr>
									{/each}
								{/if}
							</tbody>
						</table>
					</Card>
					{#if userTotal > PAGE_SIZE}
						<div class="flex items-center justify-between mt-4">
							<span class="text-sm text-muted-foreground">{i18n('team.pagination.total', userTotal)}</span>
							<div class="flex items-center gap-2">
								<Button size="sm" variant="outline" disabled={userPage <= 1} onclick={() => { userPage--; loadUsers(); }}>
									<ChevronLeft class="h-4 w-4" />
								</Button>
								<span class="text-sm">{userPage} / {Math.ceil(userTotal / PAGE_SIZE)}</span>
								<Button size="sm" variant="outline" disabled={userPage >= Math.ceil(userTotal / PAGE_SIZE)} onclick={() => { userPage++; loadUsers(); }}>
									<ChevronRight class="h-4 w-4" />
								</Button>
							</div>
						</div>
					{/if}
				</div>

			{:else if activeTab === 'team'}
				<!-- ═══════ Team Tab ═══════ -->
				<div class="space-y-4">
					<div class="flex items-center justify-between">
						<div class="relative w-80">
							<Search class="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
							<input type="text" bind:value={teamSearch} placeholder={i18n('team.input.search.placeholder') || 'Search...'}
								class="flex h-10 w-full rounded-md border border-input bg-background pl-8 pr-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
								onkeydown={(e) => { if (e.key === 'Enter') loadTeams(); }} />
						</div>
						<Button onclick={() => { editingTeam = { code: '', name: '', description: '', status: 'VALID' }; teamFormErrors = {}; teamDialogOpen = true; }}>
							<Plus class="mr-1 h-4 w-4" />{i18n('team.action.addTeam')}
						</Button>
					</div>
					<Card class="overflow-hidden">
						<table class="w-full text-sm">
							<thead class="bg-muted/50"><tr>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.team.addForm.code')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.team.addForm.name')}</th>
								<th class="px-4 py-2.5 text-left font-medium text-muted-foreground">{i18n('team.text.status')}</th>
								<th class="px-4 py-2.5 text-right font-medium text-muted-foreground w-[320px]">{i18n('common.text.action')}</th>
							</tr></thead>
							<tbody>
								{#if teamLoading}
									<tr><td colspan="4" class="px-4 py-8 text-center"><Spinner size="sm" class="mx-auto text-primary" /></td></tr>
								{:else if teams.length === 0}
									<tr><td colspan="4" class="px-4 py-8 text-center text-muted-foreground">{i18n('common.text.noData')}</td></tr>
								{:else}
									{#each teams as team (team.id)}
										<tr class="border-t border-border hover:bg-accent/30">
											<td class="px-4 py-2.5 font-medium">{team.code}</td>
											<td class="px-4 py-2.5">{team.name}</td>
											<td class="px-4 py-2.5"><Badge variant={team.status === 'VALID' ? 'default' : 'destructive'}>{team.status || '-'}</Badge></td>
											<td class="px-4 py-2.5 text-right">
												<div class="flex gap-1 justify-end">
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => { editingTeam = { ...team, description: team.description ?? '' }; teamFormErrors = {}; teamDialogOpen = true; }}>
														<Pencil class="h-3.5 w-3.5" />{i18n('common.button.edit')}
													</Button>
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDrawer('TEAM_USER', team.id)}>
														<Users class="h-3.5 w-3.5" />{i18n('team.action.affiliation.user')}
													</Button>
													<Button size="sm" variant="ghost" class="gap-1" onclick={() => openDrawer('TEAM_DATASOURCE', team.id)}>
														<Database class="h-3.5 w-3.5" />{i18n('team.action.affiliation.datasource')}
													</Button>
													<Button size="sm" variant="ghost" class="text-destructive gap-1" onclick={() => openDeleteDialog('team', team.id, team.code)}>
														<Trash2 class="h-3.5 w-3.5" />{i18n('common.button.delete')}
													</Button>
												</div>
											</td>
										</tr>
									{/each}
								{/if}
							</tbody>
						</table>
					</Card>
					{#if teamTotal > PAGE_SIZE}
						<div class="flex items-center justify-between mt-4">
							<span class="text-sm text-muted-foreground">{i18n('team.pagination.total', teamTotal)}</span>
							<div class="flex items-center gap-2">
								<Button size="sm" variant="outline" disabled={teamPage <= 1} onclick={() => { teamPage--; loadTeams(); }}>
									<ChevronLeft class="h-4 w-4" />
								</Button>
								<span class="text-sm">{teamPage} / {Math.ceil(teamTotal / PAGE_SIZE)}</span>
								<Button size="sm" variant="outline" disabled={teamPage >= Math.ceil(teamTotal / PAGE_SIZE)} onclick={() => { teamPage++; loadTeams(); }}>
									<ChevronRight class="h-4 w-4" />
								</Button>
							</div>
						</div>
					{/if}
				</div>
			{/if}
		</div>
	</div>
</div>

<!-- ═══════════════════════════════════════════════ -->
<!-- DataSource Create/Edit Sheet (Side Panel)      -->
<!-- ═══════════════════════════════════════════════ -->
<Sheet bind:open={dsSheetOpen} class="w-full sm:max-w-4xl !p-0 overflow-hidden">
	<div class="h-full">
		{#if dsSheetOpen}
			<CreateConnectionForm
				connectionDetail={dsConnectionDetail}
				onsubmit={handleDsSubmit}
				oncancel={() => { dsSheetOpen = false; }}
			/>
		{/if}
	</div>
</Sheet>

<!-- ═══════════════════════════════════════════════ -->
<!-- User Create/Edit Dialog                         -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={userDialogOpen} onclose={() => { editingUser = {}; userFormErrors = {}; }}>
	<h2 class="text-lg font-semibold mb-4 pr-6">
		{editingUser.id ? i18n('team.action.editUser') : i18n('team.action.addUser')}
	</h2>
	<div class="space-y-4">
		<div class="space-y-2">
			<label class="text-sm font-medium" for="user-userName">{i18n('team.user.addForm.userName')} <span class="text-destructive">*</span></label>
			<Input id="user-userName" bind:value={editingUser.userName} autocomplete="off" />
			{#if userFormErrors.userName}<p class="text-xs text-destructive">{userFormErrors.userName}</p>{/if}
		</div>
		<div class="space-y-2">
			<label class="text-sm font-medium" for="user-nickName">{i18n('team.user.addForm.nickName')} <span class="text-destructive">*</span></label>
			<Input id="user-nickName" bind:value={editingUser.nickName} autocomplete="off" />
			{#if userFormErrors.nickName}<p class="text-xs text-destructive">{userFormErrors.nickName}</p>{/if}
		</div>
		<div class="space-y-2">
			<label class="text-sm font-medium" for="user-email">{i18n('team.user.addForm.email')} <span class="text-destructive">*</span></label>
			<Input id="user-email" bind:value={editingUser.email} type="email" autocomplete="off" />
			{#if userFormErrors.email}<p class="text-xs text-destructive">{userFormErrors.email}</p>{/if}
		</div>
		<div class="space-y-2">
			<label class="text-sm font-medium" for="user-password">{i18n('team.user.addForm.password')} {#if !editingUser.id}<span class="text-destructive">*</span>{/if}</label>
			<Input id="user-password" type="password" bind:value={editingUser.password} placeholder={editingUser.id ? 'Leave blank to keep current' : ''} autocomplete="new-password" />
			{#if userFormErrors.password}
				<p class="text-xs text-destructive">{userFormErrors.password}</p>
			{:else}
				<p class="text-xs text-muted-foreground">
					{editingUser.id
						? `Optional. Leave blank to keep the current password. ${PW_MIN_LEN}–${PW_MAX_LEN} characters if changing.`
						: `${PW_MIN_LEN}–${PW_MAX_LEN} characters.`}
				</p>
			{/if}
		</div>

		<Separator />

		<fieldset class="space-y-2">
			<legend class="text-sm font-medium">{i18n('team.user.addForm.roleCode')} <span class="text-destructive">*</span></legend>
			<div class="flex gap-4">
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="role" value="ADMIN" checked={editingUser.roleCode === 'ADMIN'} onchange={() => editingUser.roleCode = 'ADMIN'} class="h-4 w-4 accent-primary" />
					{i18n('team.user.addForm.roleCode.admin')}
				</label>
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="role" value="USER" checked={editingUser.roleCode === 'USER'} onchange={() => editingUser.roleCode = 'USER'} class="h-4 w-4 accent-primary" />
					{i18n('team.user.addForm.roleCode.user')}
				</label>
			</div>
		</fieldset>

		<fieldset class="space-y-2">
			<legend class="text-sm font-medium">{i18n('team.user.addForm.status')} <span class="text-destructive">*</span></legend>
			<div class="flex gap-4">
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="userStatus" value="VALID" checked={editingUser.status === 'VALID'} onchange={() => editingUser.status = 'VALID'} class="h-4 w-4 accent-primary" />
					{i18n('team.user.addForm.status.valid')}
				</label>
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="userStatus" value="INVALID" checked={editingUser.status === 'INVALID'} onchange={() => editingUser.status = 'INVALID'} class="h-4 w-4 accent-primary" />
					{i18n('team.user.addForm.status.invalid')}
				</label>
			</div>
		</fieldset>
	</div>
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={() => { userDialogOpen = false; editingUser = {}; userFormErrors = {}; }}>{i18n('common.button.cancel')}</Button>
		<Button onclick={handleSaveUser}>{i18n('common.button.save')}</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Team Create/Edit Dialog                         -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={teamDialogOpen} onclose={() => { editingTeam = {}; teamFormErrors = {}; }}>
	<h2 class="text-lg font-semibold mb-4 pr-6">
		{editingTeam.id ? i18n('team.action.editTeam') : i18n('team.action.addTeam')}
	</h2>
	<div class="space-y-4">
		<div class="space-y-2">
			<label class="text-sm font-medium" for="team-code">{i18n('team.team.addForm.code')} <span class="text-destructive">*</span></label>
			<Input id="team-code" bind:value={editingTeam.code} />
			{#if teamFormErrors.code}<p class="text-xs text-destructive">{teamFormErrors.code}</p>{/if}
		</div>
		<div class="space-y-2">
			<label class="text-sm font-medium" for="team-name">{i18n('team.team.addForm.name')} <span class="text-destructive">*</span></label>
			<Input id="team-name" bind:value={editingTeam.name} />
			{#if teamFormErrors.name}<p class="text-xs text-destructive">{teamFormErrors.name}</p>{/if}
		</div>
		<div class="space-y-2">
			<label class="text-sm font-medium" for="team-desc">{i18n('team.team.addForm.description')}</label>
			<textarea
				id="team-desc"
				bind:value={editingTeam.description}
				class="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm min-h-[80px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
			></textarea>
		</div>

		<Separator />

		<fieldset class="space-y-2">
			<legend class="text-sm font-medium">{i18n('team.team.addForm.status')} <span class="text-destructive">*</span></legend>
			<div class="flex gap-4">
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="teamStatus" value="VALID" checked={editingTeam.status === 'VALID'} onchange={() => editingTeam.status = 'VALID'} class="h-4 w-4 accent-primary" />
					{i18n('team.team.addForm.status.valid')}
				</label>
				<label class="flex cursor-pointer items-center gap-2 text-sm">
					<input type="radio" name="teamStatus" value="INVALID" checked={editingTeam.status === 'INVALID'} onchange={() => editingTeam.status = 'INVALID'} class="h-4 w-4 accent-primary" />
					{i18n('team.team.addForm.status.invalid')}
				</label>
			</div>
		</fieldset>
	</div>
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={() => { teamDialogOpen = false; editingTeam = {}; teamFormErrors = {}; }}>{i18n('common.button.cancel')}</Button>
		<Button onclick={handleSaveTeam}>{i18n('common.button.save')}</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Delete Confirmation Dialog                      -->
<!-- ═══════════════════════════════════════════════ -->
<Dialog bind:open={deleteDialogOpen}>
	<div class="space-y-4 pr-6">
		<h2 class="text-lg font-semibold">{i18n('common.tips.delete.confirm')}</h2>
		<p class="text-sm text-muted-foreground">{i18n('team.delete.confirm.message', deleteTarget.name)}</p>
	</div>
	<div class="flex gap-2 justify-end mt-6">
		<Button variant="outline" onclick={() => { deleteDialogOpen = false; }}>{i18n('common.button.cancel')}</Button>
		<Button variant="destructive" onclick={confirmDelete}>{i18n('common.button.delete')}</Button>
	</div>
</Dialog>

<!-- ═══════════════════════════════════════════════ -->
<!-- Universal Drawer for Affiliations               -->
<!-- ═══════════════════════════════════════════════ -->
<UniversalDrawer
	open={drawerOpen}
	type={drawerType}
	byId={drawerById}
	onclose={() => drawerOpen = false}
/>
