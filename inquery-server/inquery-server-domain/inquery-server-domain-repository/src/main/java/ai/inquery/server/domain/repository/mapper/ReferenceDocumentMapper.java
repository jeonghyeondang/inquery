package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.ReferenceDocumentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReferenceDocumentMapper extends BaseMapper<ReferenceDocumentDO> {

    @Select("SELECT id, content FROM reference_document WHERE id = #{id} AND COALESCE(deleted, 'n') = 'n'")
    ReferenceDocumentDO selectContentRowById(@Param("id") Long id);

    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM reference_document WHERE user_id = #{userId} AND COALESCE(deleted, 'n') = 'n'")
    Long sumSizeBytesByUser(@Param("userId") Long userId);
}
