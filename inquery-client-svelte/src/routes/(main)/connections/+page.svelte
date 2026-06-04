<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import i18n from '$lib/i18n';
	import { cn } from '$lib/utils/cn';
	import { Button, Card, Spinner, Textarea, Checkbox, Switch, DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, Popover, PopoverTrigger, PopoverContent } from '$lib/components/ui';
	import connectionService from '$lib/service/connection';
	import historyService from '$lib/service/history';
	import { databaseMap, DatabaseTypeCode, databaseTypeList } from '$lib/types/database';
	import { Plus, MoreVertical, Play, Edit2, Copy, Trash2, Database, X, Shield, Settings, Search, Upload, LayoutGrid, Server, Cloud, Disc, Download, FileUp, Loader2, ChevronDown, Check } from 'lucide-svelte';
	import { setCurrentConnection, setPrefetchCache } from '$lib/stores/workspace.svelte';
	import message from '$lib/utils/message';
	import confirmDialog from '$lib/utils/confirmDialog';

	let connections = $state<any[]>([]);
	let loading = $state(true);
	let showCreateDialog = $state(false);
	let showImportDialog = $state(false);
	let editingConnection = $state<any>(null);
	// (bits-ui DropdownMenu/Popover handles open/close state internally)

	// Form Popover states
	let envPopoverOpen = $state(false);
	let authPopoverOpen = $state(false);
	let sshAuthPopoverOpen = $state(false);
	let driverPopoverOpen = $state(false);

	// Import
	let importFile = $state<File | null>(null);
	let importing = $state(false);
	let importError = $state('');

	// Driver
	let driverList = $state<{ jdbcDriver: string; jdbcDriverClass: string }[]>([]);
	let driverLoading = $state(false);
	let selectedDriver = $state('');
	let selectedDriverClass = $state('');
	let driverDownloadStatus = $state<'default' | 'loading' | 'error' | 'success'>('default');
	let showUploadDriverModal = $state(false);
	let uploadDriverClass = $state('');
	let uploadDriverFile = $state<File | null>(null);
	let uploadingDriver = $state(false);

	// Environments
	let envList = $state<{ id: number; name: string; shortName: string; color: string }[]>([]);

	// ─── DB Picker (step 1 of modal) ───
	let modalStep = $state<'picker' | 'form'>('picker');
	let dbSearchQuery = $state('');
	let dbSelectedCategory = $state('ALL');

	const DB_CATEGORIES = [
		{ id: 'ALL', label: 'All', icon: LayoutGrid },
		{ id: 'RDB', label: 'Relational', icon: Database },
		{ id: 'NOSQL', label: 'NoSQL', icon: Server },
		{ id: 'BIGDATA', label: 'Big Data / Cloud', icon: Cloud }
	];

	// URL templates per DB type (same as React's regEXFormatting)
	const URL_TEMPLATES: Record<string, string> = {
		MYSQL: 'jdbc:mysql://{host}:{port}/{database}',
		POSTGRESQL: 'jdbc:postgresql://{host}:{port}/{database}',
		ORACLE: 'jdbc:oracle:{driver}:@{host}:{port}:{sid}',
		H2: 'jdbc:h2:tcp://{host}:{port}/{database}',
		SQLSERVER: 'jdbc:sqlserver://{host}:{port};database={database}',
		SQLITE: 'jdbc:sqlite:{file}',
		MARIADB: 'jdbc:mariadb://{host}:{port}/{database}',
		CLICKHOUSE: 'jdbc:clickhouse://{host}:{port}/{database}',
		DM: 'jdbc:dm://{host}:{port}/{database}',
		DB2: 'jdbc:db2://{host}:{port}/{database}',
		PRESTO: 'jdbc:presto://{host}:{port}/{database}',
		OCEANBASE: 'jdbc:oceanbase://{host}:{port}/{database}',
		REDIS: 'jdbc:redis://{host}:{port}/{database}',
		HIVE: 'jdbc:hive2://{host}:{port}/{database}',
		KINGBASE: 'jdbc:kingbase8://{host}:{port}/{database}',
		MONGODB: 'mongodb://{host}:{port}/{database}',
		TIMEPLUS: 'jdbc:timeplus://{host}:{port}/{database}',
		DATABRICKS: 'jdbc:databricks://{host}:{port}',
	};

	const authOptions = [
		{ value: 'USERANDPASSWORD', label: 'User & Password' },
		{ value: 'KEYPAIR', label: 'Key-Pair' },
		{ value: 'NONE', label: 'NONE' }
	];

	const sshAuthOptions = [
		{ value: 'password', label: 'Password' },
		{ value: 'key', label: 'Private Key' }
	];

	let aliasManuallyChanged = false;

	function buildUrlFromFields() {
		const tpl = URL_TEMPLATES[connForm.type];
		if (!tpl) return;
		let url = tpl;
		url = url.replace('{host}', connForm.host || '');
		url = url.replace('{port}', connForm.port || '');
		url = url.replace('{database}', connForm.database || '');
		url = url.replace('{driver}', (connForm as any).oracleDriver || 'thin');
		url = url.replace('{sid}', (connForm as any).sid || '');
		url = url.replace('{file}', connForm.database || '');
		connForm.url = url;
	}

	function onHostInput(e: Event) {
		const val = (e.target as HTMLInputElement).value;
		if (!aliasManuallyChanged) {
			connForm.alias = '@' + val;
		}
		buildUrlFromFields();
	}

	function onPortInput() { buildUrlFromFields(); }
	function onDatabaseInput() { buildUrlFromFields(); }
	function onAliasInput() { aliasManuallyChanged = true; }

	let filteredDatabases = $derived(
		databaseTypeList.filter(db => {
			const matchesSearch = db.name.toLowerCase().includes(dbSearchQuery.toLowerCase());
			const matchesCategory = dbSelectedCategory === 'ALL' || db.category === dbSelectedCategory;
			return matchesSearch && matchesCategory;
		})
	);

	function handleSelectDatabase(db: typeof databaseTypeList[0]) {
		connForm.type = db.code;
		connForm.alias = db.name;
		if (db.port) connForm.port = String(db.port);
		// Set defaults for special DB types
		if (db.code === 'SNOWFLAKE') {
			connForm.authenticationType = 'KEYPAIR';
			connForm.url = 'jdbc:snowflake://';
		} else if (db.code === 'BIGQUERY') {
			connForm.url = 'jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443';
		} else if (db.code === 'DATABRICKS') {
			connForm.url = 'jdbc:databricks://';
			connForm.port = '443';
		} else if (db.code === 'ORACLE') {
			connForm.serviceType = 'SID';
			connForm.oracleDriver = 'thin';
		} else if (db.code === 'SQLSERVER') {
			connForm.port = '1433';
		} else if (db.code === 'MONGODB') {
			connForm.url = 'mongodb://';
			connForm.port = '27017';
		} else if (db.code === 'REDIS') {
			connForm.port = '6379';
		} else if (db.code === 'SQLITE') {
			connForm.useUrl = false;
		}
		modalStep = 'form';
		loadDrivers(db.code);
	}

	async function loadDrivers(dbType: string) {
		driverLoading = true;
		try {
			const res = await connectionService.getDriverList({ dbType });
			const data = res as any;
			driverList = data?.driverConfigList || [];
			if (data?.defaultDriverConfig?.jdbcDriverClass) {
				selectedDriverClass = data.defaultDriverConfig.jdbcDriverClass;
			}
			if (driverList.length > 0 && !selectedDriver) {
				selectedDriver = driverList[0].jdbcDriver;
				selectedDriverClass = driverList[0].jdbcDriverClass;
			}
		} catch { driverList = []; }
		finally { driverLoading = false; }
	}

	// A driver config may bundle many JARs (e.g. BigQuery ships ~60). Show a short,
	// readable label instead of the full comma-separated jar list.
	function driverLabel(jdbcDriver: string): string {
		if (!jdbcDriver) return '';
		const jars = jdbcDriver.split(',').map((s) => s.trim()).filter(Boolean);
		if (jars.length <= 1) return jars[0] || jdbcDriver;
		return `${jars[0]} (+${jars.length - 1} more)`;
	}

	async function loadEnvironments() {
		try {
			const res = await connectionService.getEnvList?.();
			envList = (res as any) || [];
		} catch { envList = []; }
	}

	// Connection form
	let connFormTab = $state('General');
	let connForm = $state({
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
		// Snowflake specific
		account: '',
		authenticationType: 'KEYPAIR' as 'USERANDPASSWORD' | 'KEYPAIR' | 'NONE',
		privateKeyContent: '',
		warehouse: '',
		role: '',
		schema: '',
		// BigQuery specific
		projectId: '',
		serviceAccountJson: '',
		defaultDataset: '',
		// Databricks specific
		httpPath: '',
		accessToken: '',
		catalog: '',
		// Oracle specific
		serviceType: 'SID' as 'SID' | 'SERVICE',
		sid: '',
		serviceName: '',
		oracleDriver: 'thin' as 'thin' | 'oci' | 'oci8',
		// SQL Server specific
		instanceName: '',
		// SSH
		ssh: {
			use: false,
			host: '',
			port: '22',
			user: '',
			authType: 'password',
			password: '',
			keyFile: '',
			passphrase: ''
		},
		// Driver config
		driverConfig: {
			jdbcDriver: '',
			jdbcDriverClass: ''
		}
	});
	let connFormErrors = $state<Record<string, string>>({});
	let testingConnection = $state(false);
	let savingConnection = $state(false);

	function resetConnForm() {
		connFormTab = 'General';
		connForm = {
			type: 'MYSQL', alias: '', host: '', port: '', user: '', password: '',
			database: '', url: '', useUrl: false, environmentId: '', extendInfo: '',
			account: '', authenticationType: 'KEYPAIR', privateKeyContent: '', warehouse: '', role: '', schema: '',
			projectId: '', serviceAccountJson: '', defaultDataset: '',
			httpPath: '', accessToken: '', catalog: '',
			serviceType: 'SID', sid: '', serviceName: '', oracleDriver: 'thin',
			instanceName: '',
			ssh: { use: false, host: '', port: '22', user: '', authType: 'password', password: '', keyFile: '', passphrase: '' },
			driverConfig: { jdbcDriver: '', jdbcDriverClass: '' }
		};
		connFormErrors = {};
		driverList = [];
		selectedDriver = '';
		selectedDriverClass = '';
		driverDownloadStatus = 'default';
		aliasManuallyChanged = false;
	}

	function validateConnForm(): boolean {
		const errors: Record<string, string> = {};
		if (!connForm.alias.trim()) errors.alias = 'Name is required';

		if (connForm.type === 'SNOWFLAKE') {
			if (!connForm.account.trim()) errors.account = 'Account is required';
			if (!connForm.url.trim()) errors.url = 'URL is required';
		} else if (connForm.type === 'BIGQUERY') {
			if (!connForm.serviceAccountJson.trim() && !editingConnection?.id) {
				errors.serviceAccountJson = 'Service Account JSON is required';
			}
		} else if (connForm.type === 'DATABRICKS') {
			if (!connForm.host.trim()) errors.host = 'Host is required';
			if (!connForm.httpPath.trim()) errors.httpPath = 'HTTP Path is required';
			if (!connForm.accessToken.trim() && !editingConnection?.id) {
				errors.accessToken = 'Access Token is required';
			}
		} else if (connForm.type === 'ORACLE') {
			if (!connForm.useUrl && !connForm.host.trim()) errors.host = 'Host is required';
			if (connForm.serviceType === 'SID' && !connForm.sid.trim()) errors.sid = 'SID is required';
			if (connForm.serviceType === 'SERVICE' && !connForm.serviceName.trim()) errors.serviceName = 'Service Name is required';
		} else if (connForm.type === 'REDIS') {
			if (!connForm.useUrl && !connForm.host.trim()) errors.host = 'Host is required';
		} else if (connForm.type === 'SQLITE') {
			if (!connForm.database.trim()) errors.database = 'File path is required';
		} else {
			if (!connForm.useUrl && !connForm.host.trim()) errors.host = 'Host is required';
			if (!connForm.user.trim()) errors.user = 'Username is required';
		}

		connFormErrors = errors;
		return Object.keys(errors).length === 0;
	}

	async function handleTestConnection() {
		if (!validateConnForm()) return;
		testingConnection = true;
		try {
			// Build test payload same as save payload
			const testPayload: any = {
				type: connForm.type,
				host: connForm.host || undefined,
				port: connForm.port ? Number(connForm.port) : undefined,
				user: connForm.user || undefined,
				password: connForm.password || undefined,
				database: connForm.database || undefined,
				url: connForm.url || undefined,
				environmentId: connForm.environmentId ? Number(connForm.environmentId) : undefined,
				ssh: connForm.ssh.use ? connForm.ssh : undefined,
				driverConfig: (connForm.driverConfig.jdbcDriver || connForm.driverConfig.jdbcDriverClass)
					? connForm.driverConfig : undefined
			};
			// Snowflake: account as host
			if (connForm.type === 'SNOWFLAKE' && connForm.account) {
				testPayload.host = connForm.account;
			}
			// Include extendInfo for special types
			const extendInfo: { key: string; value: string }[] = [];
			if (connForm.type === 'SNOWFLAKE') {
				for (const f of ['warehouse', 'role', 'schema', 'authenticationType', 'privateKeyContent']) {
					const val = (connForm as any)[f];
					if (val) extendInfo.push({ key: f, value: val });
				}
			} else if (connForm.type === 'BIGQUERY') {
				for (const f of ['serviceAccountJson', 'defaultDataset']) {
					const val = (connForm as any)[f];
					if (val) extendInfo.push({ key: f, value: val });
				}
			} else if (connForm.type === 'DATABRICKS') {
				for (const f of ['httpPath', 'accessToken', 'catalog', 'schema']) {
					const val = (connForm as any)[f];
					if (val) extendInfo.push({ key: f, value: val });
				}
			}
			if (extendInfo.length > 0) testPayload.extendInfo = extendInfo;

			if (editingConnection?.id) testPayload.id = editingConnection.id;

			await connectionService.test?.(testPayload);
			message.success('Connection successful!');
		} catch (e: any) {
			message.error(e?.message || 'Connection failed');
		} finally {
			testingConnection = false;
		}
	}

	async function handleSaveConnection() {
		if (!validateConnForm()) return;
		savingConnection = true;
		try {
			const extendInfo: { key: string; value: string }[] = [];

			// Snowflake-specific fields → extendInfo
			if (connForm.type === 'SNOWFLAKE') {
				const sfFields = ['warehouse', 'role', 'schema', 'authenticationType', 'privateKeyContent'];
				for (const f of sfFields) {
					const val = (connForm as any)[f];
					if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
				}
			}
			// BigQuery-specific fields → extendInfo
			if (connForm.type === 'BIGQUERY') {
				const bqFields = ['serviceAccountJson', 'defaultDataset'];
				for (const f of bqFields) {
					const val = (connForm as any)[f];
					if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
				}
			}
			// Databricks-specific fields → extendInfo
			if (connForm.type === 'DATABRICKS') {
				const dkFields = ['httpPath', 'accessToken', 'catalog', 'schema'];
				for (const f of dkFields) {
					const val = (connForm as any)[f];
					if (val !== undefined && val !== '') extendInfo.push({ key: f, value: val });
				}
			}

			const payload: any = {
				type: connForm.type,
				alias: connForm.alias,
				host: connForm.host || undefined,
				port: connForm.port ? Number(connForm.port) : undefined,
				user: connForm.user || undefined,
				password: connForm.password || undefined,
				database: connForm.database || undefined,
				url: connForm.url || undefined,
				environmentId: connForm.environmentId ? Number(connForm.environmentId) : undefined,
				extendInfo: extendInfo.length > 0 ? extendInfo : (connForm.extendInfo || undefined),
				ssh: connForm.ssh.use ? connForm.ssh : undefined,
				driverConfig: (connForm.driverConfig.jdbcDriver || connForm.driverConfig.jdbcDriverClass)
					? connForm.driverConfig : undefined
			};

			// Snowflake uses account instead of host
			if (connForm.type === 'SNOWFLAKE' && connForm.account) {
				payload.host = connForm.account;
			}
			if (editingConnection?.id) {
				payload.id = editingConnection.id;
				await connectionService.update(payload);
			} else {
				const newId = await connectionService.save(payload);
				if (newId) {
					// Attach the data source on the backend: triggers JDBC validation +
					// metadata cache warmup + lineage auto-detect in background.
					// Fire-and-forget: navigation/UI must not wait on detection.
					connectionService.connect({ id: newId }).catch(() => {});

					// Connection created — prefetch workspace data in background
					const dbListPromise = connectionService.getDatabaseList({
						dataSourceId: newId, refresh: false
					});
					const consoleListPromise = historyService.getConsoleList({
						tabOpened: 'y', pageNo: 1, pageSize: 20
					});
					const savedConsoleListPromise = historyService.getConsoleList({
						status: 'RELEASE', pageNo: 1, pageSize: 100
					});
					setPrefetchCache({
						connectionId: newId,
						dbListPromise,
						consoleListPromise,
						savedConsoleListPromise,
						timestamp: Date.now()
					});
				}
			}
			handleCloseModal();
			resetConnForm();
			await loadConnections();
		} catch (e: any) {
			message.error(e?.message || 'Save failed');
		} finally {
			savingConnection = false;
		}
	}

	onMount(() => {
		loadConnections();
		loadEnvironments();
	});

	async function loadConnections() {
		loading = true;
		try {
			const res = await connectionService.getList({});
			connections = (res as any)?.data || [];
		} catch { connections = []; }
		finally { loading = false; }
	}

	function handleConnect(item: any) {
		setCurrentConnection(item);
		// Attach the data source on the backend: triggers JDBC validation +
		// metadata cache warmup + lineage auto-detect in background.
		// Fire-and-forget so navigation isn't blocked by lineage detection.
		connectionService.connect({ id: item.id }).catch(() => {});
		// If no prefetch cache exists yet (e.g. clicking an existing connection),
		// start prefetch now for the page transition
		setPrefetchCache({
			connectionId: item.id,
			connectionList: connections,
			dbListPromise: connectionService.getDatabaseList({ dataSourceId: item.id, refresh: false }),
			consoleListPromise: historyService.getConsoleList({ tabOpened: 'y', pageNo: 1, pageSize: 20 }),
			savedConsoleListPromise: historyService.getConsoleList({ status: 'RELEASE', pageNo: 1, pageSize: 100 }),
			timestamp: Date.now()
		});
		goto('/workspace');
	}

	async function handleDelete(item: any) {
		const confirmed = await confirmDialog({
			title: 'Delete Connection',
			message: `Are you sure you want to delete "${item.alias}"?`,
			confirmText: 'Delete',
			variant: 'destructive'
		});
		if (!confirmed) return;
		await connectionService.remove({ id: item.id });
		await loadConnections();
	}

	async function handleCopy(item: any) {
		await connectionService.clone({ id: item.id });
		await loadConnections();
	}

	async function handleEdit(item: any) {
		editingConnection = item;
		resetConnForm();
		modalStep = 'form';
		showCreateDialog = true;

		// Load full details from API (like React's getDetails)
		try {
			const detail = await connectionService.getDetails({ id: item.id }) as any;
			const data = detail || item;
			connForm.type = data.type || 'MYSQL';
			connForm.alias = data.alias || '';
			connForm.host = data.host || '';
			connForm.port = data.port ? String(data.port) : '';
			connForm.user = data.user || '';
			connForm.password = data.password || '';
			connForm.database = data.database || '';
			connForm.url = data.url || '';
			connForm.environmentId = data.environmentId ? String(data.environmentId) : '';
			aliasManuallyChanged = true; // Don't auto-overwrite alias on edit

			// SSH config
			if (data.ssh) {
				connForm.ssh = { ...connForm.ssh, ...data.ssh, use: true };
			}

			// Driver config
			if (data.driverConfig) {
				connForm.driverConfig = { ...data.driverConfig };
				selectedDriver = data.driverConfig.jdbcDriver || '';
				selectedDriverClass = data.driverConfig.jdbcDriverClass || '';
			}

			// Restore extendInfo fields for special DB types
			if (data.extendInfo && Array.isArray(data.extendInfo)) {
				for (const ext of data.extendInfo) {
					if (ext.key && ext.value !== undefined) {
						if (['warehouse', 'role', 'schema', 'authenticationType', 'privateKeyContent', 'account'].includes(ext.key)) {
							(connForm as any)[ext.key] = ext.value;
						} else if (['serviceAccountJson', 'defaultDataset'].includes(ext.key)) {
							(connForm as any)[ext.key] = ext.value;
						} else if (['httpPath', 'accessToken', 'catalog'].includes(ext.key)) {
							(connForm as any)[ext.key] = ext.value;
						}
					}
				}
			}

			editingConnection = data; // Update with full details
			loadDrivers(connForm.type);
		} catch {
			// Fallback to list item data if API fails
			connForm.type = item.type || 'MYSQL';
			connForm.alias = item.alias || '';
			connForm.host = item.host || '';
			connForm.port = item.port ? String(item.port) : '';
			connForm.user = item.user || '';
			connForm.database = item.database || '';
			connForm.url = item.url || '';
			loadDrivers(connForm.type);
		}
	}

	function handleAddNew() {
		editingConnection = null;
		resetConnForm();
		dbSearchQuery = '';
		dbSelectedCategory = 'ALL';
		modalStep = 'picker';
		showCreateDialog = true;
	}

	function handleCloseModal() {
		showCreateDialog = false;
		editingConnection = null;
	}

	function getDbInfo(type: string) {
		return databaseMap[type] || { name: type, img: '' };
	}

	function getEnvColor(item: any) {
		return item.environment?.color?.toLowerCase() || '#888';
	}

