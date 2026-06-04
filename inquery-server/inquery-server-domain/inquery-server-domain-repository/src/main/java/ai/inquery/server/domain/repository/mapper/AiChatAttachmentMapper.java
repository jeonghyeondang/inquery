package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI Chat attachment mapper.
 *
 * <p>The BYTEA / TEXT columns ({@code content},
 * {@code thumbnail_content}, {@code extracted_text}) are excluded from
 * default SELECT to keep meta queries lightweight. The methods below
 * fetch each lazy column on demand.
 *
 * <p>Note: PostgreSQL BYTEA columns can't reliably be read as a
 * top-level {@code byte[]} return type — MyBatis-Plus + the PG JDBC
 * driver tries to convert the BYTEA hex string into a single
 * {@code byte} primitive ("Bad value for type byte"). Returning the
 * full DO and letting MyBatis populate the {@code byte[]} field on
 * the entity sidesteps that path.
 */
public interface AiChatAttachmentMapper extends BaseMapper<AiChatAttachmentDO> {

    @Select("SELECT id, content FROM ai_chat_attachment WHERE id = #{id} AND COALESCE(deleted, 'n') = 'n'")
    AiChatAttachmentDO selectContentRowById(@Param("id") Long id);

    @Select("SELECT id, thumbnail_content FROM ai_chat_attachment WHERE id = #{id} AND COALESCE(deleted, 'n') = 'n'")
    AiChatAttachmentDO selectThumbnailRowById(@Param("id") Long id);

    @Select("SELECT extracted_text FROM ai_chat_attachment WHERE id = #{id} AND COALESCE(deleted, 'n') = 'n'")
    String selectExtractedTextById(@Param("id") Long id);
}
