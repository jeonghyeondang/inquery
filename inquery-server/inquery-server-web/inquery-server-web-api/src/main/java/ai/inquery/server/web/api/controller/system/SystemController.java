package ai.inquery.server.web.api.controller.system;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.param.SystemConfigParam;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.core.cache.CacheManage;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.config.InqueryProperties;
import ai.inquery.server.tools.common.enums.ModeEnum;
import ai.inquery.server.tools.common.model.ConfigJson;
import ai.inquery.server.tools.common.util.ConfigUtils;
import ai.inquery.server.tools.common.util.EasyEnumUtils;
import ai.inquery.server.web.api.controller.ai.inquery.client.InqueryAIClient;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import ai.inquery.server.web.api.controller.system.util.SystemUtils;
import ai.inquery.server.web.api.controller.system.vo.AppVersionVO;
import ai.inquery.server.web.api.controller.system.vo.SystemVO;
import ai.inquery.server.web.api.controller.system.vo.VersionInfoVO;
import ai.inquery.spi.ssh.SSHManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;


/**
 */
@RestController
@RequestMapping("/api/system")
@Slf4j
public class SystemController {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private InqueryProperties inqueryProperties;

    @Autowired
    private ConfigService configService;

    @Autowired
    private Environment environment;

    @Autowired
    private ai.inquery.server.web.api.service.VectorStoreRegistry vectorStoreRegistry;

    /**
     * Get version information
     *
     * @return VersionInfoVO
     */
    @GetMapping("/get-version-info")
    public DataResult<VersionInfoVO> getVersionInfo() {
        String backendVersion = inqueryProperties.getVersion();
        String frontendVersion = System.getProperty("frontend.version", "unknown");
        Long buildTime = System.currentTimeMillis();
        String[] activeProfiles = environment.getActiveProfiles();
        String env = activeProfiles.length > 0 ? activeProfiles[0] : "dev";

        VersionInfoVO versionInfo = VersionInfoVO.builder()
                .backendVersion(backendVersion)
                .frontendVersion(frontendVersion)
                .buildTime(buildTime)
                .environment(env)
                .build();

        return DataResult.of(versionInfo);
    }

    /**
     * Check if the test is successful
     *
     * @return
     */
    @GetMapping
    public DataResult<SystemVO> get() {
        // Pre-warm Pinecone connection in background when user accesses the site
        warmupPineconeAsync();

        String clientVersion = System.getProperty("client.version");
        String version = ConfigUtils.getLatestLocalVersion();
        // In web mode, client.version may not exist, so change to debug level
        log.debug("clientVersion:{},version:{}", clientVersion, version);
        if (!StringUtils.equals(clientVersion, version) && !StringUtils.isEmpty(clientVersion)) {
            stop();
            return null;
        } else {
            ConfigJson configJson = ConfigUtils.getConfig();
            return DataResult.of(SystemVO.builder()
                    .systemUuid(configJson.getSystemUuid())
                    .build());
        }
    }

    /**
     * Pre-warm vector store connection asynchronously when user visits the site.
     */
    private void warmupPineconeAsync() {
        new Thread(() -> {
            try {
                var provider = vectorStoreRegistry.getActiveProvider();
                provider.refresh();
                if (provider.isConfigured()) {
                    log.info("Pre-warming {} connection...", provider.getType());
                    long start = System.currentTimeMillis();
                    provider.ensureConnection();
                    log.info("{} connection pre-warmed in {}ms", provider.getType(), System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                log.debug("Vector store warmup failed (non-fatal): {}", e.getMessage());
            }
        }, "vectordb-warmup").start();
    }

    private static final String UPDATE_TYPE = "client_update_type";

    @GetMapping("/get_latest_version")
    public DataResult<AppVersionVO> getLatestVersion(String currentVersion) {
        ModeEnum mode = EasyEnumUtils.getEnum(ModeEnum.class, System.getProperty("inquery.mode"));
        if (mode != ModeEnum.DESKTOP) {
            // In this mode, no user login is required, so only local access is available
            return DataResult.of(null);
        }
        String user = "";
        DataResult<Config> dataResult = configService.find(InqueryAIClient.INQUERY_OPENAI_KEY);
        if (dataResult.getData() != null) {
            user = dataResult.getData().getContent();
        }
        AppVersionVO appVersionVO = SystemUtils.getLatestVersion(currentVersion, "manual", user);
        if (appVersionVO == null) {
            appVersionVO = new AppVersionVO();
            appVersionVO.setVersion(currentVersion);
            appVersionVO.setType("manual");
        }
        DataResult<Config> updateType = configService.find(UPDATE_TYPE);
        if (updateType.getData() != null) {
            appVersionVO.setType(updateType.getData().getContent());
        }
        // In this mode, no user login is required, so only local access is available
        appVersionVO.setDesktop(true);
        return DataResult.of(appVersionVO);
    }

    @PostMapping("/update_desktop_version")
    public DataResult<String> updateDesktopVersion(@RequestBody AppVersionVO version) {
        new Thread(() -> {
            SystemUtils.upgrade(version);
        }).start();
        return DataResult.of(version.getVersion());
    }

    @GetMapping("/is_update_success")
    public DataResult<Boolean> isUpdateSuccess(String version) {
        String localVersion = ConfigUtils.getLocalVersion();
        if (StringUtils.isEmpty(localVersion)) {
            return DataResult.of(false);
        }
        return DataResult.of(localVersion.equals(version));
    }

    @PostMapping("/set_update_type")
    public ActionResult setUpdateType(@RequestBody String updateType) {
        SystemConfigParam systemConfigParam = new SystemConfigParam();
        systemConfigParam.setCode(UPDATE_TYPE);
        systemConfigParam.setContent(updateType);
        systemConfigParam.setSummary("client update type");
        configService.createOrUpdate(systemConfigParam);
        return ActionResult.isSuccess();
    }

    /**
     * Get the current version number
     *
     * @return
     */
    @GetMapping("/get-version-a")
    public DataResult<String> getVersion() {
        return DataResult.of(inqueryProperties.getVersion());
    }

    /**
     * Exit service
     */
    @RequestMapping("/stop")
    public DataResult<String> stop(boolean forceQuit) {
        log.info("Exit application");
        if (forceQuit) {
            stop();
        } else {
//            String clientVersion = System.getProperty("client.version");
//            String version = ConfigUtils.getLatestLocalVersion();
//            log.error("clientVersion:{},version:{}", clientVersion, version);
//            if (!StringUtils.equals(clientVersion, version)) {
            stop();
            //}
        }
        return DataResult.of("ok");
    }

    private void stop() {
        new Thread(() -> {
            //  Will exit the background after 100ms
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log.info("Start exiting Spring application");
            SSHManager.close();
            try {
                SpringApplication.exit(applicationContext);
            } catch (Exception ignore) {
            }
            // It is possible that SpringApplication.exit will fail to exit
            // Direct system exit
            log.info("Start exiting system applications");
            CacheManage.close();
            try {
                System.exit(0);
            } catch (Exception ignore) {
            }

        }).start();
    }
}
