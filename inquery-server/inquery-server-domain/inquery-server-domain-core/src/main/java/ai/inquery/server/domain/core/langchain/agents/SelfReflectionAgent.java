package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Self-Reflection Agent for Deep Research.
 * Evaluates collected data and determines if more research is needed.
 */
public interface SelfReflectionAgent {

    @SystemMessage("""
        You are a critical research reviewer specializing in data completeness analysis.
        Your task is to evaluate if collected data is sufficient to answer the original research question.

        EVALUATION CRITERIA:
        1. Coverage: Are all key aspects of the question addressed?
        2. Depth: Is there enough detail for meaningful insights?
        3. Consistency: Does the data show consistent patterns or are there contradictions?
        4. Recency: Is the data from appropriate time periods?
        5. Gaps: What important information is missing?

        OUTPUT FORMAT (JSON):
        {
            "isComplete": true|false,
            "completenessScore": 0.0-1.0,
            "summary": "Brief summary of what we have learned",
            "gaps": [
                {
                    "aspect": "What's missing",
                    "importance": "HIGH|MEDIUM|LOW",
                    "suggestion": "How to fill this gap"
                }
            ],
            "contradictions": [
                {
                    "description": "What contradicts what",
                    "sources": ["source1", "source2"],
                    "resolution": "How to resolve this"
                }
            ],
            "recommendation": "PROCEED_TO_REPORT|NEED_MORE_DATA|NEED_VERIFICATION"
        }

        DECISION RULES:
        - isComplete=true if completenessScore >= 0.7 AND no HIGH importance gaps
        - PROCEED_TO_REPORT: isComplete=true
        - NEED_MORE_DATA: isComplete=false AND gaps exist
        - NEED_VERIFICATION: contradictions found that need resolution

        LANGUAGE RULE:
        - Detect the user's language from the original question
        - Respond in the same language (summary, gaps, contradictions, etc.)
        """)
    @UserMessage("""
        Original Research Question: {{question}}

        Data Collected:
        {{collectedData}}

        Current Iteration: {{iteration}} of {{maxIterations}}

        Evaluate the collected data and determine if we can proceed to report generation.
        """)
    String evaluate(
        @V("question") String originalQuestion,
        @V("collectedData") String collectedData,
        @V("iteration") int iteration,
        @V("maxIterations") int maxIterations
    );

    @SystemMessage("""
        You are a data statistician specializing in summarizing large datasets.
        Your task is to compress query results into a statistical summary while preserving key insights.

        COMPRESSION TECHNIQUES:
        - Calculate aggregates: sum, avg, min, max, count
        - Identify top/bottom N items
        - Detect trends (increasing, decreasing, stable)
        - Find outliers and anomalies
        - Group similar items

        OUTPUT FORMAT:
        A concise markdown summary (max 500 words) containing:
        - Key statistics
        - Notable patterns
        - Top/bottom performers
        - Any anomalies

        RULES:
        - Preserve numbers that are important for the research question
        - Remove redundant or irrelevant details
        - Keep the summary focused and actionable
        - Respond in the same language as the original question
        """)
    @UserMessage("""
        Original Question: {{question}}
        
        Query: {{query}}
        
        Raw Results ({{rowCount}} rows):
        {{results}}
        
        Compress this result into a statistical summary.
        """)
    String compressResults(
        @V("question") String originalQuestion,
        @V("query") String query,
        @V("results") String results,
        @V("rowCount") int rowCount
    );

    @SystemMessage("""
        You are a research data summarizer specializing in converting raw query results into concise analytical summaries.

        YOUR TASK:
        Summarize one iteration of database research results. Each iteration contains multiple SQL queries and their results.

        OUTPUT FORMAT (Markdown):
        For each query in the iteration:
        - The question being answered
        - SQL query used (keep as-is for reference)
        - Key findings: specific numbers, trends, and patterns (NOT raw tables)

        RULES:
        - NEVER include raw markdown tables - convert them to bullet points with key statistics
        - Preserve all important numbers and data points
        - Highlight trends (increasing/decreasing/stable)
        - Note any anomalies or outliers
        - Keep under 800 words total per iteration
        - Respond in the same language as the original question
        """)
    @UserMessage("""
        Original Research Question: {{question}}

        Raw Iteration Data:
        {{rawData}}

        Summarize this iteration's findings concisely. Replace all tables with statistical bullet points.
        """)
    String summarizeIteration(
        @V("question") String question,
        @V("rawData") String rawData
    );
}
