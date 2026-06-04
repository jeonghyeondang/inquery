package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.chart.ChartCreateParam;
import ai.inquery.server.domain.api.chart.ChartListQueryParam;
import ai.inquery.server.domain.api.chart.ChartQueryParam;
import ai.inquery.server.domain.api.chart.ChartUpdateParam;
import ai.inquery.server.domain.api.model.Chart;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.service.ChartService;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.core.converter.ChartConverter;
import ai.inquery.server.domain.core.util.PermissionUtils;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.ChartDO;
import ai.inquery.server.domain.repository.entity.DashboardChartRelationDO;
import ai.inquery.server.domain.repository.mapper.ChartMapper;
import ai.inquery.server.domain.repository.mapper.DashboardChartRelationMapper;
import ai.inquery.server.tools.base.enums.YesOrNoEnum;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.exception.DataNotFoundException;
import ai.inquery.server.tools.common.model.EasyLambdaQueryWrapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @version ChartServiceImpl.java, v 0.1 June 9, 2023 16:06 moji Exp $
 */
@Service
public class ChartServiceImpl implements ChartService {

    @Autowired
    private DataSourceService dataSourceService;


    private DashboardChartRelationMapper getDashboardMapper() {
        return Dbutils.getMapper(DashboardChartRelationMapper.class);
    }


    @Autowired
    private ChartConverter chartConverter;

    @Override
    public DataResult<Long> createWithPermission(ChartCreateParam param) {
        param.setGmtCreate(LocalDateTime.now());
        param.setGmtModified(LocalDateTime.now());
        param.setDeleted(YesOrNoEnum.NO.getLetter());
        param.setUserId(ContextUtils.getUserId());
        // Set default sourceType if not provided
        if (StringUtils.isBlank(param.getSourceType())) {
            param.setSourceType("DASHBOARD");
        }
        ChartDO chartDO = chartConverter.param2do(param);
        getMapper().insert(chartDO);
        return DataResult.of(chartDO.getId());
    }

