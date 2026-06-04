
package ai.inquery.server.web.api.controller.data.source.request;

/**
 */
public interface DataSourceBaseRequestInfo {

    /**
     * Get datasoure id
     * @return
     */
    Long getDataSourceId();

    /**
     * get datasoure name
     * @return
     */
    String getDatabaseName();

    /**
     * get schema name
     * @return
     */
    String getSchemaName();
}