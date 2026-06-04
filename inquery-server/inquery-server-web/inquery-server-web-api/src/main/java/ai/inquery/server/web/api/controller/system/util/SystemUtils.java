package ai.inquery.server.web.api.controller.system.util;


import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.util.ConfigUtils;
import ai.inquery.server.web.api.controller.system.vo.AppVersionVO;
import ai.inquery.spi.ssh.SSHManager;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.http.HttpUtil;
import com.dtflys.forest.Forest;
import com.dtflys.forest.utils.TypeReference;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.time.Duration;

/**
 * System Toolkit
 *
 */
@Slf4j
public class SystemUtils {

    /**
     * Stop current application
     */
    public static void stop() {
        new Thread(() -> {
            log.info("Exit the application after 1 second");
            // Automatically exit the application after 1 second
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // Direct system exit
            log.info("Start exiting system applications");
            SSHManager.close();
            try {
                System.exit(0);
            } catch (Exception ignore) {
            }
        }).start();
    }

    private static final OkHttpClient client = new OkHttpClient();

    private static final String VERSION_URL = ""; // Disabled external version check

    private static final String ZIP_FILE_PATH = ConfigUtils.APP_PATH + File.separator + "versions" + File.separator;

    public static void upgrade(AppVersionVO appVersion) {

        String appPath = ConfigUtils.APP_PATH;

        log.info("appPath: {}", appPath);
        if (StringUtils.isBlank(appPath) || !appPath.contains("app")) {
            return;
        }
        try {
            String zipPath = ZIP_FILE_PATH + appVersion.getVersion() + ".zip";

            HttpUtil.downloadFile(appVersion.getHotUpgradeUrl(), ZIP_FILE_PATH + appVersion.getVersion() + ".zip");

            ZipUtil.unzip(zipPath);

            FileUtil.del(zipPath);

            ConfigUtils.updateVersion(appVersion.getVersion());
        } catch (Exception e) {
            log.error("checkVersionUpdates error", e);
        }
    }

    private static final String LATEST_VERSION_URL = ""; // Disabled external version check

    public static AppVersionVO getLatestVersion(String version, String type, String userId) {
        // External version check disabled for security
        return null;
    }

}
