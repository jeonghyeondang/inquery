package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.ColumnMetaCacheDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Column metadata cache Mapper interface
 */
@Mapper
public interface ColumnMetaCacheMapper extends BaseMapper<ColumnMetaCacheDO> {

    /**
     * Find all columns by table cache ID
     */
    List<ColumnMetaCacheDO> findByTableCacheId(@Param("tableCacheId") Long tableCacheId);

    /**
     * Find all columns by schema cache ID (join with table_meta_cache)
     */
    List<ColumnMetaCacheDO> findBySchemaCacheId(@Param("schemaCacheId") Long schemaCacheId);

    /**
     * Delete all columns by table cache ID
     */
    int deleteByTableCacheId(@Param("tableCacheId") Long tableCacheId);

    /**
     * Delete all columns by schema cache ID
     */
    int deleteBySchemaCacheId(@Param("schemaCacheId") Long schemaCacheId);

    /**
     * Batch insert columns
     */
    int batchInsert(@Param("list") List<ColumnMetaCacheDO> list);
}



