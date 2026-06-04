package ai.inquery.server.domain.core.impl;

import java.util.List;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.model.Team;
import ai.inquery.server.domain.api.param.team.TeamCreateParam;
import ai.inquery.server.domain.api.param.team.TeamPageQueryParam;
import ai.inquery.server.domain.api.param.team.TeamSelector;
import ai.inquery.server.domain.api.param.team.TeamUpdateParam;
import ai.inquery.server.domain.api.service.TeamService;
import ai.inquery.server.domain.core.converter.TeamConverter;
import ai.inquery.server.domain.core.converter.UserConverter;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataSourceAccessDO;
import ai.inquery.server.domain.repository.entity.TeamDO;
import ai.inquery.server.domain.repository.entity.TeamUserDO;
import ai.inquery.server.domain.repository.mapper.DataSourceAccessMapper;
import ai.inquery.server.domain.repository.mapper.TeamMapper;
import ai.inquery.server.domain.repository.mapper.TeamUserMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.exception.DataAlreadyExistsBusinessException;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.model.EasyLambdaQueryWrapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * team
 *
 */
@Slf4j
@Service
public class TeamServiceImpl implements TeamService {

    private TeamMapper getTeamMapper() {
        return Dbutils.getMapper(TeamMapper.class);
    }

    private TeamUserMapper getTeamUserMapper() {
        return Dbutils.getMapper(TeamUserMapper.class);
    }

    private DataSourceAccessMapper getDataSourceAccessMapper() {
        return Dbutils.getMapper(DataSourceAccessMapper.class);
    }
    @Resource
    private TeamConverter teamConverter;
    @Resource
    private UserConverter userConverter;

    @Override
    public ListResult<Team> listQuery(List<Long> idList) {
        if (CollectionUtils.isEmpty(idList)) {
            return ListResult.empty();
        }
        LambdaQueryWrapper<TeamDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(TeamDO::getId, idList);
        List<TeamDO> dataList = getTeamMapper().selectList(queryWrapper);
        List<Team> list = teamConverter.do2dto(dataList);
        return ListResult.of(list);
    }

    @Override
    public PageResult<Team> pageQuery(TeamPageQueryParam param, TeamSelector selector) {
        EasyLambdaQueryWrapper<TeamDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(param.getSearchKey())) {
            queryWrapper.and(wrapper -> wrapper.like(TeamDO::getCode, "%" + param.getSearchKey() + "%")
                .or()
                .like(TeamDO::getName, "%" + param.getSearchKey() + "%"));
        }
        Page<TeamDO> page = new Page<>(param.getPageNo(), param.getPageSize());
        page.setSearchCount(param.getEnableReturnCount());
        queryWrapper.orderBy(param.getOrderByList());
        IPage<TeamDO> iPage = getTeamMapper().selectPage(page, queryWrapper);
        List<Team> list = teamConverter.do2dto(iPage.getRecords());

        fillData(list, selector);

        return PageResult.of(list, iPage.getTotal(), param);
    }

    @Override
    public DataResult<Long> create(TeamCreateParam param) {
        LambdaQueryWrapper<TeamDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TeamDO::getCode, param.getCode());
        Page<TeamDO> page = new Page<>(1, 1);
        page.setSearchCount(false);
        IPage<TeamDO> iPage = getTeamMapper().selectPage(page, queryWrapper);
        if (CollectionUtils.isNotEmpty(iPage.getRecords())) {
            throw new DataAlreadyExistsBusinessException("code", param.getCode());
        }
        if (RoleCodeEnum.DESKTOP.getCode().equals(param.getRoleCode())) {
            throw new ParamBusinessException("roleCode");
        }

        TeamDO data = teamConverter.param2do(param, ContextUtils.getUserId());
        getTeamMapper().insert(data);
        return DataResult.of(data.getId());
    }

    @Override
    public DataResult<Long> update(TeamUpdateParam param) {
        TeamDO data = teamConverter.param2do(param, ContextUtils.getUserId());
        getTeamMapper().updateById(data);
        return DataResult.of(data.getId());
    }

    @Override
    public ActionResult delete(Long id) {
        getTeamMapper().deleteById(id);

        LambdaQueryWrapper<TeamUserDO> teamUserQueryWrapper = new LambdaQueryWrapper<>();
        teamUserQueryWrapper.eq(TeamUserDO::getTeamId, id);
        getTeamUserMapper().delete(teamUserQueryWrapper);

        LambdaQueryWrapper<DataSourceAccessDO>  dataSourceAccessQueryWrapper = new LambdaQueryWrapper<>();
        dataSourceAccessQueryWrapper.eq(DataSourceAccessDO::getAccessObjectId, id)
            .eq(DataSourceAccessDO::getAccessObjectType, AccessObjectTypeEnum.TEAM.getCode())
        ;
        getDataSourceAccessMapper().delete(dataSourceAccessQueryWrapper);
        return ActionResult.isSuccess();
    }

    private void fillData(List<Team> list, TeamSelector selector) {
        if (CollectionUtils.isEmpty(list) || selector == null) {
            return;
        }
        fillUser(list, selector);
    }

    private void fillUser(List<Team> list, TeamSelector selector) {
        if (BooleanUtils.isNotTrue(selector.getModifiedUser())) {
            return;
        }
        userConverter.fillDetail(EasyCollectionUtils.toList(list, Team::getModifiedUser));
    }

}
