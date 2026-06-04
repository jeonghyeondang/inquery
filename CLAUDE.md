# Inquery Project

AI-powered database query assistant that converts natural language to SQL.

## Quick Reference

- Backend rules: @.claude/rules/backend.md
- Code style: @.claude/rules/code-style.md
- Reasoning guidelines: @.claude/rules/reasoning.md

## Project Structure

```
inquery-server/          # Java Spring Boot backend (Maven multi-module)
├── inquery-server-domain/
│   ├── inquery-server-domain-api/       # Domain interfaces
│   ├── inquery-server-domain-core/      # Domain implementation
│   │   └── langchain/                   # LangChain4j Agent integration
│   │       ├── InqueryAgentService.java # Main agent service
│   │       ├── InqueryDataAssistant.java # AI Service interface
│   │       ├── tools/DatabaseTools.java  # Legacy/simple agent tools
│   │       ├── tools/ApprovalToolProvider.java # MCP tool approval wrapper
│   │       ├── tools/ToolApproval*.java  # Approval request/response/callback/manager
│   │       ├── tools/calling/            # Root-agent callable tools
│   │       │   ├── MetadataTools.java    # Catalog, metadata, lineage, quality/profile evidence tools
│   │       │   ├── SearchTools.java      # External search tools
│   │       │   └── WriteTools.java       # Approval-gated write tools
│   │       ├── mcp/McpConnectionManager.java  # MCP server connections
│   │       └── agents/                   # Multi-agent pattern
│   │           ├── InqueryRootAgent.java       # Tool-calling root agent contract/prompt
│   │           ├── InqueryRootAgentRunner.java # Root-agent tool wiring and data workflow
│   │           ├── DeepResearchAgent.java    # Deep Research orchestrator
│   │           └── ReportSynthesizerAgent.java # Report generation
│   └── inquery-server-domain-repository/ # Database access
├── inquery-server-web/
│   └── inquery-server-web-api/
│       └── controller/ai/
│           ├── ChatController.java       # Main AI API endpoints
│           └── DeepResearchController.java # Deep Research API
└── inquery-server-web-start/             # Spring Boot application entry

inquery-client-svelte/   # Svelte 5 frontend (SvelteKit, migrating from Next.js)
├── src/
│   ├── routes/(main)/
│   │   ├── ai-chat/+page.svelte         # Main AI chat page
│   │   ├── workspace/+page.svelte       # SQL workspace
│   │   ├── data-catalog/+page.svelte    # Data catalog management
│   │   └── dashboard/+page.svelte       # Dashboard & analytics
│   ├── lib/
│   │   ├── stores/                       # Svelte 5 runes-based stores
│   │   │   ├── aiChat.svelte.ts         # AI chat state (rooms, messages, SSE)
│   │   │   ├── workspace.svelte.ts      # Workspace state
│   │   │   └── deepResearch.svelte.ts   # Deep Research state
│   │   ├── components/
│   │   │   ├── ui/                       # shadcn-svelte UI components
│   │   │   ├── EmbeddedAIChat/          # Workspace embedded AI chat panel
│   │   │   ├── ToolApproval/            # MCP tool approval UI component
│   │   │   ├── ConsoleEditor/           # SQL editor component
│   │   │   └── DeepResearch/            # Deep Research components
│   │   ├── service/                      # API service layer
│   │   └── utils/                        # Utility functions
│   └── app.css                           # Global styles & CSS variables
```

## Quick Start

### Backend

```bash
cd inquery-server
mvn clean package -DskipTests
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     -Dspring.profiles.active=dev \
     -jar inquery-server-web-start/target/inquery-server-web-start.jar
```

### Frontend (Svelte 5)

```bash
cd inquery-client-svelte
npm run dev
```

## Key Features

| Feature               | Description                                                                 |
| --------------------- | --------------------------------------------------------------------------- |
| Tool-Calling Root AI  | LLM-driven root agent chooses tools and orchestrates data/search/write flows |
| Data Workflow Tools   | Query, comparison, metadata, lineage, quality, profiling, and metric evidence |
| Suggested Follow-ups  | LLM-generated follow-up buttons based on result evidence                    |
| Auto-Execute          | Automatically executes generated SQL when enabled                           |
| Deep Research         | Comprehensive research reports with infographic HTML generation             |
| MCP Tools             | External service integration (Slack, Confluence, Jira, GitHub)              |
| Tool Approval         | User approval UI for write operations via MCP tools                         |