</script>

<div class="flex flex-col h-full w-full p-6 bg-background/50 overflow-auto">
	<!-- Header -->
	<div class="flex flex-col mb-8">
		<h1 class="text-3xl font-bold tracking-tight text-foreground bg-clip-text text-transparent bg-gradient-to-r from-foreground to-foreground/70">
			{i18n('connection.title.connections')}
		</h1>
		<p class="text-muted-foreground mt-1">
			Manage your database connections.
			<span class="ml-2 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-primary/10 text-primary">
				{connections.length} Active
			</span>
		</p>
	</div>

	{#if loading}
		<div class="flex items-center justify-center py-20">
			<Spinner size="lg" class="text-primary" />
		</div>
	{:else}
		<!-- Grid Layout -->
		<div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6 pb-10">
			<!-- Add New Card -->
			<button
				onclick={handleAddNew}
				class={cn(
					'group relative flex flex-col items-center justify-center p-6 rounded-xl cursor-pointer transition-all duration-300',
					'border-2 border-dashed border-border/50 hover:border-primary/50',
					'bg-card/20 hover:bg-card/40 active:scale-[0.98]',
					'min-h-[200px] h-full text-left'
				)}
			>
				<div class="relative mb-4 flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 group-hover:bg-primary/20 transition-colors">
					<Plus class="w-8 h-8 text-primary/70 group-hover:text-primary transition-colors" />
					<div class="absolute inset-0 rounded-full bg-primary/20 blur-md opacity-0 group-hover:opacity-100 transition-opacity"></div>
				</div>
				<h3 class="text-base font-semibold text-foreground/80 group-hover:text-foreground transition-colors">
					{i18n('connection.button.addConnection') || 'Add Connection'}
				</h3>
				<p class="text-xs text-muted-foreground mt-1 text-center">
					Connect to a new database
				</p>
			</button>

			<!-- Connection Cards -->
			{#each connections as item (item.id)}
				{@const dbInfo = getDbInfo(item.type)}
				{@const envColor = getEnvColor(item)}
				<div
					onclick={() => handleConnect(item)}
					role="button"
					tabindex="0"
					onkeydown={(e) => { if (e.key === 'Enter') handleConnect(item); }}
					class={cn(
						'group relative flex flex-col p-5 rounded-xl transition-all duration-300',
						'border border-border/40 hover:border-primary/30',
						'bg-gradient-to-br from-card/40 to-card/10 backdrop-blur-md',
						'hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1',
						'cursor-pointer h-full justify-between'
					)}
				>
					<!-- Background Gradient Effect -->
					<div
						class="absolute inset-0 rounded-xl opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none"
						style="background: radial-gradient(circle at top right, {envColor}15, transparent 70%)"
					></div>

					<div class="relative z-10">
						<div class="flex justify-between items-start mb-4">
							<!-- Status Badge -->
							<div class="flex items-center gap-1.5 px-2 py-1 rounded-full bg-background/30 border border-white/5 backdrop-blur-sm">
								<span class="w-2 h-2 rounded-full shadow-[0_0_8px_currentColor]" style="color: {envColor}; background-color: {envColor}"></span>
								<span class="text-[10px] uppercase font-bold tracking-wider text-muted-foreground/80">
									{item.environment?.name || 'Env'}
								</span>
							</div>

						<!-- Dropdown Menu -->
						<div onclick={(e) => e.stopPropagation()} role="none">
							<DropdownMenu>
								<DropdownMenuTrigger class="flex h-7 w-7 items-center justify-center rounded-md opacity-0 group-hover:opacity-100 transition-opacity hover:bg-accent">
									<MoreVertical class="h-4 w-4" />
								</DropdownMenuTrigger>
								<DropdownMenuContent>
									<DropdownMenuItem onSelect={() => handleConnect(item)}>
										<Play class="h-3.5 w-3.5" />
										{i18n('connection.button.connect')}
									</DropdownMenuItem>
									<DropdownMenuItem onSelect={() => handleEdit(item)}>
										<Edit2 class="h-3.5 w-3.5" />
										Edit
									</DropdownMenuItem>
									<DropdownMenuItem onSelect={() => handleCopy(item)}>
										<Copy class="h-3.5 w-3.5" />
										{i18n('common.button.copy')}
									</DropdownMenuItem>
									<DropdownMenuSeparator />
									<DropdownMenuItem destructive onSelect={() => handleDelete(item)}>
										<Trash2 class="h-3.5 w-3.5" />
										{i18n('connection.button.remove') || 'Remove'}
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</div>
						</div>

						<!-- Logo & Name -->
						<div class="flex flex-col items-center text-center mt-2 mb-4">
							<div class="h-10 w-10 relative flex items-center justify-center bg-card/50 rounded-lg p-2 ring-1 ring-border/50 group-hover:ring-primary/20 transition-all">
								{#if dbInfo.img}
									<img
										src={dbInfo.img}
										alt={item.alias}
										class={cn(
											'w-full h-full object-contain',
											item.type === DatabaseTypeCode.SQLSERVER && 'scale-[2]'
										)}
									/>
								{:else}
									<Database class="w-8 h-8 text-foreground/80" />
								{/if}
							</div>
							<h3 class="text-lg font-semibold text-foreground tracking-tight line-clamp-1 w-full px-2" title={item.alias}>
								{item.alias}
							</h3>
							<p class="text-xs text-muted-foreground font-medium mt-1">
								{dbInfo.name}
							</p>
						</div>
					</div>

					<!-- Footer -->
					<div class="relative z-10 pt-3 border-t border-white/5 flex items-center justify-center">
						<div class="flex items-center gap-1.5 text-xs text-muted-foreground/60">
							<span class="truncate max-w-[150px]">{item.user || 'Unknown User'}</span>
						</div>
					</div>
				</div>
			{/each}
		</div>

		<!-- Empty State -->
		{#if connections.length === 0}
			<div class="flex flex-col items-center justify-center py-20 text-center">
				<Database class="h-12 w-12 text-muted-foreground/30 mb-4" />
				<p class="text-muted-foreground">No connections yet</p>
				<Button class="mt-4" onclick={handleAddNew}>
					{i18n('connection.button.addConnection') || 'Add Connection'}
				</Button>
			</div>
		{/if}
	{/if}
</div>

<!-- Create/Edit Connection Dialog -->
{#if showCreateDialog}
	{@const isEdit = !!editingConnection?.id}
	{@const dbInfo = connForm.type ? databaseMap[connForm.type] : null}
	{@const sidebarTabs = [
		{ key: 'General', label: 'General', icon: Database },
		{ key: 'SSH', label: 'SSH Tunnel', icon: Shield },
		{ key: 'Advanced', label: 'Advanced', icon: Settings },
		{ key: 'Driver', label: 'Driver', icon: Disc }
	]}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/80" role="dialog" tabindex="-1" onclick={(e) => { if (e.target === e.currentTarget) handleCloseModal(); }} onkeydown={(e) => { if (e.key === 'Escape') handleCloseModal(); }}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="w-full max-w-4xl h-[90vh] bg-background border border-border/50 rounded-xl overflow-hidden shadow-2xl flex flex-col" role="document" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()}>

			{#if modalStep === 'picker' && !isEdit}
				<!-- ══════════════════════════════════════ -->
				<!-- Step 1: Database Type Picker           -->
				<!-- ══════════════════════════════════════ -->
				<div class="flex w-full h-full">
					<!-- Category Sidebar -->
					<div class="w-48 shrink-0 border-r border-border/50 bg-card/30 flex flex-col pt-4">
						<div class="px-4 mb-4">
							<h2 class="text-sm font-semibold text-foreground/80 mb-2">Categories</h2>
						</div>
						<div class="space-y-1 px-2">
							{#each DB_CATEGORIES as cat}
								<button
									onclick={() => dbSelectedCategory = cat.id}
									class={cn(
										'w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors',
										dbSelectedCategory === cat.id
											? 'bg-primary/15 text-primary font-medium'
											: 'text-muted-foreground hover:bg-accent/50 hover:text-foreground'
									)}
								>
									<cat.icon class="w-4 h-4" />
									{cat.label}
								</button>
							{/each}
						</div>
					</div>

					<!-- Main Content -->
					<div class="flex-1 flex flex-col bg-background/40">
						<!-- Search Header -->
						<div class="flex items-center justify-between p-6 pb-2">
							<div class="relative w-full max-w-md">
								<Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
								<input
									type="text"
									placeholder="Search database..."
									class="flex h-10 w-full rounded-md border border-input bg-accent/20 pl-9 pr-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus:bg-background transition-all"
									bind:value={dbSearchQuery}
									autocomplete="one-time-code"
								/>
							</div>
							<button
								onclick={() => {/* TODO: Import modal */}}
								class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-accent/50 rounded-md transition-colors"
							>
								<Upload class="w-4 h-4" />
								Import
							</button>
						</div>

						<!-- Database Grid -->
						<div class="flex-1 overflow-auto p-6 pt-4">
							<div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
								{#each filteredDatabases as db (db.code)}
									<button
										onclick={() => handleSelectDatabase(db)}
										class={cn(
											'group relative flex flex-col items-center justify-center p-4 rounded-xl cursor-pointer transition-all duration-300',
											'bg-card/40 border border-border/40 hover:border-primary/50',
											'hover:bg-card/60 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1'
										)}
									>
										<div class="w-12 h-12 mb-3 flex items-center justify-center p-2 rounded-xl bg-background/50 shadow-inner group-hover:scale-110 transition-transform">
											{#if db.img?.startsWith('/')}
												<img
													src={db.img}
													alt={db.name}
													class={cn(
														'w-full h-full object-contain',
														db.code === 'SQLSERVER' && 'scale-[2]'
													)}
												/>
											{:else}
												<Database class="h-6 w-6 text-muted-foreground" />
											{/if}
										</div>
										<span class="text-sm font-medium text-foreground/90 group-hover:text-foreground">{db.name}</span>
										<div class="absolute inset-0 rounded-xl bg-gradient-to-tr from-primary/5 to-transparent opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none"></div>
									</button>
								{/each}

								{#if filteredDatabases.length === 0}
									<div class="col-span-full flex flex-col items-center justify-center h-40 text-muted-foreground">
										<Search class="w-8 h-8 mb-2 opacity-30" />
										<p>No databases found matching "{dbSearchQuery}"</p>
									</div>
								{/if}
							</div>
						</div>
					</div>
				</div>

			{:else}
				<!-- ══════════════════════════════════════ -->
				<!-- Step 2: Connection Form                -->
				<!-- ══════════════════════════════════════ -->

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
							<span class="font-semibold text-lg leading-tight">{dbInfo?.name || connForm.type}</span>
							<span class="text-xs text-muted-foreground">Connection Details</span>
						</div>
					</div>
					<button onclick={handleCloseModal} class="p-2 -mr-2 rounded-md hover:bg-accent/50 text-muted-foreground hover:text-foreground transition-colors">
						<X class="w-5 h-5" />
					</button>
				</div>

				<!-- Body: Sidebar + Content -->
				<div class="flex flex-1 overflow-hidden">
					<!-- Vertical Sidebar Tabs -->
					<div class="w-48 border-r border-border/40 bg-card/20 flex flex-col py-6 gap-1 shrink-0">
						{#each sidebarTabs as tab}
							<button
								onclick={() => connFormTab = tab.key}
								class="flex items-center gap-3 px-6 py-3 text-sm font-medium transition-all relative
									{connFormTab === tab.key ? 'text-primary bg-primary/10' : 'text-muted-foreground hover:text-foreground hover:bg-accent/30'}"
							>
								{#if connFormTab === tab.key}
									<div class="absolute left-0 top-0 bottom-0 w-1 bg-primary rounded-r-full"></div>
								{/if}
								<tab.icon class="w-4 h-4 {connFormTab === tab.key ? 'text-primary' : 'opacity-70'}" />
								{tab.label}
							</button>
						{/each}
					</div>

					<!-- Form Content -->
					<div class="flex-1 overflow-auto p-8">
						{#if connFormTab === 'General'}
							<div class="mb-6">
								<h3 class="text-lg font-medium mb-1">General Settings</h3>
								<p class="text-sm text-muted-foreground">Configure the basic connection details.</p>
							</div>
							<div class="grid grid-cols-2 gap-4 max-w-3xl">

								<!-- Alias (common) -->
								<div class="col-span-2 space-y-1.5">
									<label class="text-sm font-medium" for="conn-alias">Name <span class="text-destructive">*</span></label>
									<input id="conn-alias" bind:value={connForm.alias} autocomplete="one-time-code" oninput={onAliasInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder={dbInfo?.name || 'My Database'} />
									{#if connFormErrors.alias}<p class="text-xs text-destructive mt-1">{connFormErrors.alias}</p>{/if}
								</div>

								<!-- Environment (common) -->
								<div class="col-span-2 space-y-1.5">
									<span class="text-sm font-medium">Environment</span>
									<Popover bind:open={envPopoverOpen}>
										<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
											<span class={connForm.environmentId ? '' : 'text-muted-foreground'}>
												{envList.find(e => String(e.id) === connForm.environmentId)?.name || 'Select environment'}
											</span>
											<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1 max-h-48 overflow-y-auto">
											<button
												class="flex w-full items-center rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {connForm.environmentId === '' ? 'bg-accent' : ''}"
												onclick={() => { connForm.environmentId = ''; envPopoverOpen = false; }}
											>
												<span class="text-muted-foreground">Select environment</span>
											</button>
											{#each envList as env}
												<button
													class="flex w-full items-center gap-2 rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {connForm.environmentId === String(env.id) ? 'bg-accent' : ''}"
													onclick={() => { connForm.environmentId = String(env.id); envPopoverOpen = false; }}
												>
													<span class="w-2 h-2 rounded-full shrink-0" style="background: {env.color?.toLowerCase() || '#888'}"></span>
													{env.name}
												</button>
											{/each}
										</PopoverContent>
									</Popover>
								</div>

								{#if connForm.type === 'SNOWFLAKE'}
									<!-- ═══ Snowflake ═══ -->
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="sf-account">Account <span class="text-destructive">*</span></label>
										<input id="sf-account" bind:value={connForm.account} autocomplete="one-time-code" oninput={(e) => {
											const val = (e.target as HTMLInputElement).value;
											connForm.url = val ? `jdbc:snowflake://${val}.snowflakecomputing.com` : 'jdbc:snowflake://';
										}} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="myorg-account" />
									</div>

									<div class="col-span-2 space-y-1.5">
										<span class="text-sm font-medium">Authentication</span>
										<Popover bind:open={authPopoverOpen}>
											<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
												<span>{authOptions.find(o => o.value === connForm.authenticationType)?.label || connForm.authenticationType}</span>
												<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
											</PopoverTrigger>
											<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1">
												{#each authOptions as opt}
													<button
														class="flex w-full items-center justify-between rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {connForm.authenticationType === opt.value ? 'bg-accent' : ''}"
														onclick={() => { connForm.authenticationType = opt.value as any; authPopoverOpen = false; }}
													>
														{opt.label}
														{#if connForm.authenticationType === opt.value}<Check class="h-3.5 w-3.5 text-primary" />{/if}
													</button>
												{/each}
											</PopoverContent>
										</Popover>
									</div>

									{#if connForm.authenticationType === 'USERANDPASSWORD'}
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="sf-user">User <span class="text-destructive">*</span></label>
											<input id="sf-user" bind:value={connForm.user} autocomplete="one-time-code" name="sf-user-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
										</div>
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="sf-pass">Password <span class="text-destructive">*</span></label>
											<input id="sf-pass" bind:value={connForm.password} type="password" autocomplete="new-password" name="conn-pass-field" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
										</div>
									{:else if connForm.authenticationType === 'KEYPAIR'}
										<div class="col-span-2 space-y-1.5">
											<label class="text-sm font-medium" for="sf-kp-user">User <span class="text-destructive">*</span></label>
											<input id="sf-kp-user" bind:value={connForm.user} autocomplete="one-time-code" name="sf-kp-user-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
										</div>
										<div class="col-span-2 space-y-1.5">
											<label class="text-sm font-medium" for="sf-pk">Private Key Content <span class="text-destructive">*</span></label>
											<Textarea id="sf-pk" bind:value={connForm.privateKeyContent} rows={4} class="font-mono min-h-[100px]" placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----" />
										</div>
									{/if}

									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="sf-db">Database</label>
										<input id="sf-db" bind:value={connForm.database} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="MY_DATABASE" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="sf-schema">Schema</label>
										<input id="sf-schema" bind:value={connForm.schema} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="PUBLIC" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="sf-wh">Warehouse</label>
										<input id="sf-wh" bind:value={connForm.warehouse} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="COMPUTE_WH" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="sf-role">Role</label>
										<input id="sf-role" bind:value={connForm.role} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="ANALYST_ROLE" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="sf-url">URL <span class="text-destructive">*</span></label>
										<input id="sf-url" bind:value={connForm.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="jdbc:snowflake://account.snowflakecomputing.com" />
									</div>

								{:else if connForm.type === 'BIGQUERY'}
									<!-- ═══ BigQuery ═══ -->
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="bq-sa">Service Account JSON <span class="text-destructive">*</span></label>
										<Textarea id="bq-sa" bind:value={connForm.serviceAccountJson} rows={6} class="font-mono min-h-[150px]" placeholder="Paste your service account JSON key here" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="bq-dataset">Default Dataset</label>
										<input id="bq-dataset" bind:value={connForm.defaultDataset} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="bq-url">URL <span class="text-destructive">*</span></label>
										<input id="bq-url" bind:value={connForm.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443" />
									</div>

								{:else if connForm.type === 'DATABRICKS'}
									<!-- ═══ Databricks ═══ -->
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="dk-host">Host <span class="text-destructive">*</span></label>
										<input id="dk-host" bind:value={connForm.host} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="adb-xxxxx.azuredatabricks.net" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="dk-port">Port <span class="text-destructive">*</span></label>
										<input id="dk-port" bind:value={connForm.port} type="number" autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="443" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="dk-http">HTTP Path <span class="text-destructive">*</span></label>
										<input id="dk-http" bind:value={connForm.httpPath} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="/sql/1.0/warehouses/xxxxx" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="dk-token">Access Token <span class="text-destructive">*</span></label>
										<input id="dk-token" bind:value={connForm.accessToken} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="Personal Access Token" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="dk-catalog">Catalog</label>
										<input id="dk-catalog" bind:value={connForm.catalog} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="Unity Catalog name (optional)" />
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="dk-schema">Schema</label>
										<input id="dk-schema" bind:value={connForm.schema} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
									</div>
									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="dk-url">URL <span class="text-destructive">*</span></label>
										<input id="dk-url" bind:value={connForm.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="jdbc:databricks://" />
									</div>

								{:else}
									<!-- ═══ Standard DB (MySQL, PostgreSQL, etc.) ═══ -->
									{#if connForm.useUrl}
										<div class="col-span-2 space-y-1.5">
											<div class="flex items-center justify-between">
												<label class="text-sm font-medium" for="conn-url">URL</label>
												<button type="button" class="text-xs text-primary" onclick={() => connForm.useUrl = false}>Use Host/Port</button>
											</div>
											<input id="conn-url" bind:value={connForm.url} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="jdbc:mysql://localhost:3306/mydb" />
										</div>
									{:else}
										<div class="space-y-1.5">
											<div class="flex items-center justify-between">
												<label class="text-sm font-medium" for="conn-host">Host <span class="text-destructive">*</span></label>
												<button type="button" class="text-xs text-primary" onclick={() => connForm.useUrl = true}>Use URL</button>
											</div>
											<input id="conn-host" bind:value={connForm.host} autocomplete="one-time-code" oninput={onHostInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="localhost" />
											{#if connFormErrors.host}<p class="text-xs text-destructive mt-1">{connFormErrors.host}</p>{/if}
										</div>
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="conn-port">Port</label>
											<input id="conn-port" bind:value={connForm.port} type="number" autocomplete="one-time-code" oninput={onPortInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="3306" />
										</div>
									{/if}

									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="conn-user">Username <span class="text-destructive">*</span></label>
										<input id="conn-user" bind:value={connForm.user} autocomplete="one-time-code" name="conn-user-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="root" />
										{#if connFormErrors.user}<p class="text-xs text-destructive mt-1">{connFormErrors.user}</p>{/if}
									</div>
									<div class="space-y-1.5">
										<label class="text-sm font-medium" for="conn-pass">Password</label>
										<input id="conn-pass" bind:value={connForm.password} type="password" autocomplete="new-password" name="conn-pass-field" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="••••••••" />
									</div>

									<div class="col-span-2 space-y-1.5">
										<label class="text-sm font-medium" for="conn-db">Database</label>
										<input id="conn-db" bind:value={connForm.database} autocomplete="one-time-code" oninput={onDatabaseInput} class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="mydb" />
									</div>
								{/if}
							</div>
						{:else if connFormTab === 'SSH'}
							<div class="mb-6">
								<h3 class="text-lg font-medium mb-1">SSH Tunnel</h3>
								<p class="text-sm text-muted-foreground">Secure your connection via an SSH tunnel.</p>
							</div>
							<div class="max-w-3xl space-y-4">
								<label class="flex items-center gap-3 text-sm font-medium cursor-pointer" for="ssh-toggle">
									<Switch id="ssh-toggle" bind:checked={connForm.ssh.use} />
									Use SSH Tunnel
								</label>
								{#if connForm.ssh.use}
									<div class="grid grid-cols-2 gap-4">
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="ssh-host">SSH Host</label>
											<input id="ssh-host" bind:value={connForm.ssh.host} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
										</div>
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="ssh-port">SSH Port</label>
											<input id="ssh-port" bind:value={connForm.ssh.port} type="number" autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="22" />
										</div>
										<div class="space-y-1.5">
											<label class="text-sm font-medium" for="ssh-user">SSH User</label>
											<input id="ssh-user" bind:value={connForm.ssh.user} autocomplete="one-time-code" name="ssh-user-x" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
										</div>
										<div class="space-y-1.5">
											<span class="text-sm font-medium">Auth Type</span>
											<Popover bind:open={sshAuthPopoverOpen}>
												<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
													<span>{sshAuthOptions.find(o => o.value === connForm.ssh.authType)?.label || connForm.ssh.authType}</span>
													<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
												</PopoverTrigger>
												<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1">
													{#each sshAuthOptions as opt}
														<button
															class="flex w-full items-center justify-between rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {connForm.ssh.authType === opt.value ? 'bg-accent' : ''}"
															onclick={() => { connForm.ssh.authType = opt.value; sshAuthPopoverOpen = false; }}
														>
															{opt.label}
															{#if connForm.ssh.authType === opt.value}<Check class="h-3.5 w-3.5 text-primary" />{/if}
														</button>
													{/each}
												</PopoverContent>
											</Popover>
										</div>
										{#if connForm.ssh.authType === 'password'}
											<div class="col-span-2 space-y-1.5">
												<label class="text-sm font-medium" for="ssh-pass">SSH Password</label>
												<input id="ssh-pass" bind:value={connForm.ssh.password} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
											</div>
										{:else}
											<div class="col-span-2 space-y-1.5">
												<label class="text-sm font-medium" for="ssh-keyfile">Private Key Path</label>
												<input id="ssh-keyfile" bind:value={connForm.ssh.keyFile} autocomplete="one-time-code" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" placeholder="~/.ssh/id_rsa" />
											</div>
											<div class="col-span-2 space-y-1.5">
												<label class="text-sm font-medium" for="ssh-passphrase">Passphrase</label>
												<input id="ssh-passphrase" bind:value={connForm.ssh.passphrase} type="password" autocomplete="new-password" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
											</div>
										{/if}
									</div>
								{/if}
							</div>
						{:else if connFormTab === 'Advanced'}
							<div class="mb-6">
								<h3 class="text-lg font-medium mb-1">Advanced Properties</h3>
								<p class="text-sm text-muted-foreground">Additional driver properties and configurations.</p>
							</div>
							<div class="max-w-3xl space-y-4">
								<div class="space-y-1.5">
									<label class="text-sm font-medium" for="conn-extra">Extra Properties</label>
									<p class="text-xs text-muted-foreground">Add additional JDBC connection properties</p>
									<Textarea
										id="conn-extra"
										bind:value={connForm.extendInfo}
										class="font-mono min-h-[120px]"
										placeholder="key1=value1&#10;key2=value2"
									/>
								</div>
							</div>
					{:else if connFormTab === 'Driver'}
						<div class="mb-6">
							<h3 class="text-lg font-medium mb-1">Driver Configuration</h3>
							<p class="text-sm text-muted-foreground">Manage JDBC drivers and class paths.</p>
						</div>
						<div class="max-w-3xl space-y-4">
							{#if driverLoading}
								<div class="flex items-center gap-2 text-sm text-muted-foreground">
									<Loader2 class="w-4 h-4 animate-spin" />
									Loading drivers...
								</div>
							{:else}
								<!-- JDBC Driver Select -->
								<div class="space-y-1.5">
									<span class="text-sm font-medium">Driver</span>
									<Popover bind:open={driverPopoverOpen}>
										<PopoverTrigger class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm hover:bg-accent/50 transition-colors">
											<span class={selectedDriver ? 'truncate' : 'text-muted-foreground truncate'} title={selectedDriver}>
												{driverLabel(selectedDriver) || 'No drivers available'}
											</span>
											<ChevronDown class="h-4 w-4 shrink-0 text-muted-foreground" />
										</PopoverTrigger>
										<PopoverContent align="start" class="w-[var(--bits-popover-trigger-width)] p-1 max-h-48 overflow-y-auto">
											{#if driverList.length === 0}
												<div class="px-3 py-2 text-sm text-muted-foreground">No drivers available</div>
											{:else}
												{#each driverList as drv}
													<button
														class="flex w-full items-center justify-between rounded-sm px-3 py-1.5 text-sm hover:bg-accent transition-colors {selectedDriver === drv.jdbcDriver ? 'bg-accent' : ''}"
														onclick={() => {
															selectedDriver = drv.jdbcDriver;
															selectedDriverClass = drv.jdbcDriverClass;
															connForm.driverConfig = { jdbcDriver: drv.jdbcDriver, jdbcDriverClass: drv.jdbcDriverClass };
															driverPopoverOpen = false;
														}}
													>
														<span class="truncate" title={drv.jdbcDriver}>{driverLabel(drv.jdbcDriver)}</span>
														{#if selectedDriver === drv.jdbcDriver}<Check class="h-3.5 w-3.5 text-primary shrink-0" />{/if}
													</button>
												{/each}
											{/if}
										</PopoverContent>
									</Popover>
								</div>

								<!-- JDBC Driver Class (readonly) -->
								<div class="space-y-1.5">
									<label class="text-sm font-medium" for="drv-class">Class</label>
									<input
										id="drv-class"
										value={selectedDriverClass}
										disabled
										class="flex h-10 w-full rounded-md border border-input bg-muted/50 px-3 py-2 text-sm text-muted-foreground cursor-not-allowed"
									/>
								</div>

								<!-- Download / Upload -->
								{#if driverList.length === 0}
									<div class="pt-2 border-t border-border/40">
										<button
											onclick={async () => {
												driverDownloadStatus = 'loading';
												try {
													await connectionService.downloadDriver({ dbType: connForm.type });
													driverDownloadStatus = 'success';
													await loadDrivers(connForm.type);
												} catch {
													driverDownloadStatus = 'error';
												}
											}}
											class="flex items-center gap-2 text-sm text-primary hover:underline"
										>
											{#if driverDownloadStatus === 'loading'}
												<Loader2 class="w-4 h-4 animate-spin" />
												Downloading...
											{:else if driverDownloadStatus === 'error'}
												<Download class="w-4 h-4" />
												Download failed. Try again
											{:else if driverDownloadStatus === 'success'}
												<Download class="w-4 h-4" />
												Downloaded successfully
											{:else}
												<Download class="w-4 h-4" />
												Download Driver
											{/if}
										</button>
									</div>
								{/if}

								<div class="pt-2">
									<Button variant="outline" class="w-full gap-2 border-dashed" onclick={() => showUploadDriverModal = true}>
										<Upload class="w-4 h-4" />
										Upload Custom Driver
									</Button>
								</div>
							{/if}
						</div>
						{/if}
					</div>
				</div>

				<!-- Footer -->
				<div class="border-t border-border/40 p-4 shrink-0 bg-card/20 flex justify-between items-center px-8">
					<div class="flex gap-4">
						<Button variant="outline" onclick={handleTestConnection} disabled={testingConnection} class="bg-accent/50 hover:bg-accent text-foreground border-border/50">
							{testingConnection ? 'Testing...' : 'Test Connection'}
						</Button>
					</div>
					<div class="flex gap-3">
						<Button variant="ghost" onclick={() => { if (isEdit) { handleCloseModal(); } else { modalStep = 'picker'; resetConnForm(); dbSearchQuery = ''; } }} class="text-muted-foreground hover:text-foreground">
							Cancel
						</Button>
						<Button onclick={handleSaveConnection} disabled={savingConnection} class="min-w-[100px]">
							{savingConnection ? 'Saving...' : (isEdit ? 'Update' : 'Connect')}
						</Button>
					</div>
				</div>
			{/if}
		</div>
	</div>
{/if}

<!-- Upload Driver Modal -->
{#if showUploadDriverModal}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<div class="fixed inset-0 z-[60] flex items-center justify-center bg-black/60" role="dialog" tabindex="-1" onclick={(e) => { if (e.target === e.currentTarget) showUploadDriverModal = false; }} onkeydown={(e) => { if (e.key === 'Escape') showUploadDriverModal = false; }}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="w-full max-w-md bg-background border border-border/50 rounded-xl shadow-2xl p-6" role="document" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()}>
			<div class="flex items-center justify-between mb-4">
				<h3 class="text-lg font-semibold">Upload Driver</h3>
				<button onclick={() => showUploadDriverModal = false} class="p-1 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground">
					<X class="w-4 h-4" />
				</button>
			</div>

			<div class="space-y-4">
				<div class="space-y-1.5">
					<label class="text-sm font-medium" for="upload-drv-class">JDBC Driver Class</label>
					<input
						id="upload-drv-class"
						bind:value={uploadDriverClass}
						autocomplete="one-time-code"
						class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						placeholder="com.mysql.cj.jdbc.Driver"
					/>
				</div>

				<div class="space-y-1.5">
					<label class="text-sm font-medium" for="upload-drv-file">Driver File (.jar)</label>
					<input
						id="upload-drv-file"
						type="file"
						accept=".jar"
						onchange={(e) => {
							const target = e.target as HTMLInputElement;
							uploadDriverFile = target.files?.[0] || null;
						}}
						class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm file:mr-2 file:border-0 file:bg-primary/10 file:text-primary file:text-sm file:font-medium file:rounded file:px-2 file:py-0.5"
					/>
				</div>
			</div>

			<div class="flex justify-end gap-3 mt-6">
				<Button variant="ghost" onclick={() => showUploadDriverModal = false} class="text-muted-foreground">
					Cancel
				</Button>
				<Button
					disabled={uploadingDriver || !uploadDriverClass.trim() || !uploadDriverFile}
					onclick={async () => {
						if (!uploadDriverFile || !uploadDriverClass.trim()) return;
						uploadingDriver = true;
						try {
							await connectionService.saveDriver({
								multipartFiles: uploadDriverFile,
								jdbcDriverClass: uploadDriverClass,
								dbType: connForm.type
							});
							showUploadDriverModal = false;
							uploadDriverClass = '';
							uploadDriverFile = null;
							await loadDrivers(connForm.type);
						} catch (e: any) {
							message.error(e?.message || 'Upload failed');
						} finally {
							uploadingDriver = false;
						}
					}}
				>
					{uploadingDriver ? 'Uploading...' : 'Upload'}
				</Button>
			</div>
		</div>
	</div>
{/if}
