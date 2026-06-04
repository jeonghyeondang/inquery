import createRequest, { getBaseURL } from "./base";
import axios from "axios";

export interface IDataCatalogColumn {
  name: string;
  columnType: string;
  nullable: boolean;
  primaryKey: boolean;
  defaultValue: string;
  comment: string;
  description: string;
  schemaInfo: string;
  exampleValues: string;
  ordinalPosition?: number;
  // For BigQuery nested columns (STRUCT/RECORD)
  isNested?: boolean;
  depth?: number;
  parentName?: string;
}

export interface IDataCatalogQueryParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  catalogTableName?: string;
}

export interface IDataCatalogQueryResult {
  tableDescription: string;
  columns: IDataCatalogColumn[];
}

export interface ITable {
  name: string;
  databaseName: string;
  schemaName: string;
  type: string; // "BASE TABLE" or "VIEW"
  comment?: string;
  pinned?: boolean;
}

const queryCatalog = createRequest<
  IDataCatalogQueryParams,
  IDataCatalogQueryResult
>("/api/catalog/query", { errorLevel: false });
const saveCatalog = createRequest<Record<string, unknown>, void>(
  "/api/catalog/save",
  { method: "post" },
);
const collectExampleValues = createRequest<
  IDataCatalogQueryParams,
  {
    exampleValues: Record<string, string[]>;
    columnDescriptions?: Record<string, string>;
  }
>("/api/catalog/collect", { method: "post" });
const collectAIMetadata = createRequest<
  IDataCatalogQueryParams,
  {
    tableDescription: string;
    columnDescriptions: Record<string, string>;
    columnExampleValues?: Record<string, string[]>;
  }
>("/api/catalog/ai/collect", { method: "post" });
const loadAllTablesAndViews = createRequest<
  { dataSourceId: number; refresh?: boolean },
  Record<string, ITable[]>
>("/api/catalog/load-all-tables-views", { errorLevel: false });
const loadTablesBySchema = createRequest<
  { dataSourceId: number; databaseName: string; schemaName?: string },
  ITable[]
>("/api/catalog/tables-by-schema", { errorLevel: false });
const getActiveTables = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string },
  string[]
>("/api/catalog/active-tables", { errorLevel: false });
const getTableActiveStates = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string },
  Record<string, boolean>
>("/api/catalog/active-states", { errorLevel: false });

// ─── Bulk AI Collection (async + polling) ───

export interface ICollectionJobProgress {
  jobId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  totalTables: number;
  processedTables: number;
  successCount: number;
  failCount: number;
  currentTable: string;
  failedTables: string[];
  schemaName: string;
  databaseName: string;
}

/** Start async bulk AI collection for a schema. Returns { jobId: string } */
async function startBulkCollection(params: {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
}): Promise<{ jobId: string }> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const queryParams = new URLSearchParams();
  queryParams.set("dataSourceId", String(params.dataSourceId));
  queryParams.set("databaseName", params.databaseName);
  if (params.schemaName) queryParams.set("schemaName", params.schemaName);

  const res = await axios.post(
    `${getBaseURL()}/api/catalog/ai/collect-all-async?${queryParams.toString()}`,
    null,
    {
      headers: token ? { Inquery: token } : {},
    },
  );
  if (res.data && !res.data.success) {
    throw new Error(res.data.errorMessage || "Failed to start bulk collection");
  }
  return res.data.data;
}

/** Poll progress for an async bulk AI collection job */
async function getCollectionProgress(
  jobId: string,
): Promise<ICollectionJobProgress> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const res = await axios.get(
    `${getBaseURL()}/api/catalog/ai/collect-all/progress?jobId=${encodeURIComponent(jobId)}`,
    {
      headers: token ? { Inquery: token } : {},
    },
  );
  if (res.data && !res.data.success) {
    throw new Error(
      res.data.errorMessage || "Failed to get collection progress",
    );
  }
  return res.data.data;
}

/** Save active state - backend uses @RequestParam, so params go as query string */
async function saveTableActiveState(params: {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  active: boolean;
}): Promise<void> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const queryParams = new URLSearchParams();
  queryParams.set("dataSourceId", String(params.dataSourceId));
  queryParams.set("databaseName", params.databaseName);
  if (params.schemaName) queryParams.set("schemaName", params.schemaName);
  queryParams.set("tableName", params.tableName);
  queryParams.set("active", String(params.active));

  const res = await axios.post(
    `${getBaseURL()}/api/catalog/active-state?${queryParams.toString()}`,
    null,
    {
      headers: token ? { Inquery: token } : {},
    },
  );
  if (res.data && !res.data.success) {
    throw new Error(res.data.errorMessage || "Failed to save active state");
  }
}

/** Batch save active states - backend uses @RequestParam for dataSourceId, @RequestBody for activeStates */
async function batchSaveTableActiveStates(
  dataSourceId: number,
  activeStates: Record<string, boolean>,
): Promise<void> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const res = await axios.post(
    `${getBaseURL()}/api/catalog/active-states/batch?dataSourceId=${dataSourceId}`,
    activeStates,
    {
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
    },
  );
  if (res.data && !res.data.success) {
    throw new Error(
      res.data.errorMessage || "Failed to batch save active states",
    );
  }
}

// ─── Lineage API ───

export interface ILineageNode {
  uniqueId: string;
  name: string;
  database: string;
  schema: string;
  resourceType: string;
  description: string;
  materialization: string | null;
  compiledSql: string | null;
}

export interface ILineageEdge {
  sourceId: string;
  targetId: string;
}

export interface ILineageGraph {
  nodes: ILineageNode[];
  edges: ILineageEdge[];
}

async function uploadManifest(
  dataSourceId: number,
  file: File,
): Promise<ILineageGraph> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const formData = new FormData();
  formData.append("file", file);

  const res = await axios.post(
    `${getBaseURL()}/api/catalog/lineage/upload-manifest?dataSourceId=${dataSourceId}`,
    formData,
    {
      headers: {
        "Content-Type": "multipart/form-data",
        ...(token ? { Inquery: token } : {}),
      },
    },
  );
  if (res.data && !res.data.success) {
    throw new Error(res.data.errorMessage || "Failed to upload manifest");
  }
  return res.data.data;
}

const getLineageGraph = createRequest<
  { dataSourceId: number },
  ILineageGraph
>("/api/catalog/lineage/graph", { errorLevel: false });

async function autoDetectLineage(params: { dataSourceId: number }) {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
  const res = await axios.post(
    `${getBaseURL()}/api/catalog/lineage/auto-detect?dataSourceId=${params.dataSourceId}`,
    {},
    { headers: { ...(token ? { Inquery: token } : {}) } },
  );
  return res.data?.data;
}

const getLineageStatus = createRequest<
  { dataSourceId: number },
  { state: string; lastRunTime?: string; nodeCount?: number; edgeCount?: number; errorMessage?: string }
>("/api/catalog/lineage/status", { errorLevel: false });

export default {
  queryCatalog,
  saveCatalog,
  collectExampleValues,
  collectAIMetadata,
  loadAllTablesAndViews,
  loadTablesBySchema,
  getActiveTables,
  getTableActiveStates,
  saveTableActiveState,
  batchSaveTableActiveStates,
  startBulkCollection,
  getCollectionProgress,
  uploadManifest,
  getLineageGraph,
  autoDetectLineage,
  getLineageStatus,
};
