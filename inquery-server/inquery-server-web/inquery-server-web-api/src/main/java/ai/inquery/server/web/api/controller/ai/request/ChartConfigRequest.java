package ai.inquery.server.web.api.controller.ai.request;

import lombok.Data;

/**
 * Request for LLM-based chart configuration recommendation
 */
@Data
public class ChartConfigRequest {

    /**
     * Original user question
     */
    private String originalQuery;

    /**
     * SQL query result data (sample rows)
     */
    private Object sqlResult;

    /**
     * Generated SQL query
     */
    private String generatedSql;

    /**
     * AI model to use (optional, defaults to gemini-3.1-flash-lite)
     */
    private String model;
}
