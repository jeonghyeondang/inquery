package ai.inquery.server.web.api.ws;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.DlExecuteParam;
import ai.inquery.server.domain.api.service.*;
import ai.inquery.server.domain.core.cache.CacheKey;
import ai.inquery.server.domain.core.cache.MemoryCacheManage;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.model.LoginUser;
import ai.inquery.server.web.api.controller.ai.inquery.client.InqueryAIClient;
import ai.inquery.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.inquery.server.web.api.controller.rdb.request.DmlRequest;
import ai.inquery.server.web.api.controller.rdb.vo.ExecuteResultVO;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.sql.ConnectInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class WsService {

    @Autowired
    private UserService userService;


    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private DataSourceAccessBusinessService dataSourceAccessBusinessService;


    @Autowired
    private RdbWebConverter rdbWebConverter;

    @Autowired
    private DlTemplateService dlTemplateService;

    public static ExecutorService executorService = Executors.newFixedThreadPool(10);

    public ListResult<ExecuteResultVO> execute(DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        ListResult<ExecuteResult> resultDTOListResult = dlTemplateService.execute(param);
        List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultDTOListResult.getData());
        return ListResult.of(resultVOS);
    }


    public LoginUser doLogin(String token) {
        Long userId = RoleCodeEnum.DESKTOP.getDefaultUserId();
        LoginUser loginUser = MemoryCacheManage.computeIfAbsent(CacheKey.getLoginUserKey(userId), () -> {
            User user = userService.query(userId).getData();
            if (user == null) {
                return null;
            }
            boolean admin = RoleCodeEnum.ADMIN.getCode().equals(user.getRoleCode());

            return LoginUser.builder()
                    .id(user.getId())
                    .nickName(user.getNickName())
                    .admin(admin)
                    .roleCode(user.getRoleCode())
                    .build();
        });


        loginUser.setToken(userId.toString());
        return loginUser;
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
        DriverConfig driverConfig = dataSource.getDriverConfig();
        if (driverConfig != null && driverConfig.notEmpty()) {
            connectInfo.setDriverConfig(driverConfig);
        }
        return connectInfo;
    }


    private String getApiKey() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(InqueryAIClient.INQUERY_OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return null;
        }
        return keyConfig.getContent();
    }


}
