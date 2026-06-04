package ai.inquery.server.domain.core.impl;

import java.sql.Connection;
import java.util.List;

import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.datasource.DataSourceCloseParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePageQueryParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePreConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceSelector;
import ai.inquery.server.domain.api.param.datasource.DataSourceTestParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import ai.inquery.server.domain.api.param.datasource.DatabaseQueryAllParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.api.service.DatabaseService;
import ai.inquery.server.domain.core.converter.DataSourceConverter;
import ai.inquery.server.domain.core.converter.EnvironmentConverter;
import ai.inquery.server.domain.core.util.DataSourceCredentialUtils;
import ai.inquery.server.domain.core.util.PermissionUtils;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataSourceAccessDO;
import ai.inquery.server.domain.repository.entity.DataSourceDO;
import ai.inquery.server.domain.repository.mapper.DataSourceAccessMapper;
import ai.inquery.server.domain.repository.mapper.DataSourceCustomMapper;
import ai.inquery.server.domain.repository.mapper.DataSourceMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.exception.DataNotFoundException;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.exception.PermissionDeniedBusinessException;
import ai.inquery.server.tools.common.model.LoginUser;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import ai.inquery.server.tools.common.util.EasyEnumUtils;
import ai.inquery.server.tools.common.util.EasySqlUtils;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.DataSourceConnect;
import ai.inquery.spi.model.Database;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.SQLExecutor;
import ai.inquery.spi.util.JdbcUtils;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @version DataSourceCoreServiceImpl.java, v 0.1 September 23, 2022 15:51 moji Exp $
 */
@Slf4j
@Service
public class DataSourceServiceImpl implements DataSourceService {


    private DataSourceMapper getMapper() {
        return Dbutils.getMapper(DataSourceMapper.class);
    }

    @Autowired
    private DataSourceConverter dataSourceConverter;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private DataCatalogService dataCatalogService;


    private DataSourceCustomMapper getCustomMapper() {
        return Dbutils.getMapper(DataSourceCustomMapper.class);
    }
    @Resource
    private EnvironmentConverter environmentConverter;
    private DataSourceAccessMapper getAccessMapper() {
        return Dbutils.getMapper(DataSourceAccessMapper.class);
    }

