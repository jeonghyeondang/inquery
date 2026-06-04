package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.TeamUserDO;
import com.baomidou.mybatisplus.core.mapper.Mapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

/**
 * Team User Custom Mapper
 *
 */
public interface TeamUserCustomMapper extends Mapper<TeamUserDO> {

    IPage<TeamUserDO> comprehensivePageQuery(IPage<TeamUserDO> page, @Param("teamId") Long teamId,
        @Param("userId") Long userId, @Param("teamSearchKey") String teamSearchKey, @Param("userSearchKey") String userSearchKey);
}
