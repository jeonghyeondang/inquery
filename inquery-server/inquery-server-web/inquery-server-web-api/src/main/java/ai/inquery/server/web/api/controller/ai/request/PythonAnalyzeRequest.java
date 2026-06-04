package ai.inquery.server.web.api.controller.ai.request;

import ai.inquery.spi.model.ExecuteResult;
import lombok.Data;

import java.util.List;

/**
 * Request for Python analysis of query results.
 */
@Data
public class PythonAnalyzeRequest {
    /**
     * Original user question (used as context for Python code generation).
     */
    private String userQuestion;

    /**
     * SQL execution result data to analyze.
     */
    private List<ExecuteResult> resultData;
}
