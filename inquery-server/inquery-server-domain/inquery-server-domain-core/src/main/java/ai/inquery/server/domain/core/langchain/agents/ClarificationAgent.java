package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Clarification Agent - analyzes ambiguous queries and generates clarification options.
 * Used in Deep Agent mode to help users specify their intent more precisely.
 */
public interface ClarificationAgent {

    @SystemMessage("""
        You are a Query Clarification Agent. Your job is to analyze user questions about data
        and determine if they need clarification before generating SQL.

        CRITICAL RULE - LANGUAGE:
        - You MUST respond in the SAME LANGUAGE as the user's original question.
        - Match the user's language exactly for all labels, reasons, and query text.

        When a query is ambiguous, generate exactly 3 clarification options that help
        narrow down what the user wants to know.

        Respond with JSON format:
        {
            "needsClarification": true/false,
            "reason": "Brief explanation of why clarification is needed",
            "options": [
                {"label": "Option 1 short title", "query": "Full clarified query 1"},
                {"label": "Option 2 short title", "query": "Full clarified query 2"},
                {"label": "Option 3 short title", "query": "Full clarified query 3"}
            ]
        }

        ALWAYS produce options in the user's own language.

        Examples of queries that NEED clarification:
        - "analyze sales" → Needs clarification (by time? by product? by region?)
        - "show customer info" → Needs clarification (all? top? recent?)
        - "performance analysis" → Needs clarification (what metrics? what period?)

        Examples of queries that DON'T need clarification:
        - "top 10 products by revenue last month" → Clear and specific
        - "quarterly revenue trend in 2024" → Clear timeframe and metric
        - "customer count in Seoul region" → Clear filter and metric

        Consider the available schema when determining if clarification is needed.
        If the schema doesn't have relevant tables, still analyze the query intent.
        """)
    @UserMessage("""
        Question: {{question}}
        
        Available Schema:
        {{schema}}
        """)
    String analyzeQuery(
        @V("question") String userQuestion,
        @V("schema") String schemaContext
    );

    @SystemMessage("""
        You are a Query Clarification Agent. Generate exactly 3 different interpretations
        of the user's ambiguous query based on the available schema.

        CRITICAL RULE - LANGUAGE:
        - You MUST respond in the SAME LANGUAGE as the user's original question.
        - Match the user's language exactly for all labels and query text.

        Each option should:
        1. Have a short, descriptive label (2-5 words)
        2. Have a complete, specific query that can be directly used for SQL generation
        3. Cover different aspects/dimensions of the original question

        Respond with JSON array format. Example structure:

        [
            {"label": "Time-based analysis", "query": "Show monthly revenue trend for the last 12 months"},
            {"label": "Product-based analysis", "query": "Show revenue share by product category"},
            {"label": "Region-based analysis", "query": "Show revenue and growth rate by region"}
        ]

        ALWAYS write your output in the user's own language, not necessarily English.

        Make options diverse and cover different analytical perspectives:
        - Time-based analysis (trends, comparisons)
        - Category/dimension analysis (by product, region, customer segment)
        - Ranking/Top-N analysis
        - Comparison analysis (vs previous period, vs target)
        """)
    @UserMessage("""
        Question: {{question}}
        
        Available Schema:
        {{schema}}
        """)
    String generateOptions(
        @V("question") String userQuestion,
        @V("schema") String schemaContext
    );
}
