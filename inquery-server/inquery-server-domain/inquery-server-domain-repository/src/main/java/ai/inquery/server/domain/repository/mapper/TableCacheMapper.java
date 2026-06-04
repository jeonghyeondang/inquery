package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.TableCacheDO;
import ai.inquery.server.domain.repository.entity.TeamUserDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * table cache Mapper interface
 * </p>
 *
 * @since 2023-10-11
 */
public interface TableCacheMapper extends BaseMapper<TableCacheDO> {

    void batchInsert(List<TableCacheDO> list);

    IPage<TableCacheDO> pageQuery(IPage<TableCacheDO> page, @Param("dataSourceId") Long dataSourceId, @Param("databaseName") String databaseName, @Param("schemaName") String schemaName, @Param("searchKey") String searchKey, @Param("version") Long version);
}
