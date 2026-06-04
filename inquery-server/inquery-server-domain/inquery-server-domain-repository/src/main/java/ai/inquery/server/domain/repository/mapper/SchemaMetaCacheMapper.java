package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.SchemaMetaCacheDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Schema metadata cache Mapper interface
 */
@Mapper
public interface SchemaMetaCacheMapper extends BaseMapper<SchemaMetaCacheDO> {

    /**
     * Find by dataSourceId, databaseName, schemaName, userId
     */
    SchemaMetaCacheDO findByKey(
        @Param("dataSourceId") Long dataSourceId,
        @Param("databaseName") String databaseName,
        @Param("schemaName") String schemaName,
        @Param("userId") Long userId
    );

    /**
     * Delete all caches for a data source
     */
    int deleteByDataSourceId(@Param("dataSourceId") Long dataSourceId, @Param("userId") Long userId);

    /**
     * Find all schema cache entries for a data source
     */
    List<SchemaMetaCacheDO> findAllByDataSourceId(@Param("dataSourceId") Long dataSourceId, @Param("userId") Long userId);

    /**
     * Get distinct database names for a data source (L2 cache for database list)
     */
    List<String> findDistinctDatabases(@Param("dataSourceId") Long dataSourceId, @Param("userId") Long userId);

    /**
     * Get distinct schema names for a data source + database (L2 cache for schema list)
     */
    List<String> findDistinctSchemas(@Param("dataSourceId") Long dataSourceId, @Param("databaseName") String databaseName, @Param("userId") Long userId);
}



