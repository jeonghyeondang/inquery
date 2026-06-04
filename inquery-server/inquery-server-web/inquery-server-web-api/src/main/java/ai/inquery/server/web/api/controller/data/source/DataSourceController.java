package ai.inquery.server.web.api.controller.data.source;

import java.util.List;

import ai.inquery.server.domain.core.util.DataSourceCredentialUtils;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.ConsoleCloseParam;
import ai.inquery.server.domain.api.param.ConsoleConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePageQueryParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePreConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceSelector;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import ai.inquery.server.domain.api.service.ConsoleService;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.tools.common.exception.ConnectionException;
import ai.inquery.server.tools.common.model.Context;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.spi.model.Database;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.ssh.SSHManager;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.base.wrapper.result.web.WebPageResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.data.source.converter.DataSourceWebConverter;
import ai.inquery.server.web.api.controller.data.source.converter.SSHWebConverter;
import ai.inquery.server.web.api.controller.data.source.request.ConsoleCloseRequest;
import ai.inquery.server.web.api.controller.data.source.request.ConsoleConnectRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceAttachRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceCloneRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceCloseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceCreateRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceQueryRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceTestRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceUpdateRequest;
import ai.inquery.server.web.api.aspect.ConnectionInfoHandler;
import ai.inquery.server.web.api.controller.data.source.request.SSHTestRequest;
import ai.inquery.server.web.api.controller.data.source.vo.DataSourceVO;
import ai.inquery.server.web.api.controller.data.source.vo.DatabaseVO;
import ai.inquery.server.domain.core.lineage.LineageDetectService;
import ai.inquery.server.domain.repository.Dbutils;
import com.jcraft.jsch.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Database connection class
 *
 * @version ConnectionController.java, v 0.1 September 16, 2022 14:07 moji Exp $
 */
@ConnectionInfoAspect
@RequestMapping("/api/connection")
@RestController
@Slf4j
public class DataSourceController {

    private static final DataSourceSelector DATA_SOURCE_SELECTOR = DataSourceSelector.builder()
        .environment(Boolean.TRUE)
        .build();

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private ConsoleService consoleService;

    @Autowired
    private DataCatalogService dataCatalogService;

    @Autowired
    private ConnectionInfoHandler connectionInfoHandler;

    @Autowired
    private DataSourceWebConverter dataSourceWebConverter;

    @Autowired
    private SSHWebConverter sshWebConverter;

    @Autowired
    private LineageDetectService lineageDetectService;

    /**
     * Database connection test
     *
     * @param request
     * @return
     */
    @RequestMapping("/datasource/pre_connect")
    public ActionResult preConnect(@RequestBody DataSourceTestRequest request) {
        DataSourcePreConnectParam param = dataSourceWebConverter.testRequest2param(request);
        return dataSourceService.preConnect(param);
    }

