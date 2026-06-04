package ai.inquery.server.domain.core.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.model.OperationLog;
import ai.inquery.server.domain.api.param.operation.OperationLogCreateParam;
import ai.inquery.server.domain.api.param.operation.OperationLogPageQueryParam;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.api.service.OperationLogService;
import ai.inquery.server.domain.core.converter.OperationLogConverter;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.OperationLogDO;
import ai.inquery.server.domain.repository.mapper.OperationLogMapper;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.model.EasyLambdaQueryWrapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.tools.common.util.EasySqlUtils;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @version UserExecutedDdlCoreServiceImpl.java, v 0.1 September 25, 2022 14:07 moji Exp $
 */
@Service
public class OperationLogServiceImpl implements OperationLogService {


    private OperationLogMapper getMapper() {
        return Dbutils.getMapper(OperationLogMapper.class);
    }

    @Autowired
    private OperationLogConverter operationLogConverter;

    @Autowired
    private DataSourceService dataSourceService;

    @Override
    public DataResult<Long> create(OperationLogCreateParam param) {
        OperationLogDO userExecutedDdlDO = operationLogConverter.param2do(param);
        userExecutedDdlDO.setGmtCreate(LocalDateTime.now());
        userExecutedDdlDO.setGmtModified(LocalDateTime.now());
        // Use userId from param if provided (for async calls), otherwise get from ThreadLocal
        Long userId = param.getUserId() != null ? param.getUserId() : ContextUtils.getUserId();
        userExecutedDdlDO.setUserId(userId);
        getMapper().insert(userExecutedDdlDO);
        return DataResult.of(userExecutedDdlDO.getId());
    }

    @Override
    public PageResult<OperationLog> queryPage(OperationLogPageQueryParam param) {
        EasyLambdaQueryWrapper<OperationLogDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        queryWrapper.likeWhenPresent(OperationLogDO::getDdl, EasySqlUtils.buildLikeRightFuzzy(param.getSearchKey()))
                .eqWhenPresent(OperationLogDO::getUserId, param.getUserId())
                .eqWhenPresent(OperationLogDO::getDataSourceId, param.getDataSourceId())
                .eqWhenPresent(OperationLogDO::getDatabaseName, param.getDatabaseName())
                .eqWhenPresent(OperationLogDO::getSchemaName, param.getSchemaName())
                .eqWhenPresent(OperationLogDO::getSource, param.getSource())
        ;
        Integer start = param.getPageNo();
        Integer offset = param.getPageSize();
        Page<OperationLogDO> page = new Page<>(start, offset);
        page.setOptimizeCountSql(false);
        page.setOrders(Arrays.asList(OrderItem.desc("gmt_create")));
        IPage<OperationLogDO> executedDdlDOIPage = getMapper().selectPage(page, queryWrapper);
        List<OperationLog> executedDdlDTOS = operationLogConverter.do2dto(executedDdlDOIPage.getRecords());
        if (CollectionUtils.isEmpty(executedDdlDTOS)) {
            return PageResult.empty(param.getPageNo(), param.getPageSize());
        }
        List<Long> dataSourceIds = executedDdlDTOS.stream().map(OperationLog::getDataSourceId).toList();
        ListResult<DataSource> dataSourceListResult = dataSourceService.queryByIds(dataSourceIds);
        Map<Long, DataSource> dataSourceMap = dataSourceListResult.getData().stream().collect(
            Collectors.toMap(DataSource::getId, Function.identity(), (a, b) -> a));
        executedDdlDTOS.stream().forEach(executeDdl -> {
            if (dataSourceMap.containsKey(executeDdl.getDataSourceId())) {
                executeDdl.setDataSourceName(dataSourceMap.get(executeDdl.getDataSourceId()).getAlias());
            }
        });
        return PageResult.of(executedDdlDTOS, executedDdlDOIPage.getTotal(), param);
    }

    @Override
    public DataResult<Boolean> delete(Long id) {
        if (id == null) {
            return DataResult.of(false);
        }
        int deleted = getMapper().deleteById(id);
        return DataResult.of(deleted > 0);
    }

    @Override
    public DataResult<Boolean> clearByDataSource(Long dataSourceId) {
        if (dataSourceId == null) {
            return DataResult.of(false);
        }
        EasyLambdaQueryWrapper<OperationLogDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        queryWrapper.eq(OperationLogDO::getDataSourceId, dataSourceId)
                    .eq(OperationLogDO::getUserId, ContextUtils.getUserId());
        int deleted = getMapper().delete(queryWrapper);
        return DataResult.of(deleted >= 0);
    }
}
