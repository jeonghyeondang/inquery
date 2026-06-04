---
paths: inquery-server/**/*.java
---

# Backend Development Rules (Java/Spring Boot)

## LangChain4j Configuration

- **Required Version**: 1.9.0 (check root `pom.xml` `<dependencyManagement>` section)
- **Important**: Do NOT use older versions (e.g., 1.0.0-beta2) - different API signatures

### API Changes in 1.9.0
- `ChatLanguageModel` → `ChatModel`
- `StreamingChatLanguageModel` → `StreamingChatModel`
- `.chatLanguageModel(model)` → `.chatModel(model)`
- Required modules: `langchain4j-core`, `langchain4j-agentic`

### Key LangChain4j Files
- `LangChainModelProvider.java` - Model provider
- `SupervisorAgent.java` - Multi-agent supervisor
- `InqueryAgentService.java` - Main agent service

## Agent Architecture

### Basic Agent
- Single LangChain4j agent
- SQL generation only (fast response)

### Deep Agent
- Multi-agent supervisor pattern
- Automatic retry on failure
- Components: `SupervisorAgent`, `SqlWriterAgent`, `ResultAnalyzerAgent`

## MCP (Model Context Protocol) Integration

### Architecture
- `McpConnectionManager` - Manages MCP server subprocess connections per user config
- `ApprovalToolProvider` - Wraps MCP ToolProvider to intercept write operations
- Read-only tools (search, get, list, etc.) execute directly without approval
- Write tools (create, post, update, delete, send) require user approval via SSE

### Tool Approval Flow
1. Agent calls a write tool → `ApprovalToolProvider` intercepts
2. Builds human-readable params per service (Confluence HTML preview, Slack message, etc.)
3. Sends `tool_approval` SSE event to frontend with `ToolApprovalRequest`
4. Frontend renders approval UI in `ToolApproval.svelte`
5. User approves/denies → `POST /api/ai/agent/tool/approve` → `CompletableFuture` resolves
6. Approved: tool executes with (possibly modified) params; Denied: returns denial message
7. 120s timeout → Broken pipe (expected SSE behavior)

### Service-Specific Parameter Parsing
- **Confluence**: Space ID (editable) + Page Title (editable) + Content Preview (HTML iframe)
- **Slack**: Channel (editable) + Message (editable textarea)
- **Jira**: Project + Issue Type + Summary + Description (all editable)
- **GitHub**: Repository + Title + Content + Labels (all editable)
- Target includes clickable link using service base URL from user config

### Key Gotcha: Jackson Map.toString()
Jackson auto-deserializes nested JSON to `LinkedHashMap`. Calling `.toString()` produces `{key=value}` (NOT valid JSON). Always use `objectMapper.writeValueAsString()` for Map/List values.

## API Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/api/ai/agent/chat/stream` | SSE streaming for AI chat |
| `/api/ai/agent/execute` | Execute SQL and return results |
| `/api/ai/interpret` | Generate business insights |
| `/api/ai/agent/tool/approve` | Submit tool approval/denial |

## Build Commands

```bash
cd inquery-server
mvn clean package -DskipTests

# Run
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     -Dspring.profiles.active=dev \
     -jar inquery-server-web-start/target/inquery-server-web-start.jar
```
## Integration Notes
- Vector search uses Pinecone for schema context retrieval
- Business context is fetched from `database_business_insight` table
- MCP servers: Slack (`@modelcontextprotocol/server-slack`), Confluence (`@aashari/mcp-server-atlassian-confluence`), Jira (`@aashari/mcp-server-atlassian-jira`), GitHub (`@modelcontextprotocol/server-github`)
