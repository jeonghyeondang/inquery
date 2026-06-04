package ai.inquery.server.web.api.scheduler;

import ai.inquery.server.domain.core.lineage.LineageDetectService;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataSourceDO;
import ai.inquery.server.domain.repository.mapper.DataSourceMapper;
import ai.inquery.server.web.api.aspect.ConnectionInfoHandler;
import ai.inquery.spi.sql.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Daily scheduled job to auto-detect lineage for all data sources.
 * Runs at 03:00 every day.
 */
@Slf4j
@Component
public class LineageDetectScheduler {

    @Autowired
    private LineageDetectService lineageDetectService;

    @Autowired
    private ConnectionInfoHandler connectionInfoHandler;

    @Scheduled(cron = "0 0 3 * * *")
    public void detectLineageForAllSources() {
        log.info("Starting daily lineage detection job");

        try {
            Dbutils.setSession();

            DataSourceMapper dataSourceMapper = Dbutils.getMapper(DataSourceMapper.class);
            List<DataSourceDO> dataSources = dataSourceMapper.selectList(null);

            if (dataSources == null || dataSources.isEmpty()) {
                log.info("No data sources found, skipping lineage detection");
                return;
            }

            int success = 0;
            int failed = 0;

            for (DataSourceDO ds : dataSources) {
                try {
                    ConnectInfo connectInfo = connectionInfoHandler.toInfo(ds.getId(), null);
                    lineageDetectService.detectAndPersist(ds.getId(), connectInfo);
                    success++;
                } catch (Exception e) {
                    log.warn("Lineage detection failed for dataSourceId={}, alias={}: {}",
                            ds.getId(), ds.getAlias(), e.getMessage());
                    failed++;
                }
            }

            log.info("Daily lineage detection completed: {} success, {} failed out of {} total",
                    success, failed, dataSources.size());

        } catch (Exception e) {
            log.error("Daily lineage detection job failed", e);
        } finally {
            Dbutils.removeSession();
        }
    }
}
