package ai.inquery.server.web.api.controller.erd.request;

import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for ERD schema information
 * Extends DataSourceBaseRequest to enable ConnectionInfoAspect to set up database connection
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ERDSchemaRequest extends DataSourceBaseRequest {
    // All fields (dataSourceId, databaseName, schemaName) are inherited from DataSourceBaseRequest
    
    /**
     * Whether to refresh the cache (force re-fetch from database)
     */
    private boolean refresh = false;
}

