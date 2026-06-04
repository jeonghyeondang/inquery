/**
 * AI Chat store - Svelte 5 Runes
 * Manages chat rooms, messages, query execution, and SSE streaming
 */

import {
  listChatRooms,
  createChatRoom,
  updateChatRoom,
  deleteChatRoom,
  saveMessage as saveMessageApi,
  updateMessage as updateMessageApi,
  getMessagesByChatRoomId,
  submitFeedback,
  type FeedbackType,
  type IChatMessage,
  type IModelSwitched,
} from "$lib/service/aiChat";
import type { IAttachment } from "$lib/service/attachment";
import message from "$lib/utils/message";
import i18n from "$lib/i18n";
import sqlService from "$lib/service/sql";
import catalogService from "$lib/service/catalog";
import { searchTableByName } from "$lib/service/schemaCache";
import { getBaseURL } from "$lib/service/base";
import { getUserStore } from "$lib/stores/user.svelte";
import { v4 as uuidv4 } from "uuid";

// ============================================================
// Types
// ============================================================

export interface IThinkingStep {
  title: string;
  description?: string;
  status: "running" | "done" | "error";
}

export interface IQueryResult {
  headerList: Array<{ name: string; dataType?: string }>;
  dataList: unknown[][];
  sql?: string;
  success?: boolean;
  message?: string;
  description?: string;
}

export interface ISchemaColumn {
  name: string;
  columnType: string;
  nullable?: boolean;
  isPrimaryKey?: boolean;
  description?: string;
  exampleValues?: string;
}

export interface ISchemaInfoEntry {
  tableName: string;
  schemaName?: string;
  databaseName?: string;
  columns: ISchemaColumn[];
  tableDescription?: string;
}

export interface IQuery {
  sql: string;
  overview?: string;
  title?: string;
  explanation?: string;
  suggestion?: string;
  result?: IQueryResult;
  executionTime?: number;
  isExecuting?: boolean;
  executionStartTime?: number;
  chartOption?: Record<string, unknown>;
  // Chart recommendation from backend
  recommendedChart?: string;
  chartXAxis?: string;
  chartYAxis?: string;
  chartDimension?: string;
  chartDimensions?: string[];
  chartXAxisFormat?: string;
  chartYAxisFormat?: string;
  chartLineVariant?: string;
  chartPieVariant?: string;
  chartBarOrientation?: string;
  chartOrder?: string;
  visualizationOnly?: boolean;
  // Per-query interpretation from LLM
  interpretation?: string;
  isInterpreting?: boolean;
  // Python analysis in progress indicator
  isAnalyzingPython?: boolean;
  // Multi-aspect analysis fields (populated only inside multi-aspect messages).
  aspectId?: string;
  aspectReason?: string;
  aspectInsight?: string;
  aspectErrorMessage?: string;
}

export interface IClarificationOption {
  label: string;
  query: string;
}

export interface IDisambiguationOption {
  label: string;
  queryType: string; // "DATA" or "CHAT"
  refinedQuery: string;
}

interface IChartUpdateAction {
  target?: string;
  queryIndex?: number;
  chartType?: string;
  chartTitle?: string;
  message?: string;
}

export interface ISuggestedFollowUp {
  title: string;
  question: string;
  reason?: string;
  type?: string;
}

export interface IToolApprovalParam {
  name: string;
  displayName: string;
  type: "text" | "textarea" | "dropdown" | "html" | "autocomplete";
  value: string;
  options?: Array<{ label: string; value: string }>;
  optionsEndpoint?: string;
  required: boolean;
}

export interface IToolApprovalRequest {
  requestId: string;
  toolName: string;
  toolDisplayName: string;
  description?: string;
  target?: string;
  parameters: IToolApprovalParam[];
  /** Set after user approves/denies — "approved", "denied", or "expired" (server timeout) */
  resolved?: "approved" | "denied" | "expired";
  /** Tool execution result — set after tool runs */
  executionSuccess?: boolean;
  executionError?: string;
}

export interface IPythonOutput {
  stdout: string;
  charts?: string[]; // base64-encoded PNG images
}

export interface ISectionCitation {
  number: number;
  type: 'database' | 'web';
  table?: string;
  query?: string;
  url?: string;
  title: string;
}

export interface IReportTable {
  caption?: string;
  markdown?: string;
  headers?: string[];
  rows?: string[][];
}

export interface IReportSection {
  title: string;
  content: string;
  tables?: IReportTable[];
  citations?: ISectionCitation[];
}

export interface IResearchReport {
  title: string;
  sections?: IReportSection[];
  mdContent?: string;
  citations?: Array<{ id: string; table: string; query: string; description: string }>;
  webSources?: Array<{ url: string; title: string; domain?: string; favicon?: string }>;
}

export interface IResearchPlan {
  title: string;
  steps: Array<{
    label: string;
    description?: string;
    icon?: 'search' | 'globe' | 'chart' | 'document' | 'clock';
    source?: 'database' | 'web' | 'both';
  }>;
  estimatedTime?: string;
  buttonLabel?: string;
  editPlanLabel?: string;
}

export interface IMessage {
  id: string;
  dbId?: number; // backend persisted ID
  role: "user" | "assistant" | "system";
  content: string;
  streamingContent?: string;
  isStreaming?: boolean;
  isThinking?: boolean;
  thinkingSteps?: IThinkingStep[];
  queries?: IQuery[];
  timestamp: number;
  chatRoomId?: number;
  // Extended fields
  generatedSql?: string;
  needsExecution?: boolean;
  /** Execution mode captured when this assistant message/request was created. */
  executionMode?: string;
  needsClarification?: boolean;
  clarificationOptions?: IClarificationOption[];
  needsDisambiguation?: boolean;
  disambiguationOptions?: IDisambiguationOption[];
  needsDateRange?: boolean;
  overview?: string;
  additionalInsightContext?: string;
  schemaInfo?: ISchemaInfoEntry[];
  feedback?: FeedbackType;
  interpretation?: string;
  // Execution result data (for chart reconstruction on reload)
  resultData?: any;
  suggestedFollowUps?: ISuggestedFollowUp[];
  // Tool approval fields
  toolApproval?: IToolApprovalRequest;
  // Python output fields
  pythonOutput?: IPythonOutput;
  // Deep Research fields
  researchPlan?: IResearchPlan;
  researchReport?: IResearchReport;
  researchSessionId?: number;
  isResearchPlan?: boolean;
  // Infographic fields
  infographicHtml?: string;
  isInfographicCard?: boolean;
  // Multi-aspect analysis fields. When multiAspect is true, queries[] holds
  // 2-3 aspect IQuery items (with aspectId/aspectInsight/aspectErrorMessage),
  // and synthesis is the cross-aspect narrative rendered below the card grid.
  multiAspect?: boolean;
  synthesis?: string;
  synthesisGoal?: string;
  /**
   * Multimodal attachments. Populated on user messages from the
   * pending-attachments composer and on assistant messages from the
   * backend list endpoint join.
   */
  attachments?: IAttachment[];
  /**
   * Set on the assistant turn when the server silently bumped the
   * selected model because the requested one couldn't handle one of
   * the attachments. Surfaces as a toast + a small badge on the
   * message bubble.
   */
  modelSwitched?: IModelSwitched;
}

export interface IChatRoom {
  id: number;
  title: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Clean SQL string by removing markdown artifacts (closing ```, **Explanation:** lines, etc.)
 */
function cleanSql(sql: string): string {
  if (!sql) return sql;
  return (
    sql
      // Remove closing markdown code block fences
      .replace(/```\s*$/gm, "")
      // Remove localized markdown explanation headings and everything after them
      .replace(/\n\s*\*\*[^*\n]{0,80}:\s*\*\*[\s\S]*$/i, "")
      // Remove leading/trailing whitespace
      .trim()
  );
}

function normalizeQueryResult(rawResult: any): IQueryResult | undefined {
  if (!rawResult) return undefined;
  return {
    headerList: rawResult.headerList || [],
    dataList: rawResult.dataList || [],
    success: rawResult.success !== false,
    message: rawResult.message,
  };
}

function firstAutoResult(responseData: any): IQueryResult | undefined {
  const resultData = responseData?.resultData;
  if (!Array.isArray(resultData) || resultData.length === 0) return undefined;
  return normalizeQueryResult(resultData[0]);
}

/**
 * Generate a meaningful title from a SQL query.
 * Extracts the main operation and table name(s) to create a concise description.
 * Examples:
 *   "SELECT * FROM users WHERE ..." → "Users Query"
 *   "SELECT count(*) FROM orders GROUP BY status" → "Orders Summary"
 *   "SELECT u.name, o.total FROM users u JOIN orders o ..." → "Users & Orders"
 */
export function generateTitleFromSql(sql: string, index?: number): string {
  if (!sql) return index != null ? `Query ${index + 1}` : "Query";
  const normalized = sql.replace(/\s+/g, " ").trim().toUpperCase();

  // Extract table names from FROM / JOIN clauses
  const tableNames: string[] = [];
  const fromMatch = normalized.match(/FROM\s+([A-Z0-9_.]+)/i);
  if (fromMatch) tableNames.push(fromMatch[1]);
  const joinMatches = normalized.matchAll(/JOIN\s+([A-Z0-9_.]+)/gi);
  for (const m of joinMatches) {
    if (!tableNames.includes(m[1])) tableNames.push(m[1]);
  }

  // Detect aggregation
  const hasAgg = /\b(COUNT|SUM|AVG|MIN|MAX|GROUP\s+BY)\b/i.test(normalized);

  // Format table names: strip schema prefix, title-case
  const formatTable = (t: string) => {
    const parts = t.split(".");
    const name = parts[parts.length - 1];
    return name.charAt(0).toUpperCase() + name.slice(1).toLowerCase();
  };

  if (tableNames.length === 0) {
    return index != null ? `Query ${index + 1}` : "Query";
  }

  const mainTable = formatTable(tableNames[0]);
  if (tableNames.length >= 2) {
    const secondTable = formatTable(tableNames[1]);
    return `${mainTable} & ${secondTable}`;
  }

  return hasAgg ? `${mainTable} Summary` : `${mainTable} Query`;
}

/**
 * Parse streaming markdown into structured queries (React parity).
 * Format: Overview text + --- + ## Title + ```sql``` + **Explanation:** + > 💡 Suggestion
 */
