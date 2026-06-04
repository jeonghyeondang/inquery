<script lang="ts">
	import { cn } from '$lib/utils/cn';
	import { Button, Separator, Spinner, Textarea, Switch, Popover, PopoverTrigger, PopoverContent } from '$lib/components/ui';
	import connectionService from '$lib/service/connection';
	import { databaseMap, databaseTypeList } from '$lib/types/database';
	import { Database, Search, X, ChevronDown, Check, Loader2, LayoutGrid, Server, Cloud, Settings, Shield, Disc } from 'lucide-svelte';
	import message from '$lib/utils/message';

	interface Props {
		connectionDetail?: any;
		onsubmit: (data: any) => void | Promise<void>;
		oncancel?: () => void;
	}

	let { connectionDetail = null, onsubmit, oncancel }: Props = $props();

	// ─── DB Picker ───
	// svelte-ignore state_referenced_locally
	let step = $state<'picker' | 'form'>(connectionDetail ? 'form' : 'picker');
	let dbSearchQuery = $state('');
	let dbSelectedCategory = $state('ALL');

	const DB_CATEGORIES = [
		{ id: 'ALL', label: 'All', icon: LayoutGrid },
		{ id: 'RDB', label: 'Relational', icon: Database },
		{ id: 'NOSQL', label: 'NoSQL', icon: Server },
		{ id: 'BIGDATA', label: 'Big Data / Cloud', icon: Cloud }
	];

	let filteredDatabases = $derived(
		databaseTypeList.filter(db => {
			const matchesSearch = db.name.toLowerCase().includes(dbSearchQuery.toLowerCase());
			const matchesCategory = dbSelectedCategory === 'ALL' || db.category === dbSelectedCategory;
			return matchesSearch && matchesCategory;
		})
	);

	// ─── Form ───
	let activeTab = $state('General');
	let envPopoverOpen = $state(false);
	let authPopoverOpen = $state(false);
	let sshAuthPopoverOpen = $state(false);
	let testingConnection = $state(false);
	let savingConnection = $state(false);
	let envList = $state<{ id: number; name: string; shortName: string; color: string }[]>([]);
	let aliasManuallyChanged = false;

	const authOptions = [
		{ value: 'USERANDPASSWORD', label: 'User & Password' },
		{ value: 'KEYPAIR', label: 'Key-Pair' },
		{ value: 'NONE', label: 'NONE' }
	];
	const sshAuthOptions = [
		{ value: 'password', label: 'Password' },
		{ value: 'key', label: 'Private Key' }
	];

	const sidebarTabs = [
		{ key: 'General', label: 'General', icon: Settings },
		{ key: 'SSH', label: 'SSH Tunnel', icon: Shield },
		{ key: 'Advanced', label: 'Advanced', icon: Disc }
	];

	let form = $state({
		type: 'MYSQL',
		alias: '',
		host: '',
		port: '',
		user: '',
		password: '',
		database: '',
		url: '',
		useUrl: false,
		environmentId: '',
		extendInfo: '',
		account: '',
		authenticationType: 'USERANDPASSWORD' as 'USERANDPASSWORD' | 'KEYPAIR' | 'NONE',
		privateKeyContent: '',
		warehouse: '',
		role: '',
		schema: '',
		projectId: '',
		serviceAccountJson: '',
		defaultDataset: '',
		httpPath: '',
		accessToken: '',
		catalog: '',
		serviceType: 'SID' as 'SID' | 'SERVICE',
		sid: '',
		serviceName: '',
		oracleDriver: 'thin' as 'thin' | 'oci' | 'oci8',
		instanceName: '',
		ssh: { use: false, host: '', port: '22', user: '', authType: 'password', password: '', keyFile: '', passphrase: '' },
		driverConfig: { jdbcDriver: '', jdbcDriverClass: '' }
	});
	let formErrors = $state<Record<string, string>>({});

	const isEdit = $derived(!!connectionDetail?.id);

	// URL templates
	const URL_TEMPLATES: Record<string, string> = {
		MYSQL: 'jdbc:mysql://{host}:{port}/{database}',
		POSTGRESQL: 'jdbc:postgresql://{host}:{port}/{database}',
		ORACLE: 'jdbc:oracle:{driver}:@{host}:{port}:{sid}',
		MARIADB: 'jdbc:mariadb://{host}:{port}/{database}',
		CLICKHOUSE: 'jdbc:clickhouse://{host}:{port}/{database}',
		SQLSERVER: 'jdbc:sqlserver://{host}:{port};database={database}',
		DB2: 'jdbc:db2://{host}:{port}/{database}',
		PRESTO: 'jdbc:presto://{host}:{port}/{database}',
		HIVE: 'jdbc:hive2://{host}:{port}/{database}',
		MONGODB: 'mongodb://{host}:{port}/{database}',
		REDIS: 'jdbc:redis://{host}:{port}/{database}',
	};

	function buildUrlFromFields() {
		const tpl = URL_TEMPLATES[form.type];
		if (!tpl) return;
		let url = tpl;
		url = url.replace('{host}', form.host || '');
		url = url.replace('{port}', form.port || '');
		url = url.replace('{database}', form.database || '');
		url = url.replace('{driver}', form.oracleDriver || 'thin');
		url = url.replace('{sid}', form.sid || '');
		form.url = url;
	}

	function onHostInput(e: Event) {
		const val = (e.target as HTMLInputElement).value;
		if (!aliasManuallyChanged) form.alias = '@' + val;
		buildUrlFromFields();
	}
	function onPortInput() { buildUrlFromFields(); }
	function onDatabaseInput() { buildUrlFromFields(); }
	function onAliasInput() { aliasManuallyChanged = true; }

	function handleSelectDatabase(db: typeof databaseTypeList[0]) {
		form.type = db.code;
		form.alias = db.name;
		if (db.port) form.port = String(db.port);
		if (db.code === 'SNOWFLAKE') {
			form.authenticationType = 'USERANDPASSWORD';
			form.url = 'jdbc:snowflake://';
		} else if (db.code === 'BIGQUERY') {
			form.url = 'jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443';
		} else if (db.code === 'DATABRICKS') {
			form.url = 'jdbc:databricks://';
			form.port = '443';
		} else if (db.code === 'MONGODB') {
			form.url = 'mongodb://';
			form.port = '27017';
		}
		step = 'form';
	}

	// Load environment list
	async function loadEnvironments() {
		try {
			const res = await connectionService.getEnvList?.();
			envList = (res as any) || [];
		} catch { envList = []; }
	}

	// Load connection detail for editing
	function loadConnectionDetail() {
		if (!connectionDetail) return;
		const data = connectionDetail;
		form.type = data.type || 'MYSQL';
		form.alias = data.alias || '';
		form.host = data.host || '';
		form.port = data.port ? String(data.port) : '';
		form.user = data.user || '';
		form.password = data.password || '';
		form.database = data.database || '';
		form.url = data.url || '';
		form.environmentId = data.environmentId ? String(data.environmentId) : '';
		aliasManuallyChanged = true;
		if (data.ssh) form.ssh = { ...form.ssh, ...data.ssh, use: true };
		if (data.driverConfig) form.driverConfig = { ...data.driverConfig };
		if (data.extendInfo && Array.isArray(data.extendInfo)) {
			for (const ext of data.extendInfo) {
				if (ext.key && ext.value !== undefined) {
					const fields = ['warehouse', 'role', 'schema', 'authenticationType', 'privateKeyContent', 'account',
						'serviceAccountJson', 'defaultDataset', 'httpPath', 'accessToken', 'catalog'];
					if (fields.includes(ext.key)) (form as any)[ext.key] = ext.value;
				}
			}
		}
		step = 'form';
	}

	$effect(() => { loadEnvironments(); });
	$effect(() => { if (connectionDetail) loadConnectionDetail(); });

	function validateForm(): boolean {
		const errors: Record<string, string> = {};
		if (!form.alias.trim()) errors.alias = 'Name is required';
		if (form.type === 'SNOWFLAKE') {
			if (!form.account.trim()) errors.account = 'Account is required';
			if (!form.url.trim()) errors.url = 'URL is required';
		} else if (form.type === 'BIGQUERY') {
			if (!form.serviceAccountJson.trim() && !connectionDetail?.id) {
				errors.serviceAccountJson = 'Service Account JSON is required';
			}
		} else if (form.type === 'DATABRICKS') {
			if (!form.host.trim()) errors.host = 'Host is required';
			if (!form.httpPath.trim()) errors.httpPath = 'HTTP Path is required';
			if (!form.accessToken.trim() && !connectionDetail?.id) {
				errors.accessToken = 'Access Token is required';
			}
		} else if (form.type !== 'SQLITE' && form.type !== 'REDIS') {
			if (!form.useUrl && !form.host.trim()) errors.host = 'Host is required';
		}
		formErrors = errors;
		return Object.keys(errors).length === 0;
	}

	function buildPayload(): any {
		const extendInfo: { key: string; value: string }[] = [];
		if (form.type === 'SNOWFLAKE') {
			const snowflakeFields = ['warehouse', 'role', 'schema', 'authenticationType'];
			if (form.authenticationType === 'KEYPAIR') snowflakeFields.push('privateKeyContent');
			for (const f of snowflakeFields) {
				const val = (form as any)[f];
				if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
			}
		} else if (form.type === 'BIGQUERY') {
			for (const f of ['serviceAccountJson', 'defaultDataset']) {
				const val = (form as any)[f];
				if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
			}
		} else if (form.type === 'DATABRICKS') {
			for (const f of ['httpPath', 'accessToken', 'catalog', 'schema']) {
				const val = (form as any)[f];
				if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
			}
		}
		const payload: any = {
			type: form.type,
			alias: form.alias,
			host: form.host || undefined,
			port: form.port ? Number(form.port) : undefined,
			user: form.user || undefined,
			password: form.password || undefined,
			database: form.database || undefined,
			url: form.url || undefined,
			environmentId: form.environmentId ? Number(form.environmentId) : undefined,
			extendInfo: extendInfo.length > 0 ? extendInfo : (form.extendInfo || undefined),
			ssh: form.ssh.use ? form.ssh : undefined,
			driverConfig: (form.driverConfig.jdbcDriver || form.driverConfig.jdbcDriverClass) ? form.driverConfig : undefined
		};
		if (form.type === 'SNOWFLAKE' && form.account) payload.host = form.account;
		if (connectionDetail?.id) payload.id = connectionDetail.id;
		return payload;
	}

	async function handleTest() {
		if (!validateForm()) return;
		testingConnection = true;
		try {
			await connectionService.test?.(buildPayload());
			message.success('Connection successful!');
		} catch (e: any) {
			message.error(e?.message || 'Connection failed');
		} finally { testingConnection = false; }
	}

	async function handleSave() {
		if (!validateForm()) return;
		savingConnection = true;
		try {
			await onsubmit(buildPayload());
		} catch (e: any) {
			message.error(e?.message || 'Save failed');
		} finally { savingConnection = false; }
	}

	let dbInfo = $derived(databaseMap[form.type]);
