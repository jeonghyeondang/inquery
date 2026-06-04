package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.service.ViewService;
import ai.inquery.server.domain.core.cache.CacheManage;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.model.Table;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static ai.inquery.server.domain.core.cache.CacheKey.getViewsKey;

@Slf4j
@Service
public class ViewServiceImpl implements ViewService {

    @Override
    public ListResult<Table> views(String databaseName, String schemaName, boolean refresh) {
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        Long dataSourceId = connectInfo != null ? connectInfo.getDataSourceId() : null;
        
        log.info("ViewService.views called - databaseName: {}, schemaName: {}, refresh: {}, dataSourceId: {}", 
                databaseName, schemaName, refresh, dataSourceId);
        
        if (dataSourceId == null) {
            // Fallback to direct query if dataSourceId is not available
            log.warn("dataSourceId is null, querying views directly without cache");
            List<Table> views = InqueryContext.getMetaData().views(InqueryContext.getConnection(), databaseName, schemaName);
            log.info("Direct query returned {} views", views != null ? views.size() : 0);
            return ListResult.of(views);
        }
        
        String viewsKey = getViewsKey(dataSourceId, databaseName, schemaName);
        log.debug("Using cache key: {}", viewsKey);
        
        List<Table> views = CacheManage.getList(viewsKey, Table.class,
                (key) -> {
                    log.debug("Cache refresh check for key: {}, refresh requested: {}", key, refresh);
                    return refresh;
                }, 
                (key) -> {
                    log.info("Cache miss or refresh requested, querying views from database - databaseName: {}, schemaName: {}", 
                            databaseName, schemaName);
                    List<Table> result = InqueryContext.getMetaData().views(InqueryContext.getConnection(), databaseName, schemaName);
                    log.info("Database query returned {} views", result != null ? result.size() : 0);
                    return result;
                });
        
        log.info("ViewService.views returning {} views", views != null ? views.size() : 0);
        return ListResult.of(views);
    }

    @Override
    public DataResult<Table> detail(String databaseName, String schemaName, String tableName) {
        MetaData metaSchema = InqueryContext.getMetaData();
        Table table = metaSchema.view(InqueryContext.getConnection(), databaseName, schemaName, tableName);
        return DataResult.of(table);
    }

}