export function parseMarkdownToQueries(markdown: string): {
  overview: string;
  queries: Array<{
    title: string;
    sql: string;
    explanation: string;
    suggestion?: string;
  }>;
  streamingText: string;
} {
  if (!markdown) return { overview: "", queries: [], streamingText: "" };

  const queries: Array<{
    title: string;
    sql: string;
    explanation: string;
    suggestion?: string;
  }> = [];
  let overview = "";
  let streamingText = "";

  // Split by horizontal rule (---) to separate overview and queries
  const firstHrIndex = markdown.indexOf("\n---\n");
  if (firstHrIndex === -1) {
    overview = markdown.trim();
    return { overview, queries: [], streamingText: "" };
  }

  overview = markdown.substring(0, firstHrIndex).trim();
  const afterOverview = markdown.substring(firstHrIndex + 5);

  // Find all ## headers to split into query sections
  const headerPositions: number[] = [];
  let searchPos = 0;
  while (searchPos < afterOverview.length) {
    const idx = afterOverview.indexOf("\n## ", searchPos);
    if (idx === -1) {
      if (searchPos === 0 && afterOverview.startsWith("## ")) {
        headerPositions.push(0);
      }
      break;
    }
    headerPositions.push(idx + 1);
    searchPos = idx + 4;
  }
  if (headerPositions.length === 0 || headerPositions[0] !== 0) {
    if (afterOverview.trimStart().startsWith("## ")) {
      const firstHeaderPos = afterOverview.indexOf("## ");
      if (
        firstHeaderPos !== -1 &&
        (headerPositions.length === 0 || firstHeaderPos < headerPositions[0])
      ) {
        headerPositions.unshift(firstHeaderPos);
      }
    }
  }

  for (let i = 0; i < headerPositions.length; i++) {
    const startIdx = headerPositions[i];
    const endIdx =
      i < headerPositions.length - 1
        ? headerPositions[i + 1]
        : afterOverview.length;
    const section = afterOverview.substring(startIdx, endIdx);

    let suggestion: string | undefined;
    if (i > 0) {
      const betweenContent = afterOverview.substring(
        headerPositions[i - 1],
        startIdx,
      );
      const suggestionMatch = betweenContent.match(/>\s*💡?\s*([^\n]+)/);
      if (suggestionMatch) suggestion = suggestionMatch[1].trim();
    }

    const titleMatch = section.match(/^## (.+?)(?:\n|$)/);
    const title = titleMatch ? `## ${titleMatch[1].trim()}` : "";

    let sql = "";
    const sqlBlockStart = section.indexOf("```sql");
    if (sqlBlockStart !== -1) {
      const contentStart = section.indexOf("\n", sqlBlockStart) + 1;
      const restOfSection = section.substring(contentStart);
      const closingBackticks = restOfSection.indexOf("```");
      if (closingBackticks !== -1) {
        sql = restOfSection.substring(0, closingBackticks).trim();
      } else {
        // Streaming: no closing backticks yet - try to find SQL end
        const semiIdx = restOfSection.lastIndexOf(";");
        if (semiIdx !== -1) {
          sql = restOfSection.substring(0, semiIdx + 1).trim();
        } else {
          sql = restOfSection.trim();
        }
      }
      sql = cleanSql(sql);
    }

    let explanation = "";
    const explMatch = section.match(
      /\*\*Explanation:\*\*\s*\n([\s\S]*?)(?=\n---|$)/,
    );
    if (explMatch) {
      explanation = explMatch[1].trim();
    } else {
      const sqlEndIdx = section.indexOf("```", section.indexOf("```sql") + 6);
      if (sqlEndIdx !== -1) {
        const afterSql = section.substring(sqlEndIdx + 3).trim();
        explanation = afterSql.replace(/^\*\*Explanation:\*\*\s*/, "").trim();
      }
    }

    if (title) queries.push({ title, sql, explanation, suggestion });
  }

  if (queries.length > 0) {
    const lastQuery = queries[queries.length - 1];
    if (!lastQuery.sql && !lastQuery.explanation) {
      streamingText = lastQuery.title;
    }
  }

  return { overview, queries, streamingText };
}

// ============================================================
// State
// ============================================================

let chatRooms = $state<IChatRoom[]>([]);
let currentRoomId = $state<number | null>(null);
let messages = $state<IMessage[]>([]);
let isLoadingMessages = $state(false);
let thinkingInterval = $state<ReturnType<typeof setInterval> | null>(null);
let streamingByRoom = $state<Record<number, boolean>>({});
let waitingApprovalByRoom = $state<Record<number, boolean>>({});

// Messages cache per room
const messagesCache = new Map<number, IMessage[]>();
const abortControllersByRoom = new Map<number, AbortController>();

// ============================================================
// Getters
// ============================================================

export function getAIChatStore() {
  return {
    get chatRooms() {
      return chatRooms;
    },
    get currentRoomId() {
      return currentRoomId;
    },
    get messages() {
      return messages;
    },
    get isStreaming() {
      return currentRoomId != null ? !!streamingByRoom[currentRoomId] : false;
    },
    get isWaitingApproval() {
      return currentRoomId != null ? !!waitingApprovalByRoom[currentRoomId] : false;
    },
    get isLoadingMessages() {
      return isLoadingMessages;
    },
    get streamingByRoom() {
      return streamingByRoom;
    },
  };
}

// ============================================================
// Chat Room Management
// ============================================================

export async function fetchChatRooms() {
  try {
    const user = getUserStore().curUser;
    const userId = user?.id ?? 1;
    const res = await listChatRooms({ userId });
    chatRooms = Array.isArray(res)
      ? res.map((r) => ({
          id: (r as any).id,
          title: (r as any).title,
          createdAt: (r as any).gmtCreate,
          updatedAt: (r as any).gmtModified,
        }))
      : [];
  } catch {
    chatRooms = [];
  }
}

// Callback for research restore (set from page to avoid circular dependency)
let onRoomSwitchCallback: ((messages: IMessage[]) => void) | null = null;

export function setOnRoomSwitch(cb: (messages: IMessage[]) => void) {
  onRoomSwitchCallback = cb;
}

export async function setCurrentRoom(roomId: number | null) {
  // Save current messages to cache
  if (currentRoomId !== null) {
    messagesCache.set(currentRoomId, [...messages]);
  }
  currentRoomId = roomId;

  // Restore from cache or load from API
  if (roomId !== null) {
    if (messagesCache.has(roomId)) {
      messages = messagesCache.get(roomId)!;
    } else {
      await loadMessages(roomId);
    }
  } else {
    messages = [];
  }

  // Notify page for research restore
  if (onRoomSwitchCallback) {
    onRoomSwitchCallback(messages);
  }
}

export async function renameChatRoom(roomId: number, title: string) {
  try {
    await updateChatRoom({ id: roomId, title });
    chatRooms = chatRooms.map((r) => (r.id === roomId ? { ...r, title } : r));
  } catch {
    /* ignore */
  }
}

export async function removeChatRoom(roomId: number) {
  try {
    await deleteChatRoom({ id: roomId });
    chatRooms = chatRooms.filter((r) => r.id !== roomId);
    messagesCache.delete(roomId);
    if (currentRoomId === roomId) {
      currentRoomId = null;
      messages = [];
    }
  } catch {
    /* ignore */
  }
}

export async function ensureChatRoom(firstMessageContent: string): Promise<number> {
  if (currentRoomId) return currentRoomId;

  const user = getUserStore().curUser;
  const userId = user?.id ?? 1;
  const title =
    firstMessageContent.length > 50
      ? firstMessageContent.slice(0, 50) + "..."
      : firstMessageContent;

  const roomId = await createChatRoom({
    conversationId: uuidv4(),
    title,
    userId,
  });
  currentRoomId = roomId;
  await fetchChatRooms();
  return roomId;
}

// ============================================================
// Message Persistence
// ============================================================

async function loadMessages(roomId: number) {
  isLoadingMessages = true;
  try {
    const res = await getMessagesByChatRoomId({ chatRoomId: roomId });
    if (Array.isArray(res)) {
      messages = res.map((m: IChatMessage) => {
        const parsed = deserializeMessage(m);
        return parsed;
      });
      messagesCache.set(roomId, [...messages]);
    } else {
      messages = [];
    }
  } catch {
    messages = [];
  } finally {
    isLoadingMessages = false;
  }
}

function deserializeMessage(m: IChatMessage): IMessage {
  let content = m.content;
  let queries: IQuery[] = [];
  let overview: string | undefined;
  let feedback: FeedbackType | undefined;
  let generatedSql: string | undefined;
  let resultData: any = undefined;
  let researchReport: IResearchReport | undefined;
  let researchSessionId: number | undefined;
  let infographicHtml: string | undefined;
  let schemaInfo: ISchemaInfoEntry[] | undefined;
  let isResearchPlan = false;
  let researchPlan: IResearchPlan | undefined;
  let needsClarification = false;
  let clarificationOptions: IClarificationOption[] | undefined;
  let needsDisambiguation = false;
  let disambiguationOptions: IDisambiguationOption[] | undefined;
  let needsDateRange = false;
  let executionMode: string | undefined;
  let additionalInsightContext: string | undefined;
  let suggestedFollowUps: ISuggestedFollowUp[] | undefined;
  let isInfographicCard = false;
  let toolApproval: IToolApprovalRequest | undefined;
  let multiAspect: boolean | undefined;
  let synthesis: string | undefined;
  let synthesisGoal: string | undefined;

  // Try to parse __meta__ from content
  try {
    if (content.includes("__meta__")) {
      const metaIdx = content.indexOf("__meta__");
      const metaJson = content.slice(metaIdx + 8);
      content = content.slice(0, metaIdx).trim();
      const meta = JSON.parse(metaJson);
      queries = meta.queries || [];
      overview = meta.overview;
      if (meta.multiAspect) multiAspect = true;
      if (meta.synthesis) synthesis = meta.synthesis;
      if (meta.synthesisGoal) synthesisGoal = meta.synthesisGoal;
      feedback = meta.feedback;
      generatedSql = meta.generatedSql;
      executionMode = meta.executionMode;
      additionalInsightContext = meta.additionalInsightContext;
      suggestedFollowUps = meta.suggestedFollowUps;
      resultData = meta.resultData;
      researchReport = meta.researchReport;
      researchSessionId = meta.researchSessionId;
      infographicHtml = meta.infographicHtml;
      schemaInfo = meta.schemaInfo;
      if (meta.isResearchPlan) isResearchPlan = true;
      if (meta.researchPlan) researchPlan = meta.researchPlan;
      if (meta.needsClarification) needsClarification = true;
      if (meta.clarificationOptions)
        clarificationOptions = meta.clarificationOptions;
      if (meta.needsDisambiguation) needsDisambiguation = true;
      if (meta.disambiguationOptions)
        disambiguationOptions = meta.disambiguationOptions;
      if (meta.needsDateRange) needsDateRange = true;
      if (meta.isInfographicCard) isInfographicCard = true;
      if (meta.toolApproval) {
        const restored = meta.toolApproval as IToolApprovalRequest;
        // Pending approvals from previous sessions are expired (server timeout is 120s)
        toolApproval = restored.resolved ? restored : { ...restored, resolved: "expired" };
      }

      // Restore query result data from meta
      if (queries.length > 0 && meta.queryResults) {
        queries = queries.map((q: IQuery, i: number) => {
          const savedResult = meta.queryResults?.[i];
          if (savedResult) {
            return { ...q, result: savedResult };
          }
          return q;
        });
      }
    }
  } catch {
    /* ignore parse errors */
  }

  // Strip "Thinking" blocks and SQL code blocks from content if queries exist (match React behavior)
  if (queries.length > 0 || generatedSql) {
    content = content
      .replace(/> \*\*Thinking\*\*[\s\S]*?(?=\n(?!>)|$)/g, "")
      .replace(/```(?:sql)?[\s\S]*?```\n*/gi, "")
      .trim();
  }

  return {
    id: `db-${m.id}`,
    dbId: m.id,
    role: m.role,
    content,
    queries,
    overview,
    feedback,
    generatedSql,
    executionMode,
    additionalInsightContext,
    suggestedFollowUps,
    resultData,
    researchReport,
    researchSessionId,
    infographicHtml,
    isInfographicCard: isInfographicCard || undefined,
    schemaInfo,
    isResearchPlan: isResearchPlan || undefined,
    researchPlan,
    needsClarification: needsClarification || undefined,
    clarificationOptions,
    needsDisambiguation: needsDisambiguation || undefined,
    disambiguationOptions,
    needsDateRange: needsDateRange || undefined,
    toolApproval,
    multiAspect,
    synthesis,
    synthesisGoal,
    timestamp: new Date(m.gmtCreate).getTime(),
    chatRoomId: m.chatRoomId,
    // Hydrated by AiChatRoomController.listMessages via the N:N join
    // — keep the array undefined when empty so downstream rendering
    // can use the truthy check without an extra length guard.
    attachments: m.attachments && m.attachments.length > 0 ? m.attachments : undefined,
  };
}

function serializeMessageContent(msg: IMessage): string {
  const meta: Record<string, unknown> = {};
  if (msg.queries && msg.queries.length > 0) {
    meta.queries = msg.queries.map((q) => ({
      sql: q.sql,
      title: q.title,
      explanation: q.explanation,
      overview: q.overview,
      suggestion: q.suggestion,
      recommendedChart: q.recommendedChart,
      chartXAxis: q.chartXAxis,
      chartYAxis: q.chartYAxis,
      chartDimension: q.chartDimension,
      chartDimensions: q.chartDimensions,
      chartXAxisFormat: q.chartXAxisFormat,
      chartYAxisFormat: q.chartYAxisFormat,
      chartLineVariant: q.chartLineVariant,
      chartPieVariant: q.chartPieVariant,
      chartBarOrientation: q.chartBarOrientation,
      chartOrder: q.chartOrder,
      visualizationOnly: q.visualizationOnly,
      interpretation: q.interpretation,
      aspectId: q.aspectId,
      aspectReason: q.aspectReason,
      aspectInsight: q.aspectInsight,
      aspectErrorMessage: q.aspectErrorMessage,
    }));
    // Save query results for restoration
    const queryResults = msg.queries.map((q) => q.result || null);
    if (queryResults.some((r) => r !== null)) {
      meta.queryResults = queryResults;
    }
  }
  if (msg.overview) meta.overview = msg.overview;
  if (msg.suggestedFollowUps && msg.suggestedFollowUps.length > 0) {
    meta.suggestedFollowUps = msg.suggestedFollowUps;
  }
  if (msg.feedback) meta.feedback = msg.feedback;
  if (msg.generatedSql) meta.generatedSql = msg.generatedSql;
  if (msg.executionMode) meta.executionMode = msg.executionMode;
  if (msg.additionalInsightContext) meta.additionalInsightContext = msg.additionalInsightContext;
  if (msg.resultData) meta.resultData = msg.resultData;
  if (msg.researchReport) meta.researchReport = msg.researchReport;
  if (msg.researchSessionId) meta.researchSessionId = msg.researchSessionId;
  if (msg.infographicHtml) meta.infographicHtml = msg.infographicHtml;
  if (msg.isInfographicCard) meta.isInfographicCard = true;
  if (msg.schemaInfo && msg.schemaInfo.length > 0)
    meta.schemaInfo = msg.schemaInfo;
  if (msg.isResearchPlan) meta.isResearchPlan = true;
  if (msg.researchPlan) meta.researchPlan = msg.researchPlan;
  if (msg.needsClarification) meta.needsClarification = true;
  if (msg.clarificationOptions && msg.clarificationOptions.length > 0)
    meta.clarificationOptions = msg.clarificationOptions;
  if (msg.needsDisambiguation) meta.needsDisambiguation = true;
  if (msg.disambiguationOptions && msg.disambiguationOptions.length > 0)
    meta.disambiguationOptions = msg.disambiguationOptions;
  if (msg.needsDateRange) meta.needsDateRange = true;
  if (msg.toolApproval) meta.toolApproval = msg.toolApproval;
  if (msg.multiAspect) meta.multiAspect = true;
  if (msg.synthesis) meta.synthesis = msg.synthesis;
  if (msg.synthesisGoal) meta.synthesisGoal = msg.synthesisGoal;

  if (Object.keys(meta).length > 0) {
    return msg.content + "__meta__" + JSON.stringify(meta);
  }
  return msg.content;
}

async function persistMessage(
  msg: IMessage,
  roomId: number,
): Promise<number | null> {
  try {
    const user = getUserStore().curUser;
    const userId = user?.id ?? 1;
    const attachmentIds = (msg.attachments || [])
      .map((a) => a.id)
      .filter((id): id is number => typeof id === "number");
    const dbId = await saveMessageApi({
      chatRoomId: roomId,
      role: msg.role,
      content: serializeMessageContent(msg),
      userId,
      ...(attachmentIds.length > 0 ? { attachmentIds } : {}),
    });
    return dbId;
  } catch {
    return null;
  }
}

async function updatePersistedMessage(msg: IMessage) {
  if (!msg.dbId) return;
  try {
    await updateMessageApi({
      id: msg.dbId,
      content: serializeMessageContent(msg),
    });
  } catch {
    /* ignore */
  }
}

function normalizeChartUpdateType(chartType?: string): string | null {
  if (!chartType) return null;
  const normalized = chartType.trim().toUpperCase().replace(/[\s-]+/g, "_");
  return ["BAR", "LINE", "PIE", "SCATTER", "TABLE", "CARD"].includes(normalized)
    ? normalized
    : null;
}

function buildChartOnlyQueryFromLatestResult(roomId: number, action: IChartUpdateAction): IQuery | null {
  const chartType = normalizeChartUpdateType(action.chartType);
  if (!chartType) return null;

  const roomMessages = getMessagesForRoom(roomId);
  for (let i = roomMessages.length - 1; i >= 0; i--) {
    const msg = roomMessages[i];
    if (msg.role !== "assistant" || !msg.queries?.length) continue;

    const explicitIndex =
      typeof action.queryIndex === "number" &&
      action.queryIndex >= 0 &&
      action.queryIndex < msg.queries.length
        ? action.queryIndex
        : -1;
    const queryIndex =
      explicitIndex >= 0
        ? explicitIndex
        : Math.max(0, msg.queries.findIndex((q) => q.result));
    const targetQuery = msg.queries[queryIndex];
    if (!targetQuery?.result) continue;

    const proposedTitle = action.chartTitle?.trim();
    return {
      ...targetQuery,
      title: proposedTitle ? proposedTitle : targetQuery.title,
      explanation: undefined,
      suggestion: undefined,
      interpretation: undefined,
      isInterpreting: false,
      isAnalyzingPython: false,
      recommendedChart: chartType,
      visualizationOnly: chartType !== "TABLE",
    };
  }
  return null;
}

// ============================================================
// Message Management
// ============================================================

export function addMessage(msg: IMessage) {
  addMessageToRoom(msg, msg.chatRoomId ?? currentRoomId ?? undefined);
}

export function updateLastAssistantMessage(update: Partial<IMessage>) {
  const lastIdx = messages.length - 1;
  if (lastIdx >= 0 && messages[lastIdx].role === "assistant") {
    messages = [
      ...messages.slice(0, lastIdx),
      { ...messages[lastIdx], ...update },
    ];
  }
}

export function updateMessageById(msgId: string, update: Partial<IMessage>) {
  if (currentRoomId != null) {
    updateMessageByIdInRoom(currentRoomId, msgId, update);
  } else {
    messages = messages.map((m) => (m.id === msgId ? { ...m, ...update } : m));
  }
}

export function getMessagesForRoom(roomId: number): IMessage[] {
  return currentRoomId === roomId ? messages : messagesCache.get(roomId) || [];
}

function setRoomMessages(roomId: number, nextMessages: IMessage[]) {
  messagesCache.set(roomId, [...nextMessages]);
  if (currentRoomId === roomId) {
    messages = nextMessages;
  }
}

function addMessageToRoom(msg: IMessage, roomId?: number) {
  if (roomId == null) {
    messages = [...messages, msg];
    return;
  }
  setRoomMessages(roomId, [...getMessagesForRoom(roomId), { ...msg, chatRoomId: msg.chatRoomId ?? roomId }]);
}

function findMessageByIdInRoom(roomId: number, msgId: string): IMessage | undefined {
  return getMessagesForRoom(roomId).find((m) => m.id === msgId);
}

export function updateMessageByIdInRoom(roomId: number, msgId: string, update: Partial<IMessage>) {
  const nextMessages = getMessagesForRoom(roomId).map((m) =>
    m.id === msgId ? { ...m, ...update, chatRoomId: m.chatRoomId ?? roomId } : m,
  );
  setRoomMessages(roomId, nextMessages);
}

function setRoomStreaming(roomId: number, streaming: boolean) {
  streamingByRoom = { ...streamingByRoom, [roomId]: streaming };
}

function setRoomWaitingApproval(roomId: number, waiting: boolean) {
  waitingApprovalByRoom = { ...waitingApprovalByRoom, [roomId]: waiting };
}

export function isRoomStreaming(roomId?: number | null) {
  return roomId != null ? !!streamingByRoom[roomId] : false;
}

export function stopStreaming(roomId: number | null = currentRoomId) {
  if (roomId == null) return;
  abortControllersByRoom.get(roomId)?.abort();
  abortControllersByRoom.delete(roomId);
  setRoomStreaming(roomId, false);
  setRoomWaitingApproval(roomId, false);
  clearThinkingSimulation();
  const roomMessages = getMessagesForRoom(roomId);
  const activeAssistant = [...roomMessages].reverse().find((m) => m.role === "assistant" && m.isStreaming);
  if (activeAssistant) {
    updateMessageByIdInRoom(roomId, activeAssistant.id, {
      isStreaming: false,
      isThinking: false,
      thinkingSteps: undefined,
    });
  }
}

/**
 * Submit tool approval/denial to the backend.
 * Called from the ToolApproval component when user approves or denies.
 */
export async function submitToolApproval(
  msgId: string,
  requestId: string,
  approved: boolean,
  parameters?: Record<string, string>,
) {
  try {
    console.log("[ToolApproval] Sending approval:", { requestId, approved, paramCount: parameters ? Object.keys(parameters).length : 0 });
    const token = typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
    const res = await fetch(`${getBaseURL()}/api/ai/agent/tool/approve`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { INQUERY: token } : {}),
      },
      body: JSON.stringify({ requestId, approved, parameters }),
    });

    let serverAccepted = false;
    if (res.ok) {
      try {
        const result = await res.json();
        serverAccepted = result?.data === true;
        console.log("[ToolApproval] Server response:", { status: res.status, accepted: serverAccepted, result });
      } catch {
        serverAccepted = false;
        console.error("[ToolApproval] Failed to parse server response");
      }
    } else {
      console.error("[ToolApproval] HTTP error:", res.status, res.statusText);
    }

    if (!serverAccepted) {
      console.error("[ToolApproval] Not accepted — server returned found=false (request may have timed out)");
    }

    // Mark approval as resolved
    const msg = messages.find((m) => m.id === msgId);
    const roomId = msg?.chatRoomId ?? currentRoomId;
    if (msg?.toolApproval) {
      const resolvedApproval: IToolApprovalRequest = {
        ...msg.toolApproval,
        resolved: serverAccepted
          ? (approved ? "approved" : "denied")
          : "expired",
      };
      updateMessageById(msgId, { toolApproval: resolvedApproval });
      const updatedMsg: IMessage = { ...msg, toolApproval: resolvedApproval };
      updatePersistedMessage(updatedMsg);

      // Show loading indicator while MCP tool executes (until next SSE event arrives)
      if (serverAccepted && approved) {
        if (roomId != null) {
          setRoomStreaming(roomId, true);
          setRoomWaitingApproval(roomId, false);
          updateMessageByIdInRoom(roomId, msgId, {
            isThinking: true,
            thinkingSteps: [{ title: "Executing action...", status: "running" as const }],
          });
        }
      } else {
        if (roomId != null) setRoomWaitingApproval(roomId, false);
      }
    }
  } catch (e) {
    console.error("Tool approval submission error:", e);
    // Mark as expired on network error
    const msg = messages.find((m) => m.id === msgId);
    const roomId = msg?.chatRoomId ?? currentRoomId;
    if (roomId != null) setRoomWaitingApproval(roomId, false);
    if (msg?.toolApproval) {
      const resolvedApproval: IToolApprovalRequest = {
        ...msg.toolApproval,
        resolved: "expired",
      };
      updateMessageById(msgId, { toolApproval: resolvedApproval });
      const updatedMsg: IMessage = { ...msg, toolApproval: resolvedApproval };
      updatePersistedMessage(updatedMsg);
    }
  }
}

