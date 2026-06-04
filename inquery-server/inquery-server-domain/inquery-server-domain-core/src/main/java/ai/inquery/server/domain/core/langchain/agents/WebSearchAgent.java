package ai.inquery.server.domain.core.langchain.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Agent for web search operations in Deep Research.
 * Uses LLM to generate search queries and synthesize results.
 */
public interface WebSearchAgent {

    /**
     * Generate search queries from a research topic.
     * Returns JSON array of search queries.
     */
    @SystemMessage("""
        You are a search query generator for research purposes.
        Generate effective search queries to find relevant information about the given topic.
        
        Output format (JSON array):
        [
            {
                "query": "search query string",
                "purpose": "what this query aims to find",
                "priority": 1-5 (1 = highest priority)
            }
        ]
        
        Guidelines:
        - Generate 3-5 search queries per topic
        - Include variations: news, trends, analysis, recent updates
        - Be specific enough to get relevant results
        - Consider different angles: technical, business, user perspective
        """)
    @UserMessage("""
        Generate search queries for the following research topic:
        
        Topic: {{topic}}
        Context: {{context}}
        
        Return a JSON array of search queries.
        """)
    String generateSearchQueries(@V("topic") String topic, @V("context") String context);

    /**
     * Synthesize web search results into a structured summary.
     */
    @SystemMessage("""
        You are a research assistant that synthesizes web search results.
        Extract key information and organize it into a structured summary.
        
        IMPORTANT: Respond in the same language as the original question.
        
        Output format (JSON):
        {
            "summary": "Brief overall summary",
            "keyFindings": [
                {
                    "topic": "Finding topic",
                    "content": "Detailed finding",
                    "source": "Source reference if available",
                    "relevance": "high" | "medium" | "low"
                }
            ],
            "trends": ["trend1", "trend2"],
            "sentiment": "positive" | "negative" | "neutral" | "mixed",
            "dataPoints": [
                {
                    "metric": "Metric name",
                    "value": "Value",
                    "context": "Context"
                }
            ]
        }
        """)
    @UserMessage("""
        Synthesize the following web search results for the research question.
        
        Original Question: {{question}}
        
        Search Results:
        {{searchResults}}
        
        Return a JSON object with structured findings.
        """)
    String synthesizeResults(@V("question") String question, @V("searchResults") String searchResults);

    /**
     * Evaluate if web search results are sufficient for the research.
     */
    @SystemMessage("""
        You are evaluating the completeness of web search results for research.
        
        Output format (JSON):
        {
            "isComplete": boolean,
            "completenessScore": 0.0-1.0,
            "missingAspects": ["aspect1", "aspect2"],
            "suggestedQueries": ["query1", "query2"]
        }
        """)
    @UserMessage("""
        Evaluate if the following search results are sufficient for the research question.
        
        Research Question: {{question}}
        
        Collected Information:
        {{collectedInfo}}
        
        Return a JSON object with evaluation results.
        """)
    String evaluateCompleteness(@V("question") String question, @V("collectedInfo") String collectedInfo);
}
