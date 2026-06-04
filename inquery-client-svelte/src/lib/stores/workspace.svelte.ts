/**
 * Workspace store - Svelte 5 Runes
 * Manages workspace layout, tabs, consoles, connection state, and auto-save
 */

import type { IConnectionListItem } from "$lib/types/connection";
import historyService from "$lib/service/history";
import connectionService from "$lib/service/connection";
import erdService from "$lib/service/erd";
import schemaCacheService from "$lib/service/schemaCache";

// Types
export interface IConsole {
  id: number | string;
  name: string;
  ddl?: string;
  status?: "DRAFT" | "RELEASE";
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: string;
  operationType?: "console" | "tableView" | "erd" | "lineage";
  tableName?: string; // for tableView: fully qualified table name, for erd/lineage: unique key
  [key: string]: unknown;
}

export interface IWorkspaceTab {
  id: string | number;
  title: string;
  type: "console" | "tableView" | "erd" | "lineage";
  consoleId?: number | string;
  [key: string]: unknown;
}

export interface ILayout {
  panelLeft: boolean;
  panelLeftWidth: number;
  panelRight: boolean;
  panelRightWidth: number;
  consoleHeight: number;
}

// Prefetch cache — holds in-flight promises started from the connections page
// so workspace can await them instead of issuing duplicate requests.
export interface IPrefetchCache {
  connectionId: number;
  connectionList?: IConnectionListItem[];
  dbListPromise?: Promise<any>;
  consoleListPromise?: Promise<any>;
  savedConsoleListPromise?: Promise<any>;
  tableListPromise?: Promise<any>;
  timestamp: number;
}

let prefetchCache = $state<IPrefetchCache | null>(null);

const PREFETCH_TTL = 15_000; // 15s — discard stale prefetch

export function setPrefetchCache(cache: IPrefetchCache) {
  prefetchCache = cache;
}

export function consumePrefetchCache(connectionId: number): IPrefetchCache | null {
  if (!prefetchCache) return null;
  if (prefetchCache.connectionId !== connectionId) return null;
  if (Date.now() - prefetchCache.timestamp > PREFETCH_TTL) {
    prefetchCache = null;
    return null;
  }
  const cache = prefetchCache;
  prefetchCache = null;
  return cache;
}

// State
let currentConnection = $state<IConnectionListItem | null>(null);
let consoleList = $state<IConsole[]>([]);
let savedConsoleList = $state<IConsole[]>([]);
let activeConsoleId = $state<string | number | null>(null);
let layout = $state<ILayout>({
  panelLeft: true,
  panelLeftWidth: 240,
  panelRight: false,
  panelRightWidth: 300,
  consoleHeight: 300,
});
let leftTab = $state<"database" | "workspace">("database");
let pendingSql = $state<string | null>(null);
let createConsoleLoading = $state(false);

// Auto-save debounce timers
const autoSaveTimers: Record<string, ReturnType<typeof setTimeout>> = {};

// Load layout from localStorage
if (typeof localStorage !== "undefined") {
  try {
    const saved = localStorage.getItem("workspace-layout");
    if (saved) {
      const parsed = JSON.parse(saved);
      if (parsed?.layout) {
        const defaultLayout: ILayout = {
          panelLeft: true,
          panelLeftWidth: 240,
          panelRight: false,
          panelRightWidth: 300,
          consoleHeight: 300,
        };
        layout = { ...defaultLayout, ...parsed.layout };
      }
      if (parsed?.currentConnection)
        currentConnection = parsed.currentConnection;
      if (parsed?.activeConsoleId != null)
        activeConsoleId = parsed.activeConsoleId;
    }
  } catch {
    /* ignore */
  }
}

function persistLayout() {
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(
      "workspace-layout",
      JSON.stringify({
        layout,
        currentConnection,
        activeConsoleId,
      }),
    );
  }
}

export function getWorkspaceStore() {
  return {
    get currentConnection() {
      return currentConnection;
    },
    get consoleList() {
      return consoleList;
    },
    get savedConsoleList() {
      return savedConsoleList;
    },
    get activeConsoleId() {
      return activeConsoleId;
    },
    get layout() {
      return layout;
    },
    get leftTab() {
      return leftTab;
    },
    get pendingSql() {
      return pendingSql;
    },
    get createConsoleLoading() {
      return createConsoleLoading;
    },
  };
}

export function setCurrentConnection(conn: IConnectionListItem | null) {
  currentConnection = conn;
  persistLayout();

  // Prefetch schema metadata in background when connection changes
  if (conn?.id) {
    setTimeout(() => prefetchSchemaMetadata(conn.id), 500);
  }
}

export function setActiveConsoleId(id: string | number | null) {
  activeConsoleId = id;
  persistLayout();
}

// --- Schema metadata background prefetching ---

const prefetchedDataSources = new Set<number>();