function clearThinkingSimulation() {
  if (thinkingInterval) {
    clearInterval(thinkingInterval);
    thinkingInterval = null;
  }
}

function compactThinkingTitle(title: string): string {
  const text = (title || "").trim();
  const lower = text.toLowerCase();
  if (!text) return i18n("aichat.thinking.answer");

  if (lower.includes("web") || lower.includes("wiki") || lower.includes("slack")
    || lower.includes("jira") || lower.includes("github") || lower.includes("external")) {
    return i18n("aichat.thinking.external");
  }
  if (lower.includes("table") || lower.includes("column") || lower.includes("schema")
    || lower.includes("metadata") || lower.includes("lineage")) {
    return i18n("aichat.thinking.schema");
  }
  if (lower.includes("sql") || lower.includes("query") || lower.includes("chart")
    || lower.includes("data")) {
    return i18n("aichat.thinking.data");
  }
  if (lower.includes("analyz") || lower.includes("deciding") || lower.includes("analyze")) {
    return i18n("aichat.thinking.analyze");
  }
  return text;
}

function currentThinkingStep(title: string, description?: string): IThinkingStep[] {
  return [{
    title: compactThinkingTitle(title),
    description,
    status: "running" as const,
  }];
}

// ============================================================
// Schema Query Handler
// ============================================================

/**
 * Handle schema queries: look up table metadata, catalog data, then summarize with LLM.
 */
