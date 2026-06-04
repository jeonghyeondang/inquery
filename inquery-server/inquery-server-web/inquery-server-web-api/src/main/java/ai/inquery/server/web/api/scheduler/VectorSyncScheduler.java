package ai.inquery.server.web.api.scheduler;

import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataSourceDO;
import ai.inquery.server.domain.repository.entity.TableCacheDO;
import ai.inquery.server.domain.repository.mapper.DataSourceMapper;
import ai.inquery.server.domain.repository.mapper.TableCacheMapper;
import ai.inquery.server.web.api.service.VectorStoreRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scheduled job that syncs vector store with actual database tables.
 * Removes orphaned vectors (vectors for tables that no longer exist in table_cache).
 * Runs every 6 hours by default.
 */
@Slf4j
@Component
public class VectorSyncScheduler {

    @Autowired
    private VectorStoreRegistry vectorStoreRegistry;

    @Scheduled(cron = "0 0 */6 * * *")
    public void syncOrphanedVectors() {
        log.info("Starting orphaned vector sync job");

        try {
            Dbutils.setSession();

            VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
            if (!provider.isConfigured()) {
                provider.refresh();
                if (!provider.isConfigured()) {
                    log.info("Vector store ({}) is not configured, skipping vector sync", provider.getType());
                    return;
                }
            }

            DataSourceMapper dataSourceMapper = Dbutils.getMapper(DataSourceMapper.class);
            List<DataSourceDO> dataSources = dataSourceMapper.selectList(null);

            if (dataSources == null || dataSources.isEmpty()) {
                log.info("No data sources found, skipping vector sync");
                return;
            }

            Set<String> namespaces = dataSources.stream()
                .map(ds -> ds.getType())
                .filter(StringUtils::isNotBlank)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

            log.info("Found {} data sources with {} unique namespaces: {}",
                dataSources.size(), namespaces.size(), namespaces);

            int totalDeleted = 0;

            for (String namespace : namespaces) {
                try {
                    int deleted = syncNamespace(provider, namespace, dataSources);
                    totalDeleted += deleted;
                } catch (Exception e) {
                    log.error("Failed to sync namespace: {}", namespace, e);
                }
            }

            log.info("Orphaned vector sync completed. Total vectors deleted: {}", totalDeleted);

        } catch (Exception e) {
            log.error("Orphaned vector sync job failed", e);
        } finally {
            Dbutils.removeSession();
        }
    }

    private int syncNamespace(VectorStoreProvider provider, String namespace, List<DataSourceDO> dataSources) {
        log.info("Syncing namespace: {}", namespace);

        List<String> allVectorIds = provider.listAllVectors(namespace);
        if (allVectorIds.isEmpty()) {
            log.info("No vectors found in namespace: {}", namespace);
            return 0;
        }

        log.info("Found {} vectors in namespace: {}", allVectorIds.size(), namespace);

        List<VectorData> allVectors = provider.fetchVectors(allVectorIds, namespace);
        log.info("Fetched metadata for {} vectors in namespace: {}", allVectors.size(), namespace);

        Map<String, List<VectorData>> vectorGroups = new HashMap<>();
        for (VectorData vector : allVectors) {
            if (vector.getMetadata() == null) continue;

            String databaseName = vector.getMetadata().get("databaseName");
            String schemaName = vector.getMetadata().get("schemaName");
            if (StringUtils.isBlank(databaseName)) continue;

            String groupKey = databaseName + ":" + (schemaName != null ? schemaName : "default");
            vectorGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(vector);
        }

        TableCacheMapper tableCacheMapper = Dbutils.getMapper(TableCacheMapper.class);
        List<String> orphanedVectorIds = new ArrayList<>();

        for (Map.Entry<String, List<VectorData>> entry : vectorGroups.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String databaseName = parts[0];
            String schemaName = "default".equals(parts[1]) ? null : parts[1];
            List<VectorData> groupVectors = entry.getValue();

            LambdaQueryWrapper<TableCacheDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(TableCacheDO::getDatabaseName, databaseName);
            if (StringUtils.isNotBlank(schemaName)) {
                queryWrapper.eq(TableCacheDO::getSchemaName, schemaName);
            }

            List<TableCacheDO> cachedTables = tableCacheMapper.selectList(queryWrapper);

            if (cachedTables.isEmpty()) {
                log.debug("No table_cache entries for db={}, schema={} - skipping (cache not populated)",
                    databaseName, schemaName);
                continue;
            }

            Set<String> existingTableNames = cachedTables.stream()
                .map(TableCacheDO::getTableName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

            for (VectorData vector : groupVectors) {
                String tableName = vector.getMetadata().get("tableName");
                if (StringUtils.isNotBlank(tableName) && !existingTableNames.contains(tableName)) {
                    orphanedVectorIds.add(vector.getId());
                    log.info("Orphaned vector found: id={}, table={}.{}.{}",
                        vector.getId(), databaseName, schemaName, tableName);
                }
            }
        }

        if (orphanedVectorIds.isEmpty()) {
            log.info("No orphaned vectors found in namespace: {}", namespace);
            return 0;
        }

        log.info("Deleting {} orphaned vectors from namespace: {}", orphanedVectorIds.size(), namespace);

        int deleted = 0;
        for (int i = 0; i < orphanedVectorIds.size(); i += 100) {
            int endIndex = Math.min(i + 100, orphanedVectorIds.size());
            List<String> batch = orphanedVectorIds.subList(i, endIndex);
            boolean success = provider.delete(batch, namespace);
            if (success) {
                deleted += batch.size();
            } else {
                log.error("Failed to delete batch {}-{} of orphaned vectors", i, endIndex);
            }
        }

        return deleted;
    }
}