    @Override
    public DataResult<Long> createWithPermission(DataSourceCreateParam param) {
        DataSourceKindEnum dataSourceKind = EasyEnumUtils.getEnum(DataSourceKindEnum.class, param.getKind());
        if (dataSourceKind == null) {
            throw new ParamBusinessException("kind");
        }
        if (dataSourceKind == DataSourceKindEnum.SHARED && !ContextUtils.getLoginUser().getAdmin()) {
            throw new PermissionDeniedBusinessException();
        }
        JdbcUtils.removePropertySameAsDefault(param.getDriverConfig());
        DataSourceDO dataSourceDO = dataSourceConverter.param2do(param);
        dataSourceDO.setGmtCreate(DateUtil.date());
        dataSourceDO.setGmtModified(DateUtil.date());
        dataSourceDO.setUserId(ContextUtils.getUserId());
        //dataSourceDO.setExtendInfo(null);

        getMapper().insert(dataSourceDO);
        preWarmingData(dataSourceDO.getId());

        // For BigQuery: auto-save predefined metadata for Google service tables (GA4, Firebase, etc.)
        if ("BIGQUERY".equalsIgnoreCase(param.getType())) {
            final Long dsId = dataSourceDO.getId();
            final ai.inquery.server.tools.common.model.Context userContext = ContextUtils.queryContext();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                Dbutils.setSession();
                try {
                    if (userContext != null) {
                        ContextUtils.setContext(userContext);
                    }
                    // Need to set up connection context for loadAllTablesAndViews
                    ConnectInfo ci = new ConnectInfo();
                    DataResult<DataSource> dsResult = queryById(dsId);
                    if (dsResult.success() && dsResult.getData() != null) {
                        DataSource ds = dsResult.getData();
                        ci.setUrl(ds.getUrl());
                        ci.setUser(ds.getUserName());
                        ci.setPassword(ds.getPassword());
                        ci.setDriverConfig(ds.getDriverConfig());
                        ci.setExtendInfo(ds.getExtendInfo());
                        ci.setDbType(ds.getType());
                        ci.setDataSourceId(dsId);
                        InqueryContext.putContext(ci);
                    }

                    var tablesResult = dataCatalogService.loadAllTablesAndViews(dsId, false);
                    if (tablesResult != null && tablesResult.getData() != null) {
                        dataCatalogService.autoSaveBigQueryPredefinedMetadata(dsId, tablesResult.getData());
                    }
                    log.info("BigQuery predefined metadata auto-save completed for dataSourceId: {}", dsId);
                } catch (Exception e) {
                    log.warn("BigQuery predefined metadata auto-save failed for dataSourceId {}: {}", dsId, e.getMessage());
                } finally {
                    InqueryContext.removeContext();
                    ContextUtils.removeContext();
                    Dbutils.removeSession();
                }
            });
        }

        return DataResult.of(dataSourceDO.getId());
    }

    private void preWarmingData(Long dataSourceId) {
        DataResult<DataSource> dataResult = queryById(dataSourceId);
        if (dataResult.success() && dataResult.getData() != null) {
            DataSource dataSource = dataResult.getData();
            DriverConfig driverConfig = dataSource.getDriverConfig();
            if (driverConfig == null || StringUtils.isBlank(driverConfig.getJdbcDriver())) {
                return;
            }
            
            // Use DBManage to properly handle database-specific authentication (e.g., Snowflake Key Pair)
            Plugin plugin = InqueryContext.PLUGIN_MAP.get(dataSource.getType());
            if (plugin == null) {
                log.warn("No plugin found for database type: {}", dataSource.getType());
                return;
            }
            
            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setUrl(dataSource.getUrl());
            connectInfo.setUser(dataSource.getUserName());
            connectInfo.setPassword(dataSource.getPassword());
            connectInfo.setDriverConfig(driverConfig);
            connectInfo.setExtendInfo(dataSource.getExtendInfo());
            connectInfo.setDbType(dataSource.getType());
            connectInfo.setDataSourceId(dataSourceId);
            
            Connection connection = null;
            try {
                connection = plugin.getDBManage().getConnection(connectInfo);
                if (connection == null) {
                    log.warn("Failed to get connection for preWarmingData, dataSourceId: {}", dataSourceId);
                    return;
                }
                DatabaseQueryAllParam databaseQueryAllParam = new DatabaseQueryAllParam();
                databaseQueryAllParam.setDataSourceId(dataSourceId);
                databaseQueryAllParam.setConnection(connection);
                databaseQueryAllParam.setDbType(dataSource.getType());
                databaseQueryAllParam.setRefresh(true);
                databaseService.queryAll(databaseQueryAllParam);
            } catch (Exception e) {
                log.error("preWarmingData error for dataSourceId: {}", dataSourceId, e);
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception e) {
                        log.debug("Error closing connection", e);
                    }
                }
            }
        }
    }

    @Override
    public DataResult<Long> updateWithPermission(DataSourceUpdateParam param) {
        DataSource dataSource = queryExistent(param.getId(), null).getData();
        PermissionUtils.checkOperationPermission(dataSource.getUserId());

        DataSourceCredentialUtils.mergeUpdateSecrets(dataSource, param);

        JdbcUtils.removePropertySameAsDefault(param.getDriverConfig());
        DataSourceDO dataSourceDO = dataSourceConverter.param2do(param);
        dataSourceDO.setGmtModified(DateUtil.date());
        getMapper().updateById(dataSourceDO);
        return DataResult.of(dataSourceDO.getId());
    }

    @Override
    public ActionResult deleteWithPermission(Long id) {

        DataSource dataSource = queryExistent(id, null).getData();
        PermissionUtils.checkOperationPermission(dataSource.getUserId());

        getMapper().deleteById(id);

        LambdaQueryWrapper<DataSourceAccessDO> dataSourceAccessQueryWrapper = new LambdaQueryWrapper<>();
        dataSourceAccessQueryWrapper.eq(DataSourceAccessDO::getDataSourceId, id)
        ;
        getAccessMapper().delete(dataSourceAccessQueryWrapper);
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<DataSource> queryById(Long id) {
        DataSourceDO dataSourceDO = getMapper().selectById(id);
        return DataResult.of(dataSourceConverter.do2dto(dataSourceDO));
    }

    @Override
    public DataResult<DataSource> queryExistent(Long id, DataSourceSelector selector) {
        DataResult<DataSource> dataResult = queryById(id);
        if (dataResult.getData() == null) {
            throw new DataNotFoundException();
        }

        fillData(Lists.newArrayList(dataResult.getData()), selector);

        return dataResult;
    }

    @Override
    public DataResult<Long> copyByIdWithPermission(Long id) {
        DataSource dataSource = queryExistent(id, null).getData();
        PermissionUtils.checkOperationPermission(dataSource.getUserId());

        DataSourceDO dataSourceDO = getMapper().selectById(id);
        dataSourceDO.setId(null);
        String alias = dataSourceDO.getAlias() + "Copy";
        dataSourceDO.setAlias(alias);
        dataSourceDO.setGmtCreate(DateUtil.date());
        dataSourceDO.setGmtModified(DateUtil.date());
        getMapper().insert(dataSourceDO);
        return DataResult.of(dataSourceDO.getId());
    }

    @Override
    public PageResult<DataSource> queryPage(DataSourcePageQueryParam param, DataSourceSelector selector) {
        LambdaQueryWrapper<DataSourceDO> queryWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(param.getSearchKey())) {
            queryWrapper.and(wrapper -> wrapper.like(DataSourceDO::getAlias, "%" + param.getSearchKey() + "%")
                    .or()
                    .like(DataSourceDO::getUrl, "%" + param.getSearchKey() + "%"));
        }
        Integer start = param.getPageNo();
        Integer offset = param.getPageSize();
        Page<DataSourceDO> page = new Page<>(start, offset);
        IPage<DataSourceDO> iPage = getMapper().selectPage(page, queryWrapper);
        List<DataSource> dataSources = dataSourceConverter.do2dto(iPage.getRecords());

        fillData(dataSources, selector);

        return PageResult.of(dataSources, iPage.getTotal(), param);
    }

    @Override
    public PageResult<DataSource> queryPageWithPermission(DataSourcePageQueryParam param, DataSourceSelector selector) {
        LoginUser loginUser = ContextUtils.getLoginUser();

        IPage<DataSourceDO> iPage = getCustomMapper().selectPageWithPermission(
                new Page<>(param.getPageNo(), param.getPageSize()),
                BooleanUtils.isTrue(loginUser.getAdmin()), loginUser.getId(), param.getSearchKey(), param.getKind(),
                EasySqlUtils.orderBy(param.getOrderByList()));

        List<DataSource> dataSources = dataSourceConverter.do2dto(iPage.getRecords());

        fillData(dataSources, selector);

        return PageResult.of(dataSources, iPage.getTotal(), param);

    }

    @Override
    public ListResult<DataSource> queryByIds(List<Long> ids) {
        return listQuery(ids, null);
    }

    @Override
    public ListResult<DataSource> listQuery(List<Long> idList, DataSourceSelector selector) {
        if (CollectionUtils.isEmpty(idList)) {
            return ListResult.empty();
        }
        List<DataSourceDO> dataList = getMapper().selectBatchIds(idList);
        List<DataSource> list = dataSourceConverter.do2dto(dataList);

        fillData(list, selector);
        return ListResult.of(list);
    }

    @Override
    public ActionResult preConnect(DataSourcePreConnectParam param) {
        if (param.getId() != null) {
            DataSource existing = queryExistent(param.getId(), null).getData();
            PermissionUtils.checkOperationPermission(existing.getUserId());
            DataSourceCredentialUtils.mergePreConnectSecrets(existing, param);
        }
        DataSourceTestParam testParam
                = dataSourceConverter.param2param(param);
        DriverConfig driverConfig = testParam.getDriverConfig();
        if (driverConfig == null || !driverConfig.notEmpty()) {
            driverConfig = InqueryContext.getDefaultDriverConfig(param.getType());
        }

        // Use plugin's getConnection() to properly handle DB-specific auth (e.g. Snowflake keypair)
        Plugin plugin = InqueryContext.PLUGIN_MAP.get(param.getType());
        if (plugin != null) {
            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setUrl(testParam.getUrl());
            connectInfo.setHost(testParam.getHost());
            if (StringUtils.isNotBlank(testParam.getPort())) {
                connectInfo.setPort(Integer.parseInt(testParam.getPort()));
            }
            connectInfo.setUser(testParam.getUsername());
            connectInfo.setPassword(testParam.getPassword());
            connectInfo.setDbType(testParam.getDbType());
            connectInfo.setDriverConfig(driverConfig);
            connectInfo.setSsh(param.getSsh());
            connectInfo.setExtendInfo(param.getExtendInfo());

            Connection connection = null;
            try {
                connection = plugin.getDBManage().getConnection(connectInfo);
                return ActionResult.isSuccess();
            } catch (Exception e) {
                log.error("preConnect via plugin failed:", e);
                Throwable t = e;
                while (t.getCause() != null) {
                    t = t.getCause();
                }
                return ActionResult.fail(t.getMessage(), null, null);
            } finally {
                if (connection != null) {
                    try { connection.close(); } catch (Exception ignore) {}
                }
            }
        }

        // Fallback to JdbcUtils.testConnect for unknown DB types
        DataSourceConnect dataSourceConnect = JdbcUtils.testConnect(testParam.getUrl(), testParam.getHost(),
                testParam.getPort(),
                testParam.getUsername(), testParam.getPassword(), testParam.getDbType(),
                driverConfig, param.getSsh(), KeyValue.toMap(param.getExtendInfo()));
        if (BooleanUtils.isNotTrue(dataSourceConnect.getSuccess())) {
            return ActionResult.fail(dataSourceConnect.getMessage(), dataSourceConnect.getDescription(),
                    dataSourceConnect.getErrorDetail());
        }
        return ActionResult.isSuccess();
    }

    @Override
    public ListResult<Database> connect(Long id) {
        DatabaseQueryAllParam queryAllParam = new DatabaseQueryAllParam();
        queryAllParam.setDataSourceId(id);
        List<Database> databases = InqueryContext.getMetaData().databases(InqueryContext.getConnection());
        return ListResult.of(databases);
    }

    @Override
    public ActionResult close(Long id) {
        DataSourceCloseParam closeParam = new DataSourceCloseParam();
        closeParam.setDataSourceId(id);
        return ActionResult.isSuccess();
    }

    private void fillData(List<DataSource> list, DataSourceSelector selector) {
        if (CollectionUtils.isEmpty(list) || selector == null) {
            return;
        }

        fillEnvironment(list, selector);

        fillSupportDatabase(list);
    }

    private void fillSupportDatabase(List<DataSource> list) {

        if(CollectionUtils.isEmpty(list)) {
            return;
        }
        for (DataSource dataSource:list) {
            String type = dataSource.getType();
            if(StringUtils.isNotBlank(type)) {
                DBConfig config = InqueryContext.getDBConfig(type);
                if(config != null) {
                    dataSource.setSupportDatabase(config.isSupportDatabase());
                    dataSource.setSupportSchema(config.isSupportSchema());
                }
            }
        }
    }


    private void fillEnvironment(List<DataSource> list, DataSourceSelector selector) {
        if (BooleanUtils.isNotTrue(selector.getEnvironment())) {
            return;
        }
        environmentConverter.fillDetail(EasyCollectionUtils.toList(list, DataSource::getEnvironment));
    }

}