</script>

{#if step === 'picker'}
	<!-- ═══ DB Type Picker ═══ -->
	<div class="flex h-full">
		<!-- Category Sidebar -->
		<div class="w-44 shrink-0 border-r border-border/50 bg-card/30 flex flex-col pt-4">
			<div class="px-4 mb-4"><h2 class="text-sm font-semibold text-foreground/80">Categories</h2></div>
			<div class="space-y-1 px-2">
				{#each DB_CATEGORIES as cat}
					<button onclick={() => dbSelectedCategory = cat.id}
						class={cn('w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors',
							dbSelectedCategory === cat.id ? 'bg-primary/15 text-primary font-medium' : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground')}>
						<cat.icon class="w-4 h-4" />{cat.label}
					</button>
				{/each}
			</div>
		</div>
		<!-- DB Grid -->
		<div class="flex-1 flex flex-col bg-background/40">
			<div class="p-6 pb-2">
				<div class="relative w-full max-w-md">
					<Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
					<input type="text" placeholder="Search database..." autocomplete="one-time-code"
						class="flex h-10 w-full rounded-md border border-input bg-accent/20 pl-9 pr-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						bind:value={dbSearchQuery} />
				</div>
			</div>
			<div class="flex-1 overflow-auto p-6 pt-4">
				<div class="grid grid-cols-2 md:grid-cols-3 gap-4">
					{#each filteredDatabases as db (db.code)}
						<button onclick={() => handleSelectDatabase(db)}
							class="group relative flex flex-col items-center justify-center p-4 rounded-xl cursor-pointer transition-all duration-300 bg-card/40 border border-border/40 hover:border-primary/50 hover:bg-card/60 hover:shadow-lg hover:-translate-y-1">
							<div class="w-12 h-12 mb-3 flex items-center justify-center p-2 rounded-xl bg-background/50 shadow-inner group-hover:scale-110 transition-transform">
							{#if db.img?.startsWith('/')}
								<img src={db.img} alt={db.name} class={cn('w-full h-full object-contain', db.code === 'SQLSERVER' && 'scale-[2]')} />
								{:else}
									<Database class="h-6 w-6 text-muted-foreground" />
								{/if}
							</div>
							<span class="text-sm font-medium">{db.name}</span>
						</button>
					{/each}
					{#if filteredDatabases.length === 0}
						<div class="col-span-full flex flex-col items-center justify-center h-40 text-muted-foreground">
							<Search class="w-8 h-8 mb-2 opacity-30" /><p>No databases found</p>
						</div>
					{/if}
				</div>
			</div>
		</div>
	</div>

{:else}
	<!-- ═══ Connection Form ═══ -->
	<div class="flex flex-col h-full">
		<!-- Header -->
		<div class="flex items-center justify-between px-6 py-4 border-b border-border/40 shrink-0">
			<div class="flex items-center gap-3">
				<div class="flex items-center justify-center p-2 rounded-lg bg-card border border-border/50">
					{#if dbInfo?.img}
						<img src={dbInfo.img} alt={dbInfo.name} class="w-6 h-6 object-contain" />
					{:else}
						<Database class="w-6 h-6 text-primary" />
					{/if}
				</div>
				<div class="flex flex-col">
					<span class="font-semibold text-lg leading-tight">{dbInfo?.name || form.type}</span>
					<span class="text-xs text-muted-foreground">Connection Details</span>
				</div>
			</div>
		</div>

		<!-- Body -->
		<div class="flex flex-1 overflow-hidden">
			<!-- Sidebar Tabs -->
			<div class="w-44 border-r border-border/40 bg-card/20 flex flex-col py-6 gap-1 shrink-0">
				{#each sidebarTabs as tab}
					<button onclick={() => activeTab = tab.key}
						class="flex items-center gap-3 px-6 py-3 text-sm font-medium transition-all relative
							{activeTab === tab.key ? 'text-primary bg-primary/10' : 'text-muted-foreground hover:text-foreground hover:bg-accent/30'}">
						{#if activeTab === tab.key}<div class="absolute left-0 top-0 bottom-0 w-1 bg-primary rounded-r-full"></div>{/if}
						<tab.icon class="w-4 h-4 {activeTab === tab.key ? 'text-primary' : 'opacity-70'}" />
						{tab.label}
					</button>
				{/each}
			</div>

			<!-- Form Content -->
			<div class="flex-1 overflow-auto p-8">
				{#if activeTab === 'General'}
					<div class="mb-6">
						<h3 class="text-lg font-medium mb-1">General Settings</h3>
						<p class="text-sm text-muted-foreground">Configure the basic connection details.</p>
					</div>
					<div class="grid grid-cols-2 gap-4 max-w-3xl">

						<!-- Alias -->
						<div class="col-span-2 space-y-1.5">
							<label class="text-sm font-medium" for="cc-alias">Name <span class="text-destructive">*</span></label>
							<input id="cc-alias" bind:value={form.alias} autocomplete="one-time-code" oninput={onAliasInput}
								class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
								placeholder={dbInfo?.name || 'My Database'} />
							{#if formErrors.alias}<p class="text-xs text-destructive mt-1">{formErrors.alias}</p>{/if}
						</div>

						<!-- Environment -->
						<div class="col-span-2 space-y-1.5">
							<span class="text-sm font-medium">Environment</span>
							<Popover bind:open={envPopoverOpen}>
								<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
									<span class={form.environmentId ? '' : 'text-muted-foreground'}>
										{envList.find(e => String(e.id) === form.environmentId)?.name || 'Select environment'}
									</span>
									<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
								</PopoverTrigger>
								<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1 max-h-48 overflow-y-auto">
									<button class="flex w-full items-center rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors"
										onclick={() => { form.environmentId = ''; envPopoverOpen = false; }}>
										<span class="text-muted-foreground">None</span>
									</button>
									{#each envList as env}
										<button class="flex w-full items-center gap-2 rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {form.environmentId === String(env.id) ? 'bg-accent' : ''}"
											onclick={() => { form.environmentId = String(env.id); envPopoverOpen = false; }}>
											{env.name}
										</button>
									{/each}
								</PopoverContent>
							</Popover>
						</div>

						{#if form.type === 'SNOWFLAKE'}
							<!-- ═══ Snowflake ═══ -->
							<div class="col-span-2 space-y-1.5">
								<label class="text-sm font-medium" for="sf-account">Account <span class="text-destructive">*</span></label>
								<input id="sf-account" bind:value={form.account} autocomplete="one-time-code"
									oninput={(e) => { form.url = (e.target as HTMLInputElement).value ? `jdbc:snowflake://${(e.target as HTMLInputElement).value}.snowflakecomputing.com` : 'jdbc:snowflake://'; }}
									class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
									placeholder="myorg-account" />
								{#if formErrors.account}<p class="text-xs text-destructive mt-1">{formErrors.account}</p>{/if}
							</div>
							<div class="col-span-2 space-y-1.5">
								<span class="text-sm font-medium">Authentication</span>
								<Popover bind:open={authPopoverOpen}>
									<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50">
										<span>{authOptions.find(o => o.value === form.authenticationType)?.label || form.authenticationType}</span>
										<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
									</PopoverTrigger>
									<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1">
										{#each authOptions as opt}
											<button class="flex w-full items-center justify-between rounded-sm px-3 py-1.5 text-sm hover:bg-accent {form.authenticationType === opt.value ? 'bg-accent' : ''}"
												onclick={() => { form.authenticationType = opt.value as any; authPopoverOpen = false; }}>
												{opt.label}
												{#if form.authenticationType === opt.value}<Check class="h-3.5 w-3.5 text-primary" />{/if}
											</button>
										{/each}
									</PopoverContent>
								</Popover>
							</div>
							{#if form.authenticationType === 'USERANDPASSWORD'}
								<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-user">User *</label><input id="sf-user" bind:value={form.user} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-pass">Password *</label><input id="sf-pass" bind:value={form.password} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							{:else if form.authenticationType === 'KEYPAIR'}
								<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="sf-kp-user">User *</label><input id="sf-kp-user" bind:value={form.user} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="sf-pk">Private Key *</label><Textarea id="sf-pk" bind:value={form.privateKeyContent} rows={4} class="font-mono min-h-[100px]" placeholder="-----BEGIN PRIVATE KEY-----" /></div>
							{/if}
							<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-db">Database</label><input id="sf-db" bind:value={form.database} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="MY_DATABASE" /></div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-schema">Schema</label><input id="sf-schema" bind:value={form.schema} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="PUBLIC" /></div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-wh">Warehouse</label><input id="sf-wh" bind:value={form.warehouse} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="COMPUTE_WH" /></div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="sf-role">Role</label><input id="sf-role" bind:value={form.role} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="ANALYST_ROLE" /></div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="sf-url">URL *</label><input id="sf-url" bind:value={form.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>

						{:else if form.type === 'BIGQUERY'}
							<!-- ═══ BigQuery ═══ -->
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="bq-sa">Service Account JSON *</label><Textarea id="bq-sa" bind:value={form.serviceAccountJson} rows={6} class="font-mono min-h-[150px]" placeholder="Paste JSON key" />{#if formErrors.serviceAccountJson}<p class="text-xs text-destructive mt-1">{formErrors.serviceAccountJson}</p>{/if}</div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="bq-ds">Default Dataset</label><input id="bq-ds" bind:value={form.defaultDataset} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="bq-url">URL *</label><input id="bq-url" bind:value={form.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>

						{:else if form.type === 'DATABRICKS'}
							<!-- ═══ Databricks ═══ -->
							<div class="space-y-1.5"><label class="text-sm font-medium" for="dk-host">Host *</label><input id="dk-host" bind:value={form.host} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="adb-xxxxx.azuredatabricks.net" />{#if formErrors.host}<p class="text-xs text-destructive mt-1">{formErrors.host}</p>{/if}</div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="dk-port">Port *</label><input id="dk-port" bind:value={form.port} type="number" autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="443" /></div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="dk-http">HTTP Path *</label><input id="dk-http" bind:value={form.httpPath} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="/sql/1.0/warehouses/xxxxx" />{#if formErrors.httpPath}<p class="text-xs text-destructive mt-1">{formErrors.httpPath}</p>{/if}</div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="dk-token">Access Token *</label><input id="dk-token" bind:value={form.accessToken} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />{#if formErrors.accessToken}<p class="text-xs text-destructive mt-1">{formErrors.accessToken}</p>{/if}</div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="dk-cat">Catalog</label><input id="dk-cat" bind:value={form.catalog} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="dk-schema">Schema</label><input id="dk-schema" bind:value={form.schema} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="dk-url">URL *</label><input id="dk-url" bind:value={form.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>

						{:else}
							<!-- ═══ Standard DB ═══ -->
							{#if form.useUrl}
								<div class="col-span-2 space-y-1.5">
									<div class="flex items-center justify-between"><label class="text-sm font-medium" for="cc-url">URL</label><button type="button" class="text-xs text-primary" onclick={() => form.useUrl = false}>Use Host/Port</button></div>
									<input id="cc-url" bind:value={form.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="jdbc:mysql://localhost:3306/mydb" />
								</div>
							{:else}
								<div class="space-y-1.5">
									<div class="flex items-center justify-between"><label class="text-sm font-medium" for="cc-host">Host *</label><button type="button" class="text-xs text-primary" onclick={() => form.useUrl = true}>Use URL</button></div>
									<input id="cc-host" bind:value={form.host} autocomplete="one-time-code" oninput={onHostInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="localhost" />
									{#if formErrors.host}<p class="text-xs text-destructive mt-1">{formErrors.host}</p>{/if}
								</div>
								<div class="space-y-1.5"><label class="text-sm font-medium" for="cc-port">Port</label><input id="cc-port" bind:value={form.port} type="number" autocomplete="one-time-code" oninput={onPortInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							{/if}
							<div class="space-y-1.5"><label class="text-sm font-medium" for="cc-user">Username</label><input id="cc-user" bind:value={form.user} autocomplete="one-time-code" name="cc-user-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="root" /></div>
							<div class="space-y-1.5"><label class="text-sm font-medium" for="cc-pass">Password</label><input id="cc-pass" bind:value={form.password} type="password" autocomplete="new-password" name="cc-pass-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
							<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="cc-db">Database</label><input id="cc-db" bind:value={form.database} autocomplete="one-time-code" oninput={onDatabaseInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
						{/if}
					</div>

				{:else if activeTab === 'SSH'}
					<div class="mb-6"><h3 class="text-lg font-medium mb-1">SSH Tunnel</h3><p class="text-sm text-muted-foreground">Secure your connection via an SSH tunnel.</p></div>
					<div class="max-w-3xl space-y-4">
						<label class="flex items-center gap-3 text-sm font-medium cursor-pointer"><Switch bind:checked={form.ssh.use} />Use SSH Tunnel</label>
						{#if form.ssh.use}
							<div class="grid grid-cols-2 gap-4">
								<div class="space-y-1.5"><label class="text-sm font-medium" for="ssh-host">SSH Host</label><input id="ssh-host" bind:value={form.ssh.host} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								<div class="space-y-1.5"><label class="text-sm font-medium" for="ssh-port">SSH Port</label><input id="ssh-port" bind:value={form.ssh.port} type="number" autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="22" /></div>
								<div class="space-y-1.5"><label class="text-sm font-medium" for="ssh-user">SSH User</label><input id="ssh-user" bind:value={form.ssh.user} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								<div class="space-y-1.5">
									<span class="text-sm font-medium">Auth Type</span>
									<Popover bind:open={sshAuthPopoverOpen}>
										<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50">
											<span>{sshAuthOptions.find(o => o.value === form.ssh.authType)?.label || form.ssh.authType}</span>
											<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1">
											{#each sshAuthOptions as opt}
												<button class="flex w-full items-center justify-between rounded-sm px-3 py-1.5 text-sm hover:bg-accent {form.ssh.authType === opt.value ? 'bg-accent' : ''}"
													onclick={() => { form.ssh.authType = opt.value; sshAuthPopoverOpen = false; }}>{opt.label}{#if form.ssh.authType === opt.value}<Check class="h-3.5 w-3.5 text-primary" />{/if}</button>
											{/each}
										</PopoverContent>
									</Popover>
								</div>
								{#if form.ssh.authType === 'password'}
									<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="ssh-pass">SSH Password</label><input id="ssh-pass" bind:value={form.ssh.password} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								{:else}
									<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="ssh-key">Private Key Path</label><input id="ssh-key" bind:value={form.ssh.keyFile} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="~/.ssh/id_rsa" /></div>
									<div class="col-span-2 space-y-1.5"><label class="text-sm font-medium" for="ssh-phrase">Passphrase</label><input id="ssh-phrase" bind:value={form.ssh.passphrase} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
								{/if}
							</div>
						{/if}
					</div>

				{:else if activeTab === 'Advanced'}
					<div class="mb-6"><h3 class="text-lg font-medium mb-1">Advanced Properties</h3><p class="text-sm text-muted-foreground">Additional connection properties.</p></div>
					<div class="max-w-3xl space-y-4">
						<div class="space-y-1.5"><label class="text-sm font-medium" for="cc-extra">Extra Properties</label><Textarea id="cc-extra" bind:value={form.extendInfo} class="font-mono min-h-[120px]" placeholder="key1=value1&#10;key2=value2" /></div>
					</div>
				{/if}
			</div>
		</div>

		<!-- Footer -->
		<div class="border-t border-border/40 p-4 shrink-0 bg-card/20 flex justify-between items-center px-8">
			<Button variant="outline" onclick={handleTest} disabled={testingConnection}>
				{#if testingConnection}<Loader2 class="mr-2 h-4 w-4 animate-spin" />{/if}
				{testingConnection ? 'Testing...' : 'Test Connection'}
			</Button>
			<div class="flex gap-3">
				<Button variant="ghost" onclick={() => { if (isEdit) { oncancel?.(); } else { step = 'picker'; } }}>Cancel</Button>
				<Button onclick={handleSave} disabled={savingConnection} class="min-w-[100px]">
					{#if savingConnection}<Loader2 class="mr-2 h-4 w-4 animate-spin" />{/if}
					{savingConnection ? 'Saving...' : (isEdit ? 'Update' : 'Connect')}
				</Button>
			</div>
		</div>
	</div>
{/if}