export async function handleSchemaQuery(
  targetTables: string[],
  userMessageContent: string,
  connectionList: any[] | null,
  selectedDatabase: string | undefined,
  targetRoomId: number | null = currentRoomId,
  assistantMsgId?: string,
): Promise<void> {
  const selectedConn = connectionList?.find(
    (c: any) => String(c.id) === String(selectedDatabase),
  ) as any;
  const dataSourceId = selectedConn?.id || 0;
  const defaultDbName = selectedConn?.databaseName || "";
  const defaultSchemaName = selectedConn?.schemaName || "";

  const tableNames = targetTables.join(", ");
  const thinkingMessages = [
    `Searching for **${tableNames}**...`,
    `Connecting to database...`,
    `Fetching table structure...`,
  ];
  let msgIdx = 0;

  const baseSteps = currentThinkingStep(thinkingMessages[0]);
  if (targetRoomId != null && assistantMsgId) {
    updateMessageByIdInRoom(targetRoomId, assistantMsgId, { thinkingSteps: baseSteps, isThinking: true });
  } else {
    updateLastAssistantMessage({ thinkingSteps: baseSteps, isThinking: true });
  }
  msgIdx++;

  const interval = setInterval(() => {
    if (msgIdx < thinkingMessages.length) {
      const steps = currentThinkingStep(thinkingMessages[msgIdx]);
      if (targetRoomId != null && assistantMsgId) {
        updateMessageByIdInRoom(targetRoomId, assistantMsgId, { thinkingSteps: steps, isThinking: true });
      } else {
        updateLastAssistantMessage({ thinkingSteps: steps, isThinking: true });
      }
      msgIdx++;
    }
  }, 800);

  try {
    // Step 1: Expand table paths
    const expandedPaths = await Promise.all(
      targetTables.map(async (tablePath: string) => {
        const parts = tablePath.split(".");
        if (parts.length === 3) {
          return [
            { dbName: parts[0], schemaName: parts[1], tableName: parts[2] },
          ];
        } else if (parts.length === 2) {
          return [
            {
              dbName: defaultDbName,
              schemaName: parts[0],
              tableName: parts[1],
            },
          ];
        } else {
          try {
            const results = await searchTableByName({
              dataSourceId,
              tableName: tablePath,
            });
            if (results && results.length > 0) {
              return results.map((r: any) => ({
                dbName: r.databaseName,
                schemaName: r.schemaName,
                tableName: r.tableName,
              }));
            }
          } catch (_) {
            /* fallthrough */
          }
          return [
            {
              dbName: defaultDbName,
              schemaName: defaultSchemaName,
              tableName: tablePath,
            },
          ];
        }
      }),
    );

    const allPaths = expandedPaths.flat();

    // Step 2: Fetch column info + catalog for each table
    const schemaResults: ISchemaInfoEntry[] = await Promise.all(
      allPaths.map(async ({ dbName, schemaName, tableName }) => {
        try {
          const columnsRaw = await sqlService.getColumnList({
            dataSourceId,
            databaseName: dbName,
            schemaName,
            tableName,
          });
          let catalogData: { tableDescription?: string; columns?: any[] } = {};
          try {
            const cResult = await catalogService.queryCatalog({
              dataSourceId,
              databaseName: dbName,
              schemaName,
              tableName,
            });
            if (cResult) catalogData = cResult;
          } catch (_) {
            /* no catalog */
          }

          const catalogMap = new Map<string, any>();
          if (catalogData.columns)
            catalogData.columns.forEach((c: any) =>
              catalogMap.set(c.name?.toLowerCase(), c),
            );

          const columns: ISchemaColumn[] = (columnsRaw || [])
            .map((col: any) => {
              const colName = col.name || col.columnName;
              const cat = catalogMap.get(colName?.toLowerCase());
              return {
                name: colName,
                columnType: col.columnType || col.dataType || "unknown",
                nullable: col.nullable !== false,
                isPrimaryKey: col.primaryKey === true || col.pk === true,
                description: cat?.description || undefined,
                exampleValues: cat?.exampleValues || undefined,
              };
            })
            .filter(
              (c: any, i: number, arr: any[]) =>
                arr.findIndex((x) => x.name === c.name) === i,
            );

          return {
            tableName,
            schemaName,
            databaseName: dbName,
            columns,
            tableDescription: catalogData.tableDescription,
          };
        } catch (_) {
          return { tableName, schemaName, databaseName: dbName, columns: [] };
        }
      }),
    );

    clearInterval(interval);

    const foundTables = schemaResults.filter((s) => s.columns.length > 0);
    const fullPaths = foundTables.map((t) =>
      [t.databaseName, t.schemaName, t.tableName].filter(Boolean).join("."),
    );
    const schemaContent =
      foundTables.length > 0
        ? `Found **${fullPaths.join(", ")}**. Here is the table structure:`
        : `Could not find table structure for **${tableNames}**.`;

    const schemaUpdate: Partial<IMessage> = {
      content: schemaContent,
      isThinking: false,
      thinkingSteps: undefined,
      isStreaming: false,
      schemaInfo: foundTables,
    };
    if (targetRoomId != null && assistantMsgId) {
      updateMessageByIdInRoom(targetRoomId, assistantMsgId, schemaUpdate);
    } else {
      updateLastAssistantMessage(schemaUpdate);
    }

    // Save to DB
    const roomId = targetRoomId ?? currentRoomId ?? null;
    const userId = getUserStore().curUser?.id || 0;
    if (roomId) {
      const assistantMsg = assistantMsgId
        ? findMessageByIdInRoom(roomId, assistantMsgId)
        : messages[messages.length - 1];
      if (assistantMsg) {
        persistMessage(assistantMsg, roomId).then((dbId) => {
          if (dbId) updateMessageByIdInRoom(roomId, assistantMsg.id, { dbId });
        });
      }
    }

    // Step 3: LLM summary
    if (foundTables.length > 0) {
      const summaryMsgId = uuidv4();
      const summaryMsg: IMessage = {
        id: summaryMsgId,
        role: "assistant",
        content: "",
        timestamp: Date.now(),
        chatRoomId: roomId || undefined,
        isThinking: true,
        thinkingSteps: [{ title: "Generating summary...", status: "running" }],
      };
      if (roomId) addMessageToRoom(summaryMsg, roomId);
      else addMessage(summaryMsg);

      try {
        const schemaContext = foundTables
          .map((t) => {
            const fullName = [t.databaseName, t.schemaName, t.tableName]
              .filter(Boolean)
              .join(".");
            const desc = t.tableDescription
              ? `\nDescription: ${t.tableDescription}`
              : "";
            const cols = t.columns
              .map((c: ISchemaColumn) => {
                let info = `  - ${c.name} (${c.columnType})`;
                if (c.isPrimaryKey) info += " [PK]";
                if (c.description) info += `: ${c.description}`;
                return info;
              })
              .join("\n");
            return `Table: ${fullName}${desc}\nColumns:\n${cols}`;
          })
          .join("\n\n");

        const token = localStorage.getItem("Inquery") || "";
        const resp = await fetch(`${getBaseURL()}/api/ai/schema/summarize`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...(token ? { Inquery: token } : {}),
          },
          body: JSON.stringify({
            originalQuery: userMessageContent,
            schemaContext,
          }),
        });
        const result = await resp.json();
        const summary = result.data?.summary || result.data || "";

        const summaryUpdate: Partial<IMessage> = {
          content: summary,
          isThinking: false,
          thinkingSteps: undefined,
        };
        if (roomId) updateMessageByIdInRoom(roomId, summaryMsgId, summaryUpdate);
        else updateMessageById(summaryMsgId, summaryUpdate);

        if (roomId) {
          const savedSummaryMsg = findMessageByIdInRoom(roomId, summaryMsgId);
          if (savedSummaryMsg) {
            persistMessage(savedSummaryMsg, roomId).then((dbId) => {
              if (dbId) updateMessageByIdInRoom(roomId, summaryMsgId, { dbId });
            });
          }
        }
      } catch (err) {
        console.error("Failed to generate schema summary:", err);
        if (roomId) {
          setRoomMessages(roomId, getMessagesForRoom(roomId).filter((m) => m.id !== summaryMsgId));
        } else {
          messages = messages.filter((m) => m.id !== summaryMsgId);
        }
      }
    }
  } catch (err) {
    clearInterval(interval);
    console.error("Failed to fetch table schemas:", err);
    const errorUpdate: Partial<IMessage> = {
      content: `Failed to fetch table structure for **${tableNames}**.`,
      isThinking: false,
      thinkingSteps: undefined,
      isStreaming: false,
    };
    if (targetRoomId != null && assistantMsgId) {
      updateMessageByIdInRoom(targetRoomId, assistantMsgId, errorUpdate);
    } else {
      updateLastAssistantMessage(errorUpdate);
    }
  }
}

// ============================================================
// SQL Editing
// ============================================================

/** Update SQL for a specific query within a message (in-place editing) */
export function updateQuerySql(
  msgId: string,
  queryIndex: number,
  newSql: string,
) {
  const msg = messages.find((m) => m.id === msgId);
  if (!msg?.queries?.[queryIndex]) return;

  const updatedQueries = [...msg.queries];
  updatedQueries[queryIndex] = { ...updatedQueries[queryIndex], sql: newSql };
  updateMessageById(msgId, { queries: updatedQueries });
}

// ============================================================
// Query Execution
// ============================================================

