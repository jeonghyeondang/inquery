package ai.inquery.server.web.api.controller.ai.request;

import lombok.Data;

/**
 * Workspace SQL generation request.
 * Used for IDE-style code generation in the workspace editor.
 */
@Data
public class WorkspaceGenerateRequest {

    /**
     * User's instruction (e.g., "add a where clause for active users")
     */
    private String message;

    /**
     * Currently selected SQL code in the editor (context for editing)
     */
    private String selectedCode;

    /**
     * Full editor content (for broader context)
     */
    private String editorContent;

    /**
     * Data source ID for schema lookup
     */
    private Long dataSourceId;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * AI model to use (optional, defaults to fast model)
     */
    private String model;
}