## Tech Stack

| Component     | Technology                                  |
| ------------- | ------------------------------------------- |
| Backend       | Java Spring Boot, Maven multi-module        |
| AI Framework  | LangChain4j 1.9.0                           |
| Frontend      | Svelte 5, SvelteKit, TypeScript, Tailwind 4 |
| State         | Svelte 5 runes ($state, $derived, $effect)  |
| UI Library    | shadcn-svelte, bits-ui                      |
| Vector Search | pgvector / Qdrant / Pinecone                |
| App Database  | PostgreSQL (migrated from H2)               |
| Supported DBs | MySQL, PostgreSQL, Snowflake, BigQuery, Oracle, SQL Server, MariaDB, SQLite, MongoDB, ClickHouse, DB2, Hive, Presto, Databricks, Redis |

## AI Models

- Gemini: `gemini-3.5-flash`
- Claude: `claude-sonnet-4-6`
- OpenAI: `gpt-5.4-mini` (gpt-5.5 is also supported but defaults to
  medium reasoning effort with tools, so it's slow for chat — pick
  it explicitly only when you need flagship quality)

## Critical Notes

### AI Architecture: Tool-Based, LLM-Decided

- **Inquery AI is tool-based.** The root agent receives callable tools and decides which tools to use for each turn.
- **All routing, analysis planning, tool sequencing, and final answer judgment belongs to the LLM/root agent.**
- Tools should provide evidence, safe actions, and deterministic execution results only. They must not hard-code business-domain analysis templates or make product-level analytical judgments.
- Prefer tool descriptions and system-prompt guidance over Java `if/else` routing for analytical intent.
- Metadata/data tools may enforce safety boundaries (read-only SQL, identifier validation, probe limits, dialect-specific SQL syntax, row limits), but should not decide “what the analysis means.”
- If a tool returns catalog/lineage/profile/quality evidence, the root agent should synthesize the final user-facing explanation from that evidence.
- If a broad business problem is asked (for example “why is revenue not growing?”), use planning/catalog evidence tools first, then let the LLM design the first concrete `queryData` request.
- Avoid hard-coded domain keyword templates such as `revenue/sales/churn/delivery/marketing` branches for analysis planning or follow-up questions. Let the LLM infer from the user request, schema, result columns, and tool outputs.
- Suggested follow-up buttons should be generated from result evidence by the LLM, not from Java keyword heuristics.

### LangChain4j Version

- **Must use version 1.9.0** - older versions have incompatible APIs
- See @.claude/rules/backend.md for API migration details

### Frontend (Svelte 5)

- **Active frontend is `inquery-client-svelte/`** — all frontend changes go here
- Use Svelte 5 runes syntax: `$state`, `$derived`, `$effect`, `$props`, `$bindable`
- Do NOT use Svelte 4 syntax (e.g., `export let`, `$:`, `on:click`) — use Svelte 5 equivalents
- Components use `bits-ui` and `shadcn-svelte` (not Ant Design)
- **`{@const}` placement rule**: `{@const}` can ONLY be the direct child of `{#if}`, `{:else if}`, `{:else}`, `{#each}`, `{:then}`, `{:catch}`, `{#snippet}`, `<svelte:fragment>`, `<svelte:boundary>`, or `<Component>`. Do NOT place `{@const}` inside regular HTML elements like `<div>` — use inline expressions or local variables in the script block instead.

### Theme Support

- **Both light and dark mode required** for all UI changes
- See @.claude/rules/code-style.md for CSS variable guidelines

### Code Quality

- English only for all code, comments, and UI text
- Remove Chinese AI clients (Zhipu, Tongyi, Wenxin, Baichuan)

### Database Migration (H2 → PostgreSQL)

- **App database is PostgreSQL** - migrated from H2 embedded database
- All schema cache, config, catalog data stored in PostgreSQL
- Do NOT reference H2 in comments or code - use "database" or "PostgreSQL"
- Flyway version: 9.16.3 (compatible with Spring Boot 3.1.0)

### API Reuse Principle

- **Always reuse existing working APIs** instead of creating new duplicate implementations
- Before implementing a batch/bulk feature, check if single-item APIs already exist
- Example: For bulk AI collection, call existing `collectAIMetadata()` + `saveCatalog()` APIs from frontend instead of duplicating logic in backend
- Benefits: Less code, fewer bugs, consistent behavior, easier maintenance