export async function executeQuery(
  msgId: string,
  queryIndex: number,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    model?: string;
    originalUserQuery?: string;
  },
) {
  const msg = messages.find((m) => m.id === msgId);
  if (!msg?.queries?.[queryIndex]) return;

  const query = msg.queries[queryIndex];

  // Mark as executing
  const updatedQueries = [...msg.queries];
  updatedQueries[queryIndex] = {
    ...query,
    isExecuting: true,
    executionStartTime: Date.now(),
  };
  updateMessageById(msgId, { queries: updatedQueries });

  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";
    // Use AI agent execute API for chart recommendations
    console.log("[executeQuery] Calling /api/ai/agent/execute with:", {
      sql: query.sql?.substring(0, 100),
      dataSourceId: options.dataSourceId,
      databaseName: options.databaseName,
      schemaName: options.schemaName,
    });
    const response = await fetch(`${getBaseURL()}/api/ai/agent/execute`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
      body: JSON.stringify({
        sql: query.sql,
        originalQuery: msg.content,
        dataSourceId: options.dataSourceId,
        databaseName: options.databaseName,
        schemaName: options.schemaName,
      }),
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const result = await response.json();
    const executionTime =
      Date.now() -
      (updatedQueries[queryIndex].executionStartTime || Date.now());
    console.log("[executeQuery] API response:", {
      success: result.success,
      hasData: !!result.data,
      resultDataType: typeof result.data?.resultData,
      resultDataLength: Array.isArray(result.data?.resultData)
        ? result.data.resultData.length
        : "N/A",
      recommendedChart: result.data?.recommendedChart,
      errorMessage: result.errorMessage,
    });

    // Check for backend error
    if (result.success === false) {
      const finalQueries = [
        ...(messages.find((m) => m.id === msgId)?.queries || []),
      ];
      finalQueries[queryIndex] = {
        ...finalQueries[queryIndex],
        result: {
          headerList: [],
          dataList: [],
          success: false,
          message: result.errorMessage || "Execution failed",
        },
        isExecuting: false,
        executionStartTime: undefined,
      };
      updateMessageById(msgId, { queries: finalQueries });
      return;
    }

    const resultData = result.data?.resultData;
    const recommendedChart = result.data?.recommendedChart;

    if (resultData && Array.isArray(resultData) && resultData.length > 0) {
      const rawResult = resultData[0] as IQueryResult;
      console.log("[executeQuery] rawResult structure:", {
        headerListLength: rawResult.headerList?.length,
        headerListSample: rawResult.headerList?.slice(0, 2),
        dataListLength: rawResult.dataList?.length,
        dataListFirstRow: rawResult.dataList?.[0],
        dataListType: typeof rawResult.dataList?.[0],
        isArray: Array.isArray(rawResult.dataList?.[0]),
        allKeys: Object.keys(rawResult),
        recommendedChart,
      });

      const finalQueries = [
        ...(messages.find((m) => m.id === msgId)?.queries || []),
      ];
      finalQueries[queryIndex] = {
        ...finalQueries[queryIndex],
        result: rawResult,
        executionTime,
        isExecuting: false,
        executionStartTime: undefined,
        // Store chart recommendation from backend (default to TABLE if none)
        recommendedChart: recommendedChart || "TABLE",
        chartXAxis: result.data?.chartXAxis,
        chartYAxis: result.data?.chartYAxis,
        chartDimension: result.data?.chartDimension,
        chartDimensions: result.data?.chartDimensions,
        chartXAxisFormat: result.data?.chartXAxisFormat,
        chartYAxisFormat: result.data?.chartYAxisFormat,
        chartLineVariant: result.data?.chartLineVariant,
        chartPieVariant: result.data?.chartPieVariant,
        chartBarOrientation: result.data?.chartBarOrientation,
        chartOrder: result.data?.chartOrder,
      };
      updateMessageById(msgId, { queries: finalQueries });

      // Persist updated message
      const updatedMsg = messages.find((m) => m.id === msgId);
      if (updatedMsg) updatePersistedMessage(updatedMsg);

      // Interpretation + Python analysis after execution
      if (rawResult.success !== false) {
        try {
          const userMessages = messages.filter((m) => m.role === "user");
          const lastUserMsg = userMessages[userMessages.length - 1];
          const originalQuery =
            options.originalUserQuery || lastUserMsg?.content || msg.content;

          // Step 1: Run Python analysis first for large result sets (100+ rows)
          let pythonStats: string | null = null;
          const rowCount = rawResult.dataList?.length ?? 0;
          if (rowCount >= 100) {
            try {
              console.log(`[executeQuery] Running Python analysis for ${rowCount} rows...`);
              const pyQueries = [
                ...(messages.find((m) => m.id === msgId)?.queries || []),
              ];
              pyQueries[queryIndex] = {
                ...pyQueries[queryIndex],
                isAnalyzingPython: true,
              };
              updateMessageById(msgId, { queries: pyQueries });

              pythonStats = await requestPythonAnalysis(originalQuery, [rawResult]);

              const pyDoneQueries = [
                ...(messages.find((m) => m.id === msgId)?.queries || []),
              ];
              pyDoneQueries[queryIndex] = {
                ...pyDoneQueries[queryIndex],
                isAnalyzingPython: false,
              };
              updateMessageById(msgId, { queries: pyDoneQueries });
              console.log("[executeQuery] Python analysis done, stats length:", pythonStats?.length);
            } catch (pyError) {
              console.error("[executeQuery] Python analysis failed:", pyError);
              const pyErrQueries = [
                ...(messages.find((m) => m.id === msgId)?.queries || []),
              ];
              pyErrQueries[queryIndex] = {
                ...pyErrQueries[queryIndex],
                isAnalyzingPython: false,
              };
              updateMessageById(msgId, { queries: pyErrQueries });
            }
          }

          // Step 2: Run interpretation with Python stats as extra context
          console.log(
            "[executeQuery] Calling interpretResults...",
            { hasData: rowCount > 0, hasPythonStats: !!pythonStats },
          );

          const interpQueries = [
            ...(messages.find((m) => m.id === msgId)?.queries || []),
          ];
          interpQueries[queryIndex] = {
            ...interpQueries[queryIndex],
            isInterpreting: true,
          };
          updateMessageById(msgId, { queries: interpQueries });

          const interpretation = await interpretResults(query.sql, rawResult, {
            dataSourceId: options.dataSourceId,
            databaseName: options.databaseName,
            originalQuery,
            model: options.model,
            pythonAnalysis: pythonStats || undefined,
            additionalInsightContext: msg.additionalInsightContext,
          });

          const doneQueries = [
            ...(messages.find((m) => m.id === msgId)?.queries || []),
          ];
          doneQueries[queryIndex] = {
            ...doneQueries[queryIndex],
            interpretation: interpretation || undefined,
            isInterpreting: false,
          };
          updateMessageById(msgId, { queries: doneQueries });

          const persistedMsg = messages.find((m) => m.id === msgId);
          if (persistedMsg) updatePersistedMessage(persistedMsg);

          console.log("[executeQuery] Interpretation stored in query");
        } catch (interpretError) {
          console.error(
            "[executeQuery] Interpretation failed:",
            interpretError,
          );
          const errQueries = [
            ...(messages.find((m) => m.id === msgId)?.queries || []),
          ];
          errQueries[queryIndex] = {
            ...errQueries[queryIndex],
            isInterpreting: false,
          };
          updateMessageById(msgId, { queries: errQueries });
        }
      }
    } else if (
      typeof resultData === "string" &&
      resultData.startsWith("Error")
    ) {
      console.warn("[executeQuery] Backend returned error string:", resultData);
      const finalQueries = [
        ...(messages.find((m) => m.id === msgId)?.queries || []),
      ];
      finalQueries[queryIndex] = {
        ...finalQueries[queryIndex],
        result: {
          headerList: [],
          dataList: [],
          success: false,
          message: resultData,
        },
        isExecuting: false,
        executionStartTime: undefined,
      };
      updateMessageById(msgId, { queries: finalQueries });
    } else {
      console.warn("[executeQuery] No valid resultData:", {
        resultData,
        fullResult: result,
      });
      const finalQueries = [
        ...(messages.find((m) => m.id === msgId)?.queries || []),
      ];
      finalQueries[queryIndex] = {
        ...finalQueries[queryIndex],
        result: {
          headerList: [],
          dataList: [],
          success: false,
          message: "No results returned",
        },
        isExecuting: false,
        executionStartTime: undefined,
      };
      updateMessageById(msgId, { queries: finalQueries });

      // Persist updated message
      const updatedMsg = messages.find((m) => m.id === msgId);
      if (updatedMsg) updatePersistedMessage(updatedMsg);
    }
  } catch (e: any) {
    console.error("[executeQuery] Exception:", e);
    const finalQueries = [
      ...(messages.find((m) => m.id === msgId)?.queries || []),
    ];
    finalQueries[queryIndex] = {
      ...finalQueries[queryIndex],
      result: {
        headerList: [],
        dataList: [],
        success: false,
        message: e.message || "Execution failed",
      },
      isExecuting: false,
      executionStartTime: undefined,
    };
    updateMessageById(msgId, { queries: finalQueries });
  }
}

export async function executeAgentQuery(
  sql: string,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
  },
): Promise<any | null> {
  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";
    const response = await fetch(`${getBaseURL()}/api/ai/agent/execute`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
      body: JSON.stringify({ sql, ...options }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch {
    return null;
  }
}

// ============================================================
// Feedback
// ============================================================

export async function handleFeedback(
  msgId: string,
  type: "up" | "down",
  options: {
    chatRoomId?: number;
    dataSourceId?: number;
    question?: string;
  } = {},
) {
  const msg = messages.find((m) => m.id === msgId);
  if (!msg) return;

  const feedbackType: FeedbackType = type === "up" ? "POSITIVE" : "NEGATIVE";
  const currentFeedback = msg.feedback;

  // Toggle feedback
  const newFeedback =
    currentFeedback === feedbackType ? undefined : feedbackType;
  updateMessageById(msgId, { feedback: newFeedback });

  // Submit to API
  if (newFeedback) {
    // Determine response type based on message content (React parity)
    let responseType:
      | "SQL_GENERATION"
      | "RESULT_INTERPRETATION"
      | "DEEP_RESEARCH" = "SQL_GENERATION";
    if (msg.researchReport || msg.researchSessionId) {
      responseType = "DEEP_RESEARCH";
    } else if (!msg.generatedSql && !msg.queries?.length && msg.content) {
      responseType = "RESULT_INTERPRETATION";
    }

    try {
      await submitFeedback({
        feedbackType: newFeedback,
        responseType,
        chatRoomId: options.chatRoomId || msg.chatRoomId,
        question: options.question,
        generatedContent:
          msg.generatedSql || msg.queries?.[0]?.sql || msg.content,
        dataSourceId: options.dataSourceId,
      });
    } catch {
      /* ignore */
    }
  }

  // Persist
  const updatedMsg = messages.find((m) => m.id === msgId);
  if (updatedMsg) updatePersistedMessage(updatedMsg);
}

// ============================================================
// Interpret Results (AI explanation of query results)
// ============================================================

export async function interpretResults(
  sql: string,
  resultData: IQueryResult,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    originalQuery?: string;
    model?: string;
    schemaContext?: string;
    pythonAnalysis?: string;
    additionalInsightContext?: string;
  },
): Promise<string | null> {
  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";
    // When Python stats are available, skip sending sample rows (stats cover full dataset)
    const rawData = options.pythonAnalysis
      ? []
      : resultData.dataList?.slice(0, 50)?.map((row) => {
          const obj: Record<string, any> = {};
          resultData.headerList?.forEach((h: any, i: number) => {
            obj[h.name || h] = row[i];
          });
          return obj;
        }) || [];

    const response = await fetch(`${getBaseURL()}/api/ai/interpret`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
      body: JSON.stringify({
        originalQuery: options.originalQuery,
        sqlResult: rawData,
        model: options.model,
        generatedSql: sql,
        schemaContext: options.schemaContext,
        additionalInsightContext: options.additionalInsightContext,
        totalRowCount: resultData.dataList?.length || 0,
        pythonAnalysis: options.pythonAnalysis,
      }),
    });
    if (!response.ok) return null;
    const result = await response.json();
    const interpretData = result.data;
    return typeof interpretData === "string"
      ? interpretData
      : interpretData?.interpretation || null;
  } catch {
    return null;
  }
}

/**
 * Request Python analysis for large result sets.
 * Sends full result data to backend, which writes CSV to disk,
 * generates Python code via LLM, and executes with full data.
 */
export async function requestPythonAnalysis(
  userQuestion: string,
  resultData: IQueryResult[],
): Promise<string | null> {
  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";
    const response = await fetch(
      `${getBaseURL()}/api/ai/agent/python/analyze`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Inquery: token } : {}),
        },
        body: JSON.stringify({ userQuestion, resultData }),
      },
    );
    if (!response.ok) return null;
    const result = await response.json();
    return result.data?.analysis || null;
  } catch (e) {
    console.error("[requestPythonAnalysis] Failed:", e);
    return null;
  }
}

// ============================================================
// Send Message with SSE Streaming
// ============================================================