/**
 * Prefetch schema metadata for all schemas in a data source.
 * Runs in background, does not block UI, fails silently.
 * Skips schemas that already have valid server cache.
 */
async function prefetchSchemaMetadata(dataSourceId: number): Promise<void> {
  if (prefetchedDataSources.has(dataSourceId)) return;
  prefetchedDataSources.add(dataSourceId);

  try {
    const databases = (await connectionService.getDatabaseList({
      dataSourceId,
      refresh: false,
    })) as any[];
    if (!databases?.length) return;

    for (const db of databases) {
      const databaseName = db.name || db;
      if (!databaseName) continue;

      try {
        const schemas = (await connectionService.getSchemaList({
          dataSourceId,
          databaseName,
          refresh: false,
        })) as any[];

        if (!schemas?.length) {
          // No schemas — prefetch at database level if no cache exists
          const exists = await schemaCacheService.hasSchemaCache({
            dataSourceId,
            databaseName,
          });
          if (!exists) {
            erdService
              .prefetchSchema({ dataSourceId, databaseName })
              .catch(() => {});
          }
          continue;
        }

        // Prefetch each schema (limit to first 5 to avoid overwhelming the server)
        for (const schema of schemas.slice(0, 5)) {
          const schemaName = schema.name || schema;
          if (!schemaName) continue;

          const exists = await schemaCacheService.hasSchemaCache({
            dataSourceId,
            databaseName,
            schemaName,
          });
          if (exists) continue;

          erdService
            .prefetchSchema({ dataSourceId, databaseName, schemaName })
            .catch(() => {});

          // Small delay between requests
          await new Promise((r) => setTimeout(r, 100));
        }
      } catch {
        // Silently fail for individual database
      }
    }
  } catch {
    prefetchedDataSources.delete(dataSourceId);
  }
}

/** Clear prefetch tracking (e.g. when user explicitly refreshes) */
export function clearPrefetchCache(dataSourceId?: number) {
  if (dataSourceId) {
    prefetchedDataSources.delete(dataSourceId);
  } else {
    prefetchedDataSources.clear();
  }
}

export function setPanelLeftWidth(width: number) {
  layout = { ...layout, panelLeftWidth: width };
  persistLayout();
}

export function togglePanelLeft() {
  layout = { ...layout, panelLeft: !layout.panelLeft };
  persistLayout();
}

export function setLeftTab(tab: "database" | "workspace") {
  leftTab = tab;
}

export function togglePanelRight() {
  layout = { ...layout, panelRight: !layout.panelRight };
  persistLayout();
}

export function setPanelRightWidth(width: number) {
  layout = { ...layout, panelRightWidth: width };
  persistLayout();
}

export function setPendingSql(sql: string | null) {
  pendingSql = sql;
}

export async function fetchConsoleList(prefetchedPromise?: Promise<any>) {
  try {
    const res = prefetchedPromise
      ? await prefetchedPromise
      : await historyService.getConsoleList({ tabOpened: "y", pageNo: 1, pageSize: 20 });
    const data = res as { data?: IConsole[] };
    consoleList = data?.data || [];
    if (consoleList.length > 0) {
      const activeExists = activeConsoleId != null && consoleList.some(c => c.id === activeConsoleId);
      if (!activeExists) {
        activeConsoleId = consoleList[0].id;
      }
    }
  } catch {
    consoleList = [];
  }
}

export async function fetchSavedConsoleList(prefetchedPromise?: Promise<any>) {
  try {
    const res = prefetchedPromise
      ? await prefetchedPromise
      : await historyService.getConsoleList({ status: "RELEASE", pageNo: 1, pageSize: 100 });
    const data = res as { data?: IConsole[] };
    savedConsoleList = data?.data || [];
  } catch {
    savedConsoleList = [];
  }
}

function findNextUntitledNumber(dataSourceName: string): number {
  const suffix = ` (${dataSourceName})`;
  const usedNumbers = new Set<number>();
  for (const c of [...consoleList, ...savedConsoleList]) {
    const name = c.name || "";
    if (!name.startsWith("Untitled ") || !name.endsWith(suffix)) continue;
    const numStr = name.slice("Untitled ".length, name.length - suffix.length);
    const num = parseInt(numStr, 10);
    if (!isNaN(num) && num > 0) usedNumbers.add(num);
  }
  for (let i = 1; ; i++) {
    if (!usedNumbers.has(i)) return i;
  }
}

