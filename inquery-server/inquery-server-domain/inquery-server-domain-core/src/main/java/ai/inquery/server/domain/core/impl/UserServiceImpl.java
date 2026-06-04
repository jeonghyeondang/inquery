package ai.inquery.server.domain.core.impl;

import java.util.List;
import java.util.Objects;

import ai.inquery.server.domain.api.enums.AccessObjectTypeEnum;
import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.param.user.UserCreateParam;
import ai.inquery.server.domain.api.param.user.UserPageQueryParam;
import ai.inquery.server.domain.api.param.user.UserSelector;
import ai.inquery.server.domain.api.param.user.UserUpdateParam;
import ai.inquery.server.domain.api.service.UserService;
import ai.inquery.server.domain.core.converter.UserConverter;
import ai.inquery.server.domain.core.event.UserCreatedEvent;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataSourceAccessDO;
import ai.inquery.server.domain.repository.entity.InqueryUserDO;
import ai.inquery.server.domain.repository.entity.TeamUserDO;
import ai.inquery.server.domain.repository.mapper.DataSourceAccessMapper;
import ai.inquery.server.domain.repository.mapper.InqueryUserMapper;
import ai.inquery.server.domain.repository.mapper.TeamUserMapper;
import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.common.exception.DataAlreadyExistsBusinessException;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.tools.common.model.EasyLambdaQueryWrapper;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * User service
 *
 */
@Service
public class UserServiceImpl implements UserService {


    private InqueryUserMapper getUserMapper() {
        return Dbutils.getMapper(InqueryUserMapper.class);
    }
    @Resource
    private UserConverter userConverter;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    private TeamUserMapper getTeamUserMapper() {
        return Dbutils.getMapper(TeamUserMapper.class);
    }
    private DataSourceAccessMapper getDataSourceAccessMapper() {
        return Dbutils.getMapper(DataSourceAccessMapper.class);
    }

    @Override
    public DataResult<User> query(Long id) {
        return DataResult.of(userConverter.do2dto(getUserMapper().selectById(id)));
    }

    @Override
    public DataResult<User> query(String userName) {
        LambdaQueryWrapper<InqueryUserDO> query = new LambdaQueryWrapper<>();
        if (Objects.nonNull(userName)) {
            query.eq(InqueryUserDO::getUserName, userName);
        }
        InqueryUserDO userDO = getUserMapper().selectOne(query);
        return DataResult.of(userConverter.do2dto(userDO));
    }

    @Override
    public ListResult<User> listQuery(List<Long> idList) {
        if (CollectionUtils.isEmpty(idList)) {
            return ListResult.empty();
        }
        LambdaQueryWrapper<InqueryUserDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(InqueryUserDO::getId, idList);
        List<InqueryUserDO> dataList = getUserMapper().selectList(queryWrapper);
        List<User> list = userConverter.do2dto(dataList);
        return ListResult.of(list);
    }

    @Override
    public PageResult<User> pageQuery(UserPageQueryParam param, UserSelector selector) {
        EasyLambdaQueryWrapper<InqueryUserDO> queryWrapper = new EasyLambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(param.getSearchKey())) {
            queryWrapper.and(wrapper -> wrapper.like(InqueryUserDO::getUserName, "%" + param.getSearchKey() + "%")
                .or()
                .like(InqueryUserDO::getNickName, "%" + param.getSearchKey() + "%")
                .or()
                .like(InqueryUserDO::getEmail, "%" + param.getSearchKey() + "%"));
        }
        // Default not to query desktop accounts
        queryWrapper.ne(InqueryUserDO::getId, RoleCodeEnum.DESKTOP.getDefaultUserId());
        queryWrapper.orderBy(param.getOrderByList());
        Page<InqueryUserDO> page = new Page<>(param.getPageNo(), param.getPageSize());
        page.setSearchCount(param.getEnableReturnCount());
        IPage<InqueryUserDO> iPage = getUserMapper().selectPage(page, queryWrapper);
        List<User> list = userConverter.do2dto(iPage.getRecords());