export async function sendMessage(
  content: string,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    executionMode?: string;
    model?: string;
    skipClarification?: boolean;
    connectionList?: any[];
    selectedDatabase?: string;
    attachments?: IAttachment[];
  } = {},
) {
  // Auto-create chat room
  const roomId = await ensureChatRoom(content);
  if (isRoomStreaming(roomId)) return;

  // Add user message — preserving the ordered attachments list so the
  // bubble can render the same chips the composer showed before send.
  const userMsg: IMessage = {
    id: `user-${Date.now()}`,
    role: "user",
    content,
    timestamp: Date.now(),
    chatRoomId: roomId,
    ...(options.attachments && options.attachments.length > 0
      ? { attachments: options.attachments }
      : {}),
  };
  addMessage(userMsg);

  // Persist user message
  const userDbId = await persistMessage(userMsg, roomId);
  if (userDbId) {
    updateMessageById(userMsg.id, { dbId: userDbId });
  }

  // Add placeholder assistant message
  const assistantMsg: IMessage = {
    id: `assistant-${Date.now()}`,
    role: "assistant",
    content: "",
    streamingContent: "",
    isStreaming: true,
    isThinking: true,
    thinkingSteps: [],
    queries: [],
    executionMode: options.executionMode,
    timestamp: Date.now(),
    chatRoomId: roomId,
  };
  addMessage(assistantMsg);

  setRoomStreaming(roomId, true);
  setRoomWaitingApproval(roomId, false);
  const abortController = new AbortController();
  abortControllersByRoom.set(roomId, abortController);

  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";

    // Build conversation history from current messages (last 10 messages, condensed)
    const conversationHistory = getMessagesForRoom(roomId)
      .filter((m) => !m.isStreaming && m.content)
      .slice(-10)
      .map((m) => ({
        role: m.role,
        content: m.content,
        generatedSql: m.queries?.[0]?.sql || undefined,
      }));

    const response = await fetch(`${getBaseURL()}/api/ai/agent/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
      body: JSON.stringify({
        message: content,
        conversationId: String(roomId),
        ...(options.model ? { model: options.model } : {}),
        ...(options.dataSourceId
          ? { dataSourceId: options.dataSourceId }
          : {}),
        ...(options.databaseName ? { databaseName: options.databaseName } : {}),
        ...(options.schemaName ? { schemaName: options.schemaName } : {}),
        executeQuery: options.executionMode !== "manual",
        agentMode: options.executionMode === "deep" ? "deep" : "basic",
        ...(options.skipClarification ? { skipClarification: true } : {}),
        conversationHistory,
        ...(options.attachments && options.attachments.length > 0
          ? { attachmentIds: options.attachments.map((a) => a.id) }
          : {}),
      }),
      signal: abortController.signal,
    });

    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    if (!response.body) throw new Error("No response body");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let accumulatedContent = "";
    let doneHandled = false;
    let responseQueries: IQuery[] = [];
    let responseNeedsExecution = false;
    let responseGeneratedSql = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Process complete SSE events (separated by \n\n)
      let eventEndIndex: number;
      while ((eventEndIndex = buffer.indexOf("\n\n")) !== -1) {
        const eventBlock = buffer.slice(0, eventEndIndex);
        buffer = buffer.slice(eventEndIndex + 2);

        // Parse event type and data (handle multi-line data)
        let eventType = "message";
        let eventDataLines: string[] = [];

        for (const line of eventBlock.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim();
          } else if (line.startsWith("data:")) {
            eventDataLines.push(line.slice(5).trim());
          }
        }
        const eventData = eventDataLines.join("\n");

        // Stop simulated thinking on first real event
        if (eventType === "thinking" || eventType === "content") {
          clearThinkingSimulation();
        }

        switch (eventType) {
          case "model_switched": {
            // Backend silently bumped the model because the requested
            // one couldn't handle one of the attachments. Surface it
            // as a one-shot toast + a badge on the message bubble.
            try {
              const sw = JSON.parse(eventData) as IModelSwitched;
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                modelSwitched: sw,
              });
              message.info(
                i18n("aichat.model.autoSwitched.toast", sw.to, sw.from),
              );
            } catch {
              /* ignore malformed payload */
            }
            break;
          }
          case "thinking": {
            try {
              const step = JSON.parse(eventData);
              // step might be a plain string (JSON-wrapped) or an object
              const stepTitle =
                typeof step === "string"
                  ? step
                  : step.title || step.step || i18n("aichat.thinking.default");
              const stepDesc =
                typeof step === "string" ? undefined : step.description;
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                thinkingSteps: currentThinkingStep(stepTitle, stepDesc),
                isThinking: true,
              });
            } catch {
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                thinkingSteps: currentThinkingStep(eventData),
                isThinking: true,
              });
            }
            break;
          }
          case "content": {
            // Backend sends content tokens as JSON-wrapped strings (e.g., "Hello" or "H")
            let token = eventData;
            if (eventData.startsWith('"')) {
              try {
                token = JSON.parse(eventData);
              } catch {
                /* use raw */
              }
            }
            // Empty content treated as newline
            if (token === "") token = "\n";

            accumulatedContent += token;

            // Progressive markdown parsing: extract structured queries during streaming
            const parsed = parseMarkdownToQueries(accumulatedContent);
            const streamUpdate: Partial<IMessage> = {
              streamingContent: accumulatedContent,
              isThinking: true, // Keep thinking indicator during streaming (React parity)
            };
            // If structured queries are found, update them progressively
            if (parsed.queries.length > 0) {
              streamUpdate.queries = parsed.queries;
              streamUpdate.overview = parsed.overview;
              // Show overview in real-time (lock it in once queries start appearing)
              streamUpdate.content = parsed.overview || "";
            } else {
              // Before first query appears, stream overview text in real-time
              streamUpdate.content = accumulatedContent;
            }

            updateMessageByIdInRoom(roomId, assistantMsg.id, streamUpdate);
            break;
          }
          case "disambiguation": {
            try {
              const disambigOptions: IDisambiguationOption[] = JSON.parse(eventData);
              clearThinkingSimulation();
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                content: "",
                needsDisambiguation: true,
                disambiguationOptions: disambigOptions,
                isStreaming: false,
                isThinking: false,
                thinkingSteps: undefined,
                streamingContent: undefined,
              });
              const disambMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
              if (roomId && disambMsg) {
                persistMessage(disambMsg, roomId).then((dbId) => {
                  if (dbId) updateMessageByIdInRoom(roomId, disambMsg.id, { dbId });
                });
              }
              doneHandled = true;
            } catch {
              /* ignore parse errors */
            }
            break;
          }
          case "response": {
            try {
              const responseData = JSON.parse(eventData);

              // Handle disambiguation (early return)
              if (
                responseData.needsDisambiguation &&
                responseData.disambiguationOptions
              ) {
                clearThinkingSimulation();
                updateMessageByIdInRoom(roomId, assistantMsg.id, {
                  content: "",
                  needsDisambiguation: true,
                  disambiguationOptions: responseData.disambiguationOptions,
                  isStreaming: false,
                  isThinking: false,
                  thinkingSteps: undefined,
                  streamingContent: undefined,
                });
                const disambMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
                if (roomId && disambMsg) {
                  persistMessage(disambMsg, roomId).then((dbId) => {
                    if (dbId) updateMessageByIdInRoom(roomId, disambMsg.id, { dbId });
                  });
                }
                doneHandled = true;
                break;
              }

              // Handle clarification (early return like React)
              if (
                responseData.needsClarification &&
                responseData.clarificationOptions
              ) {
                clearThinkingSimulation();
                updateMessageByIdInRoom(roomId, assistantMsg.id, {
                  content:
                    responseData.content ||
                    accumulatedContent ||
                    "I need some clarification:",
                  needsClarification: true,
                  clarificationOptions: responseData.clarificationOptions,
                  isStreaming: false,
                  isThinking: false,
                  thinkingSteps: undefined,
                  streamingContent: undefined,
                });
                // Save clarification message to DB
                const clarMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
                if (roomId && clarMsg) {
                  persistMessage(clarMsg, roomId).then((dbId) => {
                    if (dbId) updateMessageByIdInRoom(roomId, clarMsg.id, { dbId });
                  });
                }
                doneHandled = true;
                break;
              }

              // Handle date range (early return like React)
              if (responseData.needsDateRange) {
                clearThinkingSimulation();
                updateMessageByIdInRoom(roomId, assistantMsg.id, {
                  content:
                    responseData.content ||
                    accumulatedContent ||
                    "Please select a date range:",
                  needsDateRange: true,
                  executionMode: options.executionMode,
                  isStreaming: false,
                  isThinking: false,
                  thinkingSteps: undefined,
                  streamingContent: undefined,
                });
                const dateMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
                if (roomId && dateMsg) {
                  persistMessage(dateMsg, roomId).then((dbId) => {
                    if (dbId) updateMessageByIdInRoom(roomId, dateMsg.id, { dbId });
                  });
                }
                doneHandled = true;
                break;
              }

              if (responseData.chartUpdate) {
                clearThinkingSimulation();
                const chartOnlyQuery = buildChartOnlyQueryFromLatestResult(roomId, responseData.chartUpdate);
                const fallback =
                  chartOnlyQuery
                    ? "Chart updated."
                    : "I could not find a previous result to visualize.";
                updateMessageByIdInRoom(roomId, assistantMsg.id, {
                  content:
                    responseData.aiMessage ||
                    responseData.chartUpdate.message ||
                    accumulatedContent ||
                    fallback,
                  queries: chartOnlyQuery ? [chartOnlyQuery] : [],
                  generatedSql: chartOnlyQuery?.sql,
                  isStreaming: false,
                  isThinking: false,
                  thinkingSteps: undefined,
                  streamingContent: undefined,
                });
                doneHandled = true;
                const chartMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
                if (roomId && chartMsg) {
                  persistMessage(chartMsg, roomId).then((dbId) => {
                    if (dbId) updateMessageByIdInRoom(roomId, chartMsg.id, { dbId });
                  });
                }
                break;
              }

              // Handle schema query (early return like React)
              if (
                responseData.schemaQuery &&
                responseData.targetTables?.length > 0
              ) {
                clearThinkingSimulation();
                handleSchemaQuery(
                  responseData.targetTables,
                  content,
                  options.connectionList || null,
                  options.selectedDatabase,
                  roomId,
                  assistantMsg.id,
                );
                doneHandled = true;
                break;
              }

              const autoResult = firstAutoResult(responseData);
              const isMultiAspect = !!responseData.multiAspect;
              responseQueries = (responseData.queries || []).map((q: any, index: number) => ({
                sql: cleanSql(q.sql || q.query || ""),
                title: q.title,
                explanation: q.explanation,
                overview: q.overview,
                suggestion: q.suggestion,
                result: normalizeQueryResult(q.result) || (index === 0 && !isMultiAspect ? autoResult : undefined),
                // Multi-aspect: each aspect has its own chart recommendation
                // baked into the QueryItem. Single-query response: the chart
                // fields live at the message level, so fall back to those.
                recommendedChart: isMultiAspect ? q.recommendedChart : responseData.recommendedChart,
                chartXAxis: isMultiAspect ? q.chartXAxis : responseData.chartXAxis,
                chartYAxis: isMultiAspect ? q.chartYAxis : responseData.chartYAxis,
                chartDimension: isMultiAspect ? q.chartDimension : responseData.chartDimension,
                chartDimensions: isMultiAspect ? q.chartDimensions : responseData.chartDimensions,
                chartXAxisFormat: isMultiAspect ? q.chartXAxisFormat : responseData.chartXAxisFormat,
                chartYAxisFormat: isMultiAspect ? q.chartYAxisFormat : responseData.chartYAxisFormat,
                chartLineVariant: isMultiAspect ? q.chartLineVariant : responseData.chartLineVariant,
                chartPieVariant: isMultiAspect ? q.chartPieVariant : responseData.chartPieVariant,
                chartBarOrientation: isMultiAspect ? q.chartBarOrientation : responseData.chartBarOrientation,
                chartOrder: isMultiAspect ? q.chartOrder : responseData.chartOrder,
                aspectId: q.aspectId,
                aspectReason: q.aspectReason,
                aspectInsight: q.aspectInsight,
                aspectErrorMessage: q.aspectErrorMessage,
                // For multi-aspect: the synthesis LLM produced a per-aspect
                // 1-2 sentence insight. Surface it as the query.interpretation
                // so the existing card layout reuses its interpretation slot.
                interpretation: isMultiAspect ? (q.aspectInsight || undefined) : undefined,
              }));
              responseNeedsExecution = responseData.needsExecution ?? false;
              responseGeneratedSql =
                responseData.generatedSql || responseQueries[0]?.sql || "";

              // Build update — when queries exist, use only the overview as content
              let responseContent: string;
              if (responseQueries.length > 0) {
                // Use overview only — queries render in Monaco editors, not in markdown
                responseContent = responseData.overview || "";
                if (!responseContent && accumulatedContent) {
                  const hrIdx = accumulatedContent.indexOf("\n---\n");
                  responseContent =
                    hrIdx !== -1
                      ? accumulatedContent.substring(0, hrIdx).trim()
                      : "";
                }
              } else {
                responseContent = responseData.content || accumulatedContent;
              }
              const responseUpdate: Partial<IMessage> = {
                content: responseContent,
                overview: responseData.overview,
                additionalInsightContext: responseData.additionalInsightContext,
                suggestedFollowUps: responseData.suggestedFollowUps,
                generatedSql: responseGeneratedSql,
                needsExecution: responseNeedsExecution,
                schemaInfo: responseData.schemaInfo,
                isStreaming: false,
                isThinking: false,
                thinkingSteps: undefined,
                streamingContent: undefined,
                // Multi-aspect analysis: card grid + synthesis section. The
                // synthesis narrative is rendered below the grid; the per
                // aspect insights are surfaced as each card's interpretation.
                multiAspect: isMultiAspect || undefined,
                synthesis: responseData.synthesis,
                synthesisGoal: responseData.synthesisGoal,
              };
              // Only override queries if backend actually sent structured queries
              if (responseQueries.length > 0) {
                responseUpdate.queries = responseQueries;
              }
              // Only override overview if backend sent it
              if (responseData.overview) {
                responseUpdate.overview = responseData.overview;
              }
              updateMessageByIdInRoom(roomId, assistantMsg.id, responseUpdate);

              // Update room ID if new
              if (
                responseData.chatRoomId &&
                currentRoomId == null
              ) {
                currentRoomId = responseData.chatRoomId;
              }
            } catch {
              /* ignore parse errors */
            }
            break;
          }
          case "tool_approval": {
            try {
              const approval: IToolApprovalRequest = JSON.parse(eventData);
              clearThinkingSimulation();
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                content: accumulatedContent || "",
                toolApproval: approval,
                isStreaming: false,
                isThinking: false,
                thinkingSteps: undefined,
                streamingContent: undefined,
              });
              // Allow user input while waiting for approval
              setRoomStreaming(roomId, false);
              setRoomWaitingApproval(roomId, true);
              // Persist now so the approval card is saved even if user refreshes
              const approvalMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
              if (roomId && approvalMsg) {
                persistMessage(approvalMsg, roomId).then((dbId) => {
                  if (dbId) updateMessageByIdInRoom(roomId, approvalMsg.id, { dbId });
                });
              }
              // Do NOT set doneHandled — after approval, SSE continues with tool result + AI response.
              // The "done" handler will update the persisted message with final content.
            } catch {
              /* ignore parse errors */
            }
            break;
          }
          case "tool_result": {
            try {
              const result = JSON.parse(eventData) as { requestId: string; success: boolean; error?: string };
              const targetMsg = messages.find((m) => m.toolApproval?.requestId === result.requestId);
              if (targetMsg?.toolApproval) {
                updateMessageById(targetMsg.id, {
                  toolApproval: {
                    ...targetMsg.toolApproval,
                    executionSuccess: result.success,
                    executionError: result.error,
                  },
                });
                const updatedMsg = messages.find((m) => m.id === targetMsg.id);
                if (updatedMsg) updatePersistedMessage(updatedMsg);
              }
            } catch { /* ignore */ }
            break;
          }
          case "python_output": {
            try {
              const pyOutput: IPythonOutput = JSON.parse(eventData);
              const currentMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                pythonOutput: pyOutput,
                content: currentMsg.content || accumulatedContent || "",
              });
            } catch {
              /* ignore parse errors */
            }
            break;
          }
          case "done": {
            if (doneHandled) break;
            doneHandled = true;
            clearThinkingSimulation();

            // Mark all thinking steps as done
            const finalMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;
            const finalSteps = (finalMsg.thinkingSteps || []).map((s) => ({
              ...s,
              status: "done" as const,
            }));

            // Parse accumulated content into structured queries if not already parsed from SSE events
            let finalQueries =
              responseQueries.length > 0
                ? responseQueries
                : finalMsg.queries || [];
            let finalOverview = finalMsg.overview;
            let finalContent = accumulatedContent || finalMsg.content;

            // If no queries from SSE events, try parseMarkdownToQueries on accumulated content
            if (finalQueries.length === 0 && accumulatedContent) {
              const parsed = parseMarkdownToQueries(accumulatedContent);
              if (parsed.queries.length > 0) {
                finalQueries = parsed.queries;
                finalOverview = parsed.overview;
                // Clean content to remove SQL code blocks (already shown in structured view)
                finalContent = parsed.overview || accumulatedContent;
              }
            }

            // When queries exist, content should be only the overview text
            if (finalQueries.length > 0) {
              finalContent = finalOverview || "";
            }

            updateMessageByIdInRoom(roomId, assistantMsg.id, {
              content: finalContent,
              queries: finalQueries,
              overview: finalOverview,
              isStreaming: false,
              isThinking: false,
              streamingContent: undefined,
              thinkingSteps: finalSteps,
            });

            // Persist assistant message (or update if already persisted, e.g., from tool_approval)
            const assistantFinal = findMessageByIdInRoom(roomId, assistantMsg.id)!;
            if (assistantFinal.dbId) {
              // Already persisted (e.g., tool_approval saved it earlier) — update with final content
              await updatePersistedMessage(assistantFinal);
            } else {
              const assistantDbId = await persistMessage(assistantFinal, roomId);
              if (assistantDbId) {
                updateMessageByIdInRoom(roomId, assistantFinal.id, { dbId: assistantDbId });
              }
            }

            // Auto-execute: frontend executes if backend didn't already
            if (
              responseNeedsExecution &&
              responseGeneratedSql &&
              options.executionMode !== "manual"
            ) {
              const autoQuery = (
                finalQueries.length > 0 ? finalQueries : responseQueries
              )[0];
              if (autoQuery) {
                await executeQuery(assistantFinal.id, 0, {
                  dataSourceId: options.dataSourceId,
                  databaseName: options.databaseName,
                  schemaName: options.schemaName,
                });

                // After execution, call interpret API for result analysis (including empty results)
                const executedMsg = getMessagesForRoom(roomId).find(
                  (m) => m.id === assistantFinal.id,
                );
                const executedQuery = executedMsg?.queries?.[0];
                if (
                  executedQuery?.result &&
                  executedQuery.result.success !== false
                ) {
                  const iq = [...(executedMsg!.queries || [])];
                  iq[0] = { ...iq[0], isInterpreting: true };
                  updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: iq });

                  const interpretation = await interpretResults(
                    executedQuery.sql,
                    executedQuery.result,
                    {
                      dataSourceId: options.dataSourceId,
                      databaseName: options.databaseName,
                      originalQuery: content,
                      model: options.model,
                      additionalInsightContext: executedMsg?.additionalInsightContext,
                    },
                  );

                  const dq = [
                    ...(findMessageByIdInRoom(roomId, assistantFinal.id)
                      ?.queries || []),
                  ];
                  dq[0] = {
                    ...dq[0],
                    interpretation: interpretation || undefined,
                    isInterpreting: false,
                  };
                  updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: dq });
                  const updMsg = getMessagesForRoom(roomId).find(
                    (m) => m.id === assistantFinal.id,
                  );
                  if (updMsg) updatePersistedMessage(updMsg);
                }
              }
            }

            // Backend already executed (Auto mode with fixSql): interpret results (including empty results)
            // Skip for multi-aspect responses — the backend's runMultiAspectSynthesis
            // already produced a cross-aspect synthesis (msg.synthesis) AND per-aspect
            // insights (q.aspectInsight) using ALL aspect results. Running /interpret
            // again here would only see the first aspect's result and incorrectly
            // claim the other aspects' data is missing.
            const isMultiAspectMessage = !!assistantFinal.multiAspect
              || (finalQueries?.some((q) => !!q.aspectId) ?? false);
            if (
              !responseNeedsExecution &&
              options.executionMode !== "manual" &&
              options.executionMode !== "deep" &&
              !isMultiAspectMessage
            ) {
              const backendQuery = (
                finalQueries.length > 0 ? finalQueries : responseQueries
              )[0];
              if (
                backendQuery?.result &&
                backendQuery.result.success !== false
              ) {
                // Step 1: Python statistics for large result sets (100+ rows)
                let pythonStats: string | null = null;
                const pyRowCount = backendQuery.result.dataList?.length ?? 0;
                if (pyRowCount >= 100) {
                  try {
                    console.log(`[Auto] Running Python statistics for ${pyRowCount} rows...`);
                    const pyQ = [...(assistantFinal.queries || [])];
                    pyQ[0] = { ...pyQ[0], isAnalyzingPython: true };
                    updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: pyQ });

                    pythonStats = await requestPythonAnalysis(content, [backendQuery.result]);

                    const pyDoneQ = [
                      ...(findMessageByIdInRoom(roomId, assistantFinal.id)?.queries || []),
                    ];
                    pyDoneQ[0] = { ...pyDoneQ[0], isAnalyzingPython: false };
                    updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: pyDoneQ });
                    console.log("[Auto] Python stats done, length:", pythonStats?.length);
                  } catch (pyErr) {
                    console.error("[Auto] Python analysis failed:", pyErr);
                    const pyErrQ = [
                      ...(findMessageByIdInRoom(roomId, assistantFinal.id)?.queries || []),
                    ];
                    pyErrQ[0] = { ...pyErrQ[0], isAnalyzingPython: false };
                    updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: pyErrQ });
                  }
                }

                // Step 2: Interpretation (with Python stats if available)
                const iq = [
                  ...(findMessageByIdInRoom(roomId, assistantFinal.id)?.queries || []),
                ];
                iq[0] = { ...iq[0], isInterpreting: true };
                updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: iq });

                const interpretation = await interpretResults(
                  backendQuery.sql,
                  backendQuery.result,
                  {
                    dataSourceId: options.dataSourceId,
                    databaseName: options.databaseName,
                    originalQuery: content,
                    model: options.model,
                    pythonAnalysis: pythonStats || undefined,
                    additionalInsightContext: assistantFinal.additionalInsightContext,
                  },
                );

                const dq = [
                  ...(findMessageByIdInRoom(roomId, assistantFinal.id)
                    ?.queries || []),
                ];
                dq[0] = {
                  ...dq[0],
                  interpretation: interpretation || undefined,
                  isInterpreting: false,
                };
                updateMessageByIdInRoom(roomId, assistantFinal.id, { queries: dq });
                const updMsg = getMessagesForRoom(roomId).find(
                  (m) => m.id === assistantFinal.id,
                );
                if (updMsg) updatePersistedMessage(updMsg);
              }
            }
            break;
          }
          case "sql_fix": {
            try {
              const fixData = JSON.parse(eventData);
              const lastMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
              if (lastMsg?.queries?.length) {
                const updatedQueries = [...lastMsg.queries];
                updatedQueries[0] = {
                  ...updatedQueries[0],
                  sql: fixData.fixedSql,
                };
                updateMessageByIdInRoom(roomId, assistantMsg.id, { queries: updatedQueries });
              }
            } catch {
              /* ignore parse errors */
            }
            break;
          }
          case "error": {
            clearThinkingSimulation();
            updateMessageByIdInRoom(roomId, assistantMsg.id, {
              content: `Error: ${eventData}`,
              isStreaming: false,
              isThinking: false,
              thinkingSteps: undefined,
            });
            break;
          }
        }
      }
    }

    // Handle stream end without explicit done event
    if (!doneHandled) {
      clearThinkingSimulation();
      const finalMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;

      // Parse content into structured queries
      let endQueries = finalMsg.queries || [];
      let endOverview = finalMsg.overview;
      let endContent = accumulatedContent || finalMsg.content;

      if (endQueries.length === 0 && accumulatedContent) {
        const parsed = parseMarkdownToQueries(accumulatedContent);
        if (parsed.queries.length > 0) {
          endQueries = parsed.queries;
          endOverview = parsed.overview;
          endContent = parsed.overview || accumulatedContent;
        }
      }

      updateMessageByIdInRoom(roomId, assistantMsg.id, {
        content: endContent,
        queries: endQueries,
        overview: endOverview,
        isStreaming: false,
        isThinking: false,
        thinkingSteps: undefined,
        streamingContent: undefined,
      });

      const assistantFinal = findMessageByIdInRoom(roomId, assistantMsg.id)!;
      const assistantDbId = await persistMessage(assistantFinal, roomId);
      if (assistantDbId) {
        updateMessageByIdInRoom(roomId, assistantFinal.id, { dbId: assistantDbId });
      }
    }
  } catch (e: any) {
    clearThinkingSimulation();
    if (e.name !== "AbortError") {
      updateMessageByIdInRoom(roomId, assistantMsg.id, {
        content: `Error: ${e.message || "Connection failed"}`,
        isStreaming: false,
        isThinking: false,
        thinkingSteps: undefined,
      });
    }
  } finally {
    setRoomStreaming(roomId, false);
    setRoomWaitingApproval(roomId, false);
    abortControllersByRoom.delete(roomId);
    clearThinkingSimulation();
  }
}

/**
 * Send a clarification response
 */
export async function sendClarification(
  originalQuestion: string,
  clarifiedQuery: string,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    executionMode?: string;
    model?: string;
  } = {},
) {
  return sendMessage(clarifiedQuery, {
    ...options,
    skipClarification: true,
  });
}

/**
 * Send a disambiguation choice (user picked an option from AMBIGUOUS classification).
 * Sends the refined query with explicit queryType to bypass re-classification.
 */
export async function sendDisambiguationChoice(
  option: IDisambiguationOption,
  options: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    executionMode?: string;
    model?: string;
    connectionList?: any[];
    selectedDatabase?: string;
  } = {},
) {
  const roomId = currentRoomId ?? (await ensureChatRoom(option.refinedQuery));
  if (isRoomStreaming(roomId)) return;

  // Add user message showing the chosen option
  const userMsg: IMessage = {
    id: `user-${Date.now()}`,
    role: "user",
    content: option.label,
    timestamp: Date.now(),
    chatRoomId: roomId,
  };
  addMessage(userMsg);
  const userDbId = await persistMessage(userMsg, roomId);
  if (userDbId) updateMessageById(userMsg.id, { dbId: userDbId });

  // Add placeholder assistant message
  const assistantMsg: IMessage = {
    id: `assistant-${Date.now()}`,
    role: "assistant",
    content: "",
    streamingContent: "",
    isStreaming: true,
    isThinking: true,
    thinkingSteps: [],
    queries: [],
    timestamp: Date.now(),
    chatRoomId: roomId,
  };
  addMessage(assistantMsg);

  setRoomStreaming(roomId, true);
  setRoomWaitingApproval(roomId, false);
  const abortController = new AbortController();
  abortControllersByRoom.set(roomId, abortController);

  try {
    const token =
      typeof window !== "undefined"
        ? localStorage.getItem("Inquery") || ""
        : "";
    const messageToSend = option.queryType === "CHAT" ? option.label : option.refinedQuery;

    const conversationHistory = getMessagesForRoom(roomId)
      .filter((m) => !m.isStreaming && m.content)
      .slice(-10)
      .map((m) => ({
        role: m.role,
        content: m.content,
        generatedSql: m.queries?.[0]?.sql || undefined,
      }));

    const response = await fetch(`${getBaseURL()}/api/ai/agent/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Inquery: token } : {}),
      },
      body: JSON.stringify({
        message: messageToSend,
        conversationId: String(roomId),
        queryType: option.queryType,
        ...(options.model ? { model: options.model } : {}),
        ...(options.dataSourceId
          ? { dataSourceId: options.dataSourceId }
          : {}),
        ...(options.databaseName ? { databaseName: options.databaseName } : {}),
        ...(options.schemaName ? { schemaName: options.schemaName } : {}),
        executeQuery: options.executionMode !== "manual",
        agentMode: options.executionMode === "deep" ? "deep" : "basic",
        conversationHistory,
      }),
      signal: abortController.signal,
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    if (!response.body) throw new Error("No response body");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let accumulatedContent = "";
    let doneHandled = false;
    let responseQueries: IQuery[] = [];
    let responseNeedsExecution = false;
    let responseGeneratedSql = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      let eventEndIndex: number;
      while ((eventEndIndex = buffer.indexOf("\n\n")) !== -1) {
        const eventBlock = buffer.slice(0, eventEndIndex);
        buffer = buffer.slice(eventEndIndex + 2);

        let eventType = "message";
        let eventDataLines: string[] = [];
        for (const line of eventBlock.split("\n")) {
          if (line.startsWith("event:")) eventType = line.slice(6).trim();
          else if (line.startsWith("data:")) eventDataLines.push(line.slice(5).trim());
        }
        const eventData = eventDataLines.join("\n");

        if (eventType === "thinking" || eventType === "content") {
          clearThinkingSimulation();
        }

        switch (eventType) {
          case "model_switched": {
            // Backend silently bumped the model because the requested
            // one couldn't handle one of the attachments. Surface it
            // as a one-shot toast + a badge on the message bubble.
            try {
              const sw = JSON.parse(eventData) as IModelSwitched;
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                modelSwitched: sw,
              });
              message.info(
                i18n("aichat.model.autoSwitched.toast", sw.to, sw.from),
              );
            } catch {
              /* ignore malformed payload */
            }
            break;
          }
          case "thinking": {
            try {
              const step = JSON.parse(eventData);
              const currentMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;
              const steps = [...(currentMsg.thinkingSteps || [])];
              const updatedSteps: IThinkingStep[] = steps.map((s) => ({ ...s, status: "done" as const }));
              const stepTitle = typeof step === "string" ? step : step.title || step.step || i18n("aichat.thinking.default");
              updatedSteps.push({ title: stepTitle, status: "running" });
              updateMessageByIdInRoom(roomId, assistantMsg.id, { thinkingSteps: updatedSteps, isThinking: true });
            } catch {
              /* ignore */
            }
            break;
          }
          case "content": {
            let token = eventData;
            if (eventData.startsWith('"')) {
              try { token = JSON.parse(eventData); } catch { /* use raw */ }
            }
            if (token === "") token = "\n";
            accumulatedContent += token;
            const parsed = parseMarkdownToQueries(accumulatedContent);
            const streamUpdate: Partial<IMessage> = { streamingContent: accumulatedContent, isThinking: true };
            if (parsed.queries.length > 0) {
              streamUpdate.queries = parsed.queries;
              streamUpdate.overview = parsed.overview;
              streamUpdate.content = parsed.overview || "";
            } else {
              streamUpdate.content = accumulatedContent;
            }
            updateMessageByIdInRoom(roomId, assistantMsg.id, streamUpdate);
            break;
          }
          case "response": {
            try {
              const responseData = JSON.parse(eventData);
              if (responseData.chartUpdate) {
                clearThinkingSimulation();
                const chartOnlyQuery = buildChartOnlyQueryFromLatestResult(roomId, responseData.chartUpdate);
                const fallback =
                  chartOnlyQuery
                    ? "Chart updated."
                    : "I could not find a previous result to visualize.";
                updateMessageByIdInRoom(roomId, assistantMsg.id, {
                  content:
                    responseData.aiMessage ||
                    responseData.chartUpdate.message ||
                    accumulatedContent ||
                    fallback,
                  queries: chartOnlyQuery ? [chartOnlyQuery] : [],
                  generatedSql: chartOnlyQuery?.sql,
                  isStreaming: false,
                  isThinking: false,
                  thinkingSteps: undefined,
                  streamingContent: undefined,
                });
                doneHandled = true;
                const chartMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
                if (roomId && chartMsg) {
                  persistMessage(chartMsg, roomId).then((dbId) => {
                    if (dbId) updateMessageByIdInRoom(roomId, chartMsg.id, { dbId });
                  });
                }
                break;
              }
              const autoResult = firstAutoResult(responseData);
              responseQueries = (responseData.queries || []).map((q: any, index: number) => ({
                sql: cleanSql(q.sql || q.query || ""),
                title: q.title,
                explanation: q.explanation,
                overview: q.overview,
                suggestion: q.suggestion,
                result: normalizeQueryResult(q.result) || (index === 0 ? autoResult : undefined),
                recommendedChart: responseData.recommendedChart,
                chartXAxis: responseData.chartXAxis,
                chartYAxis: responseData.chartYAxis,
                chartDimension: responseData.chartDimension,
                chartDimensions: responseData.chartDimensions,
                chartXAxisFormat: responseData.chartXAxisFormat,
                chartYAxisFormat: responseData.chartYAxisFormat,
                chartLineVariant: responseData.chartLineVariant,
                chartPieVariant: responseData.chartPieVariant,
                chartBarOrientation: responseData.chartBarOrientation,
                chartOrder: responseData.chartOrder,
              }));
              responseNeedsExecution = responseData.needsExecution ?? false;
              responseGeneratedSql = responseData.generatedSql || responseQueries[0]?.sql || "";
              let responseContent = responseQueries.length > 0 ? (responseData.overview || "") : (responseData.content || accumulatedContent);
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                content: responseContent,
                overview: responseData.overview,
                generatedSql: responseGeneratedSql,
                needsExecution: responseNeedsExecution,
                isStreaming: false,
                isThinking: false,
                thinkingSteps: undefined,
                streamingContent: undefined,
                ...(responseQueries.length > 0 ? { queries: responseQueries } : {}),
              });
            } catch { /* ignore */ }
            break;
          }
          case "tool_approval": {
            try {
              const approval: IToolApprovalRequest = JSON.parse(eventData);
              clearThinkingSimulation();
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                content: accumulatedContent || "",
                toolApproval: approval,
                isStreaming: false,
                isThinking: false,
                thinkingSteps: undefined,
                streamingContent: undefined,
              });
              // Allow user input while waiting for approval
              setRoomStreaming(roomId, false);
              setRoomWaitingApproval(roomId, true);
              const approvalMsg = findMessageByIdInRoom(roomId, assistantMsg.id);
              if (roomId && approvalMsg) {
                persistMessage(approvalMsg, roomId).then((dbId) => {
                  if (dbId) updateMessageByIdInRoom(roomId, approvalMsg.id, { dbId });
                });
              }
              // Do NOT set doneHandled — SSE continues after tool execution
            } catch { /* ignore */ }
            break;
          }
          case "tool_result": {
            try {
              const result = JSON.parse(eventData) as { requestId: string; success: boolean; error?: string };
              const targetMsg = messages.find((m) => m.toolApproval?.requestId === result.requestId);
              if (targetMsg?.toolApproval) {
                updateMessageById(targetMsg.id, {
                  toolApproval: {
                    ...targetMsg.toolApproval,
                    executionSuccess: result.success,
                    executionError: result.error,
                  },
                });
                const updatedMsg = messages.find((m) => m.id === targetMsg.id);
                if (updatedMsg) updatePersistedMessage(updatedMsg);
              }
            } catch { /* ignore */ }
            break;
          }
          case "python_output": {
            try {
              const pyOutput: IPythonOutput = JSON.parse(eventData);
              const currentMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;
              updateMessageByIdInRoom(roomId, assistantMsg.id, {
                pythonOutput: pyOutput,
                content: currentMsg.content || accumulatedContent || "",
              });
            } catch { /* ignore */ }
            break;
          }
          case "done": {
            if (doneHandled) break;
            doneHandled = true;
            clearThinkingSimulation();
            const finalMsg = findMessageByIdInRoom(roomId, assistantMsg.id) || assistantMsg;
            let finalQueries = responseQueries.length > 0 ? responseQueries : finalMsg.queries || [];
            let finalOverview = finalMsg.overview;
            let finalContent = accumulatedContent || finalMsg.content;
            if (finalQueries.length === 0 && accumulatedContent) {
              const parsed = parseMarkdownToQueries(accumulatedContent);
              if (parsed.queries.length > 0) { finalQueries = parsed.queries; finalOverview = parsed.overview; finalContent = parsed.overview || accumulatedContent; }
            }
            if (finalQueries.length > 0) finalContent = finalOverview || "";
            updateMessageByIdInRoom(roomId, assistantMsg.id, { content: finalContent, queries: finalQueries, overview: finalOverview, isStreaming: false, isThinking: false, streamingContent: undefined });
            const assistantFinal = findMessageByIdInRoom(roomId, assistantMsg.id)!;
            if (assistantFinal.dbId) {
              await updatePersistedMessage(assistantFinal);
            } else {
              const assistantDbId = await persistMessage(assistantFinal, roomId);
              if (assistantDbId) updateMessageByIdInRoom(roomId, assistantFinal.id, { dbId: assistantDbId });
            }

            if (responseNeedsExecution && responseGeneratedSql && options.executionMode !== "manual") {
              const autoQuery = (finalQueries.length > 0 ? finalQueries : responseQueries)[0];
              if (autoQuery) {
                await executeQuery(assistantFinal.id, 0, {
                  dataSourceId: options.dataSourceId,
                  databaseName: options.databaseName,
                  schemaName: options.schemaName,
                });
              }
            }
            break;
          }
          case "error": {
            clearThinkingSimulation();
            updateMessageByIdInRoom(roomId, assistantMsg.id, { content: `Error: ${eventData}`, isStreaming: false, isThinking: false, thinkingSteps: undefined });
            break;
          }
        }
      }
    }

    if (!doneHandled) {
      clearThinkingSimulation();
      updateMessageByIdInRoom(roomId, assistantMsg.id, { content: accumulatedContent || "", isStreaming: false, isThinking: false, streamingContent: undefined });
      const assistantFinal = findMessageByIdInRoom(roomId, assistantMsg.id)!;
      const assistantDbId = await persistMessage(assistantFinal, roomId);
      if (assistantDbId) updateMessageByIdInRoom(roomId, assistantFinal.id, { dbId: assistantDbId });
    }
  } catch (e: any) {
    clearThinkingSimulation();
    if (e.name !== "AbortError") {
      updateMessageByIdInRoom(roomId, assistantMsg.id, { content: `Error: ${e.message || "Connection failed"}`, isStreaming: false, isThinking: false, thinkingSteps: undefined });
    }
  } finally {
    setRoomStreaming(roomId, false);
    setRoomWaitingApproval(roomId, false);
    abortControllersByRoom.delete(roomId);
    clearThinkingSimulation();
  }
}
