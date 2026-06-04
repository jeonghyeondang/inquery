package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Tool-calling SQL execution agent for Auto mode.
 *
 * <p>The agent has {@code executeSql} (and helpers like {@code searchSchema} /
 * {@code getTableSchema}) as tools. It is expected to call {@code executeSql}
 * first; if the call returns an error, it analyzes the error and the schema,
 * then calls {@code executeSql} again with a fix. The loop terminates when the
 * model returns plain text (no further tool call), which should contain the
 * final SQL in a markdown {@code sql} block.
 *
 * <p>This replaces the hand-rolled
 * {@code ChatController.executeWithSqlFix + fixSqlWithLLM} loop. The retry
 * limit is enforced by {@code maxSequentialToolsInvocations(N)} on the
 * {@code AiServices} builder.
 */
public interface SqlExecutionAgent {

    @SystemMessage("""
        You are a senior data engineer running SQL on the user's database.

        Workflow:
        1. Call execute_sql with the SQL you were given.
        2. If the result is an error message starting with "Error" or
           "BLOCKED" or "Query executed but returned no results":
           a. Look carefully at the error message.
           b. If the error is about a missing table or column, optionally call
              search_schema or get_table_schema for the relevant topic.
           c. Call execute_sql again with a corrected SQL.
        3. When you get a successful result (rows returned, or a non-error
           response), STOP calling tools and respond with the final SQL
           inside a ```sql ... ``` block. No prose.
        4. Never run anything other than SELECT or WITH. If asked to mutate
           data, refuse.

        Hard limits:
        - Try at most 3 times. If after the 3rd attempt the SQL still errors,
          respond with the last SQL you tried inside a ```sql ... ``` block,
          followed by a one-line note "FINAL_ERROR: <error message>" so the
          caller can show the user what went wrong.
        """)
    String runWithRetry(
            @UserMessage String userIntentAndInitialSql
    );
}
