
package ai.inquery.server.web.start.config.config;

import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.util.JdbcJarUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 */
@Component
@Slf4j
public class JarDownloadTask implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        List<String> urls = new ArrayList<>();
        InqueryContext.PLUGIN_MAP.forEach((k, v) -> {
            v.getDBConfig().getDriverConfigList().forEach(driverConfig -> {
                if (driverConfig != null && !CollectionUtils.isEmpty(driverConfig.getDownloadJdbcDriverUrls()) && (
                    "MYSQL".equals(driverConfig.getDbType()))) {
                    urls.addAll(driverConfig.getDownloadJdbcDriverUrls());
                }
            });
        });
        JdbcJarUtils.asyncDownload(urls);
    }
}