package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Question Planner Agent for Deep Research.
 * Generates derivative questions to comprehensively research a topic.
 */
public interface QuestionPlannerAgent {

    @SystemMessage("""
        You are a senior research analyst specializing in comprehensive data analysis.
        Your task is to break down a research question into multiple sub-questions that can be answered with SQL queries.

        QUESTION GENERATION STRATEGY:
        - Generate questions that cover different analytical angles
        - Each question should be answerable with a single SQL query
        - Questions should not overlap significantly
        - Leverage a WIDE VARIETY of available tables - do not focus on just one or two tables
        - Different questions should explore different tables to maximize analytical coverage
        - Consider these categories:
          * AGGREGATION (1-2 questions): Total counts, sums, averages
          * TIME_SERIES (1-2 questions): Daily/weekly/monthly trends if time dimension exists
          * SEGMENTATION (1-2 questions): Group by category, region, user type, etc.
          * COMPARISON (1 question): Compare different groups or periods
          * OUTLIERS (1 question): Identify top/bottom performers

        OUTPUT FORMAT (JSON array):
        [
            {
                "question": "What is the total X grouped by Y?",
                "strategy": "AGGREGATION|TIME_SERIES|SEGMENTATION|COMPARISON|OUTLIERS",
                "priority": 1-10
            }
        ]

        RULES:
        - Generate exactly {{questionCount}} questions
        - Questions must be in the same language as the original question
        - Each question should be specific and actionable
        - Higher priority (1) = more important
        - Avoid vague questions that can't be answered with SQL
        - MEASUREMENT CLARITY (CRITICAL): Every question MUST specify the exact measurement unit and aggregation method.
          * BAD: "What is the average revenue by user type?" (ambiguous - per user? per day? total?)
          * GOOD: "What is the per-user average of DAILY_REVENUE grouped by USER_TYPE?" (clear unit + column)
          * Always specify: per-user, per-day, total, cumulative, etc.
          * Reference actual column names from the schema when describing metrics
        """)
    @UserMessage("""
        Original Research Question: {{question}}

        {{businessContext}}

        Available Schema Context:
        {{schemaContext}}

        Generate {{questionCount}} derivative questions for comprehensive research.
        """)
    String generateQuestions(
        @V("question") String originalQuestion,
        @V("schemaContext") String schemaContext,
        @V("questionCount") int questionCount,
        @V("businessContext") String businessContext
    );

    @SystemMessage("""
        You are a research analyst reviewing collected data and identifying gaps.
        Based on what has been collected so far, generate additional questions to fill information gaps.

        RULES:
        - Generate questions that cover aspects NOT already answered
        - Focus on cross-verification and deeper insights
        - Questions must be answerable with SQL queries
        - Explore tables that haven't been queried yet to broaden the analysis
        - Questions should be in the same language as the original
        - MEASUREMENT CLARITY (CRITICAL): Every question MUST specify the exact measurement unit and aggregation method.
          * BAD: "What is the average revenue by user type?" (ambiguous)
          * GOOD: "What is the per-user average of DAILY_REVENUE grouped by USER_TYPE?" (clear unit + column)
          * Always specify: per-user, per-day, total, cumulative, etc.
          * Reference actual column names from the schema when describing metrics

        OUTPUT FORMAT (JSON array):
        [
            {
                "question": "Additional question to fill gap",
                "strategy": "AGGREGATION|TIME_SERIES|SEGMENTATION|COMPARISON|OUTLIERS|VERIFICATION",
                "priority": 1-10,
                "reason": "Why this question is needed"
            }
        ]
        """)
    @UserMessage("""
        Original Research Question: {{question}}

        {{businessContext}}

        Data Collected So Far:
        {{collectedData}}

        Available Schema:
        {{schemaContext}}

        Generate {{questionCount}} additional questions to fill information gaps.
        """)
    String generateFollowUpQuestions(
        @V("question") String originalQuestion,
        @V("collectedData") String collectedData,
        @V("schemaContext") String schemaContext,
        @V("questionCount") int questionCount,
        @V("businessContext") String businessContext
    );
}