    /**
     * Database connection test
     *
     * @param request
     * @return
     */
    @RequestMapping("/ssh/pre_connect")
    public ActionResult sshConnect(@RequestBody SSHTestRequest request) {
        Session session = null;
        try {
            session = SSHManager.getSSHSession(sshWebConverter.toInfo(request));
        } catch (Exception e) {
            log.error("sshConnect error", e);
            throw new ConnectionException("connection.ssh.error", null, e);
        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
        return ActionResult.isSuccess();
    }

    /**
     * Database connection
     *
     * @param request
     * @return
     */
    @GetMapping("/datasource/connect")
    public ListResult<DatabaseVO> attach(@Valid @NotNull DataSourceAttachRequest request) {
        ListResult<Database> databaseDTOListResult = dataSourceService.connect(request.getId());
        List<DatabaseVO> databaseVOS = dataSourceWebConverter.databaseDto2vo(databaseDTOListResult.getData());

        // Warm up table/view metadata cache in background after successful connection.
        // This ensures IntelliSense data is available instantly when user opens a console.
        // Capture thread-local contexts before going async (they won't be available in the new thread).
        final Long dataSourceId = request.getId();
        final ConnectInfo connectInfo = connectionInfoHandler.toInfo(dataSourceId, null);
        final Context userContext = ContextUtils.queryContext();
        CompletableFuture.runAsync(() -> {
            try {
                // Restore thread-local contexts in the async thread
                if (userContext != null) {
                    ContextUtils.setContext(userContext);
                }
                InqueryContext.putContext(connectInfo);
                var tablesResult = dataCatalogService.loadAllTablesAndViews(dataSourceId, false);
                log.info("Background metadata warmup completed for dataSourceId: {}", dataSourceId);

                // Auto-save predefined metadata for BigQuery Google service tables (GA4, Firebase, etc.)
                if ("BIGQUERY".equalsIgnoreCase(connectInfo.getDbType()) && tablesResult != null && tablesResult.getData() != null) {
                    dataCatalogService.autoSaveBigQueryPredefinedMetadata(dataSourceId, tablesResult.getData());
                }
            } catch (Exception e) {
                log.warn("Background metadata warmup failed for dataSourceId {}: {}", dataSourceId, e.getMessage());
            } finally {
                InqueryContext.removeContext();
                ContextUtils.removeContext();
            }
        });

        // Auto-detect lineage in background on first connection
        ConnectInfo lineageConnectInfo = connectionInfoHandler.toInfo(dataSourceId, null);
        CompletableFuture.runAsync(() -> {
            Dbutils.setSession();
            try {
                lineageDetectService.detectAndPersist(dataSourceId, lineageConnectInfo);
            } catch (Exception e) {
                log.warn("Background lineage detection failed for dataSourceId {}: {}", dataSourceId, e.getMessage());
            } finally {
                Dbutils.removeSession();
            }
        });

        return ListResult.of(databaseVOS);
    }

    /**
     * Close database connection
     *
     * @param request
     * @return
     */
    @GetMapping("/datasource/close")
    public ActionResult close(@Valid @NotNull DataSourceCloseRequest request) {
        return dataSourceService.close(request.getId());
    }

    /**
     * Console connection
     *
     * @param request
     * @return
     */
    @GetMapping("/console/connect")
    public ActionResult connect(@Valid @NotNull ConsoleConnectRequest request) {
        ConsoleConnectParam consoleConnectParam = dataSourceWebConverter.request2connectParam(request);
        return consoleService.createConsole(consoleConnectParam);
    }

    /**
     * Close the Console connection
     *
     * @param request
     * @return
     */
    @GetMapping("/console/close")
    public ActionResult closeConsole(@Valid @NotNull ConsoleCloseRequest request) {
        ConsoleCloseParam closeParam = dataSourceWebConverter.request2closeParam(request);
        return consoleService.closeConsole(closeParam);
    }

    /**
     * Query the database connection I established
     *
     * @param request
     * @return
     * @version 2.1.0
     */
    @GetMapping("/datasource/list")
    public WebPageResult<DataSourceVO> list(DataSourceQueryRequest request) {
        DataSourcePageQueryParam param = dataSourceWebConverter.queryReq2param(request);
        PageResult<DataSource> result = dataSourceService.queryPageWithPermission(param, DATA_SOURCE_SELECTOR);
        List<DataSource> dataSources = result.getData();
        if (dataSources != null) {
            dataSources.forEach(DataSourceCredentialUtils::maskForClient);
        }
        List<DataSourceVO> dataSourceVOS = dataSourceWebConverter.dto2vo(dataSources);
        return WebPageResult.of(dataSourceVOS, result.getTotal(), result.getPageNo(), result.getPageSize());
    }

    /**
     * Get connection content
     *
     * @param id
     * @return
     */
    @GetMapping("/datasource/{id}")
    public DataResult<DataSourceVO> queryById(@PathVariable("id") Long id) {
        DataResult<DataSource> dataResult = dataSourceService.queryExistent(id, DATA_SOURCE_SELECTOR);
        DataSource dataSource = dataResult.getData();
        DataSourceCredentialUtils.maskForClient(dataSource);
        DataSourceVO dataSourceVO = dataSourceWebConverter.dto2vo(dataSource);
        if (StringUtils.isNotBlank(dataSourceVO.getUser())) {
            dataSourceVO.setAuthenticationType("1");
        } else {
            dataSourceVO.setAuthenticationType("2");
        }
        return DataResult.of(dataSourceVO);
    }

    /**
     * save connection
     *
     * @param request
     * @return
     */
    @PostMapping("/datasource/create")
    public DataResult<Long> create(@RequestBody DataSourceCreateRequest request) {
        DataSourceCreateParam param = dataSourceWebConverter.createReq2param(request);
        return dataSourceService.createWithPermission(param);
    }

    /**
     * Update connection
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/datasource/update", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<Long> update(@RequestBody DataSourceUpdateRequest request) {
        DataSourceUpdateParam param = dataSourceWebConverter.updateReq2param(request);
        return dataSourceService.updateWithPermission(param);
    }

    /**
     *  clone connection
     *
     * @param request
     * @return
     */
    @PostMapping("/datasource/clone")
    public DataResult<Long> copy(@RequestBody DataSourceCloneRequest request) {
        return dataSourceService.copyByIdWithPermission(request.getId());
    }

    /**
     * Delete connection
     *
     * @param id
     * @return
     */
    @DeleteMapping("/datasource/{id}")
    public ActionResult delete(@PathVariable Long id) {
        return dataSourceService.deleteWithPermission(id);
    }

}
