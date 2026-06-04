package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.DataSourceAccessDO;
import com.baomidou.mybatisplus.core.mapper.Mapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

/**
 * Data Source Access Mapper
 *
 */
public interface DataSourceAccessCustomMapper extends Mapper<DataSourceAccessDO> {

    IPage<DataSourceAccessDO> comprehensivePageQuery(IPage<DataSourceAccessDO> page, @Param("dataSourceId") Long dataSourceId,
        @Param("accessObjectType") String accessObjectType,
        @Param("accessObjectId") Long accessObjectId,
        @Param("userOrTeamSearchKey") String userOrTeamSearchKey,
        @Param("dataSourceSearchKey") String dataSourceSearchKey);

    DataSourceAccessDO checkTeamPermission( @Param("dataSourceId") Long dataSourceId, @Param("userId") Long userId);
}
