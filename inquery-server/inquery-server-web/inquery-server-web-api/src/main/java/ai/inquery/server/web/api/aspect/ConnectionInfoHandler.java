package ai.inquery.server.web.api.aspect;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.service.DataSourceAccessBusinessService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequestInfo;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceConsoleRequestInfo;
import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 */
@Component
@Aspect
@Slf4j
public class ConnectionInfoHandler {

    @Autowired
    private DataSourceService dataSourceService;
    @Resource
    private DataSourceAccessBusinessService dataSourceAccessBusinessService;

    @Around("within(@ai.inquery.server.web.api.aspect.ConnectionInfoAspect *)")
    public Object connectionInfoHandler(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        try {
            Object[] params = proceedingJoinPoint.getArgs();
            if (params != null && params.length > 0) {
                for (int i = 0; i < params.length; i++) {
                    Object param = params[i];
                    if (param instanceof DataSourceBaseRequest) {
                        Long dataSourceId = ((DataSourceBaseRequest) param).getDataSourceId();
                        // Skip if dataSourceId is null (allow general chat without DB)
                        if (dataSourceId != null) {
                            String schemaName = ((DataSourceBaseRequest) param).getSchemaName();
                            String database = ((DataSourceBaseRequest) param).getDatabaseName();
                            InqueryContext.putContext(toInfo(dataSourceId, database, null, schemaName));
                        }
                    } else if (param instanceof DataSourceConsoleRequestInfo) {
                        Long dataSourceId = ((DataSourceConsoleRequestInfo) param).getDataSourceId();
                        // Skip if dataSourceId is null
                        if (dataSourceId != null) {
                            Long consoleId = ((DataSourceConsoleRequestInfo) param).getConsoleId();
                            String database = ((DataSourceConsoleRequestInfo) param).getDatabaseName();
                            InqueryContext.putContext(toInfo(dataSourceId, database, consoleId, null));
                        }
                    } else if (param instanceof DataSourceBaseRequestInfo) {
                        Long dataSourceId = ((DataSourceBaseRequestInfo) param).getDataSourceId();
                        // Skip if dataSourceId is null
                        if (dataSourceId != null) {
                            String database = ((DataSourceBaseRequestInfo) param).getDatabaseName();
                            String schemaName = ((DataSourceBaseRequestInfo) param).getSchemaName();
                            InqueryContext.putContext(toInfo(dataSourceId, database, null, schemaName));
                        }
                    }
                }
            }
            return proceedingJoinPoint.proceed();
        } finally {
            InqueryContext.removeContext();
        }
    }

    public ConnectInfo toInfo(Long dataSourceId, String database, Long consoleId, String schemaName) {
        DataResult<DataSource> result = dataSourceService.queryById(dataSourceId);
        DataSource dataSource = result.getData();
        if (!result.success() || dataSource == null) {
            throw new ParamBusinessException("dataSourceId");
        }

        // Verify permissions
        dataSourceAccessBusinessService.checkPermission(dataSource);

        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setAlias(dataSource.getAlias());
        connectInfo.setUser(dataSource.getUserName());
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setPassword(dataSource.getPassword());
        connectInfo.setDbType(dataSource.getType());
        connectInfo.setUrl(dataSource.getUrl());
        connectInfo.setDatabase(database);
        connectInfo.setSchemaName(schemaName);
        connectInfo.setConsoleOwn(false);
        connectInfo.setDriver(dataSource.getDriver());
        connectInfo.setSsh(dataSource.getSsh());
        connectInfo.setSsl(dataSource.getSsl());
        connectInfo.setJdbc(dataSource.getJdbc());
        connectInfo.setExtendInfo(dataSource.getExtendInfo());
        connectInfo.setUrl(dataSource.getUrl());
        connectInfo.setPort(StringUtils.isNotBlank(dataSource.getPort()) ? Integer.parseInt(dataSource.getPort()) : null);
        connectInfo.setHost(dataSource.getHost());
        connectInfo.setLoginUser(ContextUtils.getLoginUser().getId() + "");
        DriverConfig driverConfig = dataSource.getDriverConfig();
        if (driverConfig != null && driverConfig.notEmpty()) {
            connectInfo.setDriverConfig(driverConfig);
        }
        return connectInfo;
    }

    public ConnectInfo toInfo(Long dataSourceId, String database) {
        return toInfo(dataSourceId, database, null, null);
    }

}