        fillData(list, selector);
        return PageResult.of(list, iPage.getTotal(), param);
    }

    @Override
    public DataResult<Long> update(UserUpdateParam param) {
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(param.getId())) {
            throw new BusinessException("user.canNotOperateSystemAccount");
        }
        if (RoleCodeEnum.DESKTOP.getCode().equals(param.getRoleCode())) {
            throw new ParamBusinessException("roleCode");
        }

        InqueryUserDO data = userConverter.param2do(param, ContextUtils.getUserId());
        if (Objects.nonNull(data.getPassword())) {
            String bcryptPassword = DigestUtil.bcrypt(data.getPassword());
            data.setPassword(bcryptPassword);
        }

        if (RoleCodeEnum.ADMIN.getDefaultUserId().equals(param.getId())) {
            data.setStatus(null);
            data.setEmail(null);
            data.setUserName(null);
            data.setRoleCode(null);
        }
        getUserMapper().updateById(data);
        return DataResult.of(data.getId());
    }

    @Override
    public DataResult<Boolean> updatePassword(Long userId, String bcryptPassword) {
        if (userId == null) {
            throw new ParamBusinessException("userId");
        }
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(userId)) {
            throw new BusinessException("user.canNotOperateSystemAccount");
        }
        InqueryUserDO data = new InqueryUserDO();
        data.setId(userId);
        data.setPassword(bcryptPassword);
        getUserMapper().updateById(data);
        return DataResult.of(Boolean.TRUE);
    }

    @Override
    public ActionResult delete(Long id) {
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(id) || RoleCodeEnum.ADMIN.getDefaultUserId().equals(id)) {
            throw new BusinessException("user.canNotOperateSystemAccount");
        }
        getUserMapper().deleteById(id);

        LambdaQueryWrapper<TeamUserDO> teamUserQueryWrapper = new LambdaQueryWrapper<>();
        teamUserQueryWrapper.eq(TeamUserDO::getUserId, id);
        getTeamUserMapper().delete(teamUserQueryWrapper);

        LambdaQueryWrapper<DataSourceAccessDO>  dataSourceAccessQueryWrapper = new LambdaQueryWrapper<>();
        dataSourceAccessQueryWrapper.eq(DataSourceAccessDO::getAccessObjectId, id)
            .eq(DataSourceAccessDO::getAccessObjectType, AccessObjectTypeEnum.USER.getCode())
        ;
        getDataSourceAccessMapper().delete(dataSourceAccessQueryWrapper);
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Long> create(UserCreateParam param) {
        LambdaQueryWrapper<InqueryUserDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(wrapper -> wrapper.eq(InqueryUserDO::getUserName, param.getUserName())
            .or()
            .eq(InqueryUserDO::getEmail, param.getEmail()));
        Page<InqueryUserDO> page = new Page<>(1, 1);
        page.setSearchCount(false);
        IPage<InqueryUserDO> iPage = getUserMapper().selectPage(page, queryWrapper);
        if (CollectionUtils.isNotEmpty(iPage.getRecords())) {
            throw new DataAlreadyExistsBusinessException("userName or email",
                param.getUserName() + " or " + param.getEmail());
        }
        if (RoleCodeEnum.DESKTOP.getCode().equals(param.getRoleCode())) {
            throw new ParamBusinessException("roleCode");
        }

        InqueryUserDO data = userConverter.param2do(param, ContextUtils.getUserId());
        String bcryptPassword = DigestUtil.bcrypt(data.getPassword());
        data.setPassword(bcryptPassword);
        getUserMapper().insert(data);
        applicationEventPublisher.publishEvent(new UserCreatedEvent(data.getId()));
        return DataResult.of(data.getId());
    }

    private void fillData(List<User> list, UserSelector selector) {
        if (CollectionUtils.isEmpty(list) || selector == null) {
            return;
        }
        fillUser(list, selector);
    }

    private void fillUser(List<User> list, UserSelector selector) {
        if (BooleanUtils.isNotTrue(selector.getModifiedUser())) {
            return;
        }
        userConverter.fillDetail(EasyCollectionUtils.toList(list, User::getModifiedUser));
    }

}