export async function createConsole(params: {
  name?: string;
  dataSourceId?: number;
  dataSourceName?: string;
  databaseName?: string;
  schemaName?: string;
  databaseType?: string;
  ddl?: string;
  operationType?: "console" | "tableView" | "erd" | "lineage";
  tableName?: string;
}): Promise<number | null> {
  if (consoleList.length >= 20) {
    console.warn("[Workspace] Max 20 consoles");
    return null;
  }
  createConsoleLoading = true;
  try {
    const dsName = params.dataSourceName || "Unknown";
    const consoleName =
      params.name ||
      `Untitled ${findNextUntitledNumber(dsName)} (${dsName})`;
    const id = await historyService.createConsole({
      name: consoleName,
      ddl: params.ddl || "",
      dataSourceId: params.dataSourceId,
      databaseName: params.databaseName,
      schemaName: params.schemaName,
      type: params.databaseType || "MYSQL",
      tabOpened: "y",
      status: "DRAFT",
      operationType: params.operationType || "console",
    });
    if (id) {
      const newConsole: IConsole = {
        id,
        name: consoleName,
        ddl: params.ddl || "",
        status: "DRAFT",
        dataSourceId: params.dataSourceId,
        databaseName: params.databaseName,
        schemaName: params.schemaName,
        databaseType: params.databaseType || "MYSQL",
        operationType: params.operationType || "console",
        ...(params.tableName && { tableName: params.tableName }),
      };
      consoleList = [...consoleList, newConsole];
      activeConsoleId = id;
    }
    return id as number;
  } catch {
    return null;
  } finally {
    createConsoleLoading = false;
  }
}

export async function saveConsole(
  consoleId: string | number,
  ddl: string,
  silent = false,
): Promise<boolean> {
  try {
    await historyService.updateSavedConsole({
      id: consoleId,
      ddl,
      status: "RELEASE",
    });
    // Update local state
    const idx = consoleList.findIndex((c) => c.id === consoleId);
    if (idx !== -1) {
      consoleList[idx] = { ...consoleList[idx], ddl, status: "RELEASE" };
    }
    // Also clean up draft from IndexedDB
    try {
      const { removeDraft } = await import("$lib/utils/indexedDB");
      await removeDraft(consoleId);
    } catch {
      /* ignore */
    }
    // Refresh saved list so sidebar updates immediately
    await fetchSavedConsoleList();
    return true;
  } catch {
    return false;
  }
}

export function debouncedAutoSave(
  consoleId: string | number,
  ddl: string,
): void {
  if (autoSaveTimers[String(consoleId)]) {
    clearTimeout(autoSaveTimers[String(consoleId)]);
  }
  autoSaveTimers[String(consoleId)] = setTimeout(async () => {
    // Save draft to IndexedDB
    try {
      const { saveDraft } = await import("$lib/utils/indexedDB");
      await saveDraft(consoleId, ddl);
    } catch {
      /* ignore */
    }
  }, 2000);
}

export function setConsoleHeight(height: number) {
  layout = { ...layout, consoleHeight: height };
  persistLayout();
}

export async function closeConsole(
  consoleId: number | string,
): Promise<boolean> {
  try {
    await historyService.updateSavedConsole({
      id: consoleId,
      tabOpened: "n",
    });
    consoleList = consoleList.filter((c) => c.id !== consoleId);
    if (activeConsoleId === consoleId) {
      activeConsoleId = consoleList[0]?.id || null;
    }
    try {
      const { removeDraft } = await import("$lib/utils/indexedDB");
      await removeDraft(consoleId);
    } catch {
      /* ignore */
    }
    return true;
  } catch {
    return false;
  }
}

export async function renameConsole(
  consoleId: number | string,
  name: string,
): Promise<boolean> {
  try {
    await historyService.updateSavedConsole({ id: consoleId, name });
    const idx = consoleList.findIndex((c) => c.id === consoleId);
    if (idx !== -1) {
      consoleList[idx] = { ...consoleList[idx], name };
    }
    const sIdx = savedConsoleList.findIndex((c) => c.id === consoleId);
    if (sIdx !== -1) {
      savedConsoleList[sIdx] = { ...savedConsoleList[sIdx], name };
    }
    return true;
  } catch {
    return false;
  }
}

export async function openSavedConsole(
  consoleId: number | string,
): Promise<boolean> {
  try {
    await historyService.updateSavedConsole({
      id: consoleId,
      tabOpened: "y",
    });
    await fetchConsoleList();
    activeConsoleId = consoleId;
    return true;
  } catch {
    return false;
  }
}

export async function deleteConsole(consoleId: number): Promise<boolean> {
  try {
    await historyService.deleteSavedConsole({ id: consoleId });
    consoleList = consoleList.filter((c) => c.id !== consoleId);
    savedConsoleList = savedConsoleList.filter((c) => c.id !== consoleId);
    if (activeConsoleId === consoleId) {
      activeConsoleId = consoleList[0]?.id || null;
    }
    try {
      const { removeDraft } = await import("$lib/utils/indexedDB");
      await removeDraft(consoleId);
    } catch {
      /* ignore */
    }
    return true;
  } catch {
    return false;
  }
}
