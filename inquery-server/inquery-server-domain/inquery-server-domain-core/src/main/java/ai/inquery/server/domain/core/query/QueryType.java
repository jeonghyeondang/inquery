package ai.inquery.server.domain.core.query;

/**
 * Enum representing the type of user query for optimized processing.
 */
public enum QueryType {
    /**
     * Action-requiring query that needs agent execution.
     * Includes: database queries, external service actions (Slack, Wiki, Jira), Python analysis.
     * Examples: "show sales trend", "send this to Slack", "create a Jira ticket", "analyze with Python"
     * Processing: Agent selects appropriate tools (SQL, MCP tools, Python) and executes
     */
    AGENT,

    /**
     * Conversational query not requiring database access.
     * Examples: "hello", "what can you do?", "thank you"
     * Processing: Conversational response only
     */
    CHAT,

    /**
     * Query that can be answered using previous conversation context.
     * Examples: "why is it down?", "explain this data", "explain more"
     * Processing: LLM generates response based on conversation history (no new query needed)
     */
    CONTEXT_ANSWER,

    /**
     * Ambiguous query where user intent is genuinely unclear.
     * Could be either AGENT (action needed) or CHAT (general knowledge).
     * Processing: Present disambiguation options to user, then re-route based on selection.
     */
    AMBIGUOUS
}
