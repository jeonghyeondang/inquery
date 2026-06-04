package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.TableMetaCacheDO;
import ai.inquery.server.domain.repository.entity.TableSearchResultDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Table metadata cache Mapper interface
 */
@Mapper
public interface TableMetaCacheMapper extends BaseMapper<TableMetaCacheDO> {

    /**
     * Find all tables by schema cache ID
     */
    List<TableMetaCacheDO> findBySchemaCacheId(@Param("schemaCacheId") Long schemaCacheId);

    /**
     * Delete all tables by schema cache ID
     */
    int deleteBySchemaCacheId(@Param("schemaCacheId") Long schemaCacheId);

    /**
     * Batch insert tables
     */
    int batchInsert(@Param("list") List<TableMetaCacheDO> list);

    /**
     * Search tables by name across all cached schemas for a data source
     * Returns table info with database/schema location
     */
    List<TableSearchResultDO> searchByTableName(
        @Param("dataSourceId") Long dataSourceId,
        @Param("tableName") String tableName,
        @Param("userId") Long userId
    );

    /**
     * Find all tables/views for a data source across all cached schemas.
     * Used as PostgreSQL L2 cache for loadAllTablesAndViews.
     */
    List<TableSearchResultDO> findAllByDataSourceId(
        @Param("dataSourceId") Long dataSourceId,
        @Param("userId") Long userId
    );

    /**
     * Find tables/views for a specific schema (L2 cache for loadTablesBySchema).
     */
    List<TableSearchResultDO> findByDataSourceAndSchema(
        @Param("dataSourceId") Long dataSourceId,
        @Param("databaseName") String databaseName,
        @Param("schemaName") String schemaName,
        @Param("userId") Long userId
    );
}



