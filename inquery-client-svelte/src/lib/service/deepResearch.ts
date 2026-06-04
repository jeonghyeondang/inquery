import createRequest, { getBaseURL } from "./base";

export interface ResearchPlan {
  title: string;
  steps: { icon: string; title: string; details: string; source?: string }[];
  estimatedTime: string;
}

export interface DeepResearchClassification {
  originalQuery: string;
  needsClarification: boolean;
  clarificationQuestion?: string;
  language?: string;
  researchPlan?: ResearchPlan;
  buttonLabel?: string;
  editPlanLabel?: string;
  requiresWebSearch?: boolean;
  webSearchTopics?: string[];
}

export interface DeepResearchSession {
  id: number;
  chatRoomId: number;
  question: string;
  researchPlan?: string;
  mdContent?: string;
  reportJson?: string;
  status: "PLANNING" | "RUNNING" | "COMPLETED" | "FAILED";
  currentIteration: number;
  totalQueriesExecuted: number;
  errorMessage?: string;
  gmtCreate: string;
  gmtModified: string;
}

export const classifyDeepResearch = createRequest<
  Record<string, unknown>,
  DeepResearchClassification
>("/api/ai/deep-research/classify", { method: "post" });
export const startDeepResearch = createRequest<
  Record<string, unknown>,
  { sessionId: number; status: string }
>("/api/ai/deep-research/start", { method: "post" });
export const getResearchStatus = createRequest<
  { sessionId: number },
  DeepResearchSession
>("/api/ai/deep-research/status/:sessionId");
export const getResearchReport = createRequest<{ sessionId: number }, unknown>(
  "/api/ai/deep-research/report/:sessionId",
);
export const getLatestResearchByRoom = createRequest<
  { chatRoomId: number },
  DeepResearchSession | null
>("/api/ai/deep-research/room/:chatRoomId/latest", { errorLevel: false });
export const deleteResearchSession = createRequest<
  { sessionId: number },
  boolean
>("/api/ai/deep-research/:sessionId", { method: "delete" });

export function executeDeepResearchStream(params: {
  sessionId: number;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
}): EventSource {
  const searchParams = new URLSearchParams();
  searchParams.append("sessionId", String(params.sessionId));
  if (params.dataSourceId)
    searchParams.append("dataSourceId", String(params.dataSourceId));
  if (params.databaseName)
    searchParams.append("databaseName", params.databaseName);
  if (params.schemaName) searchParams.append("schemaName", params.schemaName);
  const token = localStorage.getItem("Inquery") || "";
  if (token) searchParams.append("satoken", token);
  return new EventSource(
    `${getBaseURL()}/api/ai/deep-research/execute/stream?${searchParams.toString()}`,
  );
}