    @Override
    public ActionResult updateWithPermission(ChartUpdateParam param) {
        Chart data = queryExistent(param.getId()).getData();
        PermissionUtils.checkOperationPermission(data.getUserId());

        param.setGmtModified(LocalDateTime.now());
        ChartDO chartDO = chartConverter.updateParam2do(param);
        getMapper().updateById(chartDO);
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Chart> find(Long id) {
        ChartDO chartDO = getMapper().selectById(id);
        if (YesOrNoEnum.YES.getLetter().equals(chartDO.getDeleted())) {
            return DataResult.empty();
        }
        Chart chart = chartConverter.do2model(chartDO);
        setDataSourceInfo(Lists.newArrayList(chart));
        return DataResult.of(chart);
    }

    @Override
    public DataResult<Chart> queryExistent(ChartQueryParam param) {
        EasyLambdaQueryWrapper<ChartDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        queryWrapper
            .eq(ChartDO::getDeleted, YesOrNoEnum.NO.getLetter())
            .eqWhenPresent(ChartDO::getId, param.getId())
            .eqWhenPresent(ChartDO::getUserId, param.getUserId());
        IPage<ChartDO> page = getMapper().selectPage(new Page<>(1, 1), queryWrapper);
        if (CollectionUtils.isEmpty(page.getRecords())) {
            throw new DataNotFoundException();
        }
        Chart data = chartConverter.do2model(page.getRecords().get(0));
        setDataSourceInfo(Lists.newArrayList(data));
        return DataResult.of(data);
    }

    @Override
    public DataResult<Chart> queryExistent(Long id) {
        DataResult<Chart> dataResult = find(id);
        if (dataResult.getData() == null) {
            throw new DataNotFoundException();
        }
        return dataResult;
    }

    @Override
    public ListResult<Chart> listQuery(ChartListQueryParam param) {
        EasyLambdaQueryWrapper<ChartDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        queryWrapper
            .eq(ChartDO::getDeleted, YesOrNoEnum.NO.getLetter())
            .inWhenPresent(ChartDO::getId, param.getIdList())
            .eqWhenPresent(ChartDO::getUserId, param.getUserId());
        List<ChartDO> queryList = getMapper().selectList(queryWrapper);
        List<Chart> list = chartConverter.do2model(queryList);
        setDataSourceInfo(list);
        return ListResult.of(list);
    }

    @Override
    public PageResult<Chart> listWithPagination(ChartListQueryParam param) {
        EasyLambdaQueryWrapper<ChartDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        queryWrapper.eq(ChartDO::getDeleted, YesOrNoEnum.NO.getLetter());

        // Search by name
        if (StringUtils.isNotBlank(param.getSearchKey())) {
            queryWrapper.like(ChartDO::getName, param.getSearchKey());
        }

        // Filter by user if onlyMine is true
        if (Boolean.TRUE.equals(param.getOnlyMine())) {
            queryWrapper.eq(ChartDO::getUserId, param.getUserId());
        }

        // Apply sorting
        String sortBy = param.getSortBy();
        if (StringUtils.isBlank(sortBy) || "recent".equals(sortBy)) {
            queryWrapper.orderByDesc(ChartDO::getGmtModified);
        } else if ("name".equals(sortBy)) {
            queryWrapper.orderByAsc(ChartDO::getName);
        } else if ("source".equals(sortBy)) {
            queryWrapper.orderByAsc(ChartDO::getSourceType);
        } else if ("vizType".equals(sortBy)) {
            queryWrapper.orderByAsc(ChartDO::getType);
        } else {
            queryWrapper.orderByDesc(ChartDO::getGmtModified);
        }

        // Pagination
        int pageNo = param.getPageNo() != null ? param.getPageNo() : 1;
        int pageSize = param.getPageSize() != null ? param.getPageSize() : 20;
        IPage<ChartDO> page = getMapper().selectPage(new Page<>(pageNo, pageSize), queryWrapper);

        List<Chart> list = chartConverter.do2model(page.getRecords());
        setDataSourceInfo(list);

        return PageResult.of(list, page.getTotal(), pageNo, pageSize);
    }

    @Override
    public ActionResult deleteWithPermission(Long id) {
        Chart data = queryExistent(id).getData();
        PermissionUtils.checkOperationPermission(data.getUserId());

        ChartDO chartDO = new ChartDO();
        chartDO.setId(id);
        chartDO.setDeleted(YesOrNoEnum.YES.getLetter());
        getMapper().updateById(chartDO);
        LambdaQueryWrapper<DashboardChartRelationDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DashboardChartRelationDO::getChartId, id);
        List<DashboardChartRelationDO> relationDO = getDashboardMapper().selectList(queryWrapper);
        List<Long> relationIds = relationDO.stream().map(DashboardChartRelationDO::getId).toList();
        if (CollectionUtils.isNotEmpty(relationIds)) {
            getDashboardMapper().deleteBatchIds(relationIds);
        }
        return ActionResult.isSuccess();
    }

    @Override
    public ListResult<Chart> queryByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return ListResult.empty();
        }
        List<ChartDO> chartDOS = getMapper().selectBatchIds(ids);
        List<Chart> charts = chartConverter.do2model(chartDOS);
        List<Chart> result = charts.stream().filter(o -> YesOrNoEnum.NO.getLetter().equals(o.getDeleted())).toList();
        setDataSourceInfo(result);
        return ListResult.of(result);
    }

    /**
     * Backfill data source information
     *
     * @param result
     */
    private void setDataSourceInfo(List<Chart> result) {
        List<Long> dataSourceIds = result.stream().map(Chart::getDataSourceId).toList();
        ListResult<DataSource> dataSourceListResult = dataSourceService.queryByIds(dataSourceIds);
        Map<Long, DataSource> dataSourceMap = dataSourceListResult.getData().stream().collect(
            Collectors.toMap(DataSource::getId, Function.identity(), (a, b) -> a));
        result.forEach(o -> {
            if (dataSourceMap.containsKey(o.getDataSourceId())) {
                o.setDataSourceName(dataSourceMap.get(o.getDataSourceId()).getAlias());
            }
        });
    }

    private ChartMapper getMapper() {
        return Dbutils.getMapper(ChartMapper.class);
    }
}
