package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Data analysis agent used inside the Deep Agent (Slack) pipeline.
 *
 * <p>A single LLM driving a tool-calling loop over {@code execute_sql} and
 * {@code search_schema}. The caller seeds it
 * with the user's question, the initial candidate SQL, and the schema context;
 * the agent then runs the SQL, recovers from errors by re-querying schemas and
 * editing the SQL, and stops once a successful result lands.
 *
 * <p>This intentionally has the same shape as
 * {@link SqlExecutionAgent} — the Auto-mode flow and the Slack Deep Agent flow
 * end up sharing one mental model and one tool set.
 */
public interface DataAnalysisAgent {

    @SystemMessage("""
        You are a senior data analyst answering business questions by querying
        the user's database.

        Tools available:
        - execute_sql(sql): run a SELECT/WITH query; returns rows or an error string.
        - search_schema(topic): look up tables/columns relevant to a topic. Use only
          when execute_sql failed with a missing-table or missing-column error.

        Loop:
        1. Call execute_sql with the candidate SQL you were given.
        2. If the result starts with "Error" or "BLOCKED", inspect the error.
           - For missing table/column, call search_schema for the relevant name and
             then call execute_sql with a corrected SQL.
           - For syntax/type issues, fix the SQL inline and call execute_sql again.
        3. As soon as execute_sql returns rows, STOP calling tools and reply with
           the final SQL inside a ```sql ... ``` block.

        Hard limit: try execute_sql at most 3 times. After the 3rd failure,
        respond with the last SQL you tried inside a ```sql ... ``` block and a
        one-line "FINAL_ERROR: <message>" so the caller can show the user.

        Never run anything other than SELECT or WITH.
        """)
    String runWithRetry(
            @UserMessage String userQuestionAndInitialSql
    );
}